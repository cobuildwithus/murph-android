package ai.withmurph.companion.app

import android.content.Context
import android.os.Looper
import ai.withmurph.companion.api.HttpCompanionApi
import ai.withmurph.companion.auth.LoginCoordinator
import ai.withmurph.companion.auth.PrivyAuthService
import ai.withmurph.companion.contacts.AndroidAddressBookContacts
import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.health.JunctionHealthSyncService
import ai.withmurph.companion.reminders.HealthSyncReminderController
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph private constructor(
    val session: AppSession,
    val login: LoginCoordinator,
    val health: JunctionHealthSyncService,
    val contacts: AddressBookContactSource,
    val healthSyncReminder: HealthSyncReminderController,
    val config: AppConfig,
    val applicationScope: CoroutineScope,
) {
    companion object {
        fun create(context: Context): AppGraph {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "AppGraph and Privy must be initialized on the main thread"
            }
            val config = AppConfig.current.also(AppConfig::requireConfigured)
            val applicationScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Main.immediate,
            )
            val auth = PrivyAuthService.create(
                context = context,
                appId = config.privyAppId,
                appClientId = config.privyAppClientId,
            )
            val api = HttpCompanionApi(
                baseUrl = config.backendBaseUrl,
                identityTokenForMember = auth::identityTokenForMember,
            )
            val localState = SharedPreferencesLocalState(context)
            val healthSyncReminder = HealthSyncReminderController(context, localState)
            val contacts = AndroidAddressBookContacts(context)
            val health = JunctionHealthSyncService(
                context = context,
                environment = config.environment,
                backfillDays = 30,
            )
            val session = AppSession(
                auth = auth,
                api = api,
                health = health,
                contacts = contacts,
                localState = localState,
                config = config,
                healthSyncReminder = healthSyncReminder,
            )
            return AppGraph(
                session = session,
                login = LoginCoordinator(
                    auth = auth,
                    appVersion = config.appVersion,
                    recordDiagnostic = { event ->
                        applicationScope.launch { api.recordAuthDiagnostic(event) }
                    },
                ),
                health = health,
                contacts = contacts,
                healthSyncReminder = healthSyncReminder,
                config = config,
                applicationScope = applicationScope,
            )
        }
    }
}
