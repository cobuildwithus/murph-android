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
import io.tryvital.vitalhealthcore.model.ConnectionPolicy
import io.tryvital.vitalhealthcore.model.ProviderAvailability
import io.tryvital.vitalhealthcore.model.VitalResource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    suspend fun permissionRequestCompleted(outcome: Deferred<PermissionOutcome>): Boolean {
        if (outcome.await() !is PermissionOutcome.Success) return false
        return configuredHealthConnectReadResources(
            manager.resourcesWithReadPermission(),
        ).isNotEmpty()
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

    override suspend fun syncAllGrantedResources() {
        withContext(Dispatchers.Main.immediate) {
            manager.pauseSynchronization = false
        }
        try {
            manager.syncData(
                resources = configuredHealthConnectReadResources(
                    manager.resourcesWithReadPermission(),
                ),
            )
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                manager.pauseSynchronization = true
            }
        }
    }

    override fun grantedResourceCount(): Int =
        configuredHealthConnectReadResources(
            manager.resourcesWithReadPermission(),
        ).size

    override suspend fun signOutSdk() {
        VitalClient.getOrCreate(appContext).signOut()
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
