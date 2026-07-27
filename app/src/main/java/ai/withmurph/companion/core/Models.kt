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

enum class AddressBookWriteCapability(val wireValue: String) {
    Enabled("enabled"),
    Disabled("disabled"),
}

data class AddressBookServerStatus(
    val writeCapability: AddressBookWriteCapability,
    val enabled: Boolean,
    val revision: Int,
    val storedContactCount: Int,
)

data class AddressBookPersonContact(
    val givenName: String?,
    val familyName: String?,
    val phoneNumbers: List<String>,
)

data class AddressBookProjection(
    val phoneNumber: String,
    val advisoryName: String,
)

data class AddressBookMutation(
    val baseRevision: Int,
    val mutationId: String,
) {
    init {
        require(baseRevision >= 0) { "Address-book base revision must be non-negative" }
        require(isUuidV4(mutationId)) { "Address-book mutation id must be UUIDv4" }
    }

    companion object {
        private val UUID_V4 = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )

        fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)
    }
}

data class AddressBookReplacementRequest(
    val mutation: AddressBookMutation,
    val contacts: List<AddressBookProjection>,
) {
    init {
        require(contacts.size <= 1_000) { "Address-book projection exceeds server limit" }
    }
}

data class AddressBookDeletionRequest(
    val mutation: AddressBookMutation,
)

sealed interface AddressBookSharingState {
    data object Loading : AddressBookSharingState
    data object Unavailable : AddressBookSharingState
    data class Server(
        val enabled: Boolean,
        val storedContactCount: Int,
        val canWrite: Boolean,
        val ownedByInstallation: Boolean,
    ) : AddressBookSharingState
}

enum class HealthConnectAvailability {
    Available,
    InstallOrUpdateRequired,
    OnboardingRequired,
    AppNotAllowed,
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
            requestedAt: Instant?,
            status: CompanionSyncStatus?,
            now: Instant,
            delayedAfter: Duration = Duration.ofHours(36),
            attentionAfter: Duration = Duration.ofHours(72),
        ): HealthSyncState {
            if (requestedAt == null) return NotConnected
            // A receipt from an older setup is not evidence for this connection.
            // Equality qualifies; Murph deliberately applies no clock-skew allowance.
            val receivedAt = status?.lastDataReceivedAt?.takeUnless {
                it.isBefore(requestedAt)
            }
            if (receivedAt == null) {
                val setupAge = Duration.between(requestedAt, now).coerceAtLeast(Duration.ZERO)
                return if (setupAge >= attentionAfter) {
                    NeedsAttention(lastDataReceivedAt = null)
                } else {
                    AwaitingFirstData
                }
            }
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
    data object Conflict : CompanionApiException("Remote revision changed")
    data class Server(val status: Int) : CompanionApiException("Server returned $status")
    data object InvalidResponse : CompanionApiException("Invalid server response")
}
