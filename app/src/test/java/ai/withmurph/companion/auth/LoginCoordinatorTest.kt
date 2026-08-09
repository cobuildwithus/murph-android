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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginCoordinatorTest {
    @Test
    fun explicitBritishFillMovesTheCuratedCodeOutOfTheField() {
        val coordinator = LoginCoordinator(FakeAuth(), localeRegion = "US")

        val shouldAutomaticallySend = coordinator.setDestination(
            "\u200E+\u206644 20 7946-0958\u2069",
        )

        assertFalse(shouldAutomaticallySend)
        assertEquals("GB", coordinator.state.value.phoneCountry.region)
        assertEquals("+44", coordinator.state.value.phoneCountry.dialCode)
        assertEquals("2079460958", coordinator.state.value.destination)
        assertEquals("+442079460958", coordinator.state.value.normalizedDestination)
    }

    @Test
    fun uncuratedInternationalFillStaysCompactAndButtonDriven() {
        val coordinator = LoginCoordinator(FakeAuth(), localeRegion = "US")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        val shouldAutomaticallySend = coordinator.setDestination(
            "\u200E+7 000 000 0000\u2069",
        )

        assertFalse(shouldAutomaticallySend)
        assertEquals("US", coordinator.state.value.phoneCountry.region)
        assertEquals("+70000000000", coordinator.state.value.destination)
        assertEquals("+70000000000", coordinator.state.value.normalizedDestination)
        assertTrue(coordinator.state.value.canSendCode)
    }

    @Test
    fun wholeFieldNanpNationalFillSignalsAndSendsOnce() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth, localeRegion = "US")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        assertTrue(coordinator.setDestination("202 555 0123"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)

        assertEquals(listOf("+12025550123"), auth.sentDestinations)
        assertTrue(coordinator.state.value.codeSent)
    }

    @Test
    fun wholeFieldExplicitNanpFillNormalizesSignalsAndSendsOnce() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth, localeRegion = "GB")
        coordinator.setPhoneCountry(CountryDialCode("GB", "+44"))

        assertTrue(coordinator.setDestination("+1 (202) 555-0123"))
        assertEquals("+1", coordinator.state.value.phoneCountry.dialCode)
        assertEquals("2025550123", coordinator.state.value.destination)
        coordinator.sendCode(fromAutomaticPhoneFill = true)

        assertEquals(listOf("+12025550123"), auth.sentDestinations)
    }

    @Test
    fun incrementalTypingStaysButtonDriven() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth, localeRegion = "US")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        assertFalse(coordinator.setDestination("202555012"))
        assertFalse(coordinator.setDestination("2025550123"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)

        assertTrue(auth.sentDestinations.isEmpty())
        assertTrue(coordinator.state.value.canSendCode)
    }

    @Test
    fun nationalFillOutsideNanpLocaleStaysButtonDriven() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth, localeRegion = "GB")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        assertFalse(coordinator.setDestination("202 555 0123"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)

        assertTrue(auth.sentDestinations.isEmpty())
        assertTrue(coordinator.state.value.canSendCode)
    }

    @Test
    fun automaticFillDoesNotRetryAnAttemptedTarget() = runTest {
        val auth = FakeAuth(sendFailuresRemaining = 1)
        val coordinator = LoginCoordinator(auth, localeRegion = "US")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        assertTrue(coordinator.setDestination("+1 202 555 0123"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)
        assertFalse(coordinator.state.value.codeSent)

        coordinator.setDestination("")
        assertFalse(coordinator.setDestination("+1 202 555 0142"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)
        assertEquals(1, auth.sendCalls)

        coordinator.sendCode()
        assertEquals(2, auth.sendCalls)
        assertTrue(coordinator.state.value.codeSent)
    }

    @Test
    fun resetStartsANewOneShotAutomaticFillAttempt() = runTest {
        val auth = FakeAuth(sendFailuresRemaining = 1)
        val coordinator = LoginCoordinator(auth, localeRegion = "US")

        assertTrue(coordinator.setDestination("+1 202 555 0123"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)
        coordinator.reset()

        assertTrue(coordinator.setDestination("+1 202 555 0123"))
    }

    @Test
    fun delayedAutomaticSendCannotUseAChangedDestination() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth, localeRegion = "US")
        coordinator.setPhoneCountry(CountryDialCode("US", "+1"))

        assertTrue(coordinator.setDestination("+1 202 555 0123"))
        assertFalse(coordinator.setDestination("2025550142"))
        coordinator.sendCode(fromAutomaticPhoneFill = true)

        assertTrue(auth.sentDestinations.isEmpty())
    }

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
    fun failedResendRetainsTheOtpStageAndUsableCode() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth)
        coordinator.setDestination("2025550123")
        coordinator.sendCode()
        coordinator.setCode("123456")
        auth.sendFailuresRemaining = 1

        coordinator.resendCode()

        assertEquals("2025550123", coordinator.state.value.destination)
        assertEquals("123456", coordinator.state.value.code)
        assertTrue(coordinator.state.value.codeSent)
        assertTrue(coordinator.state.value.canConfirmCode)
        assertEquals(
            "We couldn't send a code to that number. Check it and try again.",
            coordinator.state.value.errorMessage,
        )

        assertTrue(coordinator.confirmCode())
        assertEquals(listOf("123456"), auth.confirmedCodes)
    }

    @Test
    fun successfulResendClearsTheOldCodeOnlyAfterSuccessAndCannotDuplicate() = runTest {
        val auth = FakeAuth()
        val coordinator = LoginCoordinator(auth)
        coordinator.setDestination("2025550123")
        coordinator.sendCode()
        coordinator.setCode("123456")
        val resendGate = CompletableDeferred<Unit>()
        auth.sendGate = resendGate

        val resend = launch { coordinator.resendCode() }
        runCurrent()

        assertTrue(coordinator.state.value.codeSent)
        assertEquals("123456", coordinator.state.value.code)
        assertTrue(coordinator.state.value.isInFlight)
        assertFalse(coordinator.state.value.canConfirmCode)
        assertEquals(2, auth.sendCalls)

        coordinator.resendCode()
        assertEquals(2, auth.sendCalls)

        resendGate.complete(Unit)
        resend.join()

        assertTrue(coordinator.state.value.codeSent)
        assertEquals("", coordinator.state.value.code)
        assertEquals("2025550123", coordinator.state.value.destination)
        assertFalse(coordinator.state.value.isInFlight)
        assertNull(coordinator.state.value.errorMessage)
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
        sendFailuresRemaining: Int = 0,
    ) : AuthProvider {
        var confirmCalls = 0
        var sendCalls = 0
        var sendFailuresRemaining = sendFailuresRemaining
        var sendGate: CompletableDeferred<Unit>? = null
        val sentDestinations = mutableListOf<String>()
        val confirmedCodes = mutableListOf<String>()

        override suspend fun currentState(): AuthSessionState = AuthSessionState.SignedOut

        override suspend fun sendCode(method: LoginMethod, destination: String) {
            sendCalls += 1
            sentDestinations += destination
            sendGate?.await()
            if (sendFailuresRemaining > 0) {
                sendFailuresRemaining -= 1
                error("Send failed")
            }
        }

        override suspend fun confirmCode(
            method: LoginMethod,
            destination: String,
            code: String,
        ) {
            confirmCalls += 1
            confirmedCodes += code
            confirmGate?.await()
            if (confirmFails) error("Rejected code")
        }

        override suspend fun identityToken(): String = "identity-token"

        override suspend fun signOut() = Unit
    }
}
