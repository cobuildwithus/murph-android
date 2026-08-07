package ai.withmurph.companion.ui

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.LaunchConsentRecoveryPhase
import ai.withmurph.companion.app.LaunchConsentRecoveryUiState
import ai.withmurph.companion.app.InitialOnboardingStage
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.home.HomeScreen
import ai.withmurph.companion.ui.login.LoginScreen
import ai.withmurph.companion.ui.onboarding.InitialOnboardingScreen
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AppTab {
    Home,
    Settings,
}

private enum class AddressBookConsentAction {
    InitialSetup,
    Settings,
}

internal data class ReadyAppShellState(
    val activeTab: AppTab,
    val showsTabBar: Boolean,
    val showsReconnect: Boolean,
)

internal data class FailureExternalAction(
    val label: String,
    val onClick: () -> Unit,
)

internal fun failureExternalActions(
    failure: AppPhase.Failed,
    onOpenSupport: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
): List<FailureExternalAction> = if (failure.canSignOut) {
    listOf(
        FailureExternalAction("Contact support", onOpenSupport),
        FailureExternalAction("Delete account", onDeleteAccount),
        FailureExternalAction("Privacy Policy", onOpenPrivacy),
        FailureExternalAction("Terms", onOpenTerms),
    )
} else {
    emptyList()
}

