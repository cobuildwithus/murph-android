package ai.withmurph.companion.visual

import ai.withmurph.companion.R
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.FailureSupplementalActions
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
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.reminders.HealthSyncReminderController
import ai.withmurph.companion.reminders.activeReminderAdmission
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.MurphApp
import ai.withmurph.companion.ui.theme.MurphTheme
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant

class ScreenshotActivity : ComponentActivity() {
    internal var openSettingsRequestId by mutableIntStateOf(0)
        private set
    internal var consumedOpenSettingsRequestCount = 0
        private set
    internal var signOutRequests = 0
        private set
    internal val healthSyncReminderPreferenceRequests = mutableListOf<Boolean>()
    private var showsReadyApp by mutableStateOf(true)
    private var healthSyncReminderTargetEnabled by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = ScreenshotScenario.from(intent.getStringExtra(SCENARIO_EXTRA))
        openSettingsRequestId = savedInstanceState?.getInt(
            STATE_OPEN_SETTINGS_REQUEST_ID,
        ) ?: 0
        consumedOpenSettingsRequestCount = savedInstanceState?.getInt(
            STATE_CONSUMED_OPEN_SETTINGS_REQUEST_COUNT,
        ) ?: 0
        showsReadyApp = savedInstanceState?.getBoolean(STATE_SHOWS_READY_APP)
            ?: !intent.getBooleanExtra(START_LAUNCHING_EXTRA, false)
        if (savedInstanceState == null && scenario.showsReminderSettings) {
            openSettingsRequestId += 1
        }
        val now = Instant.now()
        if (scenario == ScreenshotScenario.ReminderNotification) {
            postSyntheticHealthSyncReminder()
        } else {
            getSystemService(NotificationManager::class.java).cancelAll()
        }

