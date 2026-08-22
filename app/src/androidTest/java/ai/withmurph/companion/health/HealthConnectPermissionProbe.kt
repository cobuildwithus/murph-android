package ai.withmurph.companion.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.runBlocking

private const val HEALTH_READ_PERMISSION_PREFIX = "android.permission.health.READ_"

/** Returns only coarse test evidence; permission names never leave the device. */
internal fun probeGrantedHealthConnectReadPermission(context: Context): Boolean? =
    runCatching {
        runBlocking {
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
                .any { it.startsWith(HEALTH_READ_PERMISSION_PREFIX) }
        }
    }.getOrNull()
