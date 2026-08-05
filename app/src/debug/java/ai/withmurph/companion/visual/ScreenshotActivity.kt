package ai.withmurph.companion.visual

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.LaunchConsentRecoveryPhase
import ai.withmurph.companion.app.LaunchConsentRecoveryUiState
import ai.withmurph.companion.app.InitialOnboardingDraft
import ai.withmurph.companion.app.InitialOnboardingStage
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.InitialOnboardingCatalog
import ai.withmurph.companion.core.InitialOnboardingContactAction
import ai.withmurph.companion.core.InitialOnboardingContactAvatar
import ai.withmurph.companion.core.InitialOnboardingContactAvatarKind
import ai.withmurph.companion.core.InitialOnboardingContactCard
import ai.withmurph.companion.core.InitialOnboardingContactKind
import ai.withmurph.companion.core.InitialOnboardingPersona
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import ai.withmurph.companion.core.InitialOnboardingTone
import ai.withmurph.companion.core.InitialOnboardingVoice
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.MurphApp
import ai.withmurph.companion.ui.theme.MurphTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.time.Duration
import java.time.Instant

class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = ScreenshotScenario.from(intent.getStringExtra(ScenarioExtra))
        val now = Instant.now()

        setContent {
            MurphTheme {
                MurphApp(
                    appState = scenario.appState(now),
                    loginState = scenario.loginState(),
                    actions = NoOpActions,
                )
            }
        }
    }

    private companion object {
        const val ScenarioExtra = "scenario"
    }
}

private enum class ScreenshotScenario {
    Login,
    Email,
    Otp,
    Setup,
    ReconnectRequired,
    Awaiting,
    Synced,
    SavedStatus,
    Delayed,
    Attention,
    ConsentRequired,
    ConsentLoadFailure,
    OnboardingContact,
    OnboardingPersona,
    OnboardingSupporting,
    OnboardingVoice,
    OnboardingTone,
    OnboardingWelcome,
    FriendlyNames,
    Failure;

    fun appState(now: Instant): AppUiState = when (this) {
        Login, Email, Otp -> AppUiState(phase = AppPhase.NeedsLogin)
        Setup -> ready(HealthSyncState.NotConnected)
        ReconnectRequired -> ready(HealthSyncState.NotConnected).copy(
            healthReconnectRequired = true,
            healthMessage = "Health Connect needs to reconnect before syncing can resume.",
        )
        Awaiting -> ready(HealthSyncState.AwaitingFirstData)
        Synced -> ready(HealthSyncState.Synced(now))
        SavedStatus -> ready(HealthSyncState.Synced(now.minus(Duration.ofMinutes(5)))).copy(
            healthStatusObservedAt = now,
            healthStatusIsStale = true,
            authVerifiedOnline = false,
            healthMessage = "You're offline. Saved sync status is shown until Murph reconnects.",
        )
        Delayed -> ready(HealthSyncState.Delayed(now.minus(Duration.ofHours(48))))
        Attention -> ready(
            HealthSyncState.NeedsAttention(now.minus(Duration.ofHours(96))),
        )
        ConsentRequired -> ready(HealthSyncState.NotConnected).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.Required,
                status = consentStatus(),
                showSheet = true,
            ),
        )
        ConsentLoadFailure -> ready(HealthSyncState.NotConnected).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.LoadFailed,
                message = "Murph couldn't load the latest consent documents. Check your connection and try again.",
                showSheet = true,
            ),
        )
        OnboardingContact -> onboardingState(InitialOnboardingStage.Contact)
        OnboardingPersona -> onboardingState(InitialOnboardingStage.MainPersona)
        OnboardingSupporting -> onboardingState(InitialOnboardingStage.SupportingPersona)
        OnboardingVoice -> onboardingState(InitialOnboardingStage.Voice)
        OnboardingTone -> onboardingState(InitialOnboardingStage.Tone)
        OnboardingWelcome -> onboardingState(InitialOnboardingStage.Welcome).copy(
            initialOnboardingCompletedNow = true,
        )
        FriendlyNames -> ready(HealthSyncState.Synced(now)).copy(
            addressBookSharing = AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = false,
            ),
        )
        Failure -> AppUiState(
            phase = AppPhase.Failed(
                message = "Murph couldn't finish signing in. Check your connection and try again.",
                canRetry = true,
                canSignOut = true,
            ),
        )
    }

    fun loginState(): LoginUiState = when (this) {
        Otp -> LoginUiState(
            method = LoginMethod.Phone,
            destination = "5555550100",
            phoneCountry = CountryDialCode("US", "+1"),
            codeSent = true,
        )
        Email -> LoginUiState(method = LoginMethod.Email)
        Login,
        Setup,
        ReconnectRequired,
        Awaiting,
        Synced,
        SavedStatus,
        Delayed,
        Attention,
        ConsentRequired,
        ConsentLoadFailure,
        OnboardingContact,
        OnboardingPersona,
        OnboardingSupporting,
        OnboardingVoice,
        OnboardingTone,
        OnboardingWelcome,
        FriendlyNames,
        Failure -> LoginUiState()
    }

    private fun ready(sync: HealthSyncState) = AppUiState(
        phase = AppPhase.Ready,
        healthAvailability = HealthConnectAvailability.Available,
        healthSync = sync,
        grantedResourceCount = if (sync == HealthSyncState.NotConnected) 0 else 4,
        totalResourceCount = 4,
        backendEnvironment = "screenshot",
    )

    private fun onboardingState(stage: InitialOnboardingStage) =
        ready(HealthSyncState.NotConnected).copy(
            initialOnboarding = screenshotOnboarding(),
            initialOnboardingStage = stage,
            initialOnboardingDraft = InitialOnboardingDraft(
                avatarId = "classic",
                mainPersonaId = "classic",
                supportingPersonaId = "coach",
                voiceId = "murph",
                toneId = "formal",
            ),
        )

    companion object {
        fun from(value: String?): ScreenshotScenario =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Setup
    }
}

