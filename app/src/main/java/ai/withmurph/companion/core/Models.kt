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
    val connectionIntent: ConnectionIntent?,
    val sdkVersions: Map<String, String>,
    val timeZone: String,
)

data class SignInTokenResponse(
    val signInToken: String,
    val environment: String,
)

enum class InitialOnboardingStatus(val wireValue: String) {
    Pending("pending"),
    Completed("completed"),
}

data class InitialOnboardingPreferences(
    val persona: String?,
    val tone: String?,
    val voice: String?,
)

data class InitialOnboardingPersona(
    val id: String,
    val label: String,
    val description: String,
    val supportDescription: String,
    val defaultTone: String,
    val defaultVoiceId: String,
    val recommendedVoiceIds: List<String>,
)

data class InitialOnboardingVoice(
    val id: String,
    val label: String,
    val description: String,
    val previewUrl: String,
)

data class InitialOnboardingTone(
    val id: String,
    val label: String,
    val sample: String,
)

data class InitialOnboardingCatalog(
    val personas: List<InitialOnboardingPersona>,
    val voices: List<InitialOnboardingVoice>,
    val tones: List<InitialOnboardingTone>,
)

enum class InitialOnboardingContactAvatarKind(val wireValue: String) {
    Headshot("headshot"),
    Logo("logo"),
    Blank("blank"),
}

data class InitialOnboardingContactAvatar(
    val id: String,
    val kind: InitialOnboardingContactAvatarKind,
    val label: String,
    val imageUrl: String?,
)

data class InitialOnboardingContactCard(
    val avatars: List<InitialOnboardingContactAvatar>,
    val defaultAvatarId: String,
)

enum class InitialOnboardingContactKind(val wireValue: String) {
    Text("text"),
    Telegram("telegram"),
    Email("email"),
}

data class InitialOnboardingContactAction(
    val href: String,
    val kind: InitialOnboardingContactKind,
    val label: String,
)

data class InitialOnboarding(
    val status: InitialOnboardingStatus,
    val completedNow: Boolean?,
    val preferences: InitialOnboardingPreferences,
    val catalog: InitialOnboardingCatalog?,
    val contactCard: InitialOnboardingContactCard?,
    val contactAction: InitialOnboardingContactAction?,
)

enum class InitialOnboardingCompletionAction(val wireValue: String) {
    Save("save"),
    Skip("skip"),
}

data class InitialOnboardingCompletionRequest(
    val action: InitialOnboardingCompletionAction,
    val preferences: InitialOnboardingPreferences?,
)

data class InitialOnboardingContactCardRequest(
    val avatarId: String,
)

data class InitialOnboardingContactCardHandoff(
    val url: String,
)

data class CompanionSyncStatus(
    val lastDataReceivedAt: Instant?,
    val observedAt: Instant,
    val resources: Map<String, ResourceStatus>,
) {
    data class ResourceStatus(val lastReceivedAt: Instant?)
}

data class PendingHealthSyncFailure(
    val resourceKeys: Set<String>,
    val receiptFloorAt: InstantValue,
) {
    init {
        require(resourceKeys.isNotEmpty())
        require(resourceKeys.all { HEALTH_RESOURCE_KEY.matches(it) })
    }

    fun isConfirmedBy(status: CompanionSyncStatus): Boolean {
        val floor = Instant.ofEpochMilli(receiptFloorAt.epochMilliseconds)
        return resourceKeys.all { resourceKey ->
            if (resourceKey == UNKNOWN_HEALTH_RESOURCE_KEY) {
                status.lastDataReceivedAt?.isAfter(floor) == true
            } else {
                status.resources[resourceKey]?.lastReceivedAt?.isAfter(floor) == true
            }
        }
    }

    fun mergedWith(other: PendingHealthSyncFailure): PendingHealthSyncFailure =
        PendingHealthSyncFailure(
            resourceKeys = resourceKeys + other.resourceKeys,
            receiptFloorAt = if (
                receiptFloorAt.epochMilliseconds >= other.receiptFloorAt.epochMilliseconds
            ) {
                receiptFloorAt
            } else {
                other.receiptFloorAt
            },
        )

    fun retainingGranted(resourceKeys: Set<String>): PendingHealthSyncFailure? {
        val retained = this.resourceKeys.filterTo(linkedSetOf()) {
            it == UNKNOWN_HEALTH_RESOURCE_KEY || it in resourceKeys
        }
        return retained.takeIf { it.isNotEmpty() }?.let {
            copy(resourceKeys = it)
        }
    }

    private companion object {
        val HEALTH_RESOURCE_KEY = Regex("[a-z0-9_]{1,64}")
    }
}

