package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant

@Composable
internal fun HealthStatusSummary(state: AppUiState, onSync: () -> Unit, onConnect: () -> Unit) {
    val status = when {
        state.healthReconnectRequired -> "Reconnect to resume syncing"
        state.healthStatusIsStale -> "Last checked online"
        else -> when (val sync = state.healthSync) {
            HealthSyncState.NotConnected -> "Not connected"
            HealthSyncState.AwaitingFirstData -> "Waiting for your first data"
            is HealthSyncState.Synced -> "Synced · " + relativeHealthTime(sync.lastDataReceivedAt)
            is HealthSyncState.Delayed -> "Sync is on its way"
            is HealthSyncState.NeedsAttention -> "Worth a quick check"
        }
    }
    Column(Modifier.fillMaxWidth().background(MurphColors.Card, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MurphIcon(if (state.healthSync is HealthSyncState.Synced && !state.healthStatusIsStale) MurphIconKind.CheckCircle else MurphIconKind.HealthCard,
                Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Health Connect", style = MaterialTheme.typography.bodyMedium, color = MurphColors.Slate)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MurphColors.SlateMuted)
            }
            if (state.healthSync == HealthSyncState.NotConnected || state.healthReconnectRequired) {
                TextButton(onClick = onConnect) { Text(if (state.healthReconnectRequired) "Reconnect" else "Connect") }
            } else {
                IconButton(onClick = onSync, enabled = !state.isSyncingHealth) {
                    if (state.isSyncingHealth) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else MurphIcon(MurphIconKind.Refresh, Modifier.size(22.dp), contentDescription = "Check for new data")
                }
            }
        }
        state.healthMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MurphColors.SlateMuted) }
    }
}

private fun relativeHealthTime(at: Instant): String {
    val age = Duration.between(at, Instant.now()).coerceAtLeast(Duration.ZERO)
    return when {
        age.toMinutes() < 1 -> "Just now"
        age.toHours() < 1 -> "${age.toMinutes()} min ago"
        age.toDays() < 1 -> "${age.toHours()} hr ago"
        else -> "${age.toDays()} days ago"
    }
}
