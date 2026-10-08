package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagingSetupCoordinatorTest {
    @Test fun phoneProofUsesCapturedTargetAndClearsSecrets() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        model.setPhone("+12025550123")
        assertTrue(model.sendCode())
        model.setPhone("+12025550999")
        model.setCode("123456")
        assertTrue(model.verifyCode())
        assertEquals("+12025550123", api.verifiedPhone)
        assertEquals(MessagingStage.Connected, model.state.value.stage)
        assertEquals("", model.state.value.code)
        assertEquals("", model.state.value.phone)
    }
    @Test fun invalidNumberAndWrongCodeRemainRetryable() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        model.setPhone("invalid")
        assertFalse(model.sendCode()); assertEquals(0, api.sends)
        model.setPhone("+12025550123"); model.sendCode(); model.setCode("123456")
        api.failure = MessagingLinkException(MessagingLinkException.Reason.InvalidCode)
        assertFalse(model.verifyCode()); assertEquals(MessagingStage.Code, model.state.value.stage)
        assertEquals(MessagingLinkException.Reason.InvalidCode.message, model.state.value.error)
        api.failure = null; assertTrue(model.sendCode()); assertEquals("", model.state.value.code)
        model.changeNumber(); assertEquals(MessagingStage.Phone, model.state.value.stage)
    }
    @Test fun acceptedWelcomeContinuesAndPassesSdkTokenToBackend() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        assertTrue(model.connectTelegram { clientId -> assertEquals("123456789", clientId); "synthetic-id-token" })
        assertEquals(MessagingStage.Connected, model.state.value.stage)
        assertEquals(listOf("a".repeat(43)), api.completedStarts)
        assertEquals(listOf("synthetic-id-token"), api.completedTokens)
    }
    @Test fun cancelledAndUnavailableLoginStayInlineAndRetryable() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        for (reason in listOf(MessagingLinkException.Reason.TelegramCancelled, MessagingLinkException.Reason.TelegramUnavailable)) {
            assertFalse(model.connectTelegram { throw MessagingLinkException(reason) })
            assertEquals(MessagingStage.Phone, model.state.value.stage)
            assertEquals(reason.message, model.state.value.error)
            assertTrue(model.state.value.telegramError)
            assertTrue(api.completedTokens.isEmpty())
        }
        assertTrue(model.connectTelegram { "synthetic-id-token" })
    }
    @Test fun explicitCancelRejectsLateStartOrProofAndAllowsRetry() = runTest {
        for (duringStart in listOf(true, false)) {
            val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
            val proof = CompletableDeferred<String>()
            if (duringStart) api.waitStart = CompletableDeferred()
            var sdkCalls = 0
            val pending = async { runCatching { model.connectTelegram { sdkCalls++; proof.await() } } }
            runCurrent()
            model.cancelTelegram()
            assertFalse(model.state.value.busy)
            assertEquals(MessagingLinkException.Reason.TelegramCancelled.message, model.state.value.error)
            api.waitStart?.complete(Unit)
            proof.complete("synthetic-id-token")
            pending.await()
            assertTrue(api.completedTokens.isEmpty())
            assertEquals(if (duringStart) 0 else 1, sdkCalls)
            api.waitStart = null
            assertTrue(model.connectTelegram { "synthetic-retry-token" })
        }
    }

    @Test fun cancelDuringSuspendedAuthCheckStopsLaterTelegramSteps() = runTest {
        for (beforeSdk in listOf(true, false)) {
            val auth = Auth(); val api = Api(); val model = MessagingSetupCoordinator(auth, api)
            val gate = CompletableDeferred<Unit>()
            if (beforeSdk) api.afterStart = { auth.suspendNext = gate }
            var sdkCalls = 0
            val pending = async {
                runCatching { model.connectTelegram { sdkCalls++; if (!beforeSdk) auth.suspendNext = gate; "synthetic-id-token" } }
            }
            runCurrent()
            model.cancelTelegram()
            gate.complete(Unit)
            pending.await()
            assertEquals(if (beforeSdk) 0 else 1, sdkCalls)
            assertTrue(api.completedTokens.isEmpty())
            assertEquals(MessagingLinkException.Reason.TelegramCancelled.message, model.state.value.error)
            api.afterStart = null
            assertTrue(model.connectTelegram { "synthetic-retry-token" })
            assertEquals(listOf("synthetic-retry-token"), api.completedTokens)
        }
    }

    @Test fun submittedTelegramProofCannotBeCancelledIntoAFalseFailure() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        api.waitComplete = CompletableDeferred()
        val pending = async { model.connectTelegram { "synthetic-id-token" } }
        runCurrent()
        assertEquals(listOf("synthetic-id-token"), api.completedTokens)
        assertEquals(TelegramProgress.Confirming, model.state.value.telegram)
        model.cancelTelegram()
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.error)
        api.waitComplete?.complete(Unit)
        assertTrue(pending.await())
        assertEquals(MessagingStage.Connected, model.state.value.stage)
        assertEquals(TelegramProgress.None, model.state.value.telegram)
        assertNull(model.state.value.error)
    }

    @Test fun resetAndMemberChangeRejectLateSdkProof() = runTest {
        for (reset in listOf(true, false)) {
            val api = Api(); val auth = Auth(); val model = MessagingSetupCoordinator(auth, api)
            val proof = CompletableDeferred<String>()
            val pending = async { runCatching { model.connectTelegram { proof.await() } } }
            runCurrent()
            assertFalse(model.connectTelegram { error("duplicate") })
            if (reset) model.reset() else auth.member = "other-member"
            proof.complete("synthetic-id-token")
            pending.await()
            assertTrue(api.completedTokens.isEmpty())
            assertEquals(MessagingStage.Phone, model.state.value.stage)
        }
    }
    @Test fun resetRejectsLateCompletionAndDuplicateSend() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        api.wait = CompletableDeferred()
        model.setPhone("+12025550123")
        val pending = async { runCatching { model.sendCode() } }
        runCurrent(); assertFalse(model.sendCode())
        model.reset(); api.wait?.complete(Unit); pending.await()
        assertEquals(MessagingSetupState(), model.state.value); assertEquals(1, api.sends)
    }
    @Test fun differentMemberCannotCompleteAnExistingPhoneFlow() = runTest {
        val api = Api(); val auth = Auth(); val model = MessagingSetupCoordinator(auth, api)
        model.setPhone("+12025550123"); model.sendCode(); model.setCode("123456")
        auth.member = "other-member"
        assertFalse(model.verifyCode()); assertNull(api.verifiedPhone)
    }

    private class Auth : AuthProvider {
        var member = "synthetic-member"
        var suspendNext: CompletableDeferred<Unit>? = null
        override suspend fun currentState(): AuthSessionState {
            suspendNext?.let { suspendNext = null; it.await() }
            return AuthSessionState.SignedIn(member, true)
        }
        override suspend fun identityToken() = "synthetic-credential"
        override suspend fun signOut() {}
        override suspend fun sendCode(method: LoginMethod, destination: String) {}
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) {}
    }
    private class Api : HostedAuthServing {
        var sends = 0; var verifiedPhone: String? = null
        var starts = 0; val completedTokens = mutableListOf<String>(); val completedStarts = mutableListOf<String>()
        var failure: Exception? = null; var wait: CompletableDeferred<Unit>? = null
        var waitStart: CompletableDeferred<Unit>? = null
        var afterStart: (() -> Unit)? = null
        var waitComplete: CompletableDeferred<Unit>? = null
        override suspend fun sendMessagingPhoneCode(phone: String, credential: String) { sends++; wait?.await(); failure?.let { throw it } }
        override suspend fun verifyMessagingPhoneCode(phone: String, code: String, credential: String) { failure?.let { throw it }; verifiedPhone = phone }
        override suspend fun startMessagingTelegram(credential: String): TelegramMessagingLink { starts++; waitStart?.await(); afterStart?.invoke(); return TelegramMessagingLink("a".repeat(43), "123456789") }
        override suspend fun completeMessagingTelegram(startId: String, idToken: String, credential: String): Boolean {
            completedStarts.add(startId); completedTokens.add(idToken); waitComplete?.await(); failure?.let { throw it }; return true
        }
        override suspend fun sendCode(method: LoginMethod, value: String) {}
        override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession = error("unused")
        override suspend fun exchange(legacyCredential: String): HostedAuthSession = error("unused")
        override suspend fun renew(credential: String): HostedAuthSessionStatus = error("unused")
        override suspend fun revoke(credential: String) {}
    }
}