        setContent {
            MurphTheme {
                val readyState = scenario.appState(now).copy(
                    healthSyncReminderTargetEnabled = healthSyncReminderTargetEnabled,
                )
                val appState = if (showsReadyApp) {
                    readyState
                } else {
                    readyState.copy(phase = AppPhase.Launching)
                }
                val fixtureModifier = if (scenario == ScreenshotScenario.AccountFailure) {
                    Modifier.fillMaxWidth().height(320.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Box(modifier = fixtureModifier) {
                    MurphApp(
                        appState = appState,
                        loginState = scenario.loginState(),
                        healthSyncNotificationsAllowed =
                            scenario != ScreenshotScenario.ReminderBlocked &&
                                scenario != ScreenshotScenario.ReminderDenied,
                        healthSyncNotificationRecoveryNeeded =
                            scenario == ScreenshotScenario.ReminderDenied,
                        openSettingsRequestId = openSettingsRequestId,
                        onOpenSettingsRequestConsumed = { requestId ->
                            if (openSettingsRequestId == requestId) {
                                openSettingsRequestId = 0
                                consumedOpenSettingsRequestCount += 1
                            }
                        },
                        actions = NoOpActions.copy(
                            onSetHealthSyncReminderEnabled = { enabled ->
                                healthSyncReminderPreferenceRequests += enabled
                                healthSyncReminderTargetEnabled = enabled
                            },
                            onSignOut = { signOutRequests += 1 },
                        ),
                        initialOnboardingContactAvatarPainters = screenshotAvatarPainters(),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_OPEN_SETTINGS_REQUEST_ID, openSettingsRequestId)
        outState.putInt(
            STATE_CONSUMED_OPEN_SETTINGS_REQUEST_COUNT,
            consumedOpenSettingsRequestCount,
        )
        outState.putBoolean(STATE_SHOWS_READY_APP, showsReadyApp)
        super.onSaveInstanceState(outState)
    }

    internal fun requestOpenSettings() {
        openSettingsRequestId += 1
    }

    internal fun publishReadyApp() {
        showsReadyApp = true
    }

    private fun postSyntheticHealthSyncReminder() {
        val preferences = getSharedPreferences(
            "visual_health_sync_reminder",
            Context.MODE_PRIVATE,
        )
        check(preferences.edit().clear().commit())
        val localState = SharedPreferencesLocalState(preferences)
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        localState.memberKey = "synthetic-member"
        localState.healthAccessRequestedAt = InstantValue(setupAt.toEpochMilli())
        localState.lastKnownStatusObservedAt = InstantValue(
            setupAt.plus(Duration.ofHours(72)).toEpochMilli(),
        )
        check(localState.setHealthSyncReminderEnabled("synthetic-member", true))
        val admission = requireNotNull(activeReminderAdmission(localState))
        HealthSyncReminderController(this, localState).postIfEligible(admission.basisToken)
        check(preferences.edit().clear().commit())
    }

    companion object {
        const val SCENARIO_EXTRA = "scenario"
        const val START_LAUNCHING_EXTRA = "start_launching"
        private const val STATE_OPEN_SETTINGS_REQUEST_ID = "open_settings_request_id"
        private const val STATE_CONSUMED_OPEN_SETTINGS_REQUEST_COUNT =
            "consumed_open_settings_request_count"
        private const val STATE_SHOWS_READY_APP = "shows_ready_app"
    }
}

internal enum class ScreenshotScenario {
    Login,
    Email,
    Otp,
    OtpResending,
    Setup,
    Disconnected,
    DisconnectedUnavailable,
    ReconnectRequired,
    Awaiting,
    Synced,
    SavedStatus,
    PermissionVerificationFailed,
    Delayed,
    Attention,
    ConsentRequired,
    ConsentBanner,
    ConsentLoadFailure,
    OnboardingLoading,
    OnboardingContact,
    OnboardingPersona,
    OnboardingSupporting,
    OnboardingVoice,
    OnboardingTone,
    OnboardingContactError,
    OnboardingError,
    OnboardingSaving,
    OnboardingWelcome,
    OnboardingConsentBanner,
    OnboardingConsentRequired,
    OnboardingConsentLoadFailure,
    OnboardingReconnectRequired,
    FriendlyNames,
    FriendlyNamesReconnectRequired,
    ReminderOff,
    ReminderOn,
    ReminderBlocked,
    ReminderDenied,
    ReminderNotification,
    AccountFailure,
    Failure;

    fun appState(now: Instant): AppUiState = when (this) {
        Login, Email, Otp, OtpResending -> AppUiState(phase = AppPhase.NeedsLogin)
        Setup -> ready(HealthSyncState.NotConnected).copy(
            initialSetupStep = InitialSetupStep.HealthConnect,
        )
        Disconnected -> ready(HealthSyncState.NotConnected)
        DisconnectedUnavailable -> ready(HealthSyncState.NotConnected).copy(
            healthAvailability = HealthConnectAvailability.TemporarilyUnavailable,
        )
        ReconnectRequired -> ready(HealthSyncState.NotConnected).copy(
            healthReconnectRequired = true,
            healthMessage = "Health Connect needs to reconnect before syncing can resume.",
        )
        Awaiting -> ready(HealthSyncState.AwaitingFirstData)
        Synced -> ready(HealthSyncState.Synced(now), observedAt = now)
        SavedStatus -> ready(HealthSyncState.Synced(now.minus(Duration.ofMinutes(5)))).copy(
            healthStatusObservedAt = now,
            healthStatusIsStale = true,
            authVerifiedOnline = false,
            healthMessage = "You're offline. Saved sync status is shown until Murph reconnects.",
        )
        PermissionVerificationFailed ->
            ready(HealthSyncState.Synced(now.minus(Duration.ofMinutes(5)))).copy(
                healthStatusObservedAt = now,
                healthStatusIsStale = true,
                healthMessage =
                    "Murph couldn't verify current Health Connect permissions. Saved status is still shown.",
            )
        Delayed -> ready(
            HealthSyncState.Delayed(now.minus(Duration.ofHours(48))),
            observedAt = now,
        )
        Attention -> ready(
            HealthSyncState.NeedsAttention(now.minus(Duration.ofHours(96))),
            observedAt = now,
        )
        ConsentRequired -> ready(HealthSyncState.NotConnected).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.Required,
                status = consentStatus(),
                showSheet = true,
            ),
        )
        ConsentBanner -> ready(HealthSyncState.NotConnected).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.Required,
                status = consentStatus(),
                showSheet = false,
            ),
        )
        ConsentLoadFailure -> ready(HealthSyncState.NotConnected).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.LoadFailed,
                message = "Murph couldn't load the latest consent documents. Check your connection and try again.",
                showSheet = true,
            ),
        )
        OnboardingLoading -> AppUiState(phase = AppPhase.Launching)
        OnboardingContact -> onboardingState(InitialOnboardingStage.Contact)
        OnboardingPersona -> onboardingState(InitialOnboardingStage.MainPersona)
        OnboardingSupporting -> onboardingState(InitialOnboardingStage.SupportingPersona)
        OnboardingVoice -> onboardingState(InitialOnboardingStage.Voice)
        OnboardingTone -> onboardingState(InitialOnboardingStage.Tone)
        OnboardingContactError -> onboardingState(InitialOnboardingStage.Contact).copy(
            initialOnboardingMessage =
                "We couldn't open the contact card. Check your connection and try again.",
        )
        OnboardingError -> onboardingState(InitialOnboardingStage.Tone).copy(
            initialOnboardingMessage =
                "Couldn't save. Try again.",
        )
        OnboardingSaving -> onboardingState(InitialOnboardingStage.Tone).copy(
            isInitialOnboardingSaving = true,
        )
        OnboardingWelcome -> onboardingState(InitialOnboardingStage.Welcome).copy(
            initialOnboardingCompletedNow = true,
        )
        OnboardingConsentBanner -> onboardingState(InitialOnboardingStage.MainPersona).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.Required,
                status = consentStatus(),
                showSheet = false,
            ),
        )
        OnboardingConsentRequired -> onboardingState(InitialOnboardingStage.MainPersona).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.Required,
                status = consentStatus(),
                message = "Murph couldn't save consent. Check your connection and try again.",
                showSheet = true,
            ),
        )
        OnboardingConsentLoadFailure -> onboardingState(InitialOnboardingStage.MainPersona).copy(
            launchConsentRecovery = LaunchConsentRecoveryUiState(
                phase = LaunchConsentRecoveryPhase.LoadFailed,
                message =
                    "Murph couldn't load the latest consent documents. Check your connection and try again.",
                showSheet = true,
            ),
        )
        OnboardingReconnectRequired -> onboardingState(InitialOnboardingStage.MainPersona).copy(
            healthReconnectRequired = true,
            healthMessage = "Health Connect needs to reconnect before syncing can resume.",
        )
        FriendlyNames -> ready(HealthSyncState.Synced(now), observedAt = now).copy(
            initialSetupStep = InitialSetupStep.FriendlyNames,
            addressBookSharing = AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = false,
            ),
        )
        FriendlyNamesReconnectRequired -> ready(HealthSyncState.NotConnected).copy(
            initialSetupStep = InitialSetupStep.FriendlyNames,
            healthReconnectRequired = true,
            healthMessage = "Health Connect needs to reconnect before syncing can resume.",
            addressBookSharing = AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = false,
            ),
        )
        ReminderOff, ReminderDenied -> ready(HealthSyncState.Synced(now))
        ReminderOn, ReminderBlocked, ReminderNotification ->
            ready(HealthSyncState.Synced(now)).copy(healthSyncReminderEnabled = true)
        AccountFailure -> AppUiState(
            phase = AppPhase.Failed(
                message = "This account cannot continue in the companion app.",
                canRetry = false,
                canSignOut = true,
                supplementalActions = FailureSupplementalActions.AccountAndLegal,
            ),
        )
        Failure -> AppUiState(
            phase = AppPhase.Failed(
                message = "This sign-in doesn't have access to the Murph companion app.",
                canRetry = false,
                canSignOut = true,
                signOutLabel = "Try a different sign-in",
                supplementalActions = FailureSupplementalActions.Support,
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
        OtpResending -> LoginUiState(
            method = LoginMethod.Phone,
            destination = "5555550100",
            phoneCountry = CountryDialCode("US", "+1"),
            code = "123456",
            codeSent = true,
            isInFlight = true,
        )
        Email -> LoginUiState(method = LoginMethod.Email)
        Login,
        Setup,
        Disconnected,
        DisconnectedUnavailable,
        ReconnectRequired,
        Awaiting,
        Synced,
        SavedStatus,
        PermissionVerificationFailed,
        Delayed,
        Attention,
        ConsentRequired,
        ConsentBanner,
        ConsentLoadFailure,
        OnboardingLoading,
        OnboardingContact,
        OnboardingPersona,
        OnboardingSupporting,
        OnboardingVoice,
        OnboardingTone,
        OnboardingContactError,
        OnboardingError,
        OnboardingSaving,
        OnboardingWelcome,
        OnboardingConsentBanner,
        OnboardingConsentRequired,
        OnboardingConsentLoadFailure,
        OnboardingReconnectRequired,
        FriendlyNames,
    FriendlyNamesReconnectRequired,
    ReminderOff,
        ReminderOn,
        ReminderBlocked,
    ReminderDenied,
    ReminderNotification,
    AccountFailure,
    Failure -> LoginUiState()
    }

    fun isInitialOnboarding(): Boolean = when (this) {
        OnboardingContact,
        OnboardingPersona,
        OnboardingSupporting,
        OnboardingVoice,
        OnboardingTone,
        OnboardingContactError,
        OnboardingError,
        OnboardingSaving,
        OnboardingWelcome,
        OnboardingConsentBanner,
        OnboardingConsentRequired,
        OnboardingConsentLoadFailure,
        OnboardingReconnectRequired -> true
        else -> false
    }

    private fun ready(
        sync: HealthSyncState,
        observedAt: Instant? = null,
    ) = AppUiState(
        phase = AppPhase.Ready,
        initialSetupStep = InitialSetupStep.Complete,
        healthAvailability = HealthConnectAvailability.Available,
        healthSync = sync,
        healthStatusObservedAt = observedAt,
        grantedResourceCount = if (sync == HealthSyncState.NotConnected) 0 else 11,
        totalResourceCount = 11,
        backendEnvironment = "screenshot",
    )

    private fun onboardingState(stage: InitialOnboardingStage) =
        ready(HealthSyncState.NotConnected).copy(
            initialOnboarding = screenshotOnboarding(),
            initialOnboardingStage = stage,
            initialOnboardingDraft = InitialOnboardingDraft(
                avatarId = "classic",
                mainPersonaId = "classic",
                supportingPersonaId = null,
                voiceId = "upbeat",
                toneId = "formal",
            ),
        )

    companion object {
        fun from(value: String?): ScreenshotScenario {
            require(!value.isNullOrBlank()) { "Screenshot scenario is required." }
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown screenshot scenario.")
        }
    }

    val showsReminderSettings: Boolean
        get() = this == SavedStatus ||
            this == ReminderOff ||
            this == ReminderOn ||
            this == ReminderBlocked ||
            this == ReminderDenied ||
            this == ReminderNotification
}

