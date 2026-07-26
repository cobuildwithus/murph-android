package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginCoordinatorTest {
    @Test
    fun successfulConfirmationClearsDestinationAndConsumedCode() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth)
        coordinator.setMethod(LoginMethod.Email)
        coordinator.setDestination("member@example.test")
        coordinator.sendCode()
        coordinator.setCode("123456")

        assertTrue(coordinator.confirmCode())

        assertEquals("", coordinator.state.value.destination)
        assertEquals("", coordinator.state.value.code)
        assertFalse(coordinator.state.value.codeSent)
        assertEquals(1, auth.confirmCalls)
    }

    @Test
    fun failedConfirmationRetainsStateForDeliberateRetry() = runTest {
        val auth = FakeAuth(confirmFails = true)
        val coordinator = LoginCoordinator(auth)
        coordinator.setDestination("2025550123")
        coordinator.sendCode()
        coordinator.setCode("123456")

        assertFalse(coordinator.confirmCode())

        assertEquals("2025550123", coordinator.state.value.destination)
        assertEquals("123456", coordinator.state.value.code)
        assertTrue(coordinator.state.value.codeSent)
        assertEquals(1, auth.confirmCalls)
    }

    @Test
    fun explicitRetryCannotDuplicateAConfirmationAlreadyInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val auth = FakeAuth(confirmGate = gate)
        val coordinator = LoginCoordinator(auth)
        coordinator.setDestination("2025550123")
        coordinator.sendCode()
        coordinator.setCode("123456")

        val firstConfirmation = launch { coordinator.confirmCode() }
        runCurrent()

        assertFalse(coordinator.confirmCode())
        assertEquals(1, auth.confirmCalls)

        gate.complete(Unit)
        firstConfirmation.join()
        assertEquals(1, auth.confirmCalls)
    }

    private class FakeAuth(
        private val confirmFails: Boolean = false,
        private val confirmGate: CompletableDeferred<Unit>? = null,
    ) : AuthProvider {
        var confirmCalls = 0

        override suspend fun currentState(): AuthSessionState = AuthSessionState.SignedOut

        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit

        override suspend fun confirmCode(
            method: LoginMethod,
            destination: String,
            code: String,
        ) {
            confirmCalls += 1
            confirmGate?.await()
            if (confirmFails) error("Rejected code")
        }

        override suspend fun identityToken(): String = "identity-token"

        override suspend fun signOut() = Unit
    }
}
