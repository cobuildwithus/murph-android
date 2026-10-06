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
    @Test fun pendingTelegramKeepsTokenAndRecipientProofAcrossRetry() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        model.selectTelegram(); assertEquals(0, api.starts)
        val original = model.startTelegram(); assertFalse(model.checkTelegram())
        assertEquals(MessagingStage.Telegram, model.state.value.stage)
        assertTrue(model.state.value.telegramPending)
        assertEquals(original, model.startTelegram()); assertEquals(1, api.starts)
        val token = "a".repeat(43); val proof = "b".repeat(43)
        val callback = "murph-messaging://telegram/complete#token=$token&proof=$proof"
        assertTrue(model.acceptTelegramReturn(callback))
        api.failure = MessagingLinkException(MessagingLinkException.Reason.Unavailable)
        assertFalse(model.checkTelegram())
        api.failure = null; api.linked = true
        assertTrue(model.checkTelegram())
        assertEquals(listOf(token, token, token), api.completedTokens)
        assertEquals(proof, api.completedProofs.last())
        assertEquals(MessagingStage.Connected, model.state.value.stage)
        assertFalse(model.acceptTelegramReturn(callback))
    }
    @Test fun otherLinksAndMethodSwitchCannotReuseTelegramProof() = runTest {
        val api = Api(); val model = MessagingSetupCoordinator(Auth(), api)
        model.startTelegram()
        val token = "a".repeat(43); val proof = "b".repeat(43)
        for (url in listOf("https://telegram/complete#token=$token&proof=$proof",
            "murph-messaging://telegram/complete#token=$proof&proof=$proof",
            "murph-messaging://telegram/complete?extra=1#token=$token&proof=$proof",
            "murph-messaging://telegram/complete#token=$token&proof=bad")) assertFalse(model.acceptTelegramReturn(url))
        model.changeNumber()
        assertFalse(model.acceptTelegramReturn("murph-messaging://telegram/complete#token=$token&proof=$proof"))
        assertEquals(MessagingStage.Phone, model.state.value.stage)
        assertTrue(api.completedTokens.isEmpty())
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
        override suspend fun currentState() = AuthSessionState.SignedIn(member, true)
        override suspend fun identityToken() = "synthetic-credential"
        override suspend fun signOut() {}
        override suspend fun sendCode(method: LoginMethod, destination: String) {}
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) {}
    }
    private class Api : HostedAuthServing {
        var sends = 0; var verifiedPhone: String? = null; var linked = false
        var starts = 0; val completedTokens = mutableListOf<String>(); val completedProofs = mutableListOf<String?>()
        var failure: Exception? = null; var wait: CompletableDeferred<Unit>? = null
        override suspend fun sendMessagingPhoneCode(phone: String, credential: String) { sends++; wait?.await(); failure?.let { throw it } }
        override suspend fun verifyMessagingPhoneCode(phone: String, code: String, credential: String) { failure?.let { throw it }; verifiedPhone = phone }
        override suspend fun startMessagingTelegram(credential: String): TelegramMessagingLink { starts++; return TelegramMessagingLink("a".repeat(43), "https://t.me/synthetic_bot?start=link_${"a".repeat(43)}") }
        override suspend fun completeMessagingTelegram(token: String, proof: String?, credential: String): Boolean {
            completedTokens.add(token); completedProofs.add(proof); failure?.let { throw it }; return linked
        }
        override suspend fun sendCode(method: LoginMethod, value: String) {}
        override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession = error("unused")
        override suspend fun exchange(legacyCredential: String): HostedAuthSession = error("unused")
        override suspend fun renew(credential: String): HostedAuthSessionStatus = error("unused")
        override suspend fun revoke(credential: String) {}
    }
}