internal fun readyAppShellState(
    selectedTab: AppTab,
    initialSetupStep: InitialSetupStep,
    healthReconnectRequired: Boolean,
): ReadyAppShellState {
    val navigationAvailable =
        initialSetupStep == InitialSetupStep.Complete || healthReconnectRequired
    val activeTab = if (navigationAvailable) selectedTab else AppTab.Home
    return ReadyAppShellState(
        activeTab = activeTab,
        showsTabBar = navigationAvailable,
        showsReconnect = healthReconnectRequired && activeTab == AppTab.Home,
    )
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
        AppPhase.Ready -> if (
            appState.initialOnboarding != null &&
            appState.launchConsentRecovery == null &&
            !appState.healthReconnectRequired
        ) {
            InitialOnboardingScreen(appState, actions)
        } else {
            ReadyApp(appState, actions)
        }
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
    var showsAddressBookConsent by rememberSaveable { mutableStateOf(false) }
    var connectAfterConsent by rememberSaveable { mutableStateOf(false) }
    var addressBookConsentAction by rememberSaveable {
        mutableStateOf<AddressBookConsentAction?>(null)
    }
    val shellState = readyAppShellState(
        selectedTab = selectedTab,
        initialSetupStep = state.initialSetupStep,
        healthReconnectRequired = state.healthReconnectRequired,
    )
    val bannerRecovery = state.launchConsentRecovery?.takeIf { !it.showSheet }

    LaunchedEffect(shellState.showsTabBar) {
        if (!shellState.showsTabBar) selectedTab = AppTab.Home
    }

    LaunchedEffect(showsHealthConsent, connectAfterConsent) {
        if (!showsHealthConsent && connectAfterConsent) {
            connectAfterConsent = false
            actions.onConnectHealth()
        }
    }
    LaunchedEffect(showsAddressBookConsent, addressBookConsentAction) {
        if (!showsAddressBookConsent) {
            when (addressBookConsentAction) {
                AddressBookConsentAction.InitialSetup ->
                    actions.onPrepareInitialAddressBookSharing()
                AddressBookConsentAction.Settings -> actions.onShareAddressBook()
                null -> Unit
            }
            addressBookConsentAction = null
        }
    }
    LaunchedEffect(state.healthSync) {
        if (state.healthSync != HealthSyncState.NotConnected) {
            showsHealthConsent = false
            connectAfterConsent = false
        }
    }
    LaunchedEffect(state.healthReconnectRequired) {
        if (state.healthReconnectRequired) {
            showsHealthConsent = false
            showsAddressBookConsent = false
            connectAfterConsent = false
            addressBookConsentAction = null
        }
    }
    LaunchedEffect(state.launchConsentRecovery != null) {
        if (state.launchConsentRecovery != null) {
            showsHealthConsent = false
            showsAddressBookConsent = false
            connectAfterConsent = false
            addressBookConsentAction = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MurphColors.Cream,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (shellState.showsTabBar) {
                MurphTabBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            bannerRecovery?.let { recovery ->
                LaunchConsentBanner(
                    recovery = recovery,
                    onOpen = actions.onShowLaunchConsent,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (shellState.showsReconnect) {
                    ReconnectHealthContent(
                        availability = state.healthAvailability,
                        message = state.healthMessage,
                        isConnecting = state.isConnectingHealth,
                        onReconnect = {
                            if (state.launchConsentRecovery == null) {
                                showsHealthConsent = true
                            } else {
                                actions.onShowLaunchConsent()
                            }
                        },
                        onOpenHealthConnect = actions.onOpenHealthConnect,
                        onSignOut = actions.onSignOut,
                    )
                } else {
                    when (shellState.activeTab) {
                        AppTab.Home -> HomeScreen(
                            state = state,
                            onConnectHealth = {
                                if (state.launchConsentRecovery == null) {
                                    showsHealthConsent = true
                                } else {
                                    actions.onShowLaunchConsent()
                                }
                            },
                            onOpenHealthConnect = actions.onOpenHealthConnect,
                            onDeferHealthSetup = actions.onDeferHealthConnectInitialSetup,
                            onSyncNow = {
                                if (state.launchConsentRecovery == null) {
                                    actions.onSyncNow()
                                } else {
                                    actions.onShowLaunchConsent()
                                }
                            },
                            onShareAddressBookFromSetup = {
                                if (state.launchConsentRecovery == null) {
                                    addressBookConsentAction =
                                        AddressBookConsentAction.InitialSetup
                                    showsAddressBookConsent = true
                                } else {
                                    actions.onShowLaunchConsent()
                                }
                            },
                            onAddressBookSetupSecondaryAction = if (
                                state.addressBookHasInterruptedReplacement
                            ) {
                                actions.onStopAddressBook
                            } else {
                                actions.onDeferAddressBookSharingInitialSetup
                            },
                            onOpenAppSettings = actions.onOpenAppSettings,
                            reserveStatusBarInset = bannerRecovery == null,
                        )
                        AppTab.Settings -> SettingsScreen(
                            state = state,
                            onShareAddressBook = {
                                if (state.launchConsentRecovery == null) {
                                    addressBookConsentAction = AddressBookConsentAction.Settings
                                    showsAddressBookConsent = true
                                } else {
                                    actions.onShowLaunchConsent()
                                }
                            },
                            onRefreshAddressBook = {
                                if (state.launchConsentRecovery == null) {
                                    actions.onRefreshAddressBook()
                                } else {
                                    actions.onShowLaunchConsent()
                                }
                            },
                            onStopAddressBook = actions.onStopAddressBook,
                            onOpenAppSettings = actions.onOpenAppSettings,
                            onOpenHealthConnect = actions.onOpenHealthConnect,
                            onOpenPrivacy = actions.onOpenPrivacy,
                            onOpenTerms = actions.onOpenTerms,
                            onOpenHealthNotice = actions.onOpenHealthNotice,
                            onOpenAiSafety = actions.onOpenAiSafety,
                            onOpenSupport = actions.onOpenSupport,
                            onDeleteAccount = actions.onDeleteAccount,
                            onSignOut = actions.onSignOut,
                            reserveStatusBarInset = bannerRecovery == null,
                        )
                    }
                }
            }
        }
    }

    state.launchConsentRecovery?.takeIf { it.showSheet }?.let { recovery ->
        val canDismissLaunchConsent by rememberUpdatedState(
            recovery.phase != LaunchConsentRecoveryPhase.Saving,
        )
        val launchConsentSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                target != SheetValue.Hidden || canDismissLaunchConsent
            },
        )
        ModalBottomSheet(
            onDismissRequest = {
                if (canDismissLaunchConsent) actions.onDismissLaunchConsent()
            },
            modifier = Modifier.padding(top = 48.dp).fillMaxHeight(),
            sheetState = launchConsentSheetState,
            containerColor = MurphColors.Cream,
            dragHandle = { MurphSheetHandle() },
        ) {
            LaunchConsentRecoveryContent(
                recovery = recovery,
                onAccept = actions.onAcceptLaunchConsent,
                onRetry = actions.onRetryLaunchConsent,
                onDismiss = actions.onDismissLaunchConsent,
                onOpenDocument = actions.onOpenConsentDocument,
            )
        }
    }

    if (showsHealthConsent && state.launchConsentRecovery == null) {
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

    if (showsAddressBookConsent && state.launchConsentRecovery == null) {
        ModalBottomSheet(
            onDismissRequest = {
                showsAddressBookConsent = false
                addressBookConsentAction = null
            },
            modifier = Modifier.padding(top = 48.dp).fillMaxHeight(),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MurphColors.Cream,
            dragHandle = { MurphSheetHandle() },
        ) {
            AddressBookConsentContent(
                onContinue = {
                    showsAddressBookConsent = false
                },
            )
        }
    }

}

@Composable
private fun ReconnectHealthContent(
    availability: HealthConnectAvailability,
    message: String?,
    isConnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onSignOut: () -> Unit,
) {
    val detail = when (availability) {
        HealthConnectAvailability.Available ->
            message ?: "Health Connect needs to reconnect before syncing can resume."
        HealthConnectAvailability.InstallOrUpdateRequired ->
            "Install or update Health Connect, then try again."
        HealthConnectAvailability.OnboardingRequired ->
            "Finish setting up Health Connect, then try again."
        HealthConnectAvailability.AppNotAllowed ->
            "This build isn't authorized for Health Connect. Contact Murph support."
        HealthConnectAvailability.Unsupported ->
            "Health Connect isn't supported on this device."
        HealthConnectAvailability.TemporarilyUnavailable ->
            "Health Connect isn't ready yet. Try again in a moment."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = detail,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )

            when (availability) {
                HealthConnectAvailability.Available -> MurphPrimaryButton(
                    text = if (isConnecting) "Connecting…" else "Reconnect Health Connect",
                    onClick = onReconnect,
                    enabled = !isConnecting,
                )
                HealthConnectAvailability.InstallOrUpdateRequired -> MurphPrimaryButton(
                    text = "Install or update Health Connect",
                    onClick = onOpenHealthConnect,
                    enabled = !isConnecting,
                )
                HealthConnectAvailability.OnboardingRequired -> MurphPrimaryButton(
                    text = "Finish setting up Health Connect",
                    onClick = onOpenHealthConnect,
                    enabled = !isConnecting,
                )
                HealthConnectAvailability.AppNotAllowed,
                HealthConnectAvailability.Unsupported,
                HealthConnectAvailability.TemporarilyUnavailable -> Unit
            }

            MurphLinkButton(
                text = "Sign out and stop syncing",
                onClick = onSignOut,
            )
        }
    }
}

