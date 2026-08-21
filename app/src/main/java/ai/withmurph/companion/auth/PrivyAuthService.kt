package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthDiagnosticCode
import ai.withmurph.companion.core.AuthDiagnosticErrorKind
import ai.withmurph.companion.core.AuthDiagnosticFailure
import ai.withmurph.companion.core.AuthDiagnosticProviderCode
import ai.withmurph.companion.core.AuthProviderException
import ai.withmurph.companion.core.AuthSessionState
import android.content.Context
import ai.withmurph.companion.core.LoginMethod
import io.privy.auth.AuthState as PrivyAuthState
import io.privy.auth.AuthenticationException
import io.privy.logging.PrivyLogLevel
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException

class PrivyAuthService private constructor(private val privy: Privy) : AuthProvider {
    override suspend fun currentState(): AuthSessionState = when (val state = privy.getAuthState()) {
        is PrivyAuthState.Authenticated -> AuthSessionState.SignedIn(
            memberKey = state.user.id,
            verifiedOnline = true,
        )
        is PrivyAuthState.AuthenticatedUnverified -> {
            privy.getUser()?.let { user ->
                AuthSessionState.SignedIn(
                    memberKey = user.id,
                    verifiedOnline = false,
                )
            } ?: AuthSessionState.TemporarilyUnavailable
        }
        is PrivyAuthState.Unauthenticated -> AuthSessionState.SignedOut
        is PrivyAuthState.NotReady -> AuthSessionState.TemporarilyUnavailable
    }

    override suspend fun sendCode(method: LoginMethod, destination: String) {
        runAuthCall {
            when (method) {
                LoginMethod.Phone -> privy.sms.sendCode(phoneNumber = destination).getOrThrow()
                LoginMethod.Email -> privy.email.sendCode(destination).getOrThrow()
            }
        }
    }

