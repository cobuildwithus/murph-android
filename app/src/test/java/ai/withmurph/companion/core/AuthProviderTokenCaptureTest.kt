package ai.withmurph.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AuthProviderTokenCaptureTest {
    @Test
    fun unverifiedMemberIsFencedBeforeTokenCapture() = runTest {
        val observed = AuthSessionState.SignedIn("member-a", verifiedOnline = false)
        val auth = FakeAuthProvider(currentStateHandler = { observed })

        val error = captureLocalAuthFailure {
            auth.identityTokenForMember("member-a")
        }

        assertSame(observed, error.observedState)
        assertEquals(0, auth.identityTokenCalls)
        assertFalse(error.toString().contains("member-a"))
    }

    @Test
    fun memberChangeDuringTokenCaptureIsReportedWithoutReturningTheToken() = runTest {
        val original = AuthSessionState.SignedIn("member-a", verifiedOnline = true)
        val changed = AuthSessionState.SignedIn("member-b", verifiedOnline = true)
        var stateCalls = 0
        val auth = FakeAuthProvider(
            currentStateHandler = {
                stateCalls += 1
                if (stateCalls == 1) original else changed
            },
        )

        val error = captureLocalAuthFailure {
            auth.identityTokenForMember("member-a")
        }

        assertSame(changed, error.observedState)
        assertEquals(1, auth.identityTokenCalls)
    }

    @Test
    fun tokenSdkFailureRecordsTheLatestSafeAuthObservation() = runTest {
        val verified = AuthSessionState.SignedIn("member-a", verifiedOnline = true)
        var stateCalls = 0
        val auth = FakeAuthProvider(
            currentStateHandler = {
                stateCalls += 1
                if (stateCalls == 1) verified else AuthSessionState.TemporarilyUnavailable
            },
            identityTokenHandler = { throw IllegalStateException("sdk unavailable") },
        )

        val error = captureLocalAuthFailure {
            auth.identityTokenForMember("member-a")
        }

        assertSame(AuthSessionState.TemporarilyUnavailable, error.observedState)
        assertEquals(1, auth.identityTokenCalls)
    }

    @Test
    fun tokenCapturePreservesCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        val auth = FakeAuthProvider(
            currentStateHandler = {
                AuthSessionState.SignedIn("member-a", verifiedOnline = true)
            },
            identityTokenHandler = { throw cancellation },
        )

        try {
            auth.identityTokenForMember("member-a")
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    private suspend fun captureLocalAuthFailure(
        block: suspend () -> Unit,
    ): CompanionApiException.LocalAuthUnavailable = try {
        block()
        fail("Expected local authentication failure")
        error("unreachable")
    } catch (error: CompanionApiException.LocalAuthUnavailable) {
        error
    }

    private class FakeAuthProvider(
        private val currentStateHandler: suspend () -> AuthSessionState,
        private val identityTokenHandler: suspend () -> String = { "identity-token" },
    ) : AuthProvider {
        var identityTokenCalls = 0

        override suspend fun currentState(): AuthSessionState = currentStateHandler()

        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit

        override suspend fun confirmCode(
            method: LoginMethod,
            destination: String,
            code: String,
        ) = Unit

        override suspend fun identityToken(): String {
            identityTokenCalls += 1
            return identityTokenHandler()
        }

        override suspend fun signOut() = Unit
    }
}
