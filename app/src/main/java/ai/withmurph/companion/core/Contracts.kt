package ai.withmurph.companion.core

import android.content.Intent

interface AuthProvider {
    suspend fun currentState(): AuthSessionState
    suspend fun sendCode(method: LoginMethod, destination: String)
    suspend fun confirmCode(method: LoginMethod, destination: String, code: String)
    suspend fun identityToken(): String

    suspend fun identityTokenForMember(memberKey: String): String {
        val before = currentState()
        if (
            before !is AuthSessionState.SignedIn ||
            !before.verifiedOnline ||
            before.memberKey != memberKey
        ) {
            throw IllegalStateException("Privy member changed before token capture")
        }
        val token = identityToken()
        val after = currentState()
        if (
            after !is AuthSessionState.SignedIn ||
            !after.verifiedOnline ||
            after.memberKey != memberKey
        ) {
            throw IllegalStateException("Privy member changed during token capture")
        }
        return token
    }

    suspend fun signOut()
}

interface CompanionApi {
    suspend fun createJunctionSignInToken(request: SignInTokenRequest): SignInTokenResponse
    suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus
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

    suspend fun createMealPhotoCaptureEnrollment(
        memberKey: String,
        request: MealPhotoCaptureEnrollmentRequest,
    ): MealPhotoCaptureEnrollment = throw CompanionApiException.InvalidResponse

    suspend fun revokeMealPhotoCaptureEnrollment(
        memberKey: String,
        request: MealPhotoCaptureRevocationRequest,
    ): Boolean = throw CompanionApiException.InvalidResponse
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
    fun configure()
    fun grantedResourceCount(): Int

    suspend fun identify(
        memberKey: String,
        authenticate: suspend () -> String,
    )

    suspend fun connectAfterPermissionRequest()
    suspend fun refreshPermissionState()
    suspend fun syncAllGrantedResources()
    suspend fun signOutSdk()
}

interface MealPhotoCaptureControlling {
    val automaticCaptureSupported: Boolean
    fun permissionRequest(): Array<String>
    suspend fun currentState(memberKey: String?): MealPhotoCaptureState
    suspend fun refresh(memberKey: String): MealPhotoCaptureState
    suspend fun enable(memberKey: String): MealPhotoCaptureState
    suspend fun resumeAfterConsent(memberKey: String): MealPhotoCaptureState
    suspend fun suspendAtTrustBoundary(): Boolean
    suspend fun pauseForConsentRecovery(memberKey: String): Boolean
    suspend fun disable(memberKey: String): Boolean
    suspend fun reviewItems(): List<MealPhotoReviewItem>
    suspend fun approveReviewItem(captureId: String): MealPhotoActionResult
    suspend fun dismissReviewItem(captureId: String): MealPhotoActionResult
}

object UnsupportedMealPhotoCapture : MealPhotoCaptureControlling {
    override val automaticCaptureSupported: Boolean = false
    override fun permissionRequest(): Array<String> = emptyArray()
    override suspend fun currentState(memberKey: String?): MealPhotoCaptureState =
        MealPhotoCaptureState.Unavailable
    override suspend fun refresh(memberKey: String): MealPhotoCaptureState =
        MealPhotoCaptureState.Unavailable
    override suspend fun enable(memberKey: String): MealPhotoCaptureState =
        MealPhotoCaptureState.Unavailable
    override suspend fun resumeAfterConsent(memberKey: String): MealPhotoCaptureState =
        MealPhotoCaptureState.Unavailable
    override suspend fun suspendAtTrustBoundary(): Boolean = true
    override suspend fun pauseForConsentRecovery(memberKey: String): Boolean = true
    override suspend fun disable(memberKey: String): Boolean = true
    override suspend fun reviewItems(): List<MealPhotoReviewItem> = emptyList()
    override suspend fun approveReviewItem(captureId: String): MealPhotoActionResult =
        MealPhotoActionResult.NeedsAttention
    override suspend fun dismissReviewItem(captureId: String): MealPhotoActionResult =
        MealPhotoActionResult.PhotoUnavailable
}

interface LocalState {
    val installationId: String
    var memberKey: String?
    var healthAccessRequestedAt: InstantValue?
    var lastKnownDataReceivedAt: InstantValue?
    val signOutPending: Boolean
    val addressBookRevision: Int?
        get() = null
    val pendingAddressBookReplacement: AddressBookMutation?
        get() = null
    val pendingAddressBookDeletion: AddressBookMutation?
        get() = null

    fun recordAddressBookRevision(revision: Int): Boolean = false
    fun recordDisabledAddressBookRevision(revision: Int): Boolean = false
    fun beginAddressBookReplacement(mutation: AddressBookMutation): Boolean = false
    fun completeAddressBookReplacement(mutationId: String, revision: Int): Boolean = false
    fun abandonAddressBookReplacement(mutationId: String): Boolean = false
    fun beginAddressBookDeletion(mutation: AddressBookMutation): Boolean = false
    fun completeAddressBookDeletion(mutationId: String, revision: Int): Boolean = false
    fun abandonAddressBookDeletion(mutationId: String): Boolean = false

    fun revokeHealthSetupAuthorization(): Boolean
    fun beginSignOut(): Boolean
    fun completeSignOut(): Boolean
    fun clearMemberScopedState()
}

@JvmInline
value class InstantValue(val epochMilliseconds: Long)
