package ai.withmurph.companion.core

import android.content.Intent

interface AuthProvider {
    suspend fun currentState(): AuthSessionState
    suspend fun sendCode(method: LoginMethod, destination: String)
    suspend fun confirmCode(method: LoginMethod, destination: String, code: String)
    suspend fun identityToken(): String
    suspend fun signOut()
}

interface CompanionApi {
    suspend fun createJunctionSignInToken(request: SignInTokenRequest): SignInTokenResponse
    suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus
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

interface LocalState {
    val installationId: String
    var memberKey: String?
    var healthAccessRequestedAt: InstantValue?
    var lastKnownDataReceivedAt: InstantValue?
    val signOutPending: Boolean

    fun beginSignOut(): Boolean
    fun completeSignOut(): Boolean
    fun clearMemberScopedState()
}

@JvmInline
value class InstantValue(val epochMilliseconds: Long)