@Composable
private fun LaunchConsentBanner(
    recovery: LaunchConsentRecoveryUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        color = MurphColors.Card,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MurphIcon(
                    kind = MurphIconKind.Checklist,
                    modifier = Modifier.size(24.dp),
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Consent needed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MurphColors.Slate,
                    )
                    Text(
                        text = recovery.message
                            ?: "Review Murph’s consent to continue health sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MurphColors.SlateMuted,
                    )
                }
                Text(
                    text = "Review",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = MurphColors.SageDark,
                )
            }
            HorizontalDivider(color = MurphColors.BorderWarm)
        }
    }
}

@Composable
private fun LaunchConsentRecoveryContent(
    recovery: LaunchConsentRecoveryUiState,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val status = recovery.status
    val missingScopes = status?.missingLaunchScopes.orEmpty()
    val isPreparing = status == null &&
        (
            recovery.phase == LaunchConsentRecoveryPhase.Pausing ||
                recovery.phase == LaunchConsentRecoveryPhase.Loading
        )
    val progressLabel = when (recovery.phase) {
        LaunchConsentRecoveryPhase.Pausing -> "Pausing health sync…"
        LaunchConsentRecoveryPhase.Loading -> "Loading consent…"
        LaunchConsentRecoveryPhase.Saving -> "Saving consent…"
        LaunchConsentRecoveryPhase.LoadFailed,
        LaunchConsentRecoveryPhase.Required -> null
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        val contentModifier = Modifier
            .align(Alignment.TopCenter)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)

        if (isPreparing) {
            Column(
                modifier = contentModifier.heightIn(min = 280.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MurphColors.SageDark,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = if (recovery.phase == LaunchConsentRecoveryPhase.Pausing) {
                        "Pausing health sync…"
                    } else {
                        "Loading consent…"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MurphColors.SlateMuted,
                )
            }
        } else {
            val copy = if (
                recovery.phase == LaunchConsentRecoveryPhase.LoadFailed && status == null
            ) {
                LaunchConsentCopy(
                    title = "Use your health data",
                    description = "Review Murph’s consent to continue health sync.",
                    showsHealthAssurances = false,
                )
            } else {
                launchConsentCopy(missingScopes)
            }
            val documents = launchConsentDocuments(recovery)

            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                LaunchConsentHeader(copy)

                if (status != null && copy.showsHealthAssurances) {
                    LaunchConsentAssurances()
                }

                if (documents.isNotEmpty()) {
                    LaunchConsentDocumentGrid(
                        documents = documents,
                        onOpenDocument = onOpenDocument,
                    )
                }

                val inlineMessage = when (recovery.phase) {
                    LaunchConsentRecoveryPhase.LoadFailed -> recovery.message
                        ?: "We couldn't load the current consent documents. Try again."
                    LaunchConsentRecoveryPhase.Required -> recovery.message
                    LaunchConsentRecoveryPhase.Pausing,
                    LaunchConsentRecoveryPhase.Loading,
                    LaunchConsentRecoveryPhase.Saving -> null
                }
                if (inlineMessage != null) {
                    LaunchConsentInlineMessage(inlineMessage)
                }

                if (progressLabel != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MurphColors.SageDark,
                            strokeWidth = 3.dp,
                        )
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MurphColors.SlateMuted,
                        )
                    }
                }

                when (recovery.phase) {
                    LaunchConsentRecoveryPhase.LoadFailed -> {
                        MurphPrimaryButton("Try again", onRetry)
                        MurphLinkButton(
                            text = "Not now",
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    LaunchConsentRecoveryPhase.Required -> {
                        MurphPrimaryButton(
                            text = if (status?.launchGranted == true) "Continue" else "I Consent",
                            onClick = onAccept,
                            enabled = recovery.canAccept || status?.launchGranted == true,
                        )
                        MurphLinkButton(
                            text = "Not now",
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    LaunchConsentRecoveryPhase.Pausing,
                    LaunchConsentRecoveryPhase.Loading,
                    LaunchConsentRecoveryPhase.Saving -> Unit
                }
            }
        }
    }
}

