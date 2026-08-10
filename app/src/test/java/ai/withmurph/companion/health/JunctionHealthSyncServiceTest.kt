package ai.withmurph.companion.health

import ai.withmurph.companion.core.HealthConnectAvailability
import androidx.work.Data
import androidx.work.WorkInfo
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun requestedResourcesMatchTheReviewedShippedClientScope() {
        assertEquals(
            setOf(
                "sleep",
                "workout",
                "activity",
                "steps",
                "activeEnergyBurned",
                "heartRateVariability",
                "respiratoryRate",
                "bloodOxygen",
                "body",
                "profile",
                "vo2Max",
            ),
            JunctionHealthSyncService.requestedReadResources.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun configuredGrantsKeepShippedActivityOwnersAndExcludeUnconfiguredResources() {
        assertEquals(
            setOf(
                VitalResource.Sleep,
                VitalResource.Vo2Max,
                VitalResource.Steps,
                VitalResource.ActiveEnergyBurned,
            ),
            JunctionHealthSyncService.configuredGrantedResources(
                setOf(
                    VitalResource.Sleep,
                    VitalResource.Vo2Max,
                    VitalResource.HeartRate,
                    VitalResource.Steps,
                    VitalResource.ActiveEnergyBurned,
                ),
            ),
        )
    }

    @Test
    fun foregroundCancellationOwnsTheExactPinnedVitalWorkerFamily() {
        assertEquals(
            setOf(
                "HC.ResourceSyncStarter",
                "HC.ResourceSyncWorker.sleep",
                "HC.ResourceSyncWorker.workout",
                "HC.ResourceSyncWorker.activity",
                "HC.ResourceSyncWorker.steps",
                "HC.ResourceSyncWorker.activeEnergyBurned",
                "HC.ResourceSyncWorker.heartRateVariability",
                "HC.ResourceSyncWorker.respiratoryRate",
                "HC.ResourceSyncWorker.bloodOxygen",
                "HC.ResourceSyncWorker.body",
                "HC.ResourceSyncWorker.profile",
                "HC.ResourceSyncWorker.vo2Max",
            ),
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
        Data.EMPTY,
        emptyList(),
        Data.EMPTY,
        0,
    )
}
