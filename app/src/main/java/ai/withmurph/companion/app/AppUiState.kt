package ai.withmurph.companion.app

import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState

sealed interface AppPhase {
    data object Launching : AppPhase
    data object NeedsLogin : AppPhase
    data object Ready : AppPhase
    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
        val canSignOut: Boolean = false,
    ) : AppPhase
}

data class AppUiState(
    val phase: AppPhase = AppPhase.Launching,
    val healthAvailability: HealthConnectAvailability = HealthConnectAvailability.TemporarilyUnavailable,
    val healthSync: HealthSyncState = HealthSyncState.NotConnected,
    val isConnectingHealth: Boolean = false,
    val isSyncingHealth: Boolean = false,
    val healthMessage: String? = null,
    val grantedResourceCount: Int = 0,
    val totalResourceCount: Int = 0,
    val backendEnvironment: String? = null,
    val authVerifiedOnline: Boolean = true,
)
