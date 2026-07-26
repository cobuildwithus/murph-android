package ai.withmurph.companion.ui.home

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onSyncNow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .padding(24.dp),
    ) {
        when (state.healthSync) {
            HealthSyncState.NotConnected -> SetupContent(
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

@Composable
private fun SetupContent(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onShowWhoopGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "HEALTH CONNECT",
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
                style = MaterialTheme.typography.headlineLarge,
                color = MurphColors.Slate,
            )
            Text(
                text = "Connect sleep, workouts, steps, and activity to power your baselines and insights.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        when (state.healthAvailability) {
            HealthConnectAvailability.Available -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MurphPrimaryButton(
                        text = if (state.isConnectingHealth) {
                            "Connecting…"
                        } else {
                            "Connect Health Connect"
                        },
                        onClick = onConnectHealth,
                        enabled = !state.isConnectingHealth,
                    )
                    MurphLinkButton(
                        text = "Set up WHOOP first",
                        onClick = onShowWhoopGuide,
                        enabled = !state.isConnectingHealth,
                    )
                }
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

    when (sync) {
        HealthSyncState.NotConnected -> return
        HealthSyncState.AwaitingFirstData -> {
            icon = MurphIconKind.Refresh
            tint = MurphColors.Sage
            title = "You're connected"
            detail = "Your first sync is underway. Android may delay background work, so the first data can take a little while."
        }
        is HealthSyncState.Synced -> {
            icon = MurphIconKind.CheckCircle
            tint = MurphColors.Sage
            title = "Synced"
            detail = relativeSentence(sync.lastDataReceivedAt)
        }
        is HealthSyncState.Delayed -> {
            icon = MurphIconKind.Clock
            tint = MurphColors.Amber
            title = "Sync is on its way"
            detail = "Sync can take up to a day. Last data ${relativeTime(sync.lastDataReceivedAt)}."
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

        if (sync is HealthSyncState.NeedsAttention) {
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
                    text = "Last data received ${relativeTime(sync.lastDataReceivedAt)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MurphColors.SlateMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        MurphOutlineButton(
            text = if (state.isSyncingHealth) "Checking…" else "Check for new data",
            onClick = onSyncNow,
            enabled = !state.isSyncingHealth,
        )

        if (state.healthMessage != null) {
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

private fun relativeSentence(date: Instant): String {
    val relative = relativeTime(date)
    return relative.replaceFirstChar(Char::uppercaseChar) + "."
}

private fun relativeTime(date: Instant, now: Instant = Instant.now()): String {
    val age = Duration.between(date, now).coerceAtLeast(Duration.ZERO)
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
