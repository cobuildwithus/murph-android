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

enum class LaunchConsentScope(val wireValue: String) {
    Legal("launch.legal"),
    HealthData("launch.health-data"),
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

data class LaunchConsentDocument(
    val id: String,
    val title: String,
    val version: String,
    val href: String,
    val pdfHref: String?,
)

data class LaunchConsentScopeStatus(
    val scope: LaunchConsentScope,
    val granted: Boolean,
    val missingDocuments: List<LaunchConsentDocument>,
)

data class LaunchConsentStatus(
    val launchGranted: Boolean,
    val documents: List<LaunchConsentDocument>,
    val launchScopes: List<LaunchConsentScopeStatus>,
) {
    val missingLaunchScopes: List<LaunchConsentScopeStatus>
        get() = launchScopes.filterNot { it.granted }
}

data class LaunchConsentAcceptanceRequest(
    val scope: LaunchConsentScope,
    val acceptedDocumentVersions: Map<String, String>,
    val source: String = "android-companion",
) {
    init {
        require(acceptedDocumentVersions.isNotEmpty()) {
            "Launch consent acceptance requires at least one document"
        }
        require(acceptedDocumentVersions.keys.all(String::isNotBlank)) {
            "Launch consent document ids must be non-blank"
        }
        require(acceptedDocumentVersions.values.all(String::isNotBlank)) {
            "Launch consent document versions must be non-blank"
        }
        require(source == "android-companion") {
            "Unsupported launch consent source"
        }
    }
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

data class MealPhotoCaptureEnrollmentRequest(
    val appInstallationId: String,
    val appVersion: String,
    val authorityRevision: Long,
    val schemaVersion: Int = 2,
) {
    init {
        require(schemaVersion == 2) { "Unsupported meal-photo enrollment schema" }
        require(authorityRevision in 1..MAX_AUTHORITY_REVISION) {
            "Meal-photo authority revision must be a positive 32-bit integer"
        }
        require(CANONICAL_UUID_V4.matches(appInstallationId)) {
            "Meal-photo installation id must be UUIDv4"
        }
        require(APP_VERSION.matches(appVersion)) { "Invalid meal-photo app version" }
    }

    private companion object {
        val APP_VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$")
    }
}

data class MealPhotoCaptureRevocationRequest(
    val appInstallationId: String,
    val authorityRevision: Long,
    val schemaVersion: Int = 2,
) {
    init {
        require(schemaVersion == 2) { "Unsupported meal-photo revocation schema" }
        require(authorityRevision in 1..MAX_AUTHORITY_REVISION) {
            "Meal-photo authority revision must be a positive 32-bit integer"
        }
        require(CANONICAL_UUID_V4.matches(appInstallationId)) {
            "Meal-photo installation id must be UUIDv4"
        }
    }
}

data class MealPhotoCaptureEnrollment(
    val uploadToken: String,
    val idempotencySecret: String,
    val expiresAt: Instant,
) {
    init {
        require(UPLOAD_TOKEN.matches(uploadToken)) { "Invalid meal-photo upload token" }
        require(IDEMPOTENCY_SECRET.matches(idempotencySecret)) {
            "Invalid meal-photo idempotency secret"
        }
    }

    private companion object {
        val UPLOAD_TOKEN = Regex("^murph_meal_photo_[A-Za-z0-9_-]{43}$")
        val IDEMPOTENCY_SECRET = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

enum class MealPhotoCaptureState {
    Unavailable,
    Off,
    Enabling,
    On,
    NeedsPhotosAccess,
    NeedsFullAccess,
    NeedsAttention,
}

enum class MealPhotoReviewStatus {
    NeedsReview,
    Sent,
}

data class MealPhotoReviewItem(
    val id: String,
    val capturedAt: Instant,
    val status: MealPhotoReviewStatus,
    /** A bounded metadata-free rendering held in memory only. */
    val thumbnailJpeg: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is MealPhotoReviewItem &&
            id == other.id &&
            capturedAt == other.capturedAt &&
            status == other.status &&
            thumbnailJpeg.contentEquals(other.thumbnailJpeg)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (thumbnailJpeg?.contentHashCode() ?: 0)
        return result
    }
}

enum class MealPhotoActionResult {
    Sent,
    Dismissed,
    PhotoUnavailable,
    NeedsAttention,
    TryAgain,
}

private val CANONICAL_UUID_V4 = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private const val MAX_AUTHORITY_REVISION = Int.MAX_VALUE.toLong()

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
    data object StaleConsentDocuments : CompanionApiException("Consent documents changed")
    data object ReconnectRequired : CompanionApiException("Health connection must be reconnected")
    data object Conflict : CompanionApiException("Remote revision changed")
    data class Server(val status: Int) : CompanionApiException("Server returned $status")
    data object InvalidResponse : CompanionApiException("Invalid server response")
}
