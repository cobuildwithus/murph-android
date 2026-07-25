package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import android.content.Context
import ai.withmurph.companion.core.LoginMethod
import io.privy.auth.AuthState as PrivyAuthState
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig

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
        when (method) {
            LoginMethod.Phone -> privy.sms.sendCode(phoneNumber = destination).getOrThrow()
            LoginMethod.Email -> privy.email.sendCode(destination).getOrThrow()
        }
    }

    override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) {
        when (method) {
            LoginMethod.Phone -> privy.sms.loginWithCode(
                code = code,
                phoneNumber = destination,
            ).getOrThrow()
            LoginMethod.Email -> privy.email.loginWithCode(code, destination).getOrThrow()
        }
    }

    override suspend fun identityToken(): String {
        val token = privy.getUser()?.identityToken
        return token?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Privy identity token is unavailable")
    }

    override suspend fun signOut() {
        privy.logout()
    }

    companion object {
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
    }
}
