package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.SettingsDivider
import ai.withmurph.companion.ui.components.SettingsRow
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MurphColors.Slate,
        )

        Section("Health Connect") {
            SettingsRow(
                title = "Health Connect Access",
                icon = MurphIconKind.HealthCard,
                onClick = onOpenHealthConnect,
            )
        }

        if (state.healthSync != HealthSyncState.NotConnected) {
            Section("Background sync") {
                SettingsRow(
                    title = "Background sync",
                    icon = MurphIconKind.Background,
                    detail = if (state.backgroundSyncEnabled) {
                        "On"
                    } else {
                        "Off"
                    },
                    actionLabel = if (state.backgroundSyncEnabled) "Turn off" else "Set up",
                    onClick = if (state.backgroundSyncEnabled) {
                        onDisableBackgroundSync
                    } else {
                        onEnableBackgroundSync
                    },
                )
            }
            Text(
                text = "Android may delay scheduled work. Foreground sync remains available.",
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
            )
        }

        Section("Legal") {
            SettingsRow(
                title = "Privacy Policy",
                icon = MurphIconKind.Shield,
                showsExternalLink = true,
                onClick = onOpenPrivacy,
            )
            SettingsDivider()
            SettingsRow(
                title = "Terms",
                icon = MurphIconKind.Checklist,
                showsExternalLink = true,
                onClick = onOpenTerms,
            )
            SettingsDivider()
            SettingsRow(
                title = "Health Data Notice",
                icon = MurphIconKind.HealthCard,
                showsExternalLink = true,
                onClick = onOpenHealthNotice,
            )
            SettingsDivider()
            SettingsRow(
                title = "AI Safety Disclosure",
                icon = MurphIconKind.Sparkles,
                showsExternalLink = true,
                onClick = onOpenAiSafety,
            )
        }

        Section("Account") {
            SettingsRow(
                title = "Support",
                icon = MurphIconKind.Envelope,
                showsExternalLink = true,
                onClick = onOpenSupport,
            )
            SettingsDivider()
            SettingsRow(
                title = "Delete Account",
                icon = MurphIconKind.Trash,
                showsExternalLink = true,
                onClick = onDeleteAccount,
            )
            SettingsDivider()
            SettingsRow(
                title = "Sign Out",
                icon = MurphIconKind.SignOut,
                onClick = onSignOut,
            )
        }

        Text(
            text = "Murph stores no health database inside this app. Health data stays in Health Connect and moves through Junction only after the permissions you approve.",
            style = MaterialTheme.typography.bodySmall,
            color = MurphColors.SlateMuted,
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MurphColors.SlateMuted,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MurphColors.Card.copy(alpha = 0.9f),
            ),
            border = BorderStroke(1.dp, MurphColors.BorderWarm),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(content = content)
        }
    }
}