private fun screenshotOnboarding() = InitialOnboarding(
    status = InitialOnboardingStatus.Pending,
    completedNow = null,
    preferences = InitialOnboardingPreferences(null, null, null),
    catalog = InitialOnboardingCatalog(
        personas = listOf(
            InitialOnboardingPersona(
                "classic", "Classic", "Warm, perceptive, and direct.",
                "Adds warmth and perspective.", "formal", "murph",
                listOf("murph", "calm"),
            ),
            InitialOnboardingPersona(
                "coach", "Coach", "Motivating without the noise.",
                "Adds momentum when it helps.", "casual", "energy",
                listOf("energy", "murph"),
            ),
            InitialOnboardingPersona(
                "scientist", "Scientist", "Evidence first, clearly explained.",
                "Adds useful context and precision.", "formal", "calm",
                listOf("calm", "murph"),
            ),
        ),
        voices = listOf(
            InitialOnboardingVoice("murph", "Murph", "Warm and direct", "https://example.test/murph.mp3"),
            InitialOnboardingVoice("calm", "Calm", "Measured and grounding", "https://example.test/calm.mp3"),
            InitialOnboardingVoice("energy", "Energy", "Bright and motivating", "https://example.test/energy.mp3"),
        ),
        tones = listOf(
            InitialOnboardingTone("formal", "Formal", "Your sleep is down this week. Want to work on sleep first?"),
            InitialOnboardingTone("casual", "Casual", "sleep is way down this week. wanna fix sleep first?"),
        ),
    ),
    contactCard = InitialOnboardingContactCard(
        avatars = listOf(
            InitialOnboardingContactAvatar("classic", InitialOnboardingContactAvatarKind.Logo, "Classic", null),
            InitialOnboardingContactAvatar("sage", InitialOnboardingContactAvatarKind.Logo, "Sage", null),
            InitialOnboardingContactAvatar("warm", InitialOnboardingContactAvatarKind.Headshot, "Warm", null),
            InitialOnboardingContactAvatar("blank", InitialOnboardingContactAvatarKind.Blank, "Blank", null),
        ),
        defaultAvatarId = "classic",
    ),
    contactAction = InitialOnboardingContactAction(
        href = "sms:+15555550123",
        kind = InitialOnboardingContactKind.Text,
        label = "Text Murph",
    ),
)

private fun consentStatus(): LaunchConsentStatus {
    val legal = LaunchConsentDocument(
        id = "terms",
        title = "Murph Terms",
        version = "2026-07-01",
        href = "https://www.withmurph.ai/legal/terms",
        pdfHref = null,
    )
    val health = LaunchConsentDocument(
        id = "health-data",
        title = "Consumer Health Data Notice",
        version = "2026-07-01",
        href = "https://www.withmurph.ai/consumer-health-data-privacy-policy",
        pdfHref = null,
    )
    return LaunchConsentStatus(
        launchGranted = false,
        documents = listOf(legal, health),
        launchScopes = listOf(
            LaunchConsentScopeStatus(
                scope = LaunchConsentScope.Legal,
                granted = false,
                documents = listOf(legal),
                missingDocuments = listOf(legal),
            ),
            LaunchConsentScopeStatus(
                scope = LaunchConsentScope.HealthData,
                granted = false,
                documents = listOf(health),
                missingDocuments = listOf(health),
            ),
        ),
    )
}

private val NoOpActions = MurphActions(
    onLoginMethodChanged = {},
    onPhoneCountryChanged = {},
    onLoginDestinationChanged = {},
    onLoginCodeChanged = {},
    onSendLoginCode = {},
    onConfirmLoginCode = {},
    onResendLoginCode = {},
    onChangeLoginDestination = {},
    onConnectHealth = {},
    onOpenHealthConnect = {},
    onSyncNow = {},
    onShareAddressBook = {},
    onRefreshAddressBook = {},
    onStopAddressBook = {},
    onShowLaunchConsent = {},
    onDismissLaunchConsent = {},
    onRetryLaunchConsent = {},
    onAcceptLaunchConsent = {},
    onSelectInitialOnboardingAvatar = {},
    onSelectInitialOnboardingMainPersona = {},
    onSelectInitialOnboardingSupportingPersona = {},
    onSelectInitialOnboardingVoice = {},
    onSelectInitialOnboardingTone = {},
    onSetInitialOnboardingStage = {},
    onPrepareInitialOnboardingContactCard = {},
    onSkipInitialOnboarding = {},
    onSaveInitialOnboarding = {},
    onDismissCompletedInitialOnboarding = {},
    onOpenInitialOnboardingContact = {},
    onOpenConsentDocument = {},
    onOpenAppSettings = {},
    onOpenPrivacy = {},
    onOpenTerms = {},
    onOpenHealthNotice = {},
    onOpenAiSafety = {},
    onOpenSupport = {},
    onDeleteAccount = {},
    onRetry = {},
    onSignOut = {},
)
