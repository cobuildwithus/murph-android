package ai.withmurph.companion.api

import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class HttpCompanionApiAuthenticationTest {
    @Test
    fun localAuthenticationFailureIsPreservedBeforeOpeningARequest() = runTest {
        val localFailure = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn("member-a", verifiedOnline = false),
        )
        var callbackCalls = 0
        val api = apiWithMemberAuthentication {
            callbackCalls += 1
            throw localFailure
        }

        val caught = captureFailure {
            api.fetchSyncStatus("member-a", "health_connect")
        }

        assertSame(localFailure, caught)
        assertEquals(1, callbackCalls)
    }

    @Test
    fun unexpectedLocalCallbackFailureNeverBecomesUnauthorized() = runTest {
        val api = apiWithMemberAuthentication {
            throw IllegalStateException("local sdk unavailable")
        }

        val caught = captureFailure {
            api.fetchSyncStatus("member-a", "health_connect")
        }

        assertSame(AuthSessionState.TemporarilyUnavailable, caught.observedState)
    }

    @Test
    fun localAuthenticationPreservesCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        val api = apiWithMemberAuthentication { throw cancellation }

        try {
            api.fetchSyncStatus("member-a", "health_connect")
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun backendUnauthorizedResponseMappingRemainsAuthoritative() {
        assertSame(
            CompanionApiException.Unauthorized,
            mapCompanionApiErrorCode(status = 401, errorCode = null),
        )
    }

    private fun apiWithMemberAuthentication(
        authenticate: suspend (String) -> String,
    ): HttpCompanionApi = HttpCompanionApi(
        baseUrl = "https://network-must-not-run.invalid",
        identityTokenForMember = authenticate,
    )

    private suspend fun captureFailure(
        block: suspend () -> Unit,
    ): CompanionApiException.LocalAuthUnavailable = try {
        block()
        fail("Expected local authentication failure")
        error("unreachable")
    } catch (error: CompanionApiException.LocalAuthUnavailable) {
        error
    }
}
