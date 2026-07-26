package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private class FakeAuth(
        private val confirmFails: Boolean = false,
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
            if (confirmFails) error("Rejected code")
        }

        override suspend fun identityToken(): String = "identity-token"

        override suspend fun signOut() = Unit
    }
}
