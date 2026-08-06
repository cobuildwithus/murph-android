package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.app.AppUiState
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
    onShareAddressBook: () -> Unit,
    onRefreshAddressBook: () -> Unit,
    onStopAddressBook: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenHealthNotice: () -> Unit,
    onOpenAiSafety: () -> Unit,
    onOpenSupport: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    reserveStatusBarInset: Boolean = true,
) {
    val addressBook = addressBookSettingsModel(state)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .verticalScroll(rememberScrollState())
            .then(if (reserveStatusBarInset) Modifier.statusBarsPadding() else Modifier)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MurphColors.Slate,
        )

        Section(
            title = "Address book",
            footer = "Friendly labels only — not identity proof. Murph sends no invitations or messages, does not store phone numbers in readable form, and may use a name in group replies that other participants can see. Contacts are read only when you choose Share, Update, or Retry — never in the background.",
        ) {
            SettingsRow(
                title = "Familiar group names",
                detail = addressBook.status,
                icon = MurphIconKind.Checklist,
                actionLabel = addressBook.primaryLabel,
                enabled = addressBook.canUsePrimaryAction,
                onClick = {
                    when (addressBook.primaryAction) {
                        AddressBookSettingsAction.Share,
                        AddressBookSettingsAction.Update,
                        AddressBookSettingsAction.Retry -> onShareAddressBook()
                        AddressBookSettingsAction.Refresh -> onRefreshAddressBook()
                        null -> Unit
                    }
                },
            )
            if (addressBook.showsStop) {
                SettingsDivider()
                SettingsRow(
                    title = "Stop and delete",
                    detail = "Delete the server projection.",
                    icon = MurphIconKind.Trash,
                    actionLabel = "Stop",
                    enabled = addressBook.canStop,
                    onClick = onStopAddressBook,
                )
            }
            if (addressBook.showsOpenAppSettings) {
                SettingsDivider()
                SettingsRow(
                    title = "Contacts permission",
                    detail = "Access is off. Other Murph features are unaffected.",
                    icon = MurphIconKind.Gear,
                    actionLabel = "Open settings",
                    onClick = onOpenAppSettings,
                )
            }
        }

        state.addressBookMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
            )
        }

        Section("Health Connect") {
            SettingsRow(
                title = "Health Connect Access",
                icon = MurphIconKind.HealthCard,
                onClick = onOpenHealthConnect,
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
    footer: String? = null,
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
        if (footer != null) {
            Text(
                text = footer,
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MurphColors.SlateMuted,
            )
        }
    }
}
