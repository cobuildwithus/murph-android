package ai.withmurph.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.app.AppLinks
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
            graph.session.onBackgroundSyncResult(enabled)
        }

        val backgroundReadPermissionLauncher = registerForActivityResult(
            graph.health.extendedPermissionContract(),
        ) { granted ->
            if (granted.isNotEmpty()) {
                backgroundSyncLauncher.launch(Unit)
            } else {
                graph.session.onBackgroundSyncResult(false)
            }
        }

        val historyPermissionLauncher = registerForActivityResult(
            graph.health.extendedPermissionContract(),
        ) {
            lifecycleScope.launch { graph.session.syncNow() }
        }

        val healthPermissionLauncher = registerForActivityResult(
            graph.health.healthPermissionContract(),
        ) { deferredOutcome ->
            lifecycleScope.launch {
                val completed = try {
                    graph.health.permissionRequestCompleted(deferredOutcome)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                val connected = graph.session.completeHealthPermissionFlow(completed)
                if (connected) {
                    val historyPermissions = graph.health.supportedHistoryPermissions()
                    if (historyPermissions.isNotEmpty()) {
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
            MurphTheme {
                MurphApp(
                    appState = appState,
                    loginState = loginState,
                    actions = MurphActions(
                        onLoginMethodChanged = graph.login::setMethod,
                        onLoginDestinationChanged = graph.login::setDestination,
                        onLoginCodeChanged = graph.login::setCode,
                        onSendLoginCode = {
                            lifecycleScope.launch { graph.login.sendCode() }
                        },
                        onConfirmLoginCode = {
                            lifecycleScope.launch {
                                if (graph.login.confirmCode()) {
                                    graph.session.didLogin()
                                }
                            }
                        },
                        onResendLoginCode = {
                            lifecycleScope.launch { graph.login.resendCode() }
                        },
                        onChangeLoginDestination = graph.login::changeDestination,
                        onConnectHealth = {
                            lifecycleScope.launch {
                                if (graph.session.prepareHealthConnection()) {
                                    healthPermissionLauncher.launch(Unit)
                                }
                            }
                        },
                        onOpenHealthConnect = ::openHealthConnect,
                        onSyncNow = {
                            lifecycleScope.launch { graph.session.syncNow() }
                        },
                        onEnableBackgroundSync = {
                            val backgroundPermissions =
                                graph.health.supportedBackgroundReadPermissions()
                            if (backgroundPermissions.isNotEmpty()) {
                                backgroundReadPermissionLauncher.launch(backgroundPermissions)
                            } else {
                                backgroundSyncLauncher.launch(Unit)
                            }
                        },
                        onDisableBackgroundSync = {
                            lifecycleScope.launch { graph.session.disableBackgroundSync() }
                        },
                        onOpenPrivacy = { openUri(AppLinks.Privacy) },
                        onOpenTerms = { openUri(AppLinks.Terms) },
                        onOpenHealthNotice = { openUri(AppLinks.HealthNotice) },
                        onOpenAiSafety = { openUri(AppLinks.AiSafety) },
                        onOpenSupport = { openUri(AppLinks.Support) },
                        onDeleteAccount = { openUri(AppLinks.AccountDeletion) },
                        onRetry = {
                            lifecycleScope.launch { graph.session.retry() }
                        },
                        onSignOut = {
                            lifecycleScope.launch {
                                graph.session.signOut()
                                graph.login.reset()
                            }
                        },
                    ),
                )
            }
        }

        lifecycleScope.launch { graph.session.start() }
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
            lifecycleScope.launch { graph.session.didBecomeActive() }
        }
    }

    private fun openHealthConnect() {
        val intent = graph.health.openHealthConnectIntent()
        if (intent != null) {
            startActivity(intent)
        }
    }

    private fun openUri(value: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    }

    private fun isHealthPermissionRationaleIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            action == "android.intent.action.VIEW_PERMISSION_USAGE"
    }
}
