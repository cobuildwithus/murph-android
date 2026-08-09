package ai.withmurph.companion.health

import android.content.Context
import android.content.Intent
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.JunctionExternalUserId
import io.tryvital.client.AuthenticateRequest
import io.tryvital.client.VitalClient
import io.tryvital.vitalhealthconnect.VitalHealthConnectManager
import io.tryvital.vitalhealthconnect.model.PermissionOutcome
import kotlinx.coroutines.Deferred
import io.tryvital.vitalhealthcore.model.ConnectionPolicy
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource

class JunctionHealthSyncService(
    context: Context,
    private val environment: AppEnvironment,
    private val backfillDays: Int = 30,
) : HealthSyncing {
    private val appContext = context.applicationContext
    private val manager = VitalHealthConnectManager.getOrCreate(appContext)

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
        manager.syncData(
            resources = configuredGrantedResources(manager.resourcesWithReadPermission()),
        )
    }

    override fun grantedResourceCount(): Int =
        configuredGrantedResources(manager.resourcesWithReadPermission()).size

    override suspend fun signOutSdk() {
        VitalClient.getOrCreate(appContext).signOut()
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
         * resource, then may automatically sync that discovered set after both
         * permission and connect flows. Its resource remapping is an identity
         * operation in this version, so Activity, Steps, and ActiveEnergyBurned
         * remain separate sync owners. Keep all three explicit here so manual
         * sync and resource counts match the already-shipped manifest grants
         * and the SDK's automatic behavior. Murph's current default intake
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
