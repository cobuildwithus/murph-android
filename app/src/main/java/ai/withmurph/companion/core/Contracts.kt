package ai.withmurph.companion.core

import android.content.Intent
import kotlinx.coroutines.CancellationException

interface AuthProvider {
    suspend fun currentState(): AuthSessionState
    suspend fun sendCode(method: LoginMethod, destination: String)
    suspend fun confirmCode(method: LoginMethod, destination: String, code: String)
    suspend fun identityToken(): String

    suspend fun identityTokenForMember(memberKey: String): String {
        val before = observedStateForTokenCapture()
        if (
            before !is AuthSessionState.SignedIn ||
            !before.verifiedOnline ||
            before.memberKey != memberKey
        ) {
            throw CompanionApiException.LocalAuthUnavailable(before)
        }
        val token = try {
            identityToken()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw CompanionApiException.LocalAuthUnavailable(
                observedStateForTokenCapture(),
            )
        }
        val after = observedStateForTokenCapture()
        if (
            after !is AuthSessionState.SignedIn ||
            !after.verifiedOnline ||
            after.memberKey != memberKey
        ) {
            throw CompanionApiException.LocalAuthUnavailable(after)
        }
        return token
    }

    private suspend fun observedStateForTokenCapture(): AuthSessionState = try {
        currentState()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AuthSessionState.TemporarilyUnavailable
    }

    suspend fun signOut()
}

interface CompanionApi {
    suspend fun admitCompanion(memberKey: String, timeZone: String)

    suspend fun createJunctionSignInToken(
        memberKey: String,
        request: SignInTokenRequest,
    ): SignInTokenResponse

    suspend fun fetchSyncStatus(
        memberKey: String,
        sourceProviderSlug: String,
    ): CompanionSyncStatus

    suspend fun fetchInitialOnboarding(memberKey: String): InitialOnboarding =
        throw CompanionApiException.InvalidResponse

    suspend fun completeInitialOnboarding(
        memberKey: String,
        request: InitialOnboardingCompletionRequest,
    ): InitialOnboarding = throw CompanionApiException.InvalidResponse

    suspend fun prepareInitialOnboardingContactCard(
        memberKey: String,
        request: InitialOnboardingContactCardRequest,
    ): InitialOnboardingContactCardHandoff = throw CompanionApiException.InvalidResponse

    suspend fun fetchLaunchConsentStatus(memberKey: String): LaunchConsentStatus =
        throw CompanionApiException.InvalidResponse

    suspend fun acceptLaunchConsent(
        memberKey: String,
        request: LaunchConsentAcceptanceRequest,
    ): LaunchConsentStatus = throw CompanionApiException.InvalidResponse

    suspend fun fetchAddressBookStatus(memberKey: String): AddressBookServerStatus =
        throw CompanionApiException.InvalidResponse

    suspend fun replaceAddressBook(
        memberKey: String,
        request: AddressBookReplacementRequest,
    ): AddressBookServerStatus = throw CompanionApiException.InvalidResponse

    suspend fun deleteAddressBook(
        memberKey: String,
        request: AddressBookDeletionRequest,
    ): AddressBookServerStatus = throw CompanionApiException.InvalidResponse
}

interface AddressBookContactSource {
    val isSupported: Boolean
        get() = true
    val readPermission: String
    fun hasPermission(): Boolean
    suspend fun readPersonContacts(): List<AddressBookPersonContact>
}

object UnsupportedAddressBookContactSource : AddressBookContactSource {
    override val isSupported: Boolean = false
    override val readPermission: String = ""
    override fun hasPermission(): Boolean = false
    override suspend fun readPersonContacts(): List<AddressBookPersonContact> = emptyList()
}

interface HealthSyncing {
    val totalResourceCount: Int

    fun availability(): HealthConnectAvailability
    fun openHealthConnectIntent(): Intent?
    fun isSignedIn(): Boolean
    fun pauseAutomaticSync()
    fun configure()
    fun grantedResourceCount(): Int
    fun revokeUnpromotedSyncLaunch()

    suspend fun identify(
        memberKey: String,
        authenticate: suspend () -> String,
    )

    suspend fun connectAfterPermissionRequest()
    suspend fun refreshPermissionState()
    suspend fun syncAllGrantedResources(expectedMemberKey: String)
    suspend fun revokeActiveSyncAuthorization()
    suspend fun signOutSdk()
}

class HealthSyncForegroundLaunchRejectedException : Exception()

