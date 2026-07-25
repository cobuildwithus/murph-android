package ai.withmurph.companion.core

import java.time.Duration
import java.time.Instant

enum class AppEnvironment(val wireValue: String) {
    Sandbox("sandbox"),
    Production("production"),
}

enum class CompanionPlatform(val wireValue: String) {
    Android("android"),
}

enum class ConnectionIntent(val wireValue: String) {
    Connect("connect"),
    Resume("resume"),
}

enum class LoginMethod {
    Phone,
    Email,
}

sealed interface AuthSessionState {
    data object SignedOut : AuthSessionState
    data class SignedIn(
        val memberKey: String,
        val verifiedOnline: Boolean,
    ) : AuthSessionState
    data object TemporarilyUnavailable : AuthSessionState
}

data class SignInTokenRequest(
    val platform: CompanionPlatform = CompanionPlatform.Android,
    val appInstallationId: String,
    val appVersion: String,
    val connectionIntent: ConnectionIntent,
    val sdkVersions: Map<String, String>,
)

data class SignInTokenResponse(
    val signInToken: String,
    val environment: String,
)

data class CompanionSyncStatus(
    val lastDataReceivedAt: Instant?,
    val resources: Map<String, ResourceStatus>,
) {
    data class ResourceStatus(val lastReceivedAt: Instant?)
}

enum class HealthConnectAvailability {
    Available,
    InstallOrUpdateRequired,
    Unsupported,
    TemporarilyUnavailable,
}

sealed interface HealthSyncState {
    data object NotConnected : HealthSyncState
    data object AwaitingFirstData : HealthSyncState
    data class Synced(val lastDataReceivedAt: Instant) : HealthSyncState
    data class Delayed(val lastDataReceivedAt: Instant) : HealthSyncState
    data class NeedsAttention(val lastDataReceivedAt: Instant?) : HealthSyncState

    companion object {
        fun derive(
            requested: Boolean,
            status: CompanionSyncStatus?,
            now: Instant,
            delayedAfter: Duration = Duration.ofHours(36),
            attentionAfter: Duration = Duration.ofHours(72),
        ): HealthSyncState {
            if (!requested) return NotConnected
            val receivedAt = status?.lastDataReceivedAt ?: return AwaitingFirstData
            val age = Duration.between(receivedAt, now).coerceAtLeast(Duration.ZERO)
            return when {
                age >= attentionAfter -> NeedsAttention(receivedAt)
                age >= delayedAfter -> Delayed(receivedAt)
                else -> Synced(receivedAt)
            }
        }
    }
}

sealed class CompanionApiException(message: String) : Exception(message) {
    data object Network : CompanionApiException("Network request failed")
    data object Unauthorized : CompanionApiException("Authentication required")
    data object NoAccount : CompanionApiException("No Murph account")
    data object ConsentRequired : CompanionApiException("Murph consent required")
    data object ReconnectRequired : CompanionApiException("Health connection must be reconnected")
    data class Server(val status: Int) : CompanionApiException("Server returned $status")
    data object InvalidResponse : CompanionApiException("Invalid server response")
}