@Composable
private fun screenshotAvatarPainters(): Map<String, Painter> = mapOf(
    "hooded" to painterResource(R.drawable.screenshot_avatar_hooded),
    "classic" to painterResource(R.drawable.screenshot_avatar_classic),
    "rancher" to painterResource(R.drawable.screenshot_avatar_rancher),
    "referee" to painterResource(R.drawable.screenshot_avatar_coach),
    "gremlin" to painterResource(R.drawable.screenshot_avatar_gremlin),
    "disco" to painterResource(R.drawable.screenshot_avatar_disco),
    "beanie" to painterResource(R.drawable.screenshot_avatar_beanie),
    "headphones" to painterResource(R.drawable.screenshot_avatar_headphones),
    "sleepy" to painterResource(R.drawable.screenshot_avatar_sleepy),
    "logo-dark" to painterResource(R.drawable.screenshot_avatar_logo_dark),
    "logo-light" to painterResource(R.drawable.screenshot_avatar_logo_light),
)

internal fun screenshotOnboarding() = InitialOnboarding(
    status = InitialOnboardingStatus.Pending,
    completedNow = null,
    preferences = InitialOnboardingPreferences(null, null, null),
    catalog = InitialOnboardingCatalog(
        personas = listOf(
            InitialOnboardingPersona(
                "classic", "Classic", "Balanced, warm, and adaptable.",
                "Adds balance and flexibility.", "formal", "upbeat",
                listOf("upbeat", "warm", "deep-calm", "classic", "expressive"),
            ),
            InitialOnboardingPersona(
                "navy-seal", "Navy SEAL", "Direct, disciplined, and accountable.",
                "Adds discipline and follow-through.", "formal", "drill-sergeant",
                listOf("drill-sergeant", "husky", "country", "football-announcer", "classic"),
            ),
            InitialOnboardingPersona(
                "stoic-philosopher", "Stoic Philosopher", "Calm, grounded, and focused.",
                "Adds calm perspective.", "formal", "deep-calm",
                listOf("deep-calm", "storyteller", "narrator", "late-night", "smooth"),
            ),
            InitialOnboardingPersona(
                "scientist", "Scientist", "Curious, rigorous, and evidence-led.",
                "Adds evidence and explanation.", "formal", "radio-host",
                listOf("radio-host", "narrator", "classic", "storyteller", "british-warm"),
            ),
            InitialOnboardingPersona(
                "hype-coach", "Hype Coach", "Energetic, encouraging, and motivating.",
                "Adds energy and momentum.", "casual", "football-announcer",
                listOf("football-announcer", "upbeat", "expressive", "bubbly", "husky"),
            ),
            InitialOnboardingPersona(
                "straight-talking-friend", "Straight-Talking Friend", "Honest, practical, and human.",
                "Adds warmth and candor.", "casual", "classic",
                listOf("classic", "easygoing", "husky", "warm", "country"),
            ),
        ),
        voices = listOf(
            screenshotVoice("upbeat", "Classic Murph", "Clear, positive, and energetic."),
            screenshotVoice("classic", "New York", "Quick, wry, and a little nerdy."),
            screenshotVoice("drill-sergeant", "Drill sergeant", "Direct, intense, and commanding."),
            screenshotVoice("grandpa", "Grandpa", "Warm, older, and familiar."),
            screenshotVoice("country", "Country", "Deep, raspy, and Texas-flavored."),
            screenshotVoice("jamaican", "Jamaican, deep", "Deep and island-inflected."),
            screenshotVoice("radio-host", "Radio host", "Polished, broadcast-ready, and British."),
            screenshotVoice("deep-calm", "Deep and calming", "Low, even, and steady."),
            screenshotVoice("warm", "Warm and friendly", "Soft, friendly, and conversational."),
            screenshotVoice("husky", "Husky and bold", "Raspy, confident, and bold."),
            screenshotVoice("storyteller", "British storyteller", "Measured, narrative, and British."),
            screenshotVoice("british-warm", "British, warm", "Warm, clear, and British."),
            screenshotVoice("late-night", "Late night radio", "Calm, neutral, and late-night."),
            screenshotVoice("easygoing", "Easygoing", "Casual, relaxed, and light."),
            screenshotVoice("northern", "Eccentric northerner", "Distinctive, offbeat, and northern."),
            screenshotVoice("football-announcer", "Football announcer", "Big, energetic, and game-day."),
            screenshotVoice("sweet", "Sweet and natural", "Gentle, natural, and sweet."),
            screenshotVoice("mysterious", "Mysterious", "Quiet, intriguing, and a little dramatic."),
            screenshotVoice("narrator", "Documentary narrator", "Steady, clear, and bookish."),
            screenshotVoice("expressive", "Warm and expressive", "Warm, animated, and expressive."),
            screenshotVoice("bubbly", "Bubbly", "Lively, bright, and playful."),
            screenshotVoice("smooth", "Smooth and sweet", "Smooth and easy to listen to."),
        ),
        tones = listOf(
            InitialOnboardingTone("formal", "Formal", "Your sleep is down this week. Want to work on sleep first?"),
            InitialOnboardingTone("casual", "Casual", "sleep is way down this week. wanna fix sleep first?"),
        ),
    ),
    contactCard = InitialOnboardingContactCard(
        avatars = listOf(
            screenshotAvatar("hooded", "Hooded", "murph-headshot-01-sm.png"),
            screenshotAvatar("classic", "Classic", "murph-headshot-02-sm.png"),
            screenshotAvatar("rancher", "Rancher", "murph-headshot-05-sm.png"),
            screenshotAvatar("referee", "Coach", "murph-headshot-04-sm.png"),
            screenshotAvatar("gremlin", "Gremlin", "murph-headshot-03-sm.png"),
            screenshotAvatar("disco", "Disco", "murph-headshot-07-sm.png"),
            screenshotAvatar("beanie", "Beanie", "murph-headshot-08-sm.png"),
            screenshotAvatar("headphones", "Headphones", "murph-headshot-09-sm.png"),
            screenshotAvatar("sleepy", "Sleepy", "murph-headshot-10-sm.png"),
            screenshotLogoAvatar("logo-dark", "Dark", "murph-logo-avatar-dark.png"),
            screenshotLogoAvatar("logo-light", "Light", "murph-logo-avatar-light.png"),
            InitialOnboardingContactAvatar(
                "none",
                InitialOnboardingContactAvatarKind.Blank,
                "No photo",
                null,
            ),
        ),
        defaultAvatarId = "classic",
    ),
    contactAction = InitialOnboardingContactAction(
        href = "sms:+15555550123",
        kind = InitialOnboardingContactKind.Text,
        label = "Text Murph",
    ),
)

