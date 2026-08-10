package ai.withmurph.companion.health

import android.content.Context
import android.content.Intent
import androidx.lifecycle.asFlow
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.HealthConnectAvailability
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class JunctionHealthSyncService(
    context: Context,
    private val environment: AppEnvironment,
    private val backfillDays: Int = 30,
) : HealthSyncing {
    private val appContext = context.applicationContext
    private val manager = VitalHealthConnectManager.getOrCreate(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    override val totalResourceCount: Int = requestedReadResources.size

    fun healthPermissionContract() = manager.createPermissionRequestContract(
        readResources = requestedReadResources,
        writeResources = emptySet(),
    )

    suspend fun permissionRequestCompleted(outcome: Deferred<PermissionOutcome>): Boolean {
        if (outcome.await() !is PermissionOutcome.Success) return false
        return configuredGrantedResources(manager.resourcesWithReadPermission()).isNotEmpty()
    }

    override fun availability(): HealthConnectAvailability =
        VitalHealthConnectManager.isAvailable(appContext).toAppAvailability()

    override fun openHealthConnectIntent(): Intent? =
        VitalHealthConnectManager.openHealthConnectIntent(appContext)

    override fun isSignedIn(): Boolean = VitalClient.Status.SignedIn in VitalClient.status

    override fun pauseAutomaticSync() {
        manager.pauseSynchronization = true
    }

    override fun cancelActiveSync() {
        manager.pauseSynchronization = true
        cancelSyncWorkers()
    }

    override suspend fun identify(
        memberKey: String,
        authenticate: suspend () -> String,
    ) {
        pauseAndAwaitSyncWorkers()
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

    override suspend fun syncAllGrantedResources() {
        withContext(Dispatchers.Main.immediate) {
            manager.pauseSynchronization = false
        }
        try {
            manager.syncData(
                resources = configuredGrantedResources(manager.resourcesWithReadPermission()),
            )
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    manager.pauseSynchronization = true
                }
                cancelAndAwaitSyncWorkers()
            }
        }
    }

    private fun cancelSyncWorkers(): List<Operation> =
        syncWorkNames(requestedReadResources).map(workManager::cancelUniqueWork)

    override fun grantedResourceCount(): Int =
        configuredGrantedResources(manager.resourcesWithReadPermission()).size

    override suspend fun signOutSdk() {
        pauseAndAwaitSyncWorkers()
        VitalClient.getOrCreate(appContext).signOut()
    }

    private suspend fun pauseAndAwaitSyncWorkers() {
        withContext(Dispatchers.Main.immediate) {
            manager.pauseSynchronization = true
        }
        cancelAndAwaitSyncWorkers()
    }

    private suspend fun cancelAndAwaitSyncWorkers() {
        val workNames = syncWorkNames(requestedReadResources)
        cancelSyncWorkers().forEach { operation ->
            val state = operation.state.asFlow().first {
                it is Operation.State.SUCCESS || it is Operation.State.FAILURE
            }
            if (state is Operation.State.FAILURE) throw state.throwable
        }
        awaitSyncWorkersTerminal(workNames) { workName ->
            workManager.getWorkInfosForUniqueWorkLiveData(workName).asFlow()
        }
    }

    companion object {
        /**
         * The complete app-owned Junction scope. Keep this set aligned with the
         * read-only Health Connect permissions in AndroidManifest.xml.
         *
         * Heart rate remains outside this set because Vital cannot request it
         * only as sleep/workout enrichment, while Murph intentionally excludes
         * the unbounded standalone stream from default ingestion.
         *
         * Vital 5.0.2 discovers granted resources by scanning every SDK
         * resource. Murph keeps the SDK paused across permission and connect
         * flows, then briefly unpauses only inside syncAllGrantedResources so
         * this configured, post-commit call owns the resource chain. Vital's
         * resource remapping is an identity operation in this version, so
         * Activity, Steps, and ActiveEnergyBurned remain separate sync owners.
         * Keep all three explicit here so manual sync and resource counts match
         * the already-shipped manifest grants. Murph's current default intake
         * admits the Activity summary but not the two standalone timeseries;
         * preserving their client upload behavior avoids a silent mobile
         * regression while that backend boundary remains explicit.
         */
        internal val requestedReadResources = setOf(
            VitalResource.Sleep,
            VitalResource.Workout,
            VitalResource.Activity,
            VitalResource.Steps,
            VitalResource.ActiveEnergyBurned,
            VitalResource.HeartRateVariability,
            VitalResource.RespiratoryRate,
            VitalResource.BloodOxygen,
            VitalResource.Body,
            VitalResource.Profile,
            VitalResource.Vo2Max,
        )

        internal fun configuredGrantedResources(
            grantedResources: Set<VitalResource>,
        ): Set<VitalResource> = requestedReadResources.intersect(grantedResources)

        /**
         * Vital 5.0.2 exposes no public cancellation API. These are its exact
         * unique WorkManager names for the umbrella worker and every app-owned
         * resource worker. Keep this mapping pinned to the reviewed SDK source.
         */
        internal fun syncWorkNames(resources: Set<VitalResource>): Set<String> = buildSet {
            add("HC.ResourceSyncStarter")
            resources.forEach { resource ->
                add("HC.ResourceSyncWorker.$resource")
            }
        }

        internal suspend fun awaitSyncWorkersTerminal(
            workNames: Set<String>,
            workInfos: (String) -> Flow<List<WorkInfo>>,
        ) {
            workNames.forEach { workName ->
                workInfos(workName).first { infos ->
                    infos.all { info -> info.state.isFinished }
                }
            }
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
