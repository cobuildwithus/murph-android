package ai.withmurph.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.SettingsRow
import ai.withmurph.companion.ui.theme.MurphColors

@Composable
fun SettingsScreen(
    state: AppUiState,
    onOpenHealthConnect: () -> Unit,
    onEnableBackgroundSync: () -> Unit,
    onDisableBackgroundSync: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenHealthNotice: () -> Unit,
    onOpenAiSafety: () -> Unit,
    onOpenSupport: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        Section("HEALTH CONNECT") {
            SettingsRow(
                title = "Health Connect access",
                detail = "${state.grantedResourceCount} of ${state.totalResourceCount} Junction resource groups",
                actionLabel = "Open",
                onClick = onOpenHealthConnect,
            )
            if (state.healthSync != HealthSyncState.NotConnected) {
                SettingsRow(
                    title = "Background sync",
                    detail = if (state.backgroundSyncEnabled) {
                        "On"
                    } else {
                        "Off · foreground sync remains available"
                    },
                    actionLabel = if (state.backgroundSyncEnabled) "Turn off" else "Set up",
                    onClick = if (state.backgroundSyncEnabled) {
                        onDisableBackgroundSync
                    } else {
                        onEnableBackgroundSync
                    },
                )
            }
        }

        Section("LEGAL") {
            SettingsRow("Privacy Policy", actionLabel = "Open", onClick = onOpenPrivacy)
            SettingsRow("Terms", actionLabel = "Open", onClick = onOpenTerms)
            SettingsRow("Health Data Notice", actionLabel = "Open", onClick = onOpenHealthNotice)
            SettingsRow("AI Safety Disclosure", actionLabel = "Open", onClick = onOpenAiSafety)
        }

        Section("ACCOUNT") {
            SettingsRow("Support", actionLabel = "Email", onClick = onOpenSupport)
            SettingsRow("Delete Account", actionLabel = "Open", onClick = onDeleteAccount)
            SettingsRow("Sign Out", actionLabel = "Sign out", onClick = onSignOut)
        }

        Text(
            "Murph stores no health database inside this app. Health data remains in Health Connect and moves through Junction to Murph after the permissions you approve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.SlateMuted,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
        MurphCard(content = content)
    }
}
