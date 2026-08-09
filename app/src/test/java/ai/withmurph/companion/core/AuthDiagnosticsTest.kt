package ai.withmurph.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthDiagnosticsTest {
    @Test
    fun eventKeepsOnlyTypedFieldsAndSafeReleaseVersion() {
        val failure = AuthDiagnosticFailure(
            errorKind = AuthDiagnosticErrorKind.Configuration,
            httpStatus = 403,
            diagnosticCode = AuthDiagnosticCode.PrivyInvalidNativeAppId,
            providerErrorCode = AuthDiagnosticProviderCode.InvalidNativeClient,
        )

        val event = AuthDiagnosticEvent.from(
            stage = AuthDiagnosticStage.SendCode,
            method = LoginMethod.Phone,
            failure = failure,
            appVersion = "0.1.0",
        )

        assertEquals(AuthDiagnosticMethod.Sms, event.method)
        assertEquals(AuthDiagnosticProviderCode.InvalidNativeClient, event.providerErrorCode)
        assertEquals("0.1.0", event.appVersion)
        assertTrue(!event.retryable)
    }

    @Test
    fun discardsUnsupportedProviderCodesAndNonReleaseVersionLabels() {
        assertNull(AuthDiagnosticProviderCode.from("destination@example.test"))
        assertNull(AuthDiagnosticProviderCode.from("otp_123456"))

        val event = AuthDiagnosticEvent.from(
            stage = AuthDiagnosticStage.ConfirmCode,
            method = LoginMethod.Email,
            failure = AuthDiagnosticFailure.Unknown,
            appVersion = "0.1.0-dev",
        )

        assertNull(event.appVersion)
    }
}
