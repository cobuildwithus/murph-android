package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthDiagnosticCode
import ai.withmurph.companion.core.AuthDiagnosticErrorKind
import ai.withmurph.companion.core.AuthDiagnosticProviderCode
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.network.PrivyHttpErrorResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivyAuthServiceTest {
    @Test
    fun rethrowsCancellationWrappedByPrivyTransport() = runTest {
        val cancellation = CancellationException("cancelled")
        val wrapped = PrivyApiException(
            statusCode = null,
            responseBody = null,
            message = "private exception prose",
            cause = cancellation,
        )

        val caught = try {
            PrivyAuthService.runAuthCall { throw wrapped }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun mapsOnlyTypedStatusAndAllowlistedProviderCode() {
        val failure = PrivyAuthService.mapAuthFailure(
            PrivyApiException(
                statusCode = 429,
                responseBody = PrivyHttpErrorResponse(
                    code = " TOO_MANY_REQUESTS ",
                    error = "private provider prose",
                ),
                message = "private exception prose",
                cause = IllegalStateException(),
            ),
        )

        assertEquals(AuthDiagnosticErrorKind.RateLimited, failure.errorKind)
        assertEquals(429, failure.httpStatus)
        assertEquals(AuthDiagnosticCode.PrivyRateLimited, failure.diagnosticCode)
        assertEquals(AuthDiagnosticProviderCode.TooManyRequests, failure.providerErrorCode)
        assertTrue(failure.retryable)
        assertFalseContainsPrivateProse(failure.toString())
    }

    @Test
    fun dropsUnsupportedProviderCodeAndNeverRetainsProviderProse() {
        val failure = PrivyAuthService.mapAuthFailure(
            PrivyApiException(
                statusCode = 400,
                responseBody = PrivyHttpErrorResponse(
                    code = "destination_2025550123",
                    error = "private provider prose",
                ),
                message = "private exception prose",
                cause = IllegalStateException(),
            ),
        )

        assertEquals(AuthDiagnosticErrorKind.Unknown, failure.errorKind)
        assertEquals(AuthDiagnosticCode.PrivyUnknown, failure.diagnosticCode)
        assertNull(failure.providerErrorCode)
        assertFalseContainsPrivateProse(failure.toString())
    }

    @Test
    fun mapsWrappedNetworkAndConfigurationFailures() {
        val offline = PrivyAuthService.mapAuthFailure(
            PrivyApiException(
                statusCode = null,
                responseBody = null,
                message = "private exception prose",
                cause = NoNetworkException,
            ),
        )
        val configuration = PrivyAuthService.mapAuthFailure(
            PrivyApiException(
                statusCode = 403,
                responseBody = PrivyHttpErrorResponse(
                    code = "invalid_native_client",
                    error = "private provider prose",
                ),
                message = "private exception prose",
                cause = IllegalStateException(),
            ),
        )

        assertEquals(AuthDiagnosticErrorKind.Network, offline.errorKind)
        assertEquals(AuthDiagnosticCode.NetworkOffline, offline.diagnosticCode)
        assertEquals(AuthDiagnosticErrorKind.Configuration, configuration.errorKind)
        assertEquals(AuthDiagnosticCode.PrivyInvalidNativeAppId, configuration.diagnosticCode)
        assertFalse(configuration.retryable)
    }

    @Test
    fun mapsIncorrectCredentialsToInvalidCodeAndOrdinaryBadRequestToProvider() {
        val invalidCode = mapProviderCode("incorrect_credentials_phone")
        val badRequest = mapProviderCode("bad_request")
        val initialization = mapProviderCode("initialization_failed")

        assertEquals(AuthDiagnosticCode.PrivyInvalidCode, invalidCode.diagnosticCode)
        assertEquals(AuthDiagnosticErrorKind.Provider, invalidCode.errorKind)
        assertEquals(AuthDiagnosticCode.PrivyBadRequest, badRequest.diagnosticCode)
        assertEquals(AuthDiagnosticErrorKind.Provider, badRequest.errorKind)
        assertEquals(AuthDiagnosticCode.PrivyInitializationFailed, initialization.diagnosticCode)
        assertEquals(AuthDiagnosticErrorKind.Configuration, initialization.errorKind)
        assertFalse(initialization.retryable)
    }

    private fun mapProviderCode(value: String) = PrivyAuthService.mapAuthFailure(
        PrivyApiException(
            statusCode = 400,
            responseBody = PrivyHttpErrorResponse(value, "private provider prose"),
            message = "private exception prose",
            cause = IllegalStateException(),
        ),
    )

    private fun assertFalseContainsPrivateProse(value: String) {
        assertTrue(!value.contains("private provider prose"))
        assertTrue(!value.contains("private exception prose"))
        assertTrue(!value.contains("2025550123"))
    }
}