private fun screenshotVoice(
    id: String,
    label: String,
    description: String,
) = InitialOnboardingVoice(
    id = id,
    label = label,
    description = description,
    previewUrl = "$ScreenshotOrigin/audio/murph-voices/$id.mp3",
)

private fun screenshotAvatar(
    id: String,
    label: String,
    filename: String,
) = InitialOnboardingContactAvatar(
    id = id,
    kind = InitialOnboardingContactAvatarKind.Headshot,
    label = label,
    imageUrl = "$ScreenshotOrigin/murph-headshots/$filename",
)

private fun screenshotLogoAvatar(
    id: String,
    label: String,
    filename: String,
) = InitialOnboardingContactAvatar(
    id = id,
    kind = InitialOnboardingContactAvatarKind.Logo,
    label = label,
    imageUrl = "$ScreenshotOrigin/brand-logos/$filename",
)

private const val ScreenshotOrigin = "https://www.withmurph.ai"

internal fun consentStatus(): LaunchConsentStatus {
    val terms = LaunchConsentDocument(
        id = "terms-of-service",
        title = "Murph Terms of Service",
        version = "2026-07-23",
        href = "https://www.withmurph.ai/legal/terms",
        pdfHref = "https://www.withmurph.ai/legal/terms.pdf",
    )
    val privacy = LaunchConsentDocument(
        id = "privacy-policy",
        title = "Murph Privacy Policy",
        version = "2026-07-23",
        href = "https://www.withmurph.ai/legal/privacy",
        pdfHref = "https://www.withmurph.ai/legal/privacy.pdf",
    )
    val health = LaunchConsentDocument(
        id = "consumer-health-data-notice",
        title = "Murph Consumer Health Data Notice",
        version = "2026-07-23",
        href = "https://www.withmurph.ai/consumer-health-data-privacy-policy",
        pdfHref = "https://www.withmurph.ai/legal/consumer-health-data-notice.pdf",
    )
    val aiSafety = LaunchConsentDocument(
        id = "health-ai-safety-disclosure",
        title = "Murph Health AI Safety Disclosure",
        version = "2026-07-23",
        href = "https://www.withmurph.ai/legal/health-ai-safety-disclosure",
        pdfHref = "https://www.withmurph.ai/legal/health-ai-safety-disclosure.pdf",
    )
    return LaunchConsentStatus(
        launchGranted = false,
        documents = listOf(terms, privacy, health, aiSafety),
        launchScopes = listOf(
            LaunchConsentScopeStatus(
                scope = LaunchConsentScope.Legal,
                granted = false,
                documents = listOf(terms, privacy, aiSafety),
                missingDocuments = listOf(terms, privacy, aiSafety),
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
    onDeferHealthConnectInitialSetup = {},
    onOpenHealthConnect = {},
    onSyncNow = {},
    onPrepareInitialAddressBookSharing = {},
    onDeferAddressBookSharingInitialSetup = {},
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
