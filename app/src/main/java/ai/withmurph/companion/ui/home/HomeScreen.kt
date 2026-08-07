package ai.withmurph.companion.ui.home

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphOutlineButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant

@Composable
fun HomeScreen(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onShowWhoopGuide: () -> Unit,
    onDeferHealthSetup: () -> Unit,
    onSyncNow: () -> Unit,
    onShareAddressBookFromSetup: () -> Unit,
    onAddressBookSetupSecondaryAction: () -> Unit,
    onOpenAppSettings: () -> Unit,
    reserveStatusBarInset: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .then(if (reserveStatusBarInset) Modifier.statusBarsPadding() else Modifier)
            .then(
                if (state.initialSetupStep != InitialSetupStep.Complete) {
                    Modifier.navigationBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(24.dp),
    ) {
        when (state.initialSetupStep) {
            InitialSetupStep.HealthConnect -> InitialHealthSetupContent(
                state = state,
                onConnectHealth = onConnectHealth,
                onOpenHealthConnect = onOpenHealthConnect,
                onShowWhoopGuide = onShowWhoopGuide,
                onNotNow = onDeferHealthSetup,
                modifier = Modifier.align(Alignment.Center),
            )
            InitialSetupStep.FriendlyNames -> FriendlyNamesSetupContent(
                state = state,
                onShare = onShareAddressBookFromSetup,
                onNotNow = onAddressBookSetupSecondaryAction,
                onOpenAppSettings = onOpenAppSettings,
                modifier = Modifier.align(Alignment.Center),
            )
            InitialSetupStep.Complete -> when (state.healthSync) {
                HealthSyncState.NotConnected -> NotConnectedStatusContent(
                    state = state,
                    onConnectHealth = onConnectHealth,
                    onOpenHealthConnect = onOpenHealthConnect,
                    onShowWhoopGuide = onShowWhoopGuide,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> SyncStatusContent(
                    state = state,
                    onSyncNow = onSyncNow,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun InitialHealthSetupContent(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onShowWhoopGuide: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "HEALTH CONNECT · 1 OF 2",
                style = MaterialTheme.typography.labelMedium,
                color = MurphColors.SlateMuted,
            )
            MurphIcon(
                kind = MurphIconKind.HealthCard,
                modifier = Modifier.size(40.dp),
                contentDescription = null,
            )
            Text(
                text = "Bring your health into Murph",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                color = MurphColors.Slate,
            )
            Text(
                text = "Connect sleep, workouts, steps, and activity to power your baselines and insights.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.healthAvailability) {
                HealthConnectAvailability.Available -> {
                    MurphPrimaryButton(
                        text = if (state.isConnectingHealth) {
                            "Connecting…"
                        } else if (state.healthReconnectRequired) {
                            "Reconnect Health Connect"
                        } else {
                            "Connect Health Connect"
                        },
                        onClick = onConnectHealth,
                        enabled = !state.isConnectingHealth,
                    )
                }

                HealthConnectAvailability.InstallOrUpdateRequired -> {
                    MurphPrimaryButton(
                        text = "Install or update Health Connect",
                        onClick = onOpenHealthConnect,
                    )
                }

                HealthConnectAvailability.OnboardingRequired -> {
                    MurphPrimaryButton(
                        text = "Finish setting up Health Connect",
                        onClick = onOpenHealthConnect,
                    )
                }

                HealthConnectAvailability.AppNotAllowed -> {
                    Text(
                        text = "This build of Murph isn't authorized for Health Connect. Contact Murph support.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MurphColors.SlateMuted,
                    )
                }

                HealthConnectAvailability.Unsupported -> {
                    Text(
                        text = "This device doesn't support Health Connect.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MurphColors.SlateMuted,
                    )
                }

                HealthConnectAvailability.TemporarilyUnavailable -> {
                    Text(
                        text = "Health Connect isn't ready yet. Update it or try again shortly.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MurphColors.SlateMuted,
                    )
                }
            }

            MurphLinkButton(
                text = "Not now",
                onClick = onNotNow,
                enabled = !state.isConnectingHealth && state.launchConsentRecovery == null,
            )
            if (state.healthAvailability == HealthConnectAvailability.Available) {
                MurphLinkButton(
                    text = "Set up WHOOP first",
                    onClick = onShowWhoopGuide,
                    enabled = !state.isConnectingHealth,
                )
            }
        }

        if (state.healthMessage != null) {
            Text(
                text = state.healthMessage,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FriendlyNamesSetupContent(
    state: AppUiState,
    onShare: () -> Unit,
    onNotNow: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWorking = state.isAddressBookBusy
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "FRIENDLY NAMES · 2 OF 2",
            style = MaterialTheme.typography.labelMedium,
            color = MurphColors.SlateMuted,
        )
        MurphIcon(
            kind = MurphIconKind.People,
            modifier = Modifier.size(42.dp),
            contentDescription = null,
        )
        Text(
            text = "Murph can know who's in the chat.",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
            color = MurphColors.Slate,
        )
        Text(
            text = "Share your contacts so Murph can tell who's who when you start a group chat with friends.",
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MurphColors.SelectedSurface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SetupAssuranceRow(
                    icon = MurphIconKind.PersonaChat,
                    text = "No invitations or automatic messages",
                )
                SetupAssuranceRow(
                    icon = MurphIconKind.Lock,
                    text = "Phone numbers aren't stored in readable form",
                )
                SetupAssuranceRow(
                    icon = MurphIconKind.People,
                    text = "Names may appear in group replies others can see",
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MurphPrimaryButton(
                text = when {
                    state.contactsPermissionDenied -> "Open Android Settings"
                    isWorking -> "Sharing…"
                    else -> "Share contacts"
                },
                onClick = if (state.contactsPermissionDenied) onOpenAppSettings else onShare,
                enabled = !isWorking,
            )
            MurphLinkButton(
                text = if (state.addressBookHasInterruptedReplacement) {
                    "Stop and delete"
                } else {
                    "Not now"
                },
                onClick = onNotNow,
                enabled = !isWorking && state.launchConsentRecovery == null,
            )
        }

        state.addressBookMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SetupAssuranceRow(icon: MurphIconKind, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MurphIcon(
            kind = icon,
            modifier = Modifier.size(20.dp),
            tint = MurphColors.Slate,
            contentDescription = null,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MurphColors.Slate,
        )
    }
}

@Composable
private fun NotConnectedStatusContent(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onShowWhoopGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (state.healthAvailability) {
        HealthConnectAvailability.AppNotAllowed -> "Health Connect isn't available"
        HealthConnectAvailability.Unsupported -> "Health Connect isn't supported"
        HealthConnectAvailability.TemporarilyUnavailable -> "Health Connect isn't ready yet"
        else -> "Connect Health Connect"
    }
    val detail = when (state.healthAvailability) {
        HealthConnectAvailability.AppNotAllowed ->
            "This build of Murph isn't authorized for Health Connect. Contact Murph support."
        HealthConnectAvailability.Unsupported ->
            "This device doesn't support Health Connect."
        HealthConnectAvailability.TemporarilyUnavailable ->
            "Health Connect is temporarily unavailable. Try again in a moment."
        else ->
            "Connect once and your sleep, workouts, and activity flow into Murph automatically."
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MurphIcon(
            kind = MurphIconKind.HealthCard,
            modifier = Modifier.size(48.dp),
            tint = MurphColors.Sage,
            contentDescription = null,
        )
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineLarge,
            color = MurphColors.Slate,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
            textAlign = TextAlign.Center,
        )
        when (state.healthAvailability) {
            HealthConnectAvailability.Available -> MurphPrimaryButton(
                text = if (state.isConnectingHealth) "Connecting…" else "Connect",
                onClick = onConnectHealth,
                enabled = !state.isConnectingHealth,
            )
            HealthConnectAvailability.InstallOrUpdateRequired -> MurphPrimaryButton(
                text = "Install or update Health Connect",
                onClick = onOpenHealthConnect,
            )
            HealthConnectAvailability.OnboardingRequired -> MurphPrimaryButton(
                text = "Finish setting up Health Connect",
                onClick = onOpenHealthConnect,
            )
            HealthConnectAvailability.TemporarilyUnavailable -> MurphPrimaryButton(
                text = "Try again",
                onClick = onConnectHealth,
            )
            HealthConnectAvailability.AppNotAllowed,
            HealthConnectAvailability.Unsupported -> Unit
        }
        if (state.healthAvailability == HealthConnectAvailability.Available) {
            MurphLinkButton(
                text = "Set up WHOOP first",
                onClick = onShowWhoopGuide,
                enabled = !state.isConnectingHealth,
            )
        }
        state.healthMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SyncStatusContent(
    state: AppUiState,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sync = state.healthSync
    val icon: MurphIconKind
    val tint: androidx.compose.ui.graphics.Color
    val title: String
    val detail: String

    if (state.healthStatusIsStale) {
        icon = MurphIconKind.Clock
        tint = MurphColors.SlateMuted
        title = "Last checked online"
        detail = "This is saved status from the last successful check, not a live sync result."
    } else when (sync) {
        HealthSyncState.NotConnected -> return
        HealthSyncState.AwaitingFirstData -> {
            icon = MurphIconKind.Refresh
            tint = MurphColors.Sage
            title = "You're connected"
            detail = "Your first sync is underway. Keep Murph open while the first data arrives."
        }
        is HealthSyncState.Synced -> {
            icon = MurphIconKind.CheckCircle
            tint = MurphColors.Sage
            title = "Synced"
            detail = relativeSentence(
                sync.lastDataReceivedAt,
                state.healthStatusObservedAt ?: sync.lastDataReceivedAt,
            )
        }
        is HealthSyncState.Delayed -> {
            icon = MurphIconKind.Clock
            tint = MurphColors.Amber
            title = "Sync is on its way"
            detail = "Sync can take up to a day. Last data ${relativeTime(
                sync.lastDataReceivedAt,
                state.healthStatusObservedAt ?: sync.lastDataReceivedAt,
            )}."
        }
        is HealthSyncState.NeedsAttention -> {
            icon = MurphIconKind.Gear
            tint = MurphColors.Amber
            title = "Worth a quick check"
            detail = "We haven't received new data in a while. It's usually a small settings thing."
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MurphIcon(
                kind = icon,
                modifier = Modifier.size(48.dp),
                tint = tint,
                contentDescription = null,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MurphColors.Slate,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
        }

        if (sync is HealthSyncState.NeedsAttention && !state.healthStatusIsStale) {
            MurphCard {
                GuidanceRow(
                    number = "1",
                    text = "Open Health Connect → App permissions → Murph, and confirm the categories you chose.",
                )
                GuidanceRow(
                    number = "2",
                    text = "Open WHOOP and confirm it is still sharing data with Health Connect.",
                )
            }
            if (sync.lastDataReceivedAt != null) {
                Text(
                    text = "Last data received ${relativeTime(
                        sync.lastDataReceivedAt,
                        state.healthStatusObservedAt ?: sync.lastDataReceivedAt,
                    )}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MurphColors.SlateMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        MurphOutlineButton(
            text = when {
                state.isSyncingHealth -> "Checking…"
                state.healthStatusIsStale -> "Check again"
                else -> "Check for new data"
            },
            onClick = onSyncNow,
            enabled = !state.isSyncingHealth,
        )

        if (state.healthMessage != null && !state.healthStatusIsStale) {
            Text(
                text = state.healthMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GuidanceRow(number: String, text: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.SageDark,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.SlateMuted,
        )
    }
}

private fun relativeSentence(date: Instant, observedAt: Instant): String {
    val relative = relativeTime(date, observedAt)
    return relative.replaceFirstChar(Char::uppercaseChar) + "."
}

private fun relativeTime(date: Instant, observedAt: Instant): String {
    val age = Duration.between(date, observedAt).coerceAtLeast(Duration.ZERO)
    return when {
        age < Duration.ofMinutes(1) -> "just now"
        age < Duration.ofHours(1) -> {
            val minutes = age.toMinutes()
            "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        }
        age < Duration.ofDays(1) -> {
            val hours = age.toHours()
            "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        }
        else -> {
            val days = age.toDays()
            "$days ${if (days == 1L) "day" else "days"} ago"
        }
    }
}
