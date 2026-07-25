package ai.withmurph.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphOutlineButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("Health sync", style = MaterialTheme.typography.headlineLarge)

        if (state.healthSync == HealthSyncState.NotConnected) {
            SetupCard(
                state = state,
                onConnectHealth = onConnectHealth,
                onOpenHealthConnect = onOpenHealthConnect,
            )
        } else {
            SyncStatusCard(state = state, onSyncNow = onSyncNow)
        }

        MurphCard {
            Text("WHOOP ON ANDROID", style = MaterialTheme.typography.labelMedium)
            Text(
                "In WHOOP, open More → App Settings → Integrations → Health Connect and enable sharing. Then return here and connect Murph.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
            MurphOutlineButton("Open Health Connect", onOpenHealthConnect)
        }

        MurphCard {
            Text("DATA ACCESS", style = MaterialTheme.typography.labelMedium)
            Text(
                "Murph requests four read-only Junction resource groups: sleep, workouts, steps, and active calories.",
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SlateMuted,
            )
            Text(
                "You choose each category in Health Connect. Murph never writes to Health Connect. History is requested during setup; background-read access is requested only if you set up optional background sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SlateMuted,
            )
            Text(
                "${state.grantedResourceCount} of ${state.totalResourceCount} Junction resource groups currently have usable read access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SageDark,
            )
        }

        if (state.healthMessage != null) {
            Text(
                state.healthMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.Sienna,
            )
        }
    }
}

@Composable
private fun SetupCard(
    state: AppUiState,
    onConnectHealth: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    MurphCard {
        Text("HEALTH CONNECT", style = MaterialTheme.typography.labelMedium)
        Text("Bring your health into Murph", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect once and approved sleep, workout, steps, and active-calorie data can flow into Murph.",
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
        )
        when (state.healthAvailability) {
            HealthConnectAvailability.Available -> MurphPrimaryButton(
                text = if (state.isConnectingHealth) "Connecting…" else "Connect Health Connect",
                onClick = onConnectHealth,
                enabled = !state.isConnectingHealth,
            )
            HealthConnectAvailability.InstallOrUpdateRequired -> MurphPrimaryButton(
                text = "Install or update Health Connect",
                onClick = onOpenHealthConnect,
            )
            HealthConnectAvailability.Unsupported -> Text(
                "This device doesn't support Health Connect.",
                color = MurphColors.SlateMuted,
            )
            HealthConnectAvailability.TemporarilyUnavailable -> Text(
                "Health Connect isn't ready yet. Update it or try again shortly.",
                color = MurphColors.SlateMuted,
            )
        }
    }
}

@Composable
private fun SyncStatusCard(state: AppUiState, onSyncNow: () -> Unit) {
    MurphCard {
        if (state.isSyncingHealth) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MurphColors.SageDark,
            )
        }
        val title: String
        val detail: String
        when (val sync = state.healthSync) {
            HealthSyncState.NotConnected -> {
                title = "Not connected"
                detail = "Connect Health Connect to begin."
            }
            HealthSyncState.AwaitingFirstData -> {
                title = "You're connected"
                detail = "The first sync is underway. Murph will call it synced only after the backend receives health data."
            }
            is HealthSyncState.Synced -> {
                title = "Synced"
                detail = "Last backend receipt ${formatInstant(sync.lastDataReceivedAt)}."
            }
            is HealthSyncState.Delayed -> {
                title = "Sync is on its way"
                detail = "Last backend receipt ${formatInstant(sync.lastDataReceivedAt)}. Android may defer background work."
            }
            is HealthSyncState.NeedsAttention -> {
                title = "Worth a quick check"
                detail = sync.lastDataReceivedAt?.let {
                    "No new backend receipt since ${formatInstant(it)}. Check WHOOP sharing and Murph's Health Connect permissions."
                } ?: "Murph hasn't received data yet. Check WHOOP sharing and Health Connect permissions."
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = MurphColors.SlateMuted)
        MurphOutlineButton(
            text = if (state.isSyncingHealth) "Checking…" else "Check for new data",
            onClick = onSyncNow,
            enabled = !state.isSyncingHealth,
        )
    }
}

private fun formatInstant(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(instant)
