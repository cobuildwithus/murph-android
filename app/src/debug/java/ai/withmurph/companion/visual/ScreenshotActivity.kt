package ai.withmurph.companion.visual

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
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
    Awaiting,
    Synced,
    Delayed,
    Attention,
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
        Login, Setup, Awaiting, Synced, Delayed, Attention, Failure -> LoginUiState()
    }

    private fun ready(sync: HealthSyncState) = AppUiState(
        phase = AppPhase.Ready,
        healthAvailability = HealthConnectAvailability.Available,
        healthSync = sync,
        grantedResourceCount = if (sync == HealthSyncState.NotConnected) 0 else 4,
        totalResourceCount = 4,
        backendEnvironment = "screenshot",
    )

    companion object {
        fun from(value: String?): ScreenshotScenario =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Setup
    }
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
    onOpenPrivacy = {},
    onOpenTerms = {},
    onOpenHealthNotice = {},
    onOpenAiSafety = {},
    onOpenSupport = {},
    onDeleteAccount = {},
    onRetry = {},
    onSignOut = {},
)