@Composable
private fun LaunchConsentHeader(copy: LaunchConsentCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MurphIcon(
            kind = MurphIconKind.Checklist,
            modifier = Modifier.size(40.dp),
            contentDescription = null,
        )
        Text(
            text = copy.title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 28.sp,
                lineHeight = 33.sp,
            ),
            color = MurphColors.Slate,
        )
        Text(
            text = copy.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
        )
    }
}

@Composable
private fun LaunchConsentAssurances() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MurphColors.MutedSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LaunchConsentAssurance("We do not sell health data.")
            LaunchConsentAssurance(
                "We do not use Murph-managed health data to train general-purpose AI models.",
            )
        }
    }
}

@Composable
private fun LaunchConsentAssurance(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MurphIcon(
            kind = MurphIconKind.CheckCircle,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            contentDescription = null,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.Slate,
        )
    }
}

@Composable
private fun LaunchConsentDocumentGrid(
    documents: List<LaunchConsentDocument>,
    onOpenDocument: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        documents.chunked(2).forEach { rowDocuments ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowDocuments.forEach { document ->
                    ConsentDocumentLink(
                        document = document,
                        onOpenDocument = onOpenDocument,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowDocuments.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConsentDocumentLink(
    document: LaunchConsentDocument,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MurphColors.MutedSurface)
            .clickable(role = Role.Button) { onOpenDocument(document.href) }
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shortConsentDocumentTitle(document.title),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MurphColors.SageDark,
        )
        MurphIcon(
            kind = MurphIconKind.External,
            modifier = Modifier.size(12.dp),
            contentDescription = null,
        )
    }
}

@Composable
private fun LaunchConsentInlineMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MurphColors.Sienna.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, MurphColors.Sienna.copy(alpha = 0.2f)),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MurphColors.Sienna,
        )
    }
}

private fun launchConsentDocuments(
    recovery: LaunchConsentRecoveryUiState,
): List<LaunchConsentDocument> {
    val status = recovery.status ?: return emptyList()
    val pendingScopeDocuments = status.missingLaunchScopes.flatMap { it.documents }
    val source = pendingScopeDocuments.ifEmpty { status.documents }
    return source.distinctBy { it.id }
}

private data class LaunchConsentCopy(
    val title: String,
    val description: String,
    val showsHealthAssurances: Boolean,
)

