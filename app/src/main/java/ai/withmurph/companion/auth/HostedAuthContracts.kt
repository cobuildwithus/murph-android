package ai.withmurph.companion.auth

import ai.withmurph.companion.core.LoginMethod
import java.time.Instant

data class HostedAuthBinding(val memberId: String, val localMemberKey: String)

data class HostedAuthSession(
    val memberId: String,
    val token: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "HostedAuthSession(<redacted>)"
}

data class HostedAuthSessionStatus(val memberId: String, val expiresAt: Instant)

/** One secure record owns both active authority and the decision to retire SDK fallback. */
sealed interface HostedAuthStoredState {
    val binding: HostedAuthBinding?

    data class Active(
        override val binding: HostedAuthBinding,
        val credential: String,
        val expiresAt: Instant,
        val verifiedAt: Instant,
    ) : HostedAuthStoredState {
        override fun toString(): String = "HostedAuthStoredState.Active(<redacted>)"
    }

    data class SignedOut(override val binding: HostedAuthBinding?) : HostedAuthStoredState
}

interface HostedAuthCredentialStoring {
    /** Only genuinely absent storage returns null. Unreadable or malformed records throw. */
    fun load(): HostedAuthStoredState?
    fun save(state: HostedAuthStoredState)
}

interface HostedAuthServing {
    suspend fun sendCode(method: LoginMethod, value: String)
    suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession
    suspend fun exchange(legacyCredential: String): HostedAuthSession
    suspend fun renew(credential: String): HostedAuthSessionStatus
    suspend fun revoke(credential: String)
}

/** Closed failures retain no contact, credential, response text or exception cause. */
sealed class HostedAuthException : Exception() {
    data object InvalidResponse : HostedAuthException()
    data object CredentialsUnavailable : HostedAuthException()
    class Response(val status: Int) : HostedAuthException()
}

internal fun validateHostedAuthState(state: HostedAuthStoredState) {
    state.binding?.let { binding ->
        require(binding.memberId.isNotBlank() && binding.memberId.length <= 256)
        require(binding.localMemberKey.isNotBlank() && binding.localMemberKey.length <= 256)
    }
    if (state is HostedAuthStoredState.Active) {
        require(HOSTED_AUTH_CREDENTIAL_PATTERN.matches(state.credential))
        require(state.expiresAt > state.verifiedAt)
    }
}

internal val HOSTED_AUTH_CREDENTIAL_PATTERN = Regex("^murph_auth_v1\\.[A-Za-z0-9]{32}$")
