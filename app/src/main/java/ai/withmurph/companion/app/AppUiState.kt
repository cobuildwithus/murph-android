package ai.withmurph.companion.app

import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.LaunchConsentStatus

sealed interface AppPhase {
    data object Launching : AppPhase
    data object NeedsLogin : AppPhase
    data object Ready : AppPhase
    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
        val canSignOut: Boolean = false,
    ) : AppPhase
}

data class AppUiState(
    val phase: AppPhase = AppPhase.Launching,
    val healthAvailability: HealthConnectAvailability = HealthConnectAvailability.TemporarilyUnavailable,
    val healthSync: HealthSyncState = HealthSyncState.NotConnected,
    val isConnectingHealth: Boolean = false,
    val isSyncingHealth: Boolean = false,
    val healthMessage: String? = null,
    val grantedResourceCount: Int = 0,
    val totalResourceCount: Int = 0,
    val backendEnvironment: String? = null,
    val authVerifiedOnline: Boolean = true,
    val addressBookSharing: AddressBookSharingState = AddressBookSharingState.Unavailable,
    val isAddressBookBusy: Boolean = false,
    val addressBookHasInterruptedReplacement: Boolean = false,
    val contactsPermissionDenied: Boolean = false,
    val addressBookMessage: String? = null,
    val launchConsentRecovery: LaunchConsentRecoveryUiState? = null,
    val pendingHealthPermissionRequestId: Int? = null,
)

enum class LaunchConsentRecoveryPhase {
    Pausing,
    Loading,
    LoadFailed,
    Required,
    Saving,
    Finishing,
}

data class LaunchConsentRecoveryUiState(
    val phase: LaunchConsentRecoveryPhase,
    val status: LaunchConsentStatus? = null,
    val message: String? = null,
    val showSheet: Boolean = true,
    val canDismiss: Boolean = phase == LaunchConsentRecoveryPhase.Required ||
        phase == LaunchConsentRecoveryPhase.LoadFailed,
    val canAccept: Boolean = phase == LaunchConsentRecoveryPhase.Required &&
        status?.missingLaunchScopes?.isNotEmpty() == true,
)
