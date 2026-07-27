package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.AddressBookSharingState

internal enum class AddressBookSettingsAction {
    Share,
    Update,
    Retry,
    Refresh,
}

internal data class AddressBookSettingsModel(
    val status: String,
    val primaryAction: AddressBookSettingsAction?,
    val primaryLabel: String?,
    val canUsePrimaryAction: Boolean,
    val showsStop: Boolean,
    val canStop: Boolean,
    val showsOpenAppSettings: Boolean,
)

internal fun addressBookSettingsModel(state: AppUiState): AddressBookSettingsModel {
    val busy = state.isAddressBookBusy
    return when (val sharing = state.addressBookSharing) {
        AddressBookSharingState.Loading -> AddressBookSettingsModel(
            status = "Checking the server…",
            primaryAction = null,
            primaryLabel = if (busy) "Working" else null,
            canUsePrimaryAction = false,
            showsStop = false,
            canStop = false,
            showsOpenAppSettings = state.contactsPermissionDenied,
        )
        AddressBookSharingState.Unavailable -> AddressBookSettingsModel(
            status = "Server status unavailable",
            primaryAction = AddressBookSettingsAction.Refresh,
            primaryLabel = if (busy) "Working" else "Refresh",
            canUsePrimaryAction = !busy,
            showsStop = false,
            canStop = false,
            showsOpenAppSettings = state.contactsPermissionDenied,
        )
        is AddressBookSharingState.Server -> {
            val primaryAction = when {
                sharing.enabled && sharing.canWrite && state.addressBookHasInterruptedReplacement ->
                    AddressBookSettingsAction.Retry
                sharing.enabled && sharing.canWrite && sharing.ownedByInstallation ->
                    AddressBookSettingsAction.Update
                !sharing.enabled && sharing.canWrite && state.addressBookHasInterruptedReplacement ->
                    AddressBookSettingsAction.Retry
                !sharing.enabled && sharing.canWrite -> AddressBookSettingsAction.Share
                else -> null
            }
            AddressBookSettingsModel(
                status = when {
                    sharing.enabled && sharing.storedContactCount == 1 ->
                        "1 friendly name is shared"
                    sharing.enabled ->
                        "${sharing.storedContactCount} friendly names are shared"
                    sharing.canWrite -> "Not shared"
                    else -> "Not shared · sharing unavailable"
                },
                primaryAction = primaryAction,
                primaryLabel = if (busy) {
                    "Working"
                } else {
                    when (primaryAction) {
                        AddressBookSettingsAction.Share -> "Share"
                        AddressBookSettingsAction.Update -> "Update"
                        AddressBookSettingsAction.Retry -> "Retry"
                        AddressBookSettingsAction.Refresh -> "Refresh"
                        null -> null
                    }
                },
                canUsePrimaryAction = primaryAction != null && !busy,
                showsStop = sharing.enabled || state.addressBookHasInterruptedReplacement,
                canStop =
                    (sharing.enabled || state.addressBookHasInterruptedReplacement) && !busy,
                showsOpenAppSettings = state.contactsPermissionDenied,
            )
        }
    }
}
