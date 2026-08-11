package ai.withmurph.companion.ui.settings

import ai.withmurph.companion.R
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    state: AppUiState,
    healthSyncNotificationsAllowed: Boolean,
    healthSyncNotificationRecoveryNeeded: Boolean,
    onShareAddressBook: () -> Unit,
    onRefreshAddressBook: () -> Unit,
    onStopAddressBook: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onSetHealthSyncReminderEnabled: (Boolean) -> Unit,
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
            .then(if (reserveStatusBarInset) Modifier.statusBarsPadding() else Modifier)
            .verticalScroll(rememberScrollState())
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
            footer = "Optional labels for group chats. Murph never messages contacts or stores readable phone numbers.",
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

        val healthConnected = state.healthSync != HealthSyncState.NotConnected
        val reminderCanEnable =
            healthConnected && state.authVerifiedOnline && !state.healthStatusIsStale
        val reminderTargetEnabled =
            state.healthSyncReminderTargetEnabled ?: state.healthSyncReminderEnabled
        Section(
            title = "Health Connect",
            footer = stringResource(R.string.health_sync_reminder_section_footer),
        ) {
            SettingsRow(
                title = "Health Connect Access",
                icon = MurphIconKind.HealthCard,
                onClick = onOpenHealthConnect,
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.health_sync_reminder_title),
                detail = stringResource(
                    when {
                        state.healthSyncReminderTargetEnabled == true ->
                            R.string.health_sync_reminder_turning_on_detail
                        state.healthSyncReminderTargetEnabled == false ->
                            R.string.health_sync_reminder_turning_off_detail
                        !healthConnected -> R.string.health_sync_reminder_unavailable_detail
                        !reminderCanEnable && !state.healthSyncReminderEnabled ->
                            R.string.health_sync_reminder_requires_online_detail
                        state.healthSyncReminderEnabled && !healthSyncNotificationsAllowed ->
                            R.string.health_sync_reminder_notifications_blocked_detail
                        state.healthSyncReminderEnabled -> R.string.health_sync_reminder_on_detail
                        else -> R.string.health_sync_reminder_off_detail
                    },
                ),
                icon = MurphIconKind.Bell,
                actionLabel = when {
                    reminderTargetEnabled ->
                        stringResource(R.string.health_sync_reminder_turn_off)
                    reminderCanEnable -> stringResource(R.string.health_sync_reminder_turn_on)
                    else -> null
                },
                enabled = reminderCanEnable || reminderTargetEnabled,
                checked = reminderTargetEnabled,
                onCheckedChange = onSetHealthSyncReminderEnabled,
            )
            if (
                !healthSyncNotificationsAllowed &&
                (state.healthSyncReminderEnabled || healthSyncNotificationRecoveryNeeded)
            ) {
                SettingsDivider()
                SettingsRow(
                    title = stringResource(
                        R.string.health_sync_reminder_notification_settings_title,
                    ),
                    detail = stringResource(
                        R.string.health_sync_reminder_notification_settings_detail,
                    ),
                    icon = MurphIconKind.Gear,
                    actionLabel = stringResource(
                        R.string.health_sync_reminder_notification_settings_action,
                    ),
                    onClick = onOpenAppSettings,
                )
            }
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