const val UNKNOWN_HEALTH_RESOURCE_KEY = "unknown"

sealed interface HealthSyncAttemptResult {
    data object Complete : HealthSyncAttemptResult

    data class PartialFailure(val resourceKeys: Set<String>) : HealthSyncAttemptResult {
        init {
            require(resourceKeys.isNotEmpty())
        }
    }
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
    val documents: List<LaunchConsentDocument>,
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

enum class InitialSetupStep(val wireValue: String) {
    HealthConnect("health_connect"),
    FriendlyNames("friendly_names"),
    Complete("complete"),
    ;

    companion object {
        fun fromWireValue(value: String?): InitialSetupStep? =
            values().firstOrNull { it.wireValue == value }
    }
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
        val defaultDelayedAfter: Duration = Duration.ofHours(36)
        val defaultAttentionAfter: Duration = Duration.ofHours(72)

        fun derive(
            requestedAt: Instant?,
            status: CompanionSyncStatus?,
            delayedAfter: Duration = defaultDelayedAfter,
            attentionAfter: Duration = defaultAttentionAfter,
        ): HealthSyncState {
            if (requestedAt == null) return NotConnected
            val observedAt = status?.observedAt ?: requestedAt
            val receivedAt = status?.lastDataReceivedAt
            if (receivedAt == null) {
                val setupAge =
                    Duration.between(requestedAt, observedAt).coerceAtLeast(Duration.ZERO)
                return if (setupAge >= attentionAfter) {
                    NeedsAttention(lastDataReceivedAt = null)
                } else {
                    AwaitingFirstData
                }
            }
            val age = Duration.between(receivedAt, observedAt).coerceAtLeast(Duration.ZERO)
            return when {
                age >= attentionAfter -> NeedsAttention(receivedAt)
                age >= delayedAfter -> Delayed(receivedAt)
                else -> Synced(receivedAt)
            }
        }

        fun attentionRemaining(
            requestedAt: Instant?,
            lastDataReceivedAt: Instant?,
            statusObservedAt: Instant?,
            attentionAfter: Duration = defaultAttentionAfter,
        ): Duration? {
            val setup = requestedAt ?: return null
            val observedAt = statusObservedAt ?: return null
            val qualifyingReceipt = lastDataReceivedAt?.takeUnless { it.isBefore(setup) }
            val age = Duration.between(qualifyingReceipt ?: setup, observedAt)
                .coerceAtLeast(Duration.ZERO)
            return attentionAfter.minus(age).coerceAtLeast(Duration.ZERO)
        }
    }
}

sealed class CompanionApiException(message: String) : Exception(message) {
    data object Network : CompanionApiException("Network request failed")
    data object Unauthorized : CompanionApiException("Authentication required")
    class LocalAuthUnavailable(
        val observedState: AuthSessionState,
    ) : CompanionApiException("Local authentication is temporarily unavailable")
    data object AccessRequired : CompanionApiException("Active Murph access required")
    data object MemberSuspended : CompanionApiException("Murph account suspended")
    data object AdmissionRetryable : CompanionApiException("Murph account setup temporarily unavailable")
    data object AdmissionSupportRequired : CompanionApiException("Murph account setup needs support")
    data object NoAccount : CompanionApiException("No Murph account")
    data object AccountConflict : CompanionApiException("Murph account conflict")
    data object ConsentRequired : CompanionApiException("Murph consent required")
    data object StaleConsentDocuments : CompanionApiException("Consent documents changed")
    data object ReconnectRequired : CompanionApiException("Health connection must be reconnected")
    data object Conflict : CompanionApiException("Remote revision changed")
    data class Server(val status: Int) : CompanionApiException("Server returned $status")
    data object InvalidResponse : CompanionApiException("Invalid server response")
}
