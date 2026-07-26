package ai.withmurph.companion.ui

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.home.HomeScreen
import ai.withmurph.companion.ui.login.LoginScreen
import ai.withmurph.companion.ui.settings.SettingsScreen
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class AppTab {
    Home,
    Settings,
}

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
            onPhoneCountryChanged = actions.onPhoneCountryChanged,
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
private fun ReadyApp(
    state: AppUiState,
    actions: MurphActions,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var showsHealthConsent by rememberSaveable { mutableStateOf(false) }
    var showsWhoopGuide by rememberSaveable { mutableStateOf(false) }
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
        modifier = Modifier.fillMaxSize(),
        containerColor = MurphColors.Cream,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            MurphTabBar(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    state = state,
                    onConnectHealth = { showsHealthConsent = true },
                    onOpenHealthConnect = actions.onOpenHealthConnect,
                    onShowWhoopGuide = { showsWhoopGuide = true },
                    onSyncNow = actions.onSyncNow,
                )
                AppTab.Settings -> SettingsScreen(
                    onOpenHealthConnect = actions.onOpenHealthConnect,
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
            modifier = Modifier.padding(top = 48.dp).fillMaxHeight(),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MurphColors.Cream,
            dragHandle = { MurphSheetHandle() },
        ) {
            HealthConsentContent(
                onContinue = {
                    connectAfterConsent = true
                    showsHealthConsent = false
                },
            )
        }
    }

    if (showsWhoopGuide) {
        ModalBottomSheet(
            onDismissRequest = { showsWhoopGuide = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MurphColors.Cream,
            dragHandle = { MurphSheetHandle() },
        ) {
            WhoopGuideContent(
                onOpenHealthConnect = {
                    showsWhoopGuide = false
                    actions.onOpenHealthConnect()
                },
                onBack = { showsWhoopGuide = false },
            )
        }
    }
}

@Composable
private fun MurphTabBar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MurphColors.Cream)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(250.dp)
                .height(72.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(36.dp),
                    ambientColor = MurphColors.Slate.copy(alpha = 0.08f),
                    spotColor = MurphColors.Slate.copy(alpha = 0.12f),
                ),
            shape = RoundedCornerShape(36.dp),
            color = MurphColors.NavigationSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MurphTab(
                    label = "Home",
                    icon = MurphIconKind.Home,
                    selected = selectedTab == AppTab.Home,
                    onClick = { onSelect(AppTab.Home) },
                    modifier = Modifier.weight(1f),
                )
                MurphTab(
                    label = "Settings",
                    icon = MurphIconKind.GearFilled,
                    selected = selectedTab == AppTab.Settings,
                    onClick = { onSelect(AppTab.Settings) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MurphTab(
    label: String,
    icon: MurphIconKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MurphColors.SageDark else MurphColors.Slate
    val background = if (selected) {
        MurphColors.MutedSurfaceOpaque
    } else {
        MurphColors.NavigationSurface
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(30.dp))
            .background(if (selected) background else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MurphIcon(
            kind = icon,
            modifier = Modifier.size(25.dp),
            tint = tint,
            backgroundColor = background,
            contentDescription = null,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = tint,
        )
    }
}

@Composable
private fun MurphSheetHandle() {
    Box(
        Modifier
            .padding(vertical = 10.dp)
            .width(40.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MurphColors.SlateMuted.copy(alpha = 0.35f)),
    )
}

@Composable
private fun HealthConsentContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MurphIcon(
                kind = MurphIconKind.HealthCard,
                modifier = Modifier.size(40.dp),
                contentDescription = null,
            )
            Text(
                text = "How Health Connect works with Murph",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 33.sp,
                ),
                color = MurphColors.Slate,
            )
            Text(
                text = "You choose what Health Connect shares.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        MurphCard {
            ConsentRow(
                icon = MurphIconKind.Checklist,
                text = "Choose each category on the next screen.",
            )
            ConsentRow(
                icon = MurphIconKind.Sparkles,
                text = "Murph and its contracted AI providers use synced data only to run Murph — never for ads, model training, or sale.",
            )
            ConsentRow(
                icon = MurphIconKind.Gear,
                text = "Revoke access in Health Connect. Manage or delete your account at withmurph.ai.",
            )
            ConsentRow(
                icon = MurphIconKind.Refresh,
                text = "Murph syncs when you open the app or choose Sync now.",
            )
        }

        MurphPrimaryButton(
            text = "Continue to Health Connect",
            onClick = onContinue,
        )
    }
}

@Composable
private fun ConsentRow(
    icon: MurphIconKind,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MurphIcon(
            kind = icon,
            modifier = Modifier.size(22.dp),
            contentDescription = null,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.SlateMuted,
        )
    }
}

@Composable
private fun WhoopGuideContent(
    onOpenHealthConnect: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MurphIcon(
                kind = MurphIconKind.Refresh,
                modifier = Modifier.size(40.dp),
                contentDescription = null,
            )
            Text(
                text = "Share WHOOP with Health Connect",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 33.sp,
                ),
                color = MurphColors.Slate,
            )
            Text(
                text = "Set up the relay once, then return to Murph.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        MurphCard {
            WhoopStep(
                number = "1",
                title = "Open WHOOP",
                detail = "Go to More → App Settings → Integrations.",
            )
            WhoopStep(
                number = "2",
                title = "Choose Health Connect",
                detail = "Turn on the categories you want WHOOP to share.",
            )
            WhoopStep(
                number = "3",
                title = "Return to Murph",
                detail = "Connect Health Connect and approve the same categories.",
            )
        }

        Text(
            text = "WHOOP decides which fields it writes. Murph can sync only data that appears in Health Connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MurphColors.SlateMuted,
        )

        MurphPrimaryButton(
            text = "Open Health Connect",
            onClick = onOpenHealthConnect,
        )
        MurphLinkButton(
            text = "Back",
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun WhoopStep(
    number: String,
    title: String,
    detail: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MurphColors.MutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SageDark,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.Slate,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
            )
        }
    }
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
private fun FailureScreen(
    failure: AppPhase.Failed,
    actions: MurphActions,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = failure.message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
            if (failure.canRetry) {
                Spacer(Modifier.height(20.dp))
                MurphPrimaryButton("Try again", actions.onRetry)
            }
            if (failure.canSignOut) {
                Spacer(Modifier.height(4.dp))
                MurphLinkButton("Sign out and start fresh", actions.onSignOut)
            }
        }
    }
}

data class MurphActions(
    val onLoginMethodChanged: (LoginMethod) -> Unit,
    val onPhoneCountryChanged: (CountryDialCode) -> Unit,
    val onLoginDestinationChanged: (String) -> Unit,
    val onLoginCodeChanged: (String) -> Unit,
    val onSendLoginCode: () -> Unit,
    val onConfirmLoginCode: () -> Unit,
    val onResendLoginCode: () -> Unit,
    val onChangeLoginDestination: () -> Unit,
    val onConnectHealth: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onSyncNow: () -> Unit,
    val onOpenPrivacy: () -> Unit,
    val onOpenTerms: () -> Unit,
    val onOpenHealthNotice: () -> Unit,
    val onOpenAiSafety: () -> Unit,
    val onOpenSupport: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onRetry: () -> Unit,
    val onSignOut: () -> Unit,
)
