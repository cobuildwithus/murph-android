package ai.withmurph.companion.app

import android.content.Context
import android.os.Looper
import ai.withmurph.companion.api.HttpCompanionApi
import ai.withmurph.companion.auth.LoginCoordinator
import ai.withmurph.companion.auth.PrivyAuthService
import ai.withmurph.companion.auth.HostedAuthApiClient
import ai.withmurph.companion.auth.HostedAuthCredentialStore
import ai.withmurph.companion.auth.HostedAuthService
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
    fun prepareMealPhotos(context: Context, generation: String, uris: List<android.net.Uri>, cameraFile: java.io.File?) {
        val appContext = context.applicationContext
        val selected = uris.distinct().take(10)
        applicationScope.launch {
            session.prepareManualMealPhotos(
                generation = generation,
                count = selected.size,
                prepare = { index ->
                    ai.withmurph.companion.meals.MealPhotoSanitizer.prepare(appContext.contentResolver, selected[index])
                },
                cleanup = {
                    if (cameraFile != null) {
                        selected.forEach { uri ->
                            appContext.revokeUriPermission(uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        cameraFile.delete()
                    }
                },
            )
        }
    }

    companion object {
        fun create(context: Context): AppGraph {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "AppGraph and Privy must be initialized on the main thread"
            }
            // This graph is constructed once per process, before any photo draft exists.
            java.io.File(context.cacheDir, "meal-camera").listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("capture-")) file.delete()
            }
            val config = AppConfig.current.also(AppConfig::requireConfigured)
            val applicationScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Main.immediate,
            )
            val auth = HostedAuthService(
                api = HostedAuthApiClient(config.backendBaseUrl),
                store = HostedAuthCredentialStore(context),
                legacyFactory = {
                    PrivyAuthService.create(
                        context = context,
                        appId = config.privyAppId,
                        appClientId = config.privyAppClientId,
                    )
                },
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
                backfillDays = 365,
            )
            val session = AppSession(
                auth = auth,
                api = api,
                health = health,
                contacts = contacts,
                localState = localState,
                config = config,
                healthSyncReminder = healthSyncReminder,
                mealHistory = ai.withmurph.companion.meals.EncryptedSentMealHistory(context),
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
