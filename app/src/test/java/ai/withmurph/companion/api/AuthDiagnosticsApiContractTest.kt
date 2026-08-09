package ai.withmurph.companion.api

import ai.withmurph.companion.core.AuthDiagnosticCode
import ai.withmurph.companion.core.AuthDiagnosticErrorKind
import ai.withmurph.companion.core.AuthDiagnosticEvent
import ai.withmurph.companion.core.AuthDiagnosticMethod
import ai.withmurph.companion.core.AuthDiagnosticProviderCode
import ai.withmurph.companion.core.AuthDiagnosticStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthDiagnosticsApiContractTest {
    @Test
    fun buildsExactAndroidContentFreeEnvelope() {
        val body = AuthDiagnosticsApiContract.body(
            AuthDiagnosticEvent(
                stage = AuthDiagnosticStage.ConfirmCode,
                method = AuthDiagnosticMethod.Email,
                errorKind = AuthDiagnosticErrorKind.Provider,
                httpStatus = 400,
                diagnosticCode = AuthDiagnosticCode.PrivyInvalidCode,
                providerErrorCode = AuthDiagnosticProviderCode.InvalidCode,
                retryable = true,
                appVersion = "0.1.0",
            ),
        )

        assertEquals(
            mapOf(
                "stage" to "confirm_code",
                "method" to "email",
                "errorKind" to "provider",
                "httpStatus" to 400,
                "diagnosticCode" to "privy_invalid_code",
                "providerErrorCode" to "invalid_code",
                "retryable" to true,
                "appVersion" to "0.1.0",
                "platform" to "android",
            ),
            body,
        )
        listOf(
            "destination",
            "email",
            "phone",
            "code",
            "message",
            "token",
            "memberKey",
            "installationId",
        ).forEach { forbidden -> assertFalse(body.containsKey(forbidden)) }
    }

    @Test
    fun omitsUnavailableOptionalFields() {
        val body = AuthDiagnosticsApiContract.body(
            AuthDiagnosticEvent(
                stage = AuthDiagnosticStage.SendCode,
                method = AuthDiagnosticMethod.Sms,
                errorKind = AuthDiagnosticErrorKind.Unknown,
                httpStatus = null,
                diagnosticCode = AuthDiagnosticCode.PrivyUnknown,
                providerErrorCode = null,
                retryable = true,
                appVersion = null,
            ),
        )

        assertFalse(body.containsKey("httpStatus"))
        assertFalse(body.containsKey("providerErrorCode"))
        assertFalse(body.containsKey("appVersion"))
    }
}
