package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.AddressBookSharingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBookSettingsModelTest {
    @Test
    fun disabledServerStateOffersShareFromServerTruth() {
        val model = addressBookSettingsModel(
            state(
                AddressBookSharingState.Server(
                    enabled = false,
                    storedContactCount = 0,
                    canWrite = true,
                    ownedByInstallation = true,
                ),
            ),
        )

        assertEquals("Not shared", model.status)
        assertEquals(AddressBookSettingsAction.Share, model.primaryAction)
        assertEquals("Share", model.primaryLabel)
        assertTrue(model.canUsePrimaryAction)
        assertFalse(model.showsStop)
    }

    @Test
    fun ownedEnabledStateOffersUpdateAndStopWithServerCount() {
        val model = addressBookSettingsModel(
            state(
                AddressBookSharingState.Server(
                    enabled = true,
                    storedContactCount = 12,
                    canWrite = true,
                    ownedByInstallation = true,
                ),
            ),
        )

        assertEquals("12 friendly names are shared", model.status)
        assertEquals(AddressBookSettingsAction.Update, model.primaryAction)
        assertTrue(model.showsStop)
        assertTrue(model.canStop)
    }

    @Test
    fun unknownEnabledProjectionCannotBeUpdatedButCanBeStopped() {
        val model = addressBookSettingsModel(
            state(
                AddressBookSharingState.Server(
                    enabled = true,
                    storedContactCount = 4,
                    canWrite = true,
                    ownedByInstallation = false,
                ),
            ),
        )

        assertNull(model.primaryAction)
        assertNull(model.primaryLabel)
        assertTrue(model.showsStop)
        assertTrue(model.canStop)
    }

    @Test
    fun interruptedReplacementOffersRetryOnlyWhenWritingIsAllowed() {
        val retry = addressBookSettingsModel(
            state(
                sharing = AddressBookSharingState.Server(
                    enabled = true,
                    storedContactCount = 1,
                    canWrite = true,
                    ownedByInstallation = false,
                ),
                interrupted = true,
            ),
        )
        val blocked = addressBookSettingsModel(
            state(
                sharing = AddressBookSharingState.Server(
                    enabled = true,
                    storedContactCount = 1,
                    canWrite = false,
                    ownedByInstallation = false,
                ),
                interrupted = true,
            ),
        )

        assertEquals(AddressBookSettingsAction.Retry, retry.primaryAction)
        assertTrue(retry.showsStop)
        assertNull(blocked.primaryAction)
        assertTrue(blocked.showsStop)
    }

    @Test
    fun disabledInterruptedReplacementCanBeStoppedWithoutRetryingContactValues() {
        val model = addressBookSettingsModel(
            state(
                sharing = AddressBookSharingState.Server(
                    enabled = false,
                    storedContactCount = 0,
                    canWrite = true,
                    ownedByInstallation = true,
                ),
                interrupted = true,
            ),
        )

        assertEquals(AddressBookSettingsAction.Retry, model.primaryAction)
        assertTrue(model.showsStop)
        assertTrue(model.canStop)
    }

    @Test
    fun unavailableAndDeniedStateKeepsRecoveryVisible() {
        val model = addressBookSettingsModel(
            state(
                sharing = AddressBookSharingState.Unavailable,
                denied = true,
            ),
        )

        assertEquals("Server status unavailable", model.status)
        assertEquals(AddressBookSettingsAction.Refresh, model.primaryAction)
        assertTrue(model.canUsePrimaryAction)
        assertTrue(model.showsOpenAppSettings)
    }

    @Test
    fun busyStateDisablesOnlyContactActions() {
        val model = addressBookSettingsModel(
            state(
                sharing = AddressBookSharingState.Server(
                    enabled = true,
                    storedContactCount = 1,
                    canWrite = true,
                    ownedByInstallation = true,
                ),
                busy = true,
            ),
        )

        assertEquals("Working", model.primaryLabel)
        assertFalse(model.canUsePrimaryAction)
        assertFalse(model.canStop)
    }

    private fun state(
        sharing: AddressBookSharingState,
        busy: Boolean = false,
        interrupted: Boolean = false,
        denied: Boolean = false,
    ) = AppUiState(
        addressBookSharing = sharing,
        isAddressBookBusy = busy,
        addressBookHasInterruptedReplacement = interrupted,
        contactsPermissionDenied = denied,
    )
}
