package ai.withmurph.companion.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.work.WorkManager
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthPermissionRequestResult
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.JunctionExternalUserId
import io.tryvital.client.AuthenticateRequest
import io.tryvital.client.VitalClient
import io.tryvital.vitalhealthconnect.VitalHealthConnectManager
import io.tryvital.vitalhealthconnect.model.PermissionOutcome
import io.tryvital.vitalhealthcore.model.ConnectionPolicy
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

private const val vitalPausePreference = "pauseSync"
internal const val vitalResourceSyncStarter = "HC.ResourceSyncStarter"

internal fun vitalResourceSyncWorkerName(resource: VitalResource): String =
    "HC.ResourceSyncWorker.${resource.name}"

private const val readExercise = "android.permission.health.READ_EXERCISE"
private const val readElevation = "android.permission.health.READ_ELEVATION_GAINED"
private const val readPower = "android.permission.health.READ_POWER"
private const val readSpeed = "android.permission.health.READ_SPEED"
private const val readMenstruation = "android.permission.health.READ_MENSTRUATION"
private const val readCervicalMucus = "android.permission.health.READ_CERVICAL_MUCUS"
private const val readIntermenstrualBleeding =
    "android.permission.health.READ_INTERMENSTRUAL_BLEEDING"
private const val readOvulationTest = "android.permission.health.READ_OVULATION_TEST"
private const val readSexualActivity = "android.permission.health.READ_SEXUAL_ACTIVITY"

private val workoutDetailPermissions = setOf(readElevation, readPower, readSpeed)
private val menstrualDetailPermissions = setOf(
    readCervicalMucus,
    readIntermenstrualBleeding,
    readOvulationTest,
    readSexualActivity,
)

// Explicitly enumerate the complete Vital 5.0.2 Health Connect surface.
// The parity test fails when a future SDK release adds a resource so dependency
// upgrades cannot silently broaden member permissions or Play declarations.
internal val healthConnectReadResources: Set<VitalResource> = setOf(
    VitalResource.Profile,
    VitalResource.Body,
    VitalResource.Workout,
    VitalResource.Activity,
    VitalResource.Sleep,
    VitalResource.Glucose,
    VitalResource.BloodPressure,
    VitalResource.BloodOxygen,
    VitalResource.HeartRate,
    VitalResource.Water,
    VitalResource.HeartRateVariability,
    VitalResource.MenstrualCycle,
    VitalResource.Steps,
    VitalResource.ActiveEnergyBurned,
    VitalResource.BasalEnergyBurned,
    VitalResource.FloorsClimbed,
    VitalResource.DistanceWalkingRunning,
    VitalResource.Vo2Max,
    VitalResource.RespiratoryRate,
    VitalResource.Temperature,
    VitalResource.Meal,
)

// Keep app-owned counts and manual syncs bound to the reviewed scope. Vital
// discovers grants across every SDK resource, so this intersection prevents a
// stale or newly added SDK grant from silently becoming Murph-owned behavior.
internal fun configuredHealthConnectReadResources(
    grantedResources: Set<VitalResource>,
): Set<VitalResource> = healthConnectReadResources.intersect(grantedResources)

internal fun healthPermissionRequestResult(
    activeResources: Set<VitalResource>,
    grantedPermissions: Set<String>,
): HealthPermissionRequestResult {
    val workoutBaseMissing =
        grantedPermissions.any(workoutDetailPermissions::contains) &&
            readExercise !in grantedPermissions
    val menstrualBaseMissing =
        grantedPermissions.any(menstrualDetailPermissions::contains) &&
            readMenstruation !in grantedPermissions
    return when {
        workoutBaseMissing && menstrualBaseMissing ->
            HealthPermissionRequestResult.MissingWorkoutAndMenstrualBases
        workoutBaseMissing -> HealthPermissionRequestResult.MissingWorkoutBase
        menstrualBaseMissing -> HealthPermissionRequestResult.MissingMenstrualBase
        configuredHealthConnectReadResources(activeResources).isEmpty() ->
            HealthPermissionRequestResult.NoActiveResource
        else -> HealthPermissionRequestResult.Ready
    }
}

