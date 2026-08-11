package ai.withmurph.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.app.AppLinks
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.reminders.HealthSyncReminderController
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.MurphApp
import ai.withmurph.companion.ui.theme.MurphTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var healthSyncNotificationsAllowed by mutableStateOf(false)
    private var healthSyncNotificationRecoveryNeeded by mutableStateOf(false)
    private var openSettingsRequestId by mutableIntStateOf(0)
    private var lastHandledReminderDeliveryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSettingsRequestId = savedInstanceState?.getInt(
            STATE_OPEN_SETTINGS_REQUEST_ID,
        ) ?: 0
        lastHandledReminderDeliveryId = savedInstanceState?.getString(
            STATE_LAST_HANDLED_REMINDER_DELIVERY_ID,
        )
        graph = (application as MurphApplication).graph
        graph.healthSyncReminder.didEnterForeground()
        healthSyncNotificationsAllowed = graph.healthSyncReminder.notificationsAllowed()
        handleHealthSyncReminderIntent(
            intent = intent,
            isRestoring = savedInstanceState != null,
        )

        if (isHealthPermissionRationaleIntent(intent)) {
            openUri(AppLinks.Privacy)
            finish()
            return
        }

        val contactsPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            graph.applicationScope.launch {
                graph.session.completeAddressBookPermissionFlow(granted)
            }
        }

        val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) graph.healthSyncReminder.prepareNotificationChannel()
            healthSyncNotificationsAllowed = graph.healthSyncReminder.notificationsAllowed()
            healthSyncNotificationRecoveryNeeded = !granted
            if (granted && healthSyncNotificationsAllowed) {
                saveHealthSyncReminderSetting(true)
            } else {
                showReminderMessage(R.string.health_sync_reminder_permission_denied)
            }
        }

        val healthPermissionLauncher = registerForActivityResult(
            graph.health.healthPermissionContract(),
        ) { deferredOutcome ->
            graph.applicationScope.launch {
                val completed = try {
                    graph.health.permissionRequestCompleted(deferredOutcome)
                } catch (error: CancellationException) {
                    graph.session.cancelHealthPermissionFlow()
                    throw error
                } catch (_: Exception) {
                    false
                }
                graph.session.completeHealthPermissionFlow(completed)
            }
        }

        setContent {
            val appState by graph.session.state.collectAsStateWithLifecycle()
            val loginState by graph.login.state.collectAsStateWithLifecycle()
            SideEffect {
                setLoginSnapshotProtection(appState.phase == AppPhase.NeedsLogin)
            }
            LaunchedEffect(appState.pendingHealthPermissionRequestId) {
                val requestId = appState.pendingHealthPermissionRequestId ?: return@LaunchedEffect
                lifecycle.withResumed {
                    if (graph.session.consumeHealthPermissionLaunchRequest(requestId)) {
                        try {
                            healthPermissionLauncher.launch(Unit)
                        } catch (_: Exception) {
                            graph.session.cancelHealthPermissionFlow()
                            graph.session.reportHealthConnectLaunchFailure(
                                "Health Connect permissions couldn't be opened. Try again.",
                            )
                        }
                    }
                }
            }
            LaunchedEffect(appState.pendingAddressBookPermissionRequestId) {
                val requestId = appState.pendingAddressBookPermissionRequestId
                    ?: return@LaunchedEffect
                lifecycle.withResumed {
                    if (graph.session.consumeAddressBookPermissionLaunchRequest(requestId)) {
                        try {
                            contactsPermissionLauncher.launch(graph.contacts.readPermission)
                        } catch (_: Exception) {
                            graph.session.cancelAddressBookPermissionFlow()
                            graph.session.reportAddressBookPermissionLaunchFailure(
                                "Contacts permission couldn't be opened. Try again.",
                            )
                        }
                    }
                }
            }
            LaunchedEffect(appState.initialOnboardingContactCardHandoff?.id) {
                val event = appState.initialOnboardingContactCardHandoff
                    ?: return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    graph.session.launchInitialOnboardingContactCardHandoff(
                        event.id,
                        ::openUri,
                    )
                }
            }
            MurphTheme {
                MurphApp(
                    appState = appState,
                    loginState = loginState,
                    healthSyncNotificationsAllowed = healthSyncNotificationsAllowed,
                    healthSyncNotificationRecoveryNeeded =
                        healthSyncNotificationRecoveryNeeded,
                    openSettingsRequestId = openSettingsRequestId,
                    onOpenSettingsRequestConsumed = { requestId ->
                        if (openSettingsRequestId == requestId) {
                            openSettingsRequestId = 0
                        }
                    },
                    actions = MurphActions(
                        onLoginMethodChanged = graph.login::setMethod,
                        onPhoneCountryChanged = graph.login::setPhoneCountry,
                        onLoginDestinationChanged = { destination ->
                            if (graph.login.setDestination(destination)) {
                                graph.applicationScope.launch {
                                    graph.login.sendCode(fromAutomaticPhoneFill = true)
                                }
                            }
                        },
                        onLoginCodeChanged = graph.login::setCode,
                        onSendLoginCode = {
                            graph.applicationScope.launch { graph.login.sendCode() }
                        },
                        onConfirmLoginCode = {
                            graph.applicationScope.launch {
                                if (graph.login.confirmCode()) {
                                    graph.session.didLogin()
                                }
                            }
                        },
                        onResendLoginCode = {
                            graph.applicationScope.launch { graph.login.resendCode() }
                        },
                        onChangeLoginDestination = graph.login::changeDestination,
                        onConnectHealth = {
                            graph.applicationScope.launch {
                                graph.session.prepareHealthConnection()
                            }
                        },
                        onDeferHealthConnectInitialSetup = {
                            graph.applicationScope.launch {
                                graph.session.deferHealthConnectInitialSetup()
                            }
                        },
                        onOpenHealthConnect = ::openHealthConnect,
                        onSetHealthSyncReminderEnabled = { enabled ->
                            when {
                                !enabled -> {
                                    saveHealthSyncReminderSetting(false)
                                }
                                else -> {
                                    graph.healthSyncReminder.prepareNotificationChannel()
                                    healthSyncNotificationsAllowed =
                                        graph.healthSyncReminder.notificationsAllowed()
                                    when {
                                        healthSyncNotificationsAllowed -> {
                                            saveHealthSyncReminderSetting(true)
                                        }
                                        healthSyncNotificationRecoveryNeeded -> openAppSettings()
                                        graph.healthSyncReminder.needsNotificationPermission() -> {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            )
                                        }
                                        else -> openAppSettings()
                                    }
                                }
                            }
                        },
                        onSyncNow = {
                            graph.applicationScope.launch { graph.session.syncNow() }
                        },
                        onPrepareInitialAddressBookSharing = {
                            graph.applicationScope.launch {
                                graph.session.prepareInitialAddressBookSharing()
                            }
                        },
                        onDeferAddressBookSharingInitialSetup = {
                            graph.applicationScope.launch {
                                graph.session.deferAddressBookSharingInitialSetup()
                            }
                        },
                        onShareAddressBook = {
                            graph.applicationScope.launch {
                                graph.session.prepareAddressBookSharing()
                            }
                        },
                        onRefreshAddressBook = {
                            graph.applicationScope.launch {
                                graph.session.refreshAddressBookSharing()
                            }
                        },
                        onStopAddressBook = {
                            graph.applicationScope.launch {
                                graph.session.stopAddressBookSharing()
                            }
                        },
                        onShowLaunchConsent = graph.session::showLaunchConsentRecovery,
                        onDismissLaunchConsent = graph.session::dismissLaunchConsentRecovery,
                        onRetryLaunchConsent = {
                            graph.applicationScope.launch {
                                graph.session.retryLaunchConsentRecovery()
                            }
                        },
                        onAcceptLaunchConsent = {
                            graph.applicationScope.launch {
                                graph.session.acceptLaunchConsent()
                            }
                        },
                        onSelectInitialOnboardingAvatar =
                            graph.session::selectInitialOnboardingAvatar,
                        onSelectInitialOnboardingMainPersona =
                            graph.session::selectInitialOnboardingMainPersona,
                        onSelectInitialOnboardingSupportingPersona =
                            graph.session::selectInitialOnboardingSupportingPersona,
                        onSelectInitialOnboardingVoice =
                            graph.session::selectInitialOnboardingVoice,
                        onSelectInitialOnboardingTone =
                            graph.session::selectInitialOnboardingTone,
                        onSetInitialOnboardingStage =
                            graph.session::setInitialOnboardingStage,
                        onPrepareInitialOnboardingContactCard = {
                            graph.applicationScope.launch {
                                graph.session.prepareInitialOnboardingContactCard()
                            }
                        },
                        onSkipInitialOnboarding = {
                            graph.applicationScope.launch {
                                graph.session.skipInitialOnboarding()
                            }
                        },
                        onSaveInitialOnboarding = {
                            graph.applicationScope.launch {
                                graph.session.saveInitialOnboarding()
                            }
                        },
                        onDismissCompletedInitialOnboarding =
                            graph.session::dismissCompletedInitialOnboarding,
                        onOpenInitialOnboardingContact = { url ->
                            if (openUri(url)) {
                                graph.session.dismissCompletedInitialOnboarding()
                            }
                        },
                        onOpenConsentDocument = { openUri(it) },
                        onOpenAppSettings = ::openAppSettings,
                        onOpenPrivacy = { openUri(AppLinks.Privacy) },
                        onOpenTerms = { openUri(AppLinks.Terms) },
                        onOpenHealthNotice = { openUri(AppLinks.HealthNotice) },
                        onOpenAiSafety = { openUri(AppLinks.AiSafety) },
                        onOpenSupport = { openUri(AppLinks.Support) },
                        onDeleteAccount = { openUri(AppLinks.AccountDeletion) },
                        onRetry = {
                            graph.applicationScope.launch { graph.session.retry() }
                        },
                        onSignOut = {
                            graph.applicationScope.launch {
                                graph.session.signOut()
                                graph.login.reset()
                            }
                        },
                    ),
                )
            }
        }

        graph.applicationScope.launch { graph.session.start() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_OPEN_SETTINGS_REQUEST_ID, openSettingsRequestId)
        lastHandledReminderDeliveryId?.let { deliveryId ->
            outState.putString(STATE_LAST_HANDLED_REMINDER_DELIVERY_ID, deliveryId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        graph.healthSyncReminder.didEnterForeground()
        handleHealthSyncReminderIntent(intent)
        if (isHealthPermissionRationaleIntent(intent)) {
            openUri(AppLinks.Privacy)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::graph.isInitialized) {
            graph.healthSyncReminder.didEnterForeground()
            healthSyncNotificationsAllowed = graph.healthSyncReminder.notificationsAllowed()
            if (healthSyncNotificationsAllowed) {
                healthSyncNotificationRecoveryNeeded = false
            }
            graph.applicationScope.launch { graph.session.didBecomeActive() }
        }
    }

    override fun onStop() {
        if (::graph.isInitialized && !isChangingConfigurations) {
            graph.healthSyncReminder.didEnterBackground()
            graph.session.didEnterBackground()
        }
        super.onStop()
    }

    private fun openHealthConnect() {
        val intent = graph.health.openHealthConnectIntent()
        if (intent == null) {
            reportHealthConnectLaunchFailure()
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            reportHealthConnectLaunchFailure()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "App settings couldn't be opened on this device.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openUri(value: String): Boolean {
        val uri = Uri.parse(value)
        val action = if (uri.scheme == "mailto") {
            Intent.ACTION_SENDTO
        } else {
            Intent.ACTION_VIEW
        }
        try {
            startActivity(Intent(action, uri))
            return true
        } catch (_: ActivityNotFoundException) {
            val message = if (uri.scheme == "mailto") {
                "No installed email app can open this link."
            } else {
                "No installed app can open this link."
            }
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
    }

    private fun reportHealthConnectLaunchFailure() {
        val message = "Health Connect couldn't be opened on this device."
        graph.session.reportHealthConnectLaunchFailure(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun handleHealthSyncReminderIntent(
        intent: Intent?,
        isRestoring: Boolean = false,
    ) {
        val deliveryId = healthSyncReminderSettingsDeliveryToConsume(
            intent = intent,
            lastHandledDeliveryId = lastHandledReminderDeliveryId,
            isRestoringLegacyIntent = isRestoring,
        ) ?: return
        lastHandledReminderDeliveryId = deliveryId
        openSettingsRequestId += 1
    }

    private fun saveHealthSyncReminderSetting(enabled: Boolean) {
        graph.applicationScope.launch {
            if (!graph.session.setHealthSyncReminderEnabled(enabled)) {
                showReminderMessage(R.string.health_sync_reminder_save_failed)
            }
        }
    }

    private fun showReminderMessage(messageId: Int) {
        Toast.makeText(this, getString(messageId), Toast.LENGTH_LONG).show()
    }

    private fun setLoginSnapshotProtection(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun isHealthPermissionRationaleIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            action == "android.intent.action.VIEW_PERMISSION_USAGE"
    }

    private companion object {
        const val STATE_OPEN_SETTINGS_REQUEST_ID = "open_settings_request_id"
        const val STATE_LAST_HANDLED_REMINDER_DELIVERY_ID =
            "last_handled_reminder_delivery_id"
    }
}

internal fun healthSyncReminderSettingsDeliveryToConsume(
    intent: Intent?,
    lastHandledDeliveryId: String?,
    isRestoringLegacyIntent: Boolean = false,
): String? {
    if (intent?.action != HealthSyncReminderController.ACTION_OPEN_SETTINGS) return null
    val deliveryId = intent.getStringExtra(
        HealthSyncReminderController.EXTRA_SETTINGS_DELIVERY_ID,
    )
    if (deliveryId == null) {
        return LEGACY_REMINDER_DELIVERY_ID.takeUnless {
            isRestoringLegacyIntent || lastHandledDeliveryId == it
        }
    }
    return deliveryId.takeUnless { it == lastHandledDeliveryId }
}

private const val LEGACY_REMINDER_DELIVERY_ID = "legacy-reminder-delivery"
