package ai.withmurph.companion.app

import android.content.Context
import android.os.Looper
import ai.withmurph.companion.api.HttpCompanionApi
import ai.withmurph.companion.auth.LoginCoordinator
import ai.withmurph.companion.auth.PrivyAuthService
import ai.withmurph.companion.health.JunctionHealthSyncService
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph private constructor(
    val session: AppSession,
    val login: LoginCoordinator,
    val health: JunctionHealthSyncService,
    val config: AppConfig,
    val applicationScope: CoroutineScope,
) {
    companion object {
        fun create(context: Context): AppGraph {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "AppGraph and Privy must be initialized on the main thread"
            }
            val config = AppConfig.current.also(AppConfig::requireConfigured)
            val auth = PrivyAuthService.create(
                context = context,
                appId = config.privyAppId,
                appClientId = config.privyAppClientId,
            )
            val api = HttpCompanionApi(
                baseUrl = config.backendBaseUrl,
                identityToken = auth::identityToken,
            )
            val localState = SharedPreferencesLocalState(context)
            val health = JunctionHealthSyncService(
                context = context,
                environment = config.environment,
                backfillDays = 30,
            )
            val session = AppSession(
                auth = auth,
                api = api,
                health = health,
                localState = localState,
                config = config,
            )
            return AppGraph(
                session = session,
                login = LoginCoordinator(auth),
                health = health,
                config = config,
                applicationScope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Main.immediate,
                ),
            )
        }
    }
}
