package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthDiagnosticCode
import ai.withmurph.companion.core.AuthDiagnosticErrorKind
import ai.withmurph.companion.core.AuthDiagnosticProviderCode
import ai.withmurph.companion.core.AuthProviderException
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
    fun refreshesBeforeReadingIdentityToken() = runTest {
        val events = mutableListOf<String>()
        val user = TokenUser(identityToken = "fresh")

        val token = PrivyAuthService.refreshedIdentityToken(
            currentUser = {
                events += "user"
                user
            },
            refreshSession = {
                events += "refresh"
            },
            currentIdentityToken = {
                events += "identity"
                it.identityToken
            },
        )

        assertEquals("fresh", token)
        assertEquals(listOf("user", "refresh", "user", "identity"), events)
    }

    @Test
    fun rereadsIdentityTokenFromCurrentUserAfterRefresh() = runTest {
        val before = TokenUser(identityToken = "stale")
        val after = TokenUser(identityToken = "fresh")
        var currentUser = before
        var userReads = 0

        val token = PrivyAuthService.refreshedIdentityToken(
            currentUser = {
                userReads += 1
                currentUser
            },
            refreshSession = { capturedUser ->
                assertSame(before, capturedUser)
                currentUser = after
            },
            currentIdentityToken = TokenUser::identityToken,
        )

        assertEquals("fresh", token)
        assertEquals(2, userReads)
    }

    @Test
    fun rejectsMissingUserAndUnavailableIdentityToken() = runTest {
        var refreshCalls = 0
        expectIdentityTokenUnavailable {
            PrivyAuthService.refreshedIdentityToken<TokenUser>(
                currentUser = { null },
                refreshSession = { refreshCalls += 1 },
                currentIdentityToken = TokenUser::identityToken,
            )
        }
        assertEquals(0, refreshCalls)

        var currentUser: TokenUser? = TokenUser(identityToken = "stale")
        expectIdentityTokenUnavailable {
            PrivyAuthService.refreshedIdentityToken(
                currentUser = {
                    currentUser.also { currentUser = null }
                },
                refreshSession = {},
                currentIdentityToken = TokenUser::identityToken,
            )
        }

        listOf<String?>(null, "   ").forEach { unavailableToken ->
            expectIdentityTokenUnavailable {
                PrivyAuthService.refreshedIdentityToken(
                    currentUser = { TokenUser(identityToken = unavailableToken) },
                    refreshSession = {},
                    currentIdentityToken = TokenUser::identityToken,
                )
            }
        }
    }

    @Test
    fun mapsRefreshFailureThroughTypedAuthBoundary() = runTest {
        val failure = captureAuthProviderFailure {
            PrivyAuthService.refreshedIdentityToken(
                currentUser = { TokenUser(identityToken = "fresh") },
                refreshSession = { throw NoNetworkException },
                currentIdentityToken = TokenUser::identityToken,
            )
        }

        assertEquals(AuthDiagnosticErrorKind.Network, failure.failure.errorKind)
        assertEquals(AuthDiagnosticCode.NetworkOffline, failure.failure.diagnosticCode)
    }

    @Test
    fun preservesCancellationDuringIdentityTokenRefresh() = runTest {
        val cancellation = CancellationException("cancelled")
        val caught = try {
            PrivyAuthService.refreshedIdentityToken(
                currentUser = { TokenUser(identityToken = "fresh") },
                refreshSession = { throw cancellation },
                currentIdentityToken = TokenUser::identityToken,
            )
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, caught)
    }

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

    private suspend fun expectIdentityTokenUnavailable(block: suspend () -> String) {
        try {
            block()
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("Expected identity token to be unavailable")
    }

    private suspend fun captureAuthProviderFailure(
        block: suspend () -> String,
    ): AuthProviderException = try {
        block()
        throw AssertionError("Expected auth provider failure")
    } catch (error: AuthProviderException) {
        error
    }

    private data class TokenUser(val identityToken: String?)

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