    override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) {
        runAuthCall {
            when (method) {
                LoginMethod.Phone -> privy.sms.loginWithCode(
                    code = code,
                    phoneNumber = destination,
                ).getOrThrow()
                LoginMethod.Email -> privy.email.loginWithCode(code, destination).getOrThrow()
            }
        }
    }

    override suspend fun identityToken(): String = refreshedIdentityToken(
        currentUser = { privy.getUser() },
        refreshSession = { user ->
            user.getAccessToken().getOrThrow()
        },
        currentIdentityToken = { user -> user.identityToken },
    )

    override suspend fun signOut() {
        privy.logout()
    }

    companion object {
        internal suspend fun <User> refreshedIdentityToken(
            currentUser: suspend () -> User?,
            refreshSession: suspend (User) -> Unit,
            currentIdentityToken: (User) -> String?,
        ): String {
            val user = currentUser()
                ?: throw IllegalStateException("Privy identity token is unavailable")
            runAuthCall {
                refreshSession(user)
            }
            return currentUser()
                ?.let(currentIdentityToken)
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Privy identity token is unavailable")
        }

        internal suspend fun runAuthCall(call: suspend () -> Unit) {
            try {
                call()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val wrappedCancellation = (error as? PrivyApiException)
                    ?.cause as? CancellationException
                if (wrappedCancellation != null) throw wrappedCancellation
                throw AuthProviderException(mapAuthFailure(error))
            }
        }

        fun create(
            context: Context,
            appId: String,
            appClientId: String,
        ): PrivyAuthService = PrivyAuthService(
            Privy.init(
                context = context.applicationContext,
                config = PrivyConfig(
                    appId = appId,
                    appClientId = appClientId,
                    logLevel = PrivyLogLevel.NONE,
                ),
            ),
        )

        internal fun mapAuthFailure(error: Exception): AuthDiagnosticFailure = when (error) {
            is NoNetworkException -> AuthDiagnosticFailure(
                errorKind = AuthDiagnosticErrorKind.Network,
                diagnosticCode = AuthDiagnosticCode.NetworkOffline,
            )
            is SocketTimeoutException -> AuthDiagnosticFailure(
                errorKind = AuthDiagnosticErrorKind.Network,
                diagnosticCode = AuthDiagnosticCode.NetworkTimeout,
            )
            is PrivyApiException -> mapPrivyApiFailure(error)
            is AuthenticationException -> AuthDiagnosticFailure(
                errorKind = AuthDiagnosticErrorKind.Provider,
                diagnosticCode = AuthDiagnosticCode.PrivyAuthenticationFailed,
            )
            is IOException -> AuthDiagnosticFailure(
                errorKind = AuthDiagnosticErrorKind.Network,
                diagnosticCode = AuthDiagnosticCode.NetworkUnknown,
            )
            else -> AuthDiagnosticFailure.Unknown
        }

        private fun mapPrivyApiFailure(error: PrivyApiException): AuthDiagnosticFailure {
            val status = error.statusCode?.takeIf { it in 100..599 }
            val providerCode = AuthDiagnosticProviderCode.from(error.responseBody?.code)
            if (providerCode == null) {
                mapTypedNetworkFailure(error.cause)?.let { return it }
                val fallbackCode = when {
                    status == 429 -> AuthDiagnosticCode.PrivyRateLimited
                    status != null && status >= 500 -> AuthDiagnosticCode.PrivyServiceUnavailable
                    else -> AuthDiagnosticCode.PrivyUnknown
                }
                return AuthDiagnosticFailure(
                    errorKind = when (fallbackCode) {
                        AuthDiagnosticCode.PrivyRateLimited -> AuthDiagnosticErrorKind.RateLimited
                        AuthDiagnosticCode.PrivyServiceUnavailable ->
                            AuthDiagnosticErrorKind.Unavailable
                        else -> AuthDiagnosticErrorKind.Unknown
                    },
                    httpStatus = status,
                    diagnosticCode = fallbackCode,
                )
            }
            val diagnosticCode = when (providerCode) {
                AuthDiagnosticProviderCode.BadEmail -> AuthDiagnosticCode.PrivyBadEmail
                AuthDiagnosticProviderCode.BadRequest,
                AuthDiagnosticProviderCode.InvalidRequest,
                AuthDiagnosticProviderCode.CustomAuthProviderReturnedNoToken,
                AuthDiagnosticProviderCode.NoCustomAuthProviderConfigured,
                -> AuthDiagnosticCode.PrivyBadRequest
                AuthDiagnosticProviderCode.ExpiredCode -> AuthDiagnosticCode.PrivyExpiredCode
                AuthDiagnosticProviderCode.Forbidden -> AuthDiagnosticCode.PrivyForbidden
                AuthDiagnosticProviderCode.InvalidCode,
                AuthDiagnosticProviderCode.IncorrectCredentialsEmail,
                AuthDiagnosticProviderCode.IncorrectCredentialsPhone,
                -> AuthDiagnosticCode.PrivyInvalidCode
                AuthDiagnosticProviderCode.InvalidEmail,
                AuthDiagnosticProviderCode.EmailNotFound,
                -> AuthDiagnosticCode.PrivyInvalidEmail
                AuthDiagnosticProviderCode.InvalidNativeAppId,
                AuthDiagnosticProviderCode.InvalidNativeAppIdentifier,
                AuthDiagnosticProviderCode.InvalidNativeClient,
                ->
                    AuthDiagnosticCode.PrivyInvalidNativeAppId
                AuthDiagnosticProviderCode.InvalidPhone,
                AuthDiagnosticProviderCode.PhoneNotFound,
                -> AuthDiagnosticCode.PrivyInvalidPhone
                AuthDiagnosticProviderCode.InitializationFailed ->
                    AuthDiagnosticCode.PrivyInitializationFailed
                AuthDiagnosticProviderCode.NotFound -> AuthDiagnosticCode.PrivyNotFound
                AuthDiagnosticProviderCode.RateLimitExceeded,
                AuthDiagnosticProviderCode.RateLimited,
                AuthDiagnosticProviderCode.TooManyRequests,
                ->
                    AuthDiagnosticCode.PrivyRateLimited
                AuthDiagnosticProviderCode.ServiceUnavailable,
                AuthDiagnosticProviderCode.Unavailable,
                -> AuthDiagnosticCode.PrivyServiceUnavailable
                AuthDiagnosticProviderCode.Timeout -> AuthDiagnosticCode.PrivyTimeout
                AuthDiagnosticProviderCode.Unauthorized,
                AuthDiagnosticProviderCode.NotLoggedIn,
                AuthDiagnosticProviderCode.InvalidJwt,
                -> AuthDiagnosticCode.PrivyUnauthorized
                else -> AuthDiagnosticCode.PrivyAuthenticationFailed
            }.let { code ->
                when {
                    status == 429 -> AuthDiagnosticCode.PrivyRateLimited
                    status != null && status >= 500 -> AuthDiagnosticCode.PrivyServiceUnavailable
                    else -> code
                }
            }
            val configurationCodes = setOf(
                AuthDiagnosticProviderCode.CustomAuthProviderReturnedNoToken,
                AuthDiagnosticProviderCode.InitializationFailed,
                AuthDiagnosticProviderCode.InvalidJwt,
                AuthDiagnosticProviderCode.InvalidNativeAppId,
                AuthDiagnosticProviderCode.InvalidNativeAppIdentifier,
                AuthDiagnosticProviderCode.InvalidNativeClient,
                AuthDiagnosticProviderCode.NoCustomAuthProviderConfigured,
            )
            val kind = when {
                status == 429 || diagnosticCode == AuthDiagnosticCode.PrivyRateLimited ->
                    AuthDiagnosticErrorKind.RateLimited
                status != null && status >= 500 ||
                    diagnosticCode == AuthDiagnosticCode.PrivyServiceUnavailable ->
                    AuthDiagnosticErrorKind.Unavailable
                providerCode in configurationCodes -> AuthDiagnosticErrorKind.Configuration
                else -> AuthDiagnosticErrorKind.Provider
            }
            return AuthDiagnosticFailure(
                errorKind = kind,
                httpStatus = status,
                diagnosticCode = diagnosticCode,
                providerErrorCode = providerCode,
            )
        }

        private fun mapTypedNetworkFailure(cause: Throwable?): AuthDiagnosticFailure? =
            when (cause) {
                is NoNetworkException -> AuthDiagnosticFailure(
                    errorKind = AuthDiagnosticErrorKind.Network,
                    diagnosticCode = AuthDiagnosticCode.NetworkOffline,
                )
                is SocketTimeoutException -> AuthDiagnosticFailure(
                    errorKind = AuthDiagnosticErrorKind.Network,
                    diagnosticCode = AuthDiagnosticCode.NetworkTimeout,
                )
                is IOException -> AuthDiagnosticFailure(
                    errorKind = AuthDiagnosticErrorKind.Network,
                    diagnosticCode = AuthDiagnosticCode.NetworkUnknown,
                )
                else -> null
            }
    }
}
