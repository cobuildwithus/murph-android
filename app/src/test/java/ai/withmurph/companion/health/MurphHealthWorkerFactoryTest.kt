package ai.withmurph.companion.health

import io.tryvital.vitalhealthcore.model.VitalResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurphHealthWorkerFactoryTest {
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
            ),
        )
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = false,
                hasCommittedHealthSetup = true,
                signOutPending = false,
            ),
        )
        assertFalse(
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = true,
                hasCommittedHealthSetup = false,
                signOutPending = false,
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
            ),
        )
    }
}
