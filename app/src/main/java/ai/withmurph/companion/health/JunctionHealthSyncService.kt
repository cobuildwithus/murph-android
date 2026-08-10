package ai.withmurph.companion.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthGrantSnapshot
import ai.withmurph.companion.core.HealthPermissionRequestResult
import ai.withmurph.companion.core.HealthSyncAttemptResult
import ai.withmurph.companion.core.HealthSyncForegroundLaunchRejectedException
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.JunctionExternalUserId
import ai.withmurph.companion.core.TEMPERATURE_HEALTH_RESOURCE_OWNER_KEY
import io.tryvital.client.AuthenticateRequest
import io.tryvital.client.VitalClient
import io.tryvital.vitalhealthconnect.VitalHealthConnectManager
import io.tryvital.vitalhealthconnect.model.PermissionOutcome
import io.tryvital.vitalhealthcore.model.ConnectionPolicy
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import kotlinx.coroutines.CancellationException
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

internal fun backendFailureOwnerKeyFor(resource: VitalResource): String = when (resource) {
    VitalResource.Profile -> "profile"
    VitalResource.Body -> "body"
    VitalResource.Workout -> "workouts"
    VitalResource.Activity -> "activity"
    VitalResource.Sleep -> "sleep"
    VitalResource.Glucose -> "glucose"
    VitalResource.BloodPressure -> "blood_pressure"
    VitalResource.BloodOxygen -> "blood_oxygen"
    VitalResource.HeartRate -> "heartrate"
    VitalResource.Water -> "water"
    VitalResource.HeartRateVariability -> "hrv"
    VitalResource.MenstrualCycle -> "menstrual_cycle"
    VitalResource.Steps -> "steps"
    VitalResource.ActiveEnergyBurned -> "calories_active"
    VitalResource.BasalEnergyBurned -> "calories_basal"
    VitalResource.FloorsClimbed -> "floors_climbed"
    VitalResource.DistanceWalkingRunning -> "distance"
    VitalResource.Vo2Max -> "vo2_max"
    VitalResource.RespiratoryRate -> "respiratory_rate"
    VitalResource.Temperature -> TEMPERATURE_HEALTH_RESOURCE_OWNER_KEY
    VitalResource.Meal -> "meal"
}

internal data class VitalStarterWorkEvidence(
    val id: java.util.UUID,
    val state: WorkInfo.State,
    val failedResources: Set<VitalResource>?,
)

internal fun healthSyncResultForStarterEvidence(
    workEvidence: List<VitalStarterWorkEvidence>,
    existingStarterIds: Set<java.util.UUID>,
): HealthSyncAttemptResult {
    val newStarterWork = workEvidence.filter { it.id !in existingStarterIds }
    if (newStarterWork.isEmpty()) return HealthSyncAttemptResult.NotStarted
    val starter = newStarterWork.singleOrNull()
        ?: return HealthSyncAttemptResult.ReconnectRequired
    return when {
        starter.state == WorkInfo.State.SUCCEEDED -> HealthSyncAttemptResult.Complete
        starter.state == WorkInfo.State.FAILED && starter.failedResources != null ->
            HealthSyncAttemptResult.PartialFailure(
                starter.failedResources.mapTo(linkedSetOf(), ::backendFailureOwnerKeyFor),
            )
        else -> HealthSyncAttemptResult.ReconnectRequired
    }
}

internal fun healthSyncResultForNewStarterWork(
    workInfos: List<WorkInfo>,
    existingStarterIds: Set<java.util.UUID>,
): HealthSyncAttemptResult = healthSyncResultForStarterEvidence(
    workEvidence = workInfos.map { workInfo ->
        VitalStarterWorkEvidence(
            id = workInfo.id,
            state = workInfo.state,
            failedResources = vitalFailedResources(workInfo.outputData),
        )
    },
    existingStarterIds = existingStarterIds,
)

internal fun healthPermissionRequestResult(
    activeResources: Set<VitalResource>,
    grantedPermissions: Set<String>,
): HealthPermissionRequestResult {
    if (configuredHealthConnectReadResources(activeResources).isNotEmpty()) {
        return HealthPermissionRequestResult.Ready
    }
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
        else -> HealthPermissionRequestResult.NoActiveResource
    }
}

/**
 * Vital reports NotPrompted when a repeated request adds no new permission.
 * That disposition does not erase grants from an earlier interaction, so both
 * successful prompt dispositions must be followed by current-state discovery.
 */
internal fun permissionOutcomeAllowsCurrentGrantClassification(
    outcome: PermissionOutcome,
): Boolean =
    outcome is PermissionOutcome.Success ||
        outcome is PermissionOutcome.NotPrompted

