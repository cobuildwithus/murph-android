package ai.withmurph.companion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.app.AppLinks
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.MurphApp
import ai.withmurph.companion.ui.theme.MurphTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as MurphApplication).graph

        if (isHealthPermissionRationaleIntent(intent)) {
            openUri(AppLinks.Privacy)
            finish()
            return
        }

        val backgroundSyncLauncher = registerForActivityResult(
            graph.health.backgroundSyncContract(),
        ) { enabled ->
            graph.applicationScope.launch {
                graph.session.completeBackgroundSync(enabled)
            }
        }

        val backgroundReadPermissionLauncher = registerForActivityResult(
            graph.health.extendedPermissionContract(),
        ) { granted ->
            graph.applicationScope.launch {
                if (
                    graph.session.continueBackgroundSyncAfterPermission(
                        granted = granted.isNotEmpty(),
                    )
                ) {
                    if (canLaunchExternalFlow()) {
                        backgroundSyncLauncher.launch(Unit)
                    } else {
                        graph.session.cancelBackgroundSyncFlow()
                    }
                }
            }
        }

        val historyPermissionLauncher = registerForActivityResult(
            graph.health.extendedPermissionContract(),
        ) {
            graph.applicationScope.launch { graph.session.syncNow() }
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
                val connected = graph.session.completeHealthPermissionFlow(completed)
                if (connected) {
                    val historyPermissions = graph.health.supportedHistoryPermissions()
                    if (historyPermissions.isNotEmpty() && canLaunchExternalFlow()) {
                        historyPermissionLauncher.launch(historyPermissions)
                    } else {
                        graph.session.syncNow()
                    }
                }
            }
        }

        setContent {
            val appState by graph.session.state.collectAsStateWithLifecycle()
            val loginState by graph.login.state.collectAsStateWithLifecycle()
            SideEffect {
                setLoginSnapshotProtection(appState.phase == AppPhase.NeedsLogin)
            }
            MurphTheme {
                MurphApp(
                    appState = appState,
                    loginState = loginState,
                    actions = MurphActions(
                        onLoginMethodChanged = graph.login::setMethod,
                        onPhoneCountryChanged = graph.login::setPhoneCountry,
                        onLoginDestinationChanged = graph.login::setDestination,
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
                                if (graph.session.prepareHealthConnection()) {
                                    if (canLaunchExternalFlow()) {
                                        healthPermissionLauncher.launch(Unit)
                                    } else {
                                        graph.session.cancelHealthPermissionFlow()
                                    }
                                }
                            }
                        },
                        onOpenHealthConnect = ::openHealthConnect,
                        onSyncNow = {
                            graph.applicationScope.launch { graph.session.syncNow() }
                        },
                        onEnableBackgroundSync = {
                            graph.applicationScope.launch {
                                if (graph.session.prepareBackgroundSync()) {
                                    val backgroundPermissions =
                                        graph.health.supportedBackgroundReadPermissions()
                                    if (!canLaunchExternalFlow()) {
                                        graph.session.cancelBackgroundSyncFlow()
                                    } else if (backgroundPermissions.isNotEmpty()) {
                                        backgroundReadPermissionLauncher.launch(
                                            backgroundPermissions,
                                        )
                                    } else {
                                        if (
                                            graph.session.continueBackgroundSyncAfterPermission(
                                                granted = true,
                                            )
                                        ) {
                                            backgroundSyncLauncher.launch(Unit)
                                        }
                                    }
                                }
                            }
                        },
                        onDisableBackgroundSync = {
                            graph.applicationScope.launch {
                                graph.session.disableBackgroundSync()
                            }
                        },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isHealthPermissionRationaleIntent(intent)) {
            openUri(AppLinks.Privacy)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::graph.isInitialized) {
            graph.applicationScope.launch { graph.session.didBecomeActive() }
        }
    }

    override fun onStop() {
        if (::graph.isInitialized && !isChangingConfigurations) {
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

    private fun openUri(value: String) {
        val uri = Uri.parse(value)
        val action = if (uri.scheme == "mailto") {
            Intent.ACTION_SENDTO
        } else {
            Intent.ACTION_VIEW
        }
        try {
            startActivity(Intent(action, uri))
        } catch (_: ActivityNotFoundException) {
            val destination = if (uri.scheme == "mailto") {
                Uri.decode(uri.schemeSpecificPart.substringBefore('?'))
            } else {
                value
            }
            Toast.makeText(
                this,
                "No installed app can open $destination",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun reportHealthConnectLaunchFailure() {
        val message = "Health Connect couldn't be opened on this device."
        graph.session.reportHealthConnectLaunchFailure(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun canLaunchExternalFlow(): Boolean =
        !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

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
}