private fun launchConsentCopy(
    missingScopes: List<LaunchConsentScopeStatus>,
): LaunchConsentCopy {
    val pending = missingScopes.map { it.scope }.toSet()
    return when (pending) {
        setOf(LaunchConsentScope.Legal) -> LaunchConsentCopy(
            title = "Review Murph’s terms",
            description = "Review the updated terms and disclosures that govern your use of Murph.",
            showsHealthAssurances = false,
        )
        setOf(LaunchConsentScope.HealthData) -> LaunchConsentCopy(
            title = "Use your health data",
            description = "Murph and contracted AI providers use your health data to personalize your experience.",
            showsHealthAssurances = true,
        )
        else -> LaunchConsentCopy(
            title = "Use your health data",
            description = "By consenting, you accept the terms and let Murph and contracted AI providers use your health data to personalize your experience.",
            showsHealthAssurances = true,
        )
    }
}

private fun shortConsentDocumentTitle(title: String): String = when (title) {
    "Murph Terms of Service" -> "Terms"
    "Murph Privacy Policy" -> "Privacy"
    "Murph Consumer Health Data Notice" -> "Health data"
    "Murph Health AI Safety Disclosure" -> "AI safety"
    else -> title.removePrefix("Murph ")
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
                modifier = Modifier.fillMaxSize().padding(6.dp).selectableGroup(),
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
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
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
private fun AddressBookConsentContent(onContinue: () -> Unit) {
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
                kind = MurphIconKind.Checklist,
                modifier = Modifier.size(40.dp),
                contentDescription = null,
            )
            Text(
                text = "Share familiar names with Murph",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 33.sp,
                ),
                color = MurphColors.Slate,
            )
            Text(
                text = "Optional",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        MurphCard {
            ConsentRow(
                icon = MurphIconKind.Checklist,
                text = "Murph can use first names and last initials in group replies that others can see.",
            )
            ConsentRow(
                icon = MurphIconKind.Shield,
                text = "Murph never messages your contacts or stores phone numbers in readable form.",
            )
            ConsentRow(
                icon = MurphIconKind.Trash,
                text = "You can stop sharing and delete these names at any time.",
            )
        }

        MurphPrimaryButton(
            text = "Continue to Contacts",
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
    val externalActions = failureExternalActions(
        failure = failure,
        onOpenSupport = actions.onOpenSupport,
        onDeleteAccount = actions.onDeleteAccount,
        onOpenPrivacy = actions.onOpenPrivacy,
        onOpenTerms = actions.onOpenTerms,
    )
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
                .clipToBounds()
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
                MurphLinkButton(failure.signOutLabel, actions.onSignOut)
            }
            if (externalActions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                MurphCard(modifier = Modifier.widthIn(max = 420.dp)) {
                    Text(
                        text = "Account and legal",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MurphColors.Slate,
                        textAlign = TextAlign.Center,
                    )
                    externalActions.forEach { action ->
                        MurphLinkButton(
                            text = action.label,
                            onClick = action.onClick,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
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
    val onDeferHealthConnectInitialSetup: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onSyncNow: () -> Unit,
    val onPrepareInitialAddressBookSharing: () -> Unit,
    val onDeferAddressBookSharingInitialSetup: () -> Unit,
    val onShareAddressBook: () -> Unit,
    val onRefreshAddressBook: () -> Unit,
    val onStopAddressBook: () -> Unit,
    val onShowLaunchConsent: () -> Unit,
    val onDismissLaunchConsent: () -> Unit,
    val onRetryLaunchConsent: () -> Unit,
    val onAcceptLaunchConsent: () -> Unit,
    val onSelectInitialOnboardingAvatar: (String) -> Unit,
    val onSelectInitialOnboardingMainPersona: (String) -> Unit,
    val onSelectInitialOnboardingSupportingPersona: (String?) -> Unit,
    val onSelectInitialOnboardingVoice: (String) -> Unit,
    val onSelectInitialOnboardingTone: (String) -> Unit,
    val onSetInitialOnboardingStage: (InitialOnboardingStage) -> Unit,
    val onPrepareInitialOnboardingContactCard: () -> Unit,
    val onSkipInitialOnboarding: () -> Unit,
    val onSaveInitialOnboarding: () -> Unit,
    val onDismissCompletedInitialOnboarding: () -> Unit,
    val onOpenInitialOnboardingContact: (String) -> Unit,
    val onOpenConsentDocument: (String) -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenPrivacy: () -> Unit,
    val onOpenTerms: () -> Unit,
    val onOpenHealthNotice: () -> Unit,
    val onOpenAiSafety: () -> Unit,
    val onOpenSupport: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onRetry: () -> Unit,
    val onSignOut: () -> Unit,
)
