package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthDiagnosticCode
import ai.withmurph.companion.core.AuthDiagnosticErrorKind
import ai.withmurph.companion.core.AuthDiagnosticFailure
import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthProviderException
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.LegacyAuthRestoring
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Serializes bounded auth operations; the existing AppSession still owns health teardown. */
class HostedAuthService(
    private val api: HostedAuthServing,
    private val store: HostedAuthCredentialStoring,
    private val legacyFactory: () -> LegacyAuthRestoring,
    private val now: () -> Instant = Instant::now,
) : AuthProvider {
    private val mutex = Mutex()
    private val legacy by lazy(legacyFactory)
    private var retryAfter = Instant.MIN

    override suspend fun currentState(): AuthSessionState = mutex.withLock {
        try {
            when (val record = store.load()) {
                null -> legacy.currentState()
                is HostedAuthStoredState.SignedOut -> AuthSessionState.SignedOut
                is HostedAuthStoredState.Active -> {
                    if (record.expiresAt <= now()) {
                        try {
                            renew(record)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // A rejected credential may have been retired by renewal.
                        }
                    }
                    stateFromStore()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
    }

    override suspend fun sendCode(method: LoginMethod, destination: String) = diagnosed {
        api.sendCode(method, destination.trim())
    }

    override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) = diagnosed {
        mutex.withLock {
            val binding = store.load()?.binding
            // Retire fallback before consuming this one-use proof. A failed
            // secure write leaves the code available for an explicit retry.
            store.save(HostedAuthStoredState.SignedOut(binding))
            val issued = api.verifyCode(method, destination.trim(), code.trim())
            currentCoroutineContext().ensureActive()
            save(issued, binding?.takeIf { it.memberId == issued.memberId }?.localMemberKey)
        }
    }

    override suspend fun identityToken(): String = mutex.withLock {
        when (val record = store.load()) {
            null -> exchange()
            is HostedAuthStoredState.SignedOut -> throw HostedAuthException.CredentialsUnavailable
            is HostedAuthStoredState.Active -> renew(record)
        }
    }

    private suspend fun exchange(): String {
        val before = legacy.currentState() as? AuthSessionState.SignedIn
            ?: throw HostedAuthException.CredentialsUnavailable
        val credential = legacy.identityToken()
        requireLegacyMember(before.memberKey)
        if (now() < retryAfter) return credential
        val issued = try {
            api.exchange(credential)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            requireLegacyMember(before.memberKey)
            if (isTransient(error) || error is HostedAuthException.Response && error.status == 409) {
                retryAfter = now().plusSeconds(60)
                return credential
            }
            throw error
        }
        currentCoroutineContext().ensureActive()
        requireLegacyMember(before.memberKey)
        try {
            save(issued, before.memberKey)
        } catch (error: HostedAuthException) {
            // A durable write failure must not discard a still-valid SDK
            // session. Once any record exists, SDK fallback stays retired.
            if (error == HostedAuthException.CredentialsUnavailable && store.load() == null) {
                retryAfter = now().plusSeconds(60)
                return credential
            }
            throw error
        }
        return issued.token
    }

    private suspend fun requireLegacyMember(expected: String) {
        val current = legacy.currentState()
        if (current !is AuthSessionState.SignedIn || !current.verifiedOnline || current.memberKey != expected) {
            throw HostedAuthException.CredentialsUnavailable
        }
    }

    private suspend fun renew(record: HostedAuthStoredState.Active): String {
        if (record.expiresAt > now() &&
            (now() < record.verifiedAt.plusSeconds(86_400) || now() < retryAfter)
        ) return record.credential
        try {
            val result = api.renew(record.credential)
            currentCoroutineContext().ensureActive()
            if (result.memberId != record.binding.memberId) throw HostedAuthException.InvalidResponse
            save(HostedAuthSession(result.memberId, record.credential, result.expiresAt), record.binding.localMemberKey)
            return record.credential
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is HostedAuthException.Response && error.status == 401) {
                store.save(HostedAuthStoredState.SignedOut(record.binding))
            } else if (isTransient(error) && record.expiresAt > now()) {
                retryAfter = now().plusSeconds(60)
                return record.credential
            }
            throw error
        }
    }

    override suspend fun signOut() = mutex.withLock {
        val record = store.load()
        when (record) {
            null -> legacy.signOut()
            is HostedAuthStoredState.Active -> api.revoke(record.credential)
            is HostedAuthStoredState.SignedOut -> Unit
        }
        currentCoroutineContext().ensureActive()
        store.save(HostedAuthStoredState.SignedOut(record?.binding))
        retryAfter = Instant.MIN
    }

    private fun save(issued: HostedAuthSession, localMemberKey: String?) {
        val instant = now()
        if (issued.expiresAt <= instant) throw HostedAuthException.InvalidResponse
        val record = HostedAuthStoredState.Active(
            binding = HostedAuthBinding(issued.memberId, localMemberKey ?: issued.memberId),
            credential = issued.token,
            expiresAt = issued.expiresAt,
            verifiedAt = instant,
        )
        try {
            validateHostedAuthState(record)
        } catch (_: IllegalArgumentException) {
            throw HostedAuthException.InvalidResponse
        }
        store.save(record)
        retryAfter = Instant.MIN
    }

    private fun stateFromStore(): AuthSessionState = when (val record = store.load()) {
        is HostedAuthStoredState.Active -> AuthSessionState.SignedIn(
            memberKey = record.binding.localMemberKey,
            verifiedOnline = record.expiresAt > now(),
        )
        is HostedAuthStoredState.SignedOut -> AuthSessionState.SignedOut
        null -> AuthSessionState.TemporarilyUnavailable
    }

    private fun isTransient(error: Exception): Boolean = error == CompanionApiException.Network ||
        error is HostedAuthException.Response && (error.status == 429 || error.status >= 500)

    private suspend fun <T> diagnosed(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val code = when (error) {
            is HostedAuthException.Response -> AuthDiagnosticCode.HostedResponseRejected
            HostedAuthException.CredentialsUnavailable -> AuthDiagnosticCode.HostedCredentialsUnavailable
            CompanionApiException.Network -> AuthDiagnosticCode.NetworkUnknown
            else -> AuthDiagnosticCode.HostedInvalidResponse
        }
        throw AuthProviderException(AuthDiagnosticFailure(
            errorKind = if (error == CompanionApiException.Network) AuthDiagnosticErrorKind.Network
                else AuthDiagnosticErrorKind.Provider,
            httpStatus = (error as? HostedAuthException.Response)?.status,
            diagnosticCode = code,
        ))
    }
}