enum class HealthPermissionRequestResult {
    Ready,
    NoActiveResource,
    MissingWorkoutBase,
    MissingMenstrualBase,
    MissingWorkoutAndMenstrualBases,
}

interface LocalState {
    val installationId: String
    var memberKey: String?
    var initialSetupStep: InitialSetupStep?
    var healthAccessRequestedAt: InstantValue?
    var healthReceiptBaselineAt: InstantValue?
    var lastKnownDataReceivedAt: InstantValue?
    var lastKnownStatusObservedAt: InstantValue?
    var healthReconnectRequired: Boolean
    val healthSyncReminderDeadline: HealthSyncReminderDeadline?
        get() = null
    val signOutPending: Boolean
    val pendingPrivySignOutMemberKey: String?
    val addressBookRevision: Int?
        get() = null
    val pendingAddressBookReplacement: AddressBookMutation?
        get() = null
    val pendingAddressBookDeletion: AddressBookMutation?
        get() = null

    fun advanceInitialSetupStep(
        expected: InitialSetupStep,
        next: InitialSetupStep,
        abandonPendingAddressBookReplacement: Boolean = false,
    ): Boolean
    fun isHealthSyncReminderEnabled(memberKey: String): Boolean = false
    fun setHealthSyncReminderEnabled(
        memberKey: String,
        enabled: Boolean,
        initialDeadline: HealthSyncReminderDeadline? = null,
    ): Boolean = false
    fun persistHealthSyncReminderDeadline(
        memberKey: String,
        deadline: HealthSyncReminderDeadline,
    ): Boolean = false

    fun recordAddressBookRevision(revision: Int): Boolean = false
    fun recordDisabledAddressBookRevision(revision: Int): Boolean = false
    fun beginAddressBookReplacement(mutation: AddressBookMutation): Boolean = false
    fun completeAddressBookReplacement(
        mutationId: String,
        revision: Int,
        completesInitialSetup: Boolean = false,
    ): Boolean = false
    fun abandonAddressBookReplacement(mutationId: String): Boolean = false
    fun beginAddressBookDeletion(mutation: AddressBookMutation): Boolean = false
    fun completeAddressBookDeletion(mutationId: String, revision: Int): Boolean = false
    fun abandonAddressBookDeletion(mutationId: String): Boolean = false

    fun completeHealthSetupAuthorization(
        requestedAt: InstantValue,
        receiptBaselineAt: InstantValue?,
        statusObservedAt: InstantValue,
        reminderDeadline: HealthSyncReminderDeadline? = null,
        completesInitialSetup: Boolean = false,
    ): Boolean
    fun requireHealthReconnect(): Boolean
    fun revokeHealthSetupAuthorization(): Boolean
    fun beginSignOut(
        expectedMemberKey: String?,
        privySignOutMemberKey: String? = null,
        preserveMemberState: Boolean = false,
    ): Boolean
    fun completeSignOut(expectedMemberKey: String?): Boolean
    fun clearMemberScopedState()
}

interface HealthSyncReminderLifecycle {
    fun cancel()

    fun didEnterForeground() = cancel()
    fun didEnterBackground() = Unit

    /** Builds a monotonic deadline from the latest member-scoped server evidence. */
    fun initialDeadline(memberKey: String): HealthSyncReminderDeadline? = null

    /** Builds the deadline that must commit atomically with a new setup authorization. */
    fun deadlineForSetupAuthorization(
        memberKey: String,
        requestedAt: InstantValue,
    ): HealthSyncReminderDeadline? = null

    /**
     * Persists fresh server evidence immediately, but reconciles delivery only when the
     * lifecycle owner is currently backgrounded.
     */
    fun refreshSchedule(freshBackendStatus: Boolean = false) = Unit
}

object NoopHealthSyncReminderLifecycle : HealthSyncReminderLifecycle {
    override fun cancel() = Unit
}

@JvmInline
value class InstantValue(val epochMilliseconds: Long)

data class HealthSyncReminderDeadline(
    val basisToken: String,
    val bootCount: Int,
    val triggerElapsedRealtimeMillis: Long,
) {
    init {
        require(REMINDER_BASIS_PATTERN.matches(basisToken))
        require(bootCount >= 0)
        require(triggerElapsedRealtimeMillis >= 0L)
    }

    private companion object {
        val REMINDER_BASIS_PATTERN = Regex("[0-9a-f]{64}")
    }
}
