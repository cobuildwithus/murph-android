package ai.withmurph.companion.health

import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthPermissionRequestResult
import io.tryvital.vitalhealthconnect.model.PermissionOutcome
import androidx.work.Data
import androidx.work.WorkInfo
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class JunctionHealthSyncServiceTest {
    @Test
    fun readResourcesCoverTheCompletePinnedVitalHealthConnectSurface() {
        val expected = VitalResource.values().map { it.name }.toSet()
        val actual = healthConnectReadResources.map { it.name }.toSet()

        assertEquals(expected, actual)
        assertEquals(21, actual.size)
    }

    @Test
    fun configuredResourcesPreserveOnlyTheGrantedReviewedSubset() {
        val granted = setOf(
            VitalResource.Sleep,
            VitalResource.BloodPressure,
            VitalResource.Meal,
        )

        assertEquals(granted, configuredHealthConnectReadResources(granted))
        assertEquals(
            emptySet<VitalResource>(),
            configuredHealthConnectReadResources(emptySet()),
        )
    }

    @Test
    fun orphanWorkoutDetailsDoNotBlockAnUnrelatedActiveResource() {
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Sleep),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SLEEP",
                    "android.permission.health.READ_POWER",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.MissingWorkoutBase,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = setOf("android.permission.health.READ_POWER"),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Workout),
                grantedPermissions = setOf(
                    "android.permission.health.READ_EXERCISE",
                    "android.permission.health.READ_ELEVATION_GAINED",
                    "android.permission.health.READ_POWER",
                    "android.permission.health.READ_SPEED",
                ),
            ),
        )
    }

    @Test
    fun orphanMenstrualDetailsDoNotBlockAnUnrelatedActiveResource() {
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Activity),
                grantedPermissions = setOf(
                    "android.permission.health.READ_STEPS",
                    "android.permission.health.READ_SEXUAL_ACTIVITY",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.MissingMenstrualBase,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SEXUAL_ACTIVITY",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.MenstrualCycle),
                grantedPermissions = setOf(
                    "android.permission.health.READ_MENSTRUATION",
                    "android.permission.health.READ_CERVICAL_MUCUS",
                    "android.permission.health.READ_INTERMENSTRUAL_BLEEDING",
                    "android.permission.health.READ_OVULATION_TEST",
                    "android.permission.health.READ_SEXUAL_ACTIVITY",
                ),
            ),
        )
    }

    @Test
    fun activeResourcesWinWhileDetailOnlySelectionNamesBothMissingBases() {
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Sleep),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SLEEP",
                    "android.permission.health.READ_SPEED",
                    "android.permission.health.READ_OVULATION_TEST",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.MissingWorkoutAndMenstrualBases,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = setOf(
                    "android.permission.health.READ_SPEED",
                    "android.permission.health.READ_OVULATION_TEST",
                ),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.NoActiveResource,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = emptySet(),
            ),
        )
    }

    @Test
    fun notPromptedRetriesClassifyExistingGrantsWithoutBroadeningConsent() {
        assertTrue(
            permissionOutcomeAllowsCurrentGrantClassification(
                PermissionOutcome.Success,
            ),
        )
        assertTrue(
            permissionOutcomeAllowsCurrentGrantClassification(
                PermissionOutcome.NotPrompted,
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.Ready,
            healthPermissionRequestResult(
                activeResources = setOf(VitalResource.Sleep),
                grantedPermissions = setOf("android.permission.health.READ_SLEEP"),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.MissingWorkoutBase,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = setOf("android.permission.health.READ_POWER"),
            ),
        )
        assertEquals(
            HealthPermissionRequestResult.NoActiveResource,
            healthPermissionRequestResult(
                activeResources = emptySet(),
                grantedPermissions = emptySet(),
            ),
        )
    }

    @Test
    fun incompletePermissionOutcomesCannotAdvanceSetup() {
        listOf(
            PermissionOutcome.Cancelled,
            PermissionOutcome.HealthConnectUnavailable,
            PermissionOutcome.UnknownError(IllegalStateException("test failure")),
        ).forEach { outcome ->
            assertFalse(permissionOutcomeAllowsCurrentGrantClassification(outcome))
        }
    }

    @Test
    fun foregroundCancellationOwnsTheExactPinnedVitalWorkerFamily() {
        assertEquals(
            buildSet {
                add("HC.ResourceSyncStarter")
                healthConnectReadResources.mapTo(this, ::vitalResourceSyncWorkerName)
            },
            JunctionHealthSyncService.syncWorkNames(
                JunctionHealthSyncService.requestedReadResources,
            ),
        )
    }

    @Test
    fun cancellationAcceptanceDoesNotReleaseTheBoundaryUntilWorkersAreTerminal() = runTest {
        val workName = "HC.ResourceSyncWorker.sleep"
        val workInfos = MutableStateFlow(listOf(workInfo(WorkInfo.State.RUNNING)))

        val terminality = async {
            JunctionHealthSyncService.awaitSyncWorkersTerminal(setOf(workName)) { requested ->
                assertEquals(workName, requested)
                workInfos
            }
        }
        runCurrent()

        assertFalse(terminality.isCompleted)
        workInfos.value = listOf(workInfo(WorkInfo.State.CANCELLED))
        terminality.await()
    }

    @Test
    fun starterIsTerminalBeforeTheDefinitiveResourceCancellationWave() = runTest {
        val resourceWorkName = "HC.ResourceSyncWorker.sleep"
        val cancellationWaves = mutableListOf<Set<String>>()
        var lateResourceWasEnqueued = false

        JunctionHealthSyncService.cancelSyncWorkerHandoff(
            resourceWorkNames = setOf(resourceWorkName),
        ) { workNames ->
            cancellationWaves += workNames
            if (workNames == setOf(JunctionHealthSyncService.RESOURCE_SYNC_STARTER_WORK_NAME)) {
                lateResourceWasEnqueued = true
            } else {
                assertTrue(lateResourceWasEnqueued)
                assertEquals(setOf(resourceWorkName), workNames)
            }
        }

        assertEquals(
            listOf(
                setOf(JunctionHealthSyncService.RESOURCE_SYNC_STARTER_WORK_NAME),
                setOf(resourceWorkName),
            ),
            cancellationWaves,
        )
    }

    @Test
    fun providerAvailabilityPreservesTheRecoveryOwner() {
        val expected = mapOf(
            ProviderAvailability.Installed to HealthConnectAvailability.Available,
            ProviderAvailability.NotInstalled to HealthConnectAvailability.InstallOrUpdateRequired,
            ProviderAvailability.OnboardingIncomplete to HealthConnectAvailability.OnboardingRequired,
            ProviderAvailability.AppNotAllowed to HealthConnectAvailability.AppNotAllowed,
            ProviderAvailability.ServiceUnavailable to HealthConnectAvailability.TemporarilyUnavailable,
            ProviderAvailability.NotSupportedSDK to HealthConnectAvailability.Unsupported,
        )

        expected.forEach { (provider, app) ->
            assertEquals(app, provider.toAppAvailability())
        }
    }

    private fun workInfo(state: WorkInfo.State): WorkInfo = WorkInfo(
        UUID.randomUUID(),
        state,
        emptySet(),
        Data.EMPTY,
        Data.EMPTY,
        0,
    )
}
