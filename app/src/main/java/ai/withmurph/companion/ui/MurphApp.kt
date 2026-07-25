package ai.withmurph.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphOutlineButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.home.HomeScreen
import ai.withmurph.companion.ui.login.LoginScreen
import ai.withmurph.companion.ui.settings.SettingsScreen
import ai.withmurph.companion.ui.theme.MurphColors

private enum class AppTab { Home, Settings }

@Composable
fun MurphApp(
    appState: AppUiState,
    loginState: LoginUiState,
    actions: MurphActions,
) {
    when (val phase = appState.phase) {
        AppPhase.Launching -> LoadingScreen()
        AppPhase.NeedsLogin -> LoginScreen(
            state = loginState,
            onMethodChanged = actions.onLoginMethodChanged,
            onDestinationChanged = actions.onLoginDestinationChanged,
            onCodeChanged = actions.onLoginCodeChanged,
            onSendCode = actions.onSendLoginCode,
            onConfirmCode = actions.onConfirmLoginCode,
            onResendCode = actions.onResendLoginCode,
            onChangeDestination = actions.onChangeLoginDestination,
            onOpenPrivacy = actions.onOpenPrivacy,
            onOpenTerms = actions.onOpenTerms,
        )
        AppPhase.Ready -> ReadyApp(appState, actions)
        is AppPhase.Failed -> FailureScreen(phase, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyApp(state: AppUiState, actions: MurphActions) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var showsHealthConsent by rememberSaveable { mutableStateOf(false) }
    var connectAfterConsent by remember { mutableStateOf(false) }

    LaunchedEffect(showsHealthConsent, connectAfterConsent) {
        if (!showsHealthConsent && connectAfterConsent) {
            connectAfterConsent = false
            actions.onConnectHealth()
        }
    }
    LaunchedEffect(state.healthSync) {
        if (state.healthSync != HealthSyncState.NotConnected) {
            showsHealthConsent = false
            connectAfterConsent = false
        }
    }

    Scaffold(
        containerColor = MurphColors.Cream,
        bottomBar = {
            NavigationBar(containerColor = MurphColors.Cream) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.Home,
                    onClick = { selectedTab = AppTab.Home },
                    icon = {
                        NavigationMark(
                            selected = selectedTab == AppTab.Home,
                            shape = CircleShape,
                        )
                    },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.Settings,
                    onClick = { selectedTab = AppTab.Settings },
                    icon = {
                        NavigationMark(
                            selected = selectedTab == AppTab.Settings,
                            shape = RoundedCornerShape(2.dp),
                        )
                    },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    state = state,
                    onConnectHealth = { showsHealthConsent = true },
                    onOpenHealthConnect = actions.onOpenHealthConnect,
                    onSyncNow = actions.onSyncNow,
                )
                AppTab.Settings -> SettingsScreen(
                    state = state,
                    onOpenHealthConnect = actions.onOpenHealthConnect,
                    onEnableBackgroundSync = actions.onEnableBackgroundSync,
                    onDisableBackgroundSync = actions.onDisableBackgroundSync,
                    onOpenPrivacy = actions.onOpenPrivacy,
                    onOpenTerms = actions.onOpenTerms,
                    onOpenHealthNotice = actions.onOpenHealthNotice,
                    onOpenAiSafety = actions.onOpenAiSafety,
                    onOpenSupport = actions.onOpenSupport,
                    onDeleteAccount = actions.onDeleteAccount,
                    onSignOut = actions.onSignOut,
                )
            }
        }
    }

    if (showsHealthConsent) {
        ModalBottomSheet(
            onDismissRequest = { showsHealthConsent = false },
            containerColor = MurphColors.Cream,
        ) {
            HealthConsentContent(
                onContinue = {
                    connectAfterConsent = true
                    showsHealthConsent = false
                },
            )
        }
    }
}

@Composable
private fun NavigationMark(selected: Boolean, shape: Shape) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(
                color = if (selected) MurphColors.SageDark else MurphColors.SlateMuted,
                shape = shape,
            ),
    )
}

@Composable
private fun HealthConsentContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "How Health Connect works with Murph",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            "You choose each requested category on the next screen.",
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
        )
        MurphCard {
            ConsentLine("Murph requests read-only sleep, workout, steps, and active-calorie data.")
            ConsentLine(
                "Murph and its contracted AI providers use synced data only to run Murph — never for ads, general model training, or sale.",
            )
            ConsentLine(
                "Revoke access in Health Connect. Manage or delete your Murph account at withmurph.ai.",
            )
            ConsentLine(
                "Unsupported fields and categories your apps do not write remain unavailable.",
            )
        }
        MurphPrimaryButton(
            text = "Continue to Health Connect",
            onClick = onContinue,
        )
    }
}

@Composable
private fun ConsentLine(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MurphColors.SlateMuted,
    )
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(MurphColors.Cream),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MurphColors.SageDark)
    }
}

@Composable
private fun FailureScreen(failure: AppPhase.Failed, actions: MurphActions) {
    Column(
        modifier = Modifier.fillMaxSize().background(MurphColors.Cream).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            failure.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
        )
        if (failure.canRetry) {
            MurphPrimaryButton("Try again", actions.onRetry)
        }
        if (failure.canSignOut) {
            MurphOutlineButton("Sign out and start fresh", actions.onSignOut)
        }
    }
}

data class MurphActions(
    val onLoginMethodChanged: (LoginMethod) -> Unit,
    val onLoginDestinationChanged: (String) -> Unit,
    val onLoginCodeChanged: (String) -> Unit,
    val onSendLoginCode: () -> Unit,
    val onConfirmLoginCode: () -> Unit,
    val onResendLoginCode: () -> Unit,
    val onChangeLoginDestination: () -> Unit,
    val onConnectHealth: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onSyncNow: () -> Unit,
    val onEnableBackgroundSync: () -> Unit,
    val onDisableBackgroundSync: () -> Unit,
    val onOpenPrivacy: () -> Unit,
    val onOpenTerms: () -> Unit,
    val onOpenHealthNotice: () -> Unit,
    val onOpenAiSafety: () -> Unit,
    val onOpenSupport: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onRetry: () -> Unit,
    val onSignOut: () -> Unit,
)
