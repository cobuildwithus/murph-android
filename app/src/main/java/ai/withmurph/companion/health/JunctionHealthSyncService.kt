package ai.withmurph.companion.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission.Companion.PERMISSION_READ_HEALTH_DATA_HISTORY
import androidx.health.connect.client.permission.HealthPermission.Companion.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.JunctionExternalUserId
import io.tryvital.client.AuthenticateRequest
import io.tryvital.client.VitalClient
import io.tryvital.vitalhealthconnect.VitalHealthConnectManager
import io.tryvital.vitalhealthconnect.disableBackgroundSync
import io.tryvital.vitalhealthconnect.enableBackgroundSyncContract
import io.tryvital.vitalhealthconnect.isBackgroundSyncEnabled
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
    private val readResources = setOf(
        VitalResource.Sleep,
        VitalResource.Workout,
        VitalResource.Steps,
        VitalResource.ActiveEnergyBurned,
    )

    override val totalResourceCount: Int = readResources.size

    fun healthPermissionContract() = manager.createPermissionRequestContract(
        readResources = readResources,
        writeResources = emptySet(),
    )

    fun extendedPermissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun permissionRequestCompleted(outcome: Deferred<PermissionOutcome>): Boolean {
        if (outcome.await() !is PermissionOutcome.Success) return false
        return manager.resourcesWithReadPermission().isNotEmpty()
    }

    fun backgroundSyncContract() = manager.enableBackgroundSyncContract()

    fun supportedHistoryPermissions(): Set<String> = supportedPermission(
        feature = HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
        permission = PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    fun supportedBackgroundReadPermissions(): Set<String> = supportedPermission(
        feature = HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        permission = PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

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
        manager.syncData(resources = null)
    }

    override fun isBackgroundSyncEnabled(): Boolean = manager.isBackgroundSyncEnabled

    override fun grantedResourceCount(): Int = manager.resourcesWithReadPermission().size

    override suspend fun disableBackgroundSync() {
        manager.disableBackgroundSync()
    }

    override suspend fun signOutSdk() {
        VitalClient.getOrCreate(appContext).signOut()
    }

    private fun supportedPermission(feature: Int, permission: String): Set<String> {
        if (availability() != HealthConnectAvailability.Available) return emptySet()
        val client = HealthConnectClient.getOrCreate(appContext)
        return if (
            client.features.getFeatureStatus(feature) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            setOf(permission)
        } else {
            emptySet()
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
