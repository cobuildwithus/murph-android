package ai.withmurph.companion.app

import android.content.Context
import android.os.Looper
import ai.withmurph.companion.api.HttpCompanionApi
import ai.withmurph.companion.auth.LoginCoordinator
import ai.withmurph.companion.auth.PrivyAuthService
import ai.withmurph.companion.contacts.AndroidAddressBookContacts
import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.core.MealPhotoCaptureControlling
import ai.withmurph.companion.health.JunctionHealthSyncService
import ai.withmurph.companion.meal.AndroidMealPhotoMediaSource
import ai.withmurph.companion.meal.HttpMealPhotoUploader
import ai.withmurph.companion.meal.KeystoreMealPhotoCredentialStore
import ai.withmurph.companion.meal.MealPhotoCaptureService
import ai.withmurph.companion.meal.MealPhotoProcessor
import ai.withmurph.companion.meal.MlKitMealPhotoImageClassifier
import ai.withmurph.companion.meal.SharedPreferencesMealPhotoStateStore
import ai.withmurph.companion.meal.SharedPreferencesMealPhotoAuthorizationStore
import ai.withmurph.companion.meal.WorkManagerMealPhotoScheduler
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph private constructor(
    val session: AppSession,
    val login: LoginCoordinator,
    val health: JunctionHealthSyncService,
    val meals: MealPhotoCaptureControlling,
    val contacts: AddressBookContactSource,
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
                identityTokenForMember = auth::identityTokenForMember,
            )
            val localState = SharedPreferencesLocalState(context)
            val contacts = AndroidAddressBookContacts(context)
            val health = JunctionHealthSyncService(
                context = context,
                environment = config.environment,
                backfillDays = 30,
            )
            val mealState = SharedPreferencesMealPhotoStateStore(context)
            val mealAuthorization = SharedPreferencesMealPhotoAuthorizationStore(context)
            val mealCredentials = KeystoreMealPhotoCredentialStore(context)
            val mealMedia = AndroidMealPhotoMediaSource(context)
            val mealUploader = HttpMealPhotoUploader(config.backendBaseUrl)
            val mealProcessor = MealPhotoProcessor(
                media = mealMedia,
                classifier = MlKitMealPhotoImageClassifier(),
                uploader = mealUploader,
                stateStore = mealState,
                credentialStore = mealCredentials,
                authorizationStore = mealAuthorization,
            )
            val meals = MealPhotoCaptureService(
                api = api,
                media = mealMedia,
                processor = mealProcessor,
                uploader = mealUploader,
                stateStore = mealState,
                credentialStore = mealCredentials,
                authorizationStore = mealAuthorization,
                scheduler = WorkManagerMealPhotoScheduler(context),
                installationId = localState.installationId,
                appVersion = config.appVersion,
            )
            val session = AppSession(
                auth = auth,
                api = api,
                health = health,
                contacts = contacts,
                meals = meals,
                localState = localState,
                config = config,
            )
            return AppGraph(
                session = session,
                login = LoginCoordinator(auth),
                health = health,
                meals = meals,
                contacts = contacts,
                config = config,
                applicationScope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Main.immediate,
                ),
            )
        }
    }
}
