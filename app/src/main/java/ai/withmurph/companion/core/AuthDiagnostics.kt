package ai.withmurph.companion.core

import java.util.Locale

enum class AuthDiagnosticStage(val wireValue: String) {
    SendCode("send_code"),
    ConfirmCode("confirm_code"),
}

enum class AuthDiagnosticMethod(val wireValue: String) {
    Email("email"),
    Sms("sms"),
}

enum class AuthDiagnosticErrorKind(val wireValue: String) {
    Configuration("configuration"),
    Network("network"),
    Provider("provider"),
    RateLimited("rate_limited"),
    Unavailable("unavailable"),
    Unknown("unknown"),
}

enum class AuthDiagnosticCode(
    val wireValue: String,
    val defaultRetryable: Boolean,
) {
    NetworkOffline("network_offline", true),
    NetworkTimeout("network_timeout", true),
    NetworkUnknown("network_unknown", true),
    PrivyAuthenticationFailed("privy_authentication_failed", true),
    PrivyBadEmail("privy_bad_email", true),
    PrivyBadRequest("privy_bad_request", false),
    PrivyExpiredCode("privy_expired_code", true),
    PrivyForbidden("privy_forbidden", false),
    PrivyInvalidCode("privy_invalid_code", true),
    PrivyInvalidEmail("privy_invalid_email", true),
    PrivyInvalidNativeAppId("privy_invalid_native_app_id", false),
    PrivyInvalidPhone("privy_invalid_phone", true),
    PrivyInitializationFailed("privy_initialization_failed", false),
    PrivyNotFound("privy_not_found", false),
    PrivyRateLimited("privy_rate_limited", true),
    PrivyServiceUnavailable("privy_service_unavailable", true),
    PrivyTimeout("privy_timeout", true),
    PrivyUnauthorized("privy_unauthorized", false),
    PrivyUnknown("privy_unknown", true),
}

enum class AuthDiagnosticProviderCode(val wireValue: String) {
    AuthenticationFailure("authentication_failure"),
    BadEmail("bad_email"),
    BadRequest("bad_request"),
    CustomAuthProviderReturnedNoToken("custom_auth_provider_returned_no_token"),
    EmailNotFound("email_not_found"),
    EmbeddedWalletFailure("embedded_wallet_failure"),
    ExpiredCode("expired_code"),
    FailureDuringAuthentication("failure_during_authentication"),
    Forbidden("forbidden"),
    IncorrectCredentialsCustomAccessToken("incorrect_credentials_custom_access_token"),
    IncorrectCredentialsEmail("incorrect_credentials_email"),
    IncorrectCredentialsOauth("incorrect_credentials_oauth"),
    IncorrectCredentialsPasskey("incorrect_credentials_passkey"),
    IncorrectCredentialsPhone("incorrect_credentials_phone"),
    IncorrectCredentialsSiwe("incorrect_credentials_siwe"),
    IncorrectCredentialsSiws("incorrect_credentials_siws"),
    IncorrectCredentialsUnknown("incorrect_credentials_unknown"),
    InitializationFailed("initialization_failed"),
    InvalidCode("invalid_code"),
    InvalidEmail("invalid_email"),
    InvalidJwt("invalid_jwt"),
    InvalidNativeAppId("invalid_native_app_id"),
    InvalidNativeAppIdentifier("invalid_native_app_identifier"),
    InvalidNativeClient("invalid_native_client"),
    InvalidPhone("invalid_phone"),
    InvalidRequest("invalid_request"),
    NoCustomAuthProviderConfigured("no_custom_auth_provider_configured"),
    NotFound("not_found"),
    NotLoggedIn("not_logged_in"),
    PasskeyAuthenticationFailed("passkey_authentication_failed"),
    PasskeyCreationFailed("passkey_creation_failed"),
    PasskeyNoCredentials("passkey_no_credentials"),
    PasskeyUserCancelled("passkey_user_cancelled"),
    PhoneNotFound("phone_not_found"),
    RateLimitExceeded("rate_limit_exceeded"),
    RateLimited("rate_limited"),
    ServiceUnavailable("service_unavailable"),
    Timeout("timeout"),
    TooManyRequests("too_many_requests"),
    Unauthorized("unauthorized"),
    Unavailable("unavailable"),
    ;

    companion object {
        fun from(rawValue: String?): AuthDiagnosticProviderCode? {
            val normalized = rawValue?.trim()?.lowercase(Locale.US) ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

data class AuthDiagnosticFailure(
    val errorKind: AuthDiagnosticErrorKind,
    val httpStatus: Int? = null,
    val diagnosticCode: AuthDiagnosticCode,
    val providerErrorCode: AuthDiagnosticProviderCode? = null,
    val retryable: Boolean = diagnosticCode.defaultRetryable,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) {
            "Auth diagnostic HTTP status must be null or a valid status"
        }
    }

    companion object {
        val Unknown = AuthDiagnosticFailure(
            errorKind = AuthDiagnosticErrorKind.Unknown,
            diagnosticCode = AuthDiagnosticCode.PrivyUnknown,
        )
    }
}

/** A privacy-bounded auth failure. It deliberately retains no provider prose or cause. */
class AuthProviderException(val failure: AuthDiagnosticFailure) : Exception()

data class AuthDiagnosticEvent(
    val stage: AuthDiagnosticStage,
    val method: AuthDiagnosticMethod,
    val errorKind: AuthDiagnosticErrorKind,
    val httpStatus: Int?,
    val diagnosticCode: AuthDiagnosticCode,
    val providerErrorCode: AuthDiagnosticProviderCode?,
    val retryable: Boolean,
    val appVersion: String?,
) {
    companion object {
        private val SAFE_APP_VERSION = Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){1,3}$")

        fun from(
            stage: AuthDiagnosticStage,
            method: LoginMethod,
            failure: AuthDiagnosticFailure,
            appVersion: String,
        ): AuthDiagnosticEvent = AuthDiagnosticEvent(
            stage = stage,
            method = when (method) {
                LoginMethod.Phone -> AuthDiagnosticMethod.Sms
                LoginMethod.Email -> AuthDiagnosticMethod.Email
            },
            errorKind = failure.errorKind,
            httpStatus = failure.httpStatus,
            diagnosticCode = failure.diagnosticCode,
            providerErrorCode = failure.providerErrorCode,
            retryable = failure.retryable,
            appVersion = appVersion.takeIf(SAFE_APP_VERSION::matches),
        )
    }
}