class JunctionHealthSyncService(
    context: Context,
    private val environment: AppEnvironment,
    private val backfillDays: Int = 30,
) : HealthSyncing {
    private val appContext = context.applicationContext
    private val manager = createVitalManagerAfterGuardedWorkManager(appContext)

    override val totalResourceCount: Int = healthConnectReadResources.size

    fun healthPermissionContract() = manager.createPermissionRequestContract(
        readResources = healthConnectReadResources,
        writeResources = emptySet(),
    )

    suspend fun permissionRequestCompleted(
        outcome: Deferred<PermissionOutcome>,
    ): HealthPermissionRequestResult {
        if (!permissionOutcomeAllowsCurrentGrantClassification(outcome.await())) {
            return HealthPermissionRequestResult.NoActiveResource
        }
        manager.reloadPermissions()
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

    override suspend fun syncAllGrantedResources(
        expectedMemberKey: String,
    ): HealthSyncAttemptResult {
        // An upgrade can inherit durable all-granted work enqueued by Vital's public
        // unpause setter. Retire every pinned chain before evaluating the exact set,
        // including when the new configured-and-granted intersection is empty.
        val preparation = try {
            cancelAndAwaitVitalWork()
            val resources = configuredHealthConnectReadResources(
                manager.resourcesWithReadPermission(),
            )
            if (resources.isEmpty()) return HealthSyncAttemptResult.Complete
            val workManager = WorkManager.getInstance(appContext)
            val existingStarterIds = workManager
                .getWorkInfosForUniqueWork(vitalResourceSyncStarter)
                .await()
                .mapTo(mutableSetOf()) { it.id }
            PreparedHealthSync(workManager, existingStarterIds, resources)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return HealthSyncAttemptResult.NotStarted
        }

        try {
            VitalHealthWorkerLease.openFor(expectedMemberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return HealthSyncAttemptResult.NotStarted
        }

        var launchRejected = false
        var syncInvocationBegan = false
        var manualGateOpened = false
        var outcome: HealthSyncAttemptResult = HealthSyncAttemptResult.NotStarted
        var cleanupFailure: Throwable? = null
        try {
            try {
                setManualSyncPaused(false)
                manualGateOpened = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                outcome = HealthSyncAttemptResult.NotStarted
            }

            if (manualGateOpened) {
                // Only enter the SDK after the manual gate is durably open.
                syncInvocationBegan = true
                outcome = try {
                    manager.syncData(resources = preparation.resources)
                    launchRejected =
                        VitalHealthWorkerLease.wasLaunchRejectedFor(expectedMemberKey)
                    syncResultFromCurrentStarterWork(preparation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    launchRejected =
                        VitalHealthWorkerLease.wasLaunchRejectedFor(expectedMemberKey)
                    if (launchRejected) {
                        HealthSyncAttemptResult.NotStarted
                    } else {
                        syncResultFromCurrentStarterWork(preparation)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    cancelAndAwaitVitalWork()
                } catch (error: Exception) {
                    cleanupFailure = error
                } finally {
                    VitalHealthWorkerLease.closeFor(expectedMemberKey)
                }
            }
        }
        cleanupFailure?.let { error ->
            if (error is CancellationException) throw error
            outcome = if (syncInvocationBegan) {
                HealthSyncAttemptResult.ReconnectRequired
            } else {
                HealthSyncAttemptResult.NotStarted
            }
        }
        if (launchRejected) throw HealthSyncForegroundLaunchRejectedException()
        return outcome
    }

    override fun grantSnapshot(): HealthGrantSnapshot = try {
        val resources = configuredHealthConnectReadResources(
            manager.resourcesWithReadPermission(),
        )
        HealthGrantSnapshot.Available(
            resourceCount = resources.size,
            resourceKeys = resources.mapTo(linkedSetOf(), ::backendFailureOwnerKeyFor),
        )
    } catch (_: Exception) {
        HealthGrantSnapshot.Unavailable
    }

    override fun revokeUnpromotedSyncLaunch() {
        VitalHealthWorkerLease.rejectUnpromoted()
    }

    override suspend fun revokeActiveSyncAuthorization() {
        VitalHealthWorkerLease.close()
        cancelAndAwaitVitalWork()
    }

    override suspend fun signOutSdk() {
        revokeActiveSyncAuthorization()
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

    /**
     * Cancel every pinned Vital foreground worker and drain the actual delegated
     * resource bodies. WorkInfo cancellation alone is not execution quiescence.
     */
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
        VitalHealthWorkerLease.awaitNoActiveExecutions()
    }

    private suspend fun syncResultFromCurrentStarterWork(
        preparation: PreparedHealthSync,
    ): HealthSyncAttemptResult = try {
        healthSyncResultForNewStarterWork(
            workInfos = preparation.workManager
                .getWorkInfosForUniqueWork(vitalResourceSyncStarter)
                .await(),
            existingStarterIds = preparation.existingStarterIds,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        HealthSyncAttemptResult.ReconnectRequired
    }

    private data class PreparedHealthSync(
        val workManager: WorkManager,
        val existingStarterIds: Set<java.util.UUID>,
        val resources: Set<VitalResource>,
    )
}

private fun createVitalManagerAfterGuardedWorkManager(
    appContext: Context,
): VitalHealthConnectManager {
    // Vital 5.0.2's Startup initializer depends on WorkManagerInitializer and is
    // removed from the manifest. This on-demand call must install the
    // MurphApplication Configuration.Provider before the Vital manager exists.
    WorkManager.getInstance(appContext)
    return VitalHealthConnectManager.getOrCreate(appContext)
}

internal fun ProviderAvailability.toAppAvailability(): HealthConnectAvailability = when (this) {
    ProviderAvailability.Installed -> HealthConnectAvailability.Available
    ProviderAvailability.NotInstalled -> HealthConnectAvailability.InstallOrUpdateRequired
    ProviderAvailability.OnboardingIncomplete -> HealthConnectAvailability.OnboardingRequired
    ProviderAvailability.AppNotAllowed -> HealthConnectAvailability.AppNotAllowed
    ProviderAvailability.ServiceUnavailable -> HealthConnectAvailability.TemporarilyUnavailable
    ProviderAvailability.NotSupportedSDK -> HealthConnectAvailability.Unsupported
}
