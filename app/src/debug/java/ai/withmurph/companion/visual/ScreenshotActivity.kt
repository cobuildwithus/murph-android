package ai.withmurph.companion.visual

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.LaunchConsentRecoveryPhase
import ai.withmurph.companion.app.LaunchConsentRecoveryUiState
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.core.MealPhotoCaptureState
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.MurphApp
import ai.withmurph.companion.ui.MurphDestination
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
                    initialDestination = scenario.destination(),
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
    Awaiting,
    Synced,
    Delayed,
    Attention,
    Meals,
    ConsentRequired,
    ConsentLoadFailure,
    Failure;

    fun appState(now: Instant): AppUiState = when (this) {
        Login, Email, Otp -> AppUiState(phase = AppPhase.NeedsLogin)
        Setup -> ready(HealthSyncState.NotConnected)
        Awaiting -> ready(HealthSyncState.AwaitingFirstData)
        Synced -> ready(HealthSyncState.Synced(now))
        Delayed -> ready(HealthSyncState.Delayed(now.minus(Duration.ofHours(48))))
        Attention -> ready(
            HealthSyncState.NeedsAttention(now.minus(Duration.ofHours(96))),
        )
        Meals -> ready(HealthSyncState.NotConnected).copy(
            mealPhotoCapture = MealPhotoCaptureState.On,
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
        Awaiting,
        Synced,
        Delayed,
        Attention,
        Meals,
        ConsentRequired,
        ConsentLoadFailure,
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

    fun destination(): MurphDestination = if (this == Meals) {
        MurphDestination.Meals
    } else {
        MurphDestination.Home
    }

    companion object {
        fun from(value: String?): ScreenshotScenario =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Setup
    }
}

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
                missingDocuments = listOf(legal),
            ),
            LaunchConsentScopeStatus(
                scope = LaunchConsentScope.HealthData,
                granted = false,
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
    onEnableMealPhotos = {},
    onRefreshMealPhotos = {},
    onTurnOffMealPhotos = {},
    onApproveMealPhoto = {},
    onDismissMealPhoto = {},
    onShowLaunchConsent = {},
    onDismissLaunchConsent = {},
    onRetryLaunchConsent = {},
    onAcceptLaunchConsent = {},
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
