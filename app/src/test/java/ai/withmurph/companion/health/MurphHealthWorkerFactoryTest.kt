package ai.withmurph.companion.health

import androidx.work.Data
import io.tryvital.vitalhealthcore.model.VitalResource
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
        VitalHealthWorkerLease.closeFor(testMemberKey)
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
