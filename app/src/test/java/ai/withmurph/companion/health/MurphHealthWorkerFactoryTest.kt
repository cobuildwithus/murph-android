package ai.withmurph.companion.health

import androidx.work.Data
import androidx.work.WorkInfo
import ai.withmurph.companion.core.HealthSyncAttemptResult
import io.tryvital.vitalhealthcore.model.VitalResource
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MurphHealthWorkerFactoryTest {
    private val testMemberKey = "did:privy:worker-lease-member"

    @After
    fun closeTestLease() {
        VitalHealthWorkerLease.close()
    }

    @Test
    fun durableWorkerNamesMatchThePinnedVitalSdk() {
        assertEquals(
            setOf(
                "io.tryvital.vitalhealthconnect.workers.ResourceSyncStarter",
                "io.tryvital.vitalhealthconnect.workers.ResourceSyncWorker",
            ),
            vitalHealthWorkerClasses,
        )
        vitalHealthWorkerClasses.forEach { className ->
            assertEquals(className, Class.forName(className).name)
        }
        assertEquals("HC.ResourceSyncStarter", vitalResourceSyncStarter)
        assertEquals(
            "HC.ResourceSyncWorker.sleep",
            vitalResourceSyncWorkerName(VitalResource.Sleep),
        )
    }

    @Test
    fun vitalWorkersRequireDurableMemberAndSetupAuthority() {
        assertTrue(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = true,
                hasCommittedHealthSetup = true,
                signOutPending = false,
                hasForegroundSyncLease = true,
            ),
        )
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = false,
                hasCommittedHealthSetup = true,
                signOutPending = false,
                hasForegroundSyncLease = true,
            ),
        )
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = true,
                hasCommittedHealthSetup = false,
                signOutPending = false,
                hasForegroundSyncLease = true,
            ),
        )
    }

    @Test
    fun signOutTombstoneRejectsRestartedVitalWorkers() {
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = true,
                hasCommittedHealthSetup = true,
                signOutPending = true,
                hasForegroundSyncLease = true,
            ),
        )
    }

    @Test
    fun restartedProcessDefaultsClosedAndLeaseIsMemberScoped() {
        assertFalse(VitalHealthWorkerLease.isOpenFor(testMemberKey))

        VitalHealthWorkerLease.openFor(testMemberKey)

        assertTrue(VitalHealthWorkerLease.isOpenFor(testMemberKey))
        assertFalse(VitalHealthWorkerLease.isOpenFor("did:privy:another-member"))
        VitalHealthWorkerLease.closeFor(testMemberKey)
        assertFalse(VitalHealthWorkerLease.isOpenFor(testMemberKey))
    }

    @Test
    fun backgroundRevokesOnlyAnUnpromotedLaunch() {
        VitalHealthWorkerLease.openFor(testMemberKey)
        assertTrue(VitalHealthWorkerLease.isLaunchAuthorizedFor(testMemberKey))

        VitalHealthWorkerLease.rejectUnpromoted()

        assertFalse(VitalHealthWorkerLease.isOpenFor(testMemberKey))
        assertTrue(VitalHealthWorkerLease.wasLaunchRejectedFor(testMemberKey))

        VitalHealthWorkerLease.closeFor(testMemberKey)
        VitalHealthWorkerLease.openFor(testMemberKey)
        assertTrue(VitalHealthWorkerLease.markPromotedFor(testMemberKey))

        VitalHealthWorkerLease.rejectUnpromoted()

        assertTrue(VitalHealthWorkerLease.isOpenFor(testMemberKey))
        assertFalse(VitalHealthWorkerLease.wasLaunchRejectedFor(testMemberKey))
    }

    @Test
    fun durableAuthorityStillRequiresTheProcessLease() {
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = true,
                hasCommittedHealthSetup = true,
                signOutPending = false,
                hasForegroundSyncLease = false,
            ),
        )
    }

    @Test
    fun workInfoCancellationCannotCompleteTeardownUntilTheDelegatedBodyExits() = runTest {
        VitalHealthWorkerLease.openFor(testMemberKey)
        assertTrue(VitalHealthWorkerLease.markPromotedFor(testMemberKey))
        assertTrue(VitalHealthWorkerLease.beginExecutionFor(testMemberKey))
        VitalHealthWorkerLease.close()

        val drain = async { VitalHealthWorkerLease.awaitNoActiveExecutions() }
        yield()

        assertFalse(drain.isCompleted)
        assertFalse(VitalHealthWorkerLease.beginExecutionFor(testMemberKey))

        VitalHealthWorkerLease.finishExecutionFor(testMemberKey)
        drain.await()
        assertTrue(drain.isCompleted)
    }

    @Test
    fun failedResourceDoesNotStarveLaterResourcesButCancellationStopsTheChain() {
        assertEquals(
            VitalResourceTerminalDecision.ContinueAfterFailure,
            vitalResourceTerminalDecision(WorkInfo.State.FAILED),
        )
        assertEquals(
            VitalResourceTerminalDecision.Continue,
            vitalResourceTerminalDecision(WorkInfo.State.SUCCEEDED),
        )
        assertEquals(
            VitalResourceTerminalDecision.Stop,
            vitalResourceTerminalDecision(WorkInfo.State.CANCELLED),
        )
        assertEquals(
            VitalResourceTerminalDecision.Stop,
            vitalResourceTerminalDecision(null),
        )

        val orderedResources = listOf(
            VitalResource.Activity,
            VitalResource.Sleep,
            VitalResource.Steps,
        )
        assertEquals(
            setOf(VitalResource.Sleep, VitalResource.Steps),
            vitalResourcesFailedByHardStop(orderedResources, stoppedAtIndex = 1),
        )
        assertEquals(
            setOf(VitalResource.Sleep, VitalResource.Steps),
            vitalFailedResources(
                vitalFailedResourcesOutputData(
                    vitalResourcesFailedByHardStop(orderedResources, stoppedAtIndex = 1),
                ),
            ),
        )
    }

    @Test
    fun partialFailureOutputPreservesExactBackendResourceOwners() {
        val failed = setOf(
            VitalResource.Activity,
            VitalResource.Sleep,
            VitalResource.Temperature,
        )

        assertEquals(
            failed,
            vitalFailedResources(vitalFailedResourcesOutputData(failed)),
        )
        assertEquals("activity", backendFailureOwnerKeyFor(VitalResource.Activity))
        assertEquals("sleep", backendFailureOwnerKeyFor(VitalResource.Sleep))
        assertEquals("temperature", backendFailureOwnerKeyFor(VitalResource.Temperature))
        assertEquals(
            healthConnectReadResources,
            healthConnectReadResources.filterTo(linkedSetOf()) {
                backendFailureOwnerKeyFor(it).isNotEmpty()
            },
        )
    }

    @Test
    fun starterEvidenceSeparatesNotStartedPartialCompleteAndReconnectOutcomes() {
        val existingId = java.util.UUID.randomUUID()
        val currentId = java.util.UUID.randomUUID()
        val existing = setOf(existingId)
        val prior = VitalStarterWorkEvidence(
            existingId,
            WorkInfo.State.SUCCEEDED,
            failedResources = null,
        )

        assertEquals(
            HealthSyncAttemptResult.NotStarted,
            healthSyncResultForStarterEvidence(listOf(prior), existing),
        )
        assertEquals(
            HealthSyncAttemptResult.PartialFailure(setOf("sleep")),
            healthSyncResultForStarterEvidence(
                listOf(
                    prior,
                    VitalStarterWorkEvidence(
                        currentId,
                        WorkInfo.State.FAILED,
                        failedResources = setOf(VitalResource.Sleep),
                    ),
                ),
                existing,
            ),
        )
        assertEquals(
            HealthSyncAttemptResult.Complete,
            healthSyncResultForStarterEvidence(
                listOf(prior, VitalStarterWorkEvidence(currentId, WorkInfo.State.SUCCEEDED, null)),
                existing,
            ),
        )
        assertEquals(
            HealthSyncAttemptResult.ReconnectRequired,
            healthSyncResultForStarterEvidence(
                listOf(prior, VitalStarterWorkEvidence(currentId, WorkInfo.State.CANCELLED, null)),
                existing,
            ),
        )
        assertEquals(
            HealthSyncAttemptResult.ReconnectRequired,
            healthSyncResultForStarterEvidence(
                listOf(
                    prior,
                    VitalStarterWorkEvidence(
                        currentId,
                        WorkInfo.State.FAILED,
                        failedResources = setOf(VitalResource.Sleep),
                    ),
                    VitalStarterWorkEvidence(
                        java.util.UUID.randomUUID(),
                        WorkInfo.State.SUCCEEDED,
                        failedResources = null,
                    ),
                ),
                existing,
            ),
        )
    }

    @Test
    fun dataSyncStarterPreservesThePinnedVitalInputContract() {
        val starterData = Data.Builder()
            .putStringArray(
                "resources",
                arrayOf(VitalResource.Sleep.toString(), VitalResource.Meal.toString()),
            )
            .putBoolean("startForeground", true)
            .putIntArray("tags", intArrayOf(2, 4))
            .build()

        assertEquals(
            setOf(VitalResource.Sleep, VitalResource.Meal),
            vitalStarterResources(starterData),
        )
        val workerData = vitalResourceWorkerInputData(
            resource = VitalResource.Sleep,
            tags = intArrayOf(2, 4),
        )
        assertEquals(VitalResource.Sleep.toString(), workerData.getString("resource"))
        assertTrue(intArrayOf(2, 4).contentEquals(workerData.getIntArray("tags")))
        assertNull(vitalStarterResources(Data.EMPTY))
    }
}