class JunctionHealthSyncService(
    context: Context,
    private val environment: AppEnvironment,
    private val backfillDays: Int = 30,
) : HealthSyncing {
    private val appContext = context.applicationContext
    private val manager = VitalHealthConnectManager.getOrCreate(appContext)

    override val totalResourceCount: Int = healthConnectReadResources.size

    fun healthPermissionContract() = manager.createPermissionRequestContract(
        readResources = healthConnectReadResources,
        writeResources = emptySet(),
    )

    suspend fun permissionRequestCompleted(
        outcome: Deferred<PermissionOutcome>,
    ): HealthPermissionRequestResult {
        if (outcome.await() !is PermissionOutcome.Success) {
            return HealthPermissionRequestResult.NoActiveResource
        }
        val grantedPermissions = HealthConnectClient.getOrCreate(appContext)
            .permissionController
            .getGrantedPermissions()
        return healthPermissionRequestResult(
            activeResources = manager.resourcesWithReadPermission(),
            grantedPermissions = grantedPermissions,
        )
    }

    override fun availability(): HealthConnectAvailability =
        VitalHealthConnectManager.isAvailable(appContext).toAppAvailability()

    override fun openHealthConnectIntent(): Intent? =
        VitalHealthConnectManager.openHealthConnectIntent(appContext)

    override fun isSignedIn(): Boolean = VitalClient.Status.SignedIn in VitalClient.status

    override fun pauseAutomaticSync() {
        manager.pauseSynchronization = true
    }

    override suspend fun identify(
        memberKey: String,
        authenticate: suspend () -> String,
    ) {
        val externalUserId = JunctionExternalUserId.derive(memberKey, environment)
        if (VitalClient.identifiedExternalUser == externalUserId) {
            // Vital 5.0.2 returns early for an unchanged external id without invoking
            // authenticate. The app has already obtained a backend-authorized one-time
            // token, so reset the stale SDK session and make that lifecycle decision win.
            VitalClient.getOrCreate(appContext).signOut()
        }
        VitalClient.identifyExternalUser(appContext, externalUserId) {
            AuthenticateRequest.SignInToken(authenticate())
        }
    }

    override fun configure() {
        pauseAutomaticSync()
        manager.configureHealthConnectClient(
            logsEnabled = false,
            syncOnAppStart = false,
            numberOfDaysToBackFill = backfillDays,
            connectionPolicy = ConnectionPolicy.Explicit,
        )
    }

    override suspend fun connectAfterPermissionRequest() {
        manager.connect()
    }

    override suspend fun refreshPermissionState() {
        manager.reloadPermissions()
    }

    override suspend fun syncAllGrantedResources(expectedMemberKey: String) {
        // An upgrade can inherit durable all-granted work enqueued by Vital's public
        // unpause setter. Retire every pinned chain before evaluating the exact set,
        // including when the new configured-and-granted intersection is empty.
        cancelAndAwaitVitalWork()
        val resources = configuredHealthConnectReadResources(
            manager.resourcesWithReadPermission(),
        )
        if (resources.isEmpty()) return
        VitalHealthWorkerLease.openFor(expectedMemberKey)
        try {
            setManualSyncPaused(false)
            manager.syncData(resources = resources)
        } finally {
            withContext(NonCancellable) {
                try {
                    cancelAndAwaitVitalWork()
                } finally {
                    VitalHealthWorkerLease.closeFor(expectedMemberKey)
                }
            }
        }
    }

    override fun grantedResourceCount(): Int =
        configuredHealthConnectReadResources(
            manager.resourcesWithReadPermission(),
        ).size

    override suspend fun signOutSdk() {
        cancelAndAwaitVitalWork()
        VitalClient.getOrCreate(appContext).signOut()
    }

    /**
     * Vital 5.0.2's public pause setter starts an all-granted automatic worker
     * when unpaused. The pinned preference is the narrower manual-sync gate:
     * changing it directly lets syncData() enqueue only the supplied set.
     */
    private suspend fun setManualSyncPaused(paused: Boolean) {
        val committed = withContext(Dispatchers.IO) {
            manager.sharedPreferences.edit()
                .putBoolean(vitalPausePreference, paused)
                .commit()
        }
        check(committed) { "Could not durably update the Vital manual-sync gate" }
    }

    /** Cancel every pinned Vital foreground worker and prove terminal state. */
    private suspend fun cancelAndAwaitVitalWork() {
        setManualSyncPaused(true)
        val workManager = WorkManager.getInstance(appContext)
        val workNames = buildList {
            add(vitalResourceSyncStarter)
            healthConnectReadResources.forEach { resource ->
                add(vitalResourceSyncWorkerName(resource))
            }
        }
        workNames.forEach { workName ->
            workManager.cancelUniqueWork(workName).result.await()
        }
        val remaining = workNames.flatMap { workName ->
            workManager.getWorkInfosForUniqueWork(workName).await()
        }
        check(remaining.all { it.state.isFinished }) {
            "Vital health workers were not terminal before identity teardown"
        }
    }
}

internal fun ProviderAvailability.toAppAvailability(): HealthConnectAvailability = when (this) {
    ProviderAvailability.Installed -> HealthConnectAvailability.Available
    ProviderAvailability.NotInstalled -> HealthConnectAvailability.InstallOrUpdateRequired
    ProviderAvailability.OnboardingIncomplete -> HealthConnectAvailability.OnboardingRequired
    ProviderAvailability.AppNotAllowed -> HealthConnectAvailability.AppNotAllowed
    ProviderAvailability.ServiceUnavailable -> HealthConnectAvailability.TemporarilyUnavailable
    ProviderAvailability.NotSupportedSDK -> HealthConnectAvailability.Unsupported
}
