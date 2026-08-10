package ai.withmurph.companion.app

import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.LaunchConsentStatus
import java.time.Instant

sealed interface AppPhase {
    data object Launching : AppPhase
    data object NeedsLogin : AppPhase
    data object Ready : AppPhase
    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
        val canSignOut: Boolean = false,
        val signOutLabel: String = "Sign out and start fresh",
        val supplementalActions: FailureSupplementalActions = if (canSignOut) {
            FailureSupplementalActions.AccountAndLegal
        } else {
            FailureSupplementalActions.None
        },
    ) : AppPhase
}

enum class FailureSupplementalActions {
    None,
    Support,
    AccountAndLegal,
}

data class AppUiState(
    val phase: AppPhase = AppPhase.Launching,
    val initialSetupStep: InitialSetupStep = InitialSetupStep.HealthConnect,
    val healthAvailability: HealthConnectAvailability = HealthConnectAvailability.TemporarilyUnavailable,
    val healthSync: HealthSyncState = HealthSyncState.NotConnected,
    val healthStatusObservedAt: Instant? = null,
    val healthStatusIsStale: Boolean = false,
    val healthReconnectRequired: Boolean = false,
    val isConnectingHealth: Boolean = false,
    val isSyncingHealth: Boolean = false,
    val healthMessage: String? = null,
    val healthSyncReminderEnabled: Boolean = false,
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
    val initialOnboarding: InitialOnboarding? = null,
    val initialOnboardingStage: InitialOnboardingStage? = null,
    val initialOnboardingDraft: InitialOnboardingDraft? = null,
    val isInitialOnboardingSaving: Boolean = false,
    val initialOnboardingCompletedNow: Boolean = false,
    val initialOnboardingNotice: InitialOnboardingNotice? = null,
    val initialOnboardingContactCardHandoff: PendingInitialOnboardingContactCardHandoff? = null,
    val pendingHealthPermissionRequestId: Int? = null,
    val pendingAddressBookPermissionRequestId: Int? = null,
)

data class PendingInitialOnboardingContactCardHandoff(
    val id: Int,
)

data class InitialOnboardingNotice(
    val message: String,
    val recoveryActions: InitialOnboardingRecoveryActions,
)

enum class InitialOnboardingRecoveryActions {
    None,
    Account,
}

enum class InitialOnboardingStage {
    Contact,
    MainPersona,
    SupportingPersona,
    Voice,
    Tone,
    Welcome,
}

data class InitialOnboardingDraft(
    val avatarId: String?,
    val mainPersonaId: String,
    val supportingPersonaId: String?,
    val voiceId: String,
    val toneId: String,
)

enum class LaunchConsentRecoveryPhase {
    Pausing,
    Loading,
    LoadFailed,
    Required,
    Saving,
}

data class LaunchConsentRecoveryUiState(
    val phase: LaunchConsentRecoveryPhase,
    val status: LaunchConsentStatus? = null,
    val message: String? = null,
    val showSheet: Boolean = true,
    val canAccept: Boolean = phase == LaunchConsentRecoveryPhase.Required &&
        status?.missingLaunchScopes?.isNotEmpty() == true,
)
