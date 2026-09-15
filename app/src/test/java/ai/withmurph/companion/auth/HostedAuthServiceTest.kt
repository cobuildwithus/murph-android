package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProviderException
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.LegacyAuthRestoring
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostedAuthServiceTest {
    private val instant = Instant.parse("2026-09-10T12:00:00Z")
    private val token = "murph_auth_v1." + "a".repeat(32)
    private val binding = HostedAuthBinding("member-a", "legacy-local-a")
    private fun issued(member: String = "member-a") = HostedAuthSession(member, token, instant.plusSeconds(2_592_000))
    private fun active(verifiedAt: Instant = instant, expiresAt: Instant = instant.plusSeconds(86_400)) =
        HostedAuthStoredState.Active(binding, token, expiresAt, verifiedAt)

    @Test fun storedSessionRestoresItsLocalOwnerWithoutInitializingPrivy() = runTest {
        val service = HostedAuthService(Api(), Store(active()), { error("SDK must not initialize") }, { instant })
        assertEquals(AuthSessionState.SignedIn("legacy-local-a", true), service.currentState())
        assertEquals(token, service.identityTokenForMember("legacy-local-a"))
    }

    @Test fun exchangePersistsBeforeReturningAndSurvivesRelaunch() = runTest {
        val store = Store()
        val api = Api()
        val service = service(api, store)
        assertEquals(token, service.identityTokenForMember("legacy-local-a"))
        assertEquals(binding, store.state?.binding)
        assertEquals("legacy-credential", api.exchangedCredential)
        val relaunched = HostedAuthService(api, store, { error("SDK must remain retired") }, { instant })
        assertEquals(token, relaunched.identityTokenForMember("legacy-local-a"))
        assertEquals(1, api.exchangeCalls)
    }

    @Test fun temporaryExchangeFailureRetainsFreshSdkCredentialAndBacksOff() = runTest {
        val api = Api().apply { exchangeResult = { throw HostedAuthException.Response(503) } }
        var now = instant
        val store = Store()
        val legacy = Legacy()
        val service = HostedAuthService(api, store, { legacy }, { now })
        repeat(3) { assertEquals("legacy-credential", service.identityToken()) }
        assertEquals(1, api.exchangeCalls)
        assertNull(store.state)
        assertEquals(3, legacy.tokenCalls)
        now = instant.plusSeconds(61)
        api.exchangeResult = { issued() }
        assertEquals(token, service.identityToken())
        assertEquals(2, api.exchangeCalls)
    }

    @Test fun failedSecureHandoffDoesNotDiscardInstalledSession() = runTest {
        val store = Store().apply { failSave = true }
        val legacy = Legacy()
        val service = service(store = store, legacy = legacy)
        assertEquals("legacy-credential", service.identityToken())
        assertNull(store.state)
        assertEquals(AuthSessionState.SignedIn("legacy-local-a", true), service.currentState())
        assertEquals(0, legacy.logoutCalls)
    }

    @Test fun simultaneousRenewalRequestsShareTheSavedFreshResult() = runTest {
        val store = Store(active(verifiedAt = instant.minusSeconds(86_401)))
        val api = Api()
        val service = service(api, store)
        assertEquals(List(20) { token }, List(20) { async { service.identityToken() } }.awaitAll())
        assertEquals(1, api.renewCalls)
        assertEquals(instant, (store.state as HostedAuthStoredState.Active).verifiedAt)
    }

    @Test fun expiredOfflineSessionIsUnverifiedAndNeverFallsBack() = runTest {
        val api = Api().apply { renewal = { throw CompanionApiException.Network } }
        val store = Store(active(instant.minusSeconds(86_400), instant.minusSeconds(1)))
        val service = HostedAuthService(api, store, { error("SDK must remain retired") }, { instant })
        assertEquals(AuthSessionState.SignedIn("legacy-local-a", false), service.currentState())
        expectFailure<CompanionApiException.LocalAuthUnavailable> { service.identityTokenForMember("legacy-local-a") }
    }

    @Test fun rejectedSessionIsDurablyRetiredWithoutSdkFallback() = runTest {
        val api = Api().apply { renewal = { throw HostedAuthException.Response(401) } }
        val store = Store(active(verifiedAt = instant.minusSeconds(86_401)))
        val service = HostedAuthService(api, store, { error("SDK must remain retired") }, { instant })
        expectFailure<HostedAuthException.Response> { service.identityToken() }
        assertEquals(HostedAuthStoredState.SignedOut(binding), store.state)
        assertSame(AuthSessionState.SignedOut, service.currentState())
    }

    @Test fun unreadableStorageDoesNotMeanAbsentStorage() = runTest {
        val store = Store().apply { failLoad = true }
        val service = HostedAuthService(Api(), store, { error("SDK must not initialize") }, { instant })
        assertSame(AuthSessionState.TemporarilyUnavailable, service.currentState())
        expectFailure<HostedAuthException> { service.identityToken() }
    }

    @Test fun explicitLoginRetiresFallbackBeforeConsumingCode() = runTest {
        val store = Store(active())
        val api = Api().apply {
            verification = {
                assertEquals(HostedAuthStoredState.SignedOut(binding), store.state)
                throw HostedAuthException.Response(400)
            }
        }
        expectFailure<AuthProviderException> { service(api, store).confirmCode(LoginMethod.Phone, "+12025550152", "123456") }
        val relaunched = HostedAuthService(api, store, { error("SDK must remain retired") }, { instant })
        assertSame(AuthSessionState.SignedOut, relaunched.currentState())
    }

    @Test fun failedRetirementWriteLeavesOneUseCodeUnconsumed() = runTest {
        val store = Store().apply { failSave = true }
        val api = Api()
        expectFailure<AuthProviderException> { service(api, store).confirmCode(LoginMethod.Email, "member@example.test", "123456") }
        assertEquals(0, api.verifyCalls)
    }

    @Test fun sameMemberLoginKeepsLocalOwnershipAndDifferentMemberGetsItsOwnKey() = runTest {
        val store = Store(active())
        val api = Api()
        val service = service(api, store)
        service.confirmCode(LoginMethod.Phone, "+12025550152", "123456")
        assertEquals(binding, store.state?.binding)
        api.verification = { issued("member-b") }
        service.confirmCode(LoginMethod.Email, "member@example.test", "123456")
        assertEquals(HostedAuthBinding("member-b", "member-b"), store.state?.binding)
    }

    @Test fun renewalCannotChangeTheCanonicalMember() = runTest {
        val store = Store(active(verifiedAt = instant.minusSeconds(86_401)))
        val api = Api().apply { renewal = { HostedAuthSessionStatus("member-b", instant.plusSeconds(86_400)) } }
        expectFailure<HostedAuthException> { service(api, store).identityToken() }
        assertEquals(binding, store.state?.binding)
    }

    @Test fun failedLogoutKeepsRetryableAuthorityUntilServerAcknowledges() = runTest {
        val store = Store(active())
        val api = Api().apply { revocation = { throw HostedAuthException.Response(503) } }
        val service = service(api, store)
        expectFailure<HostedAuthException.Response> { service.signOut() }
        assertTrue(store.state is HostedAuthStoredState.Active)
        api.revocation = {}
        service.signOut()
        assertEquals(HostedAuthStoredState.SignedOut(binding), store.state)
        assertEquals(2, api.logoutCalls)
    }

    @Test fun logoutWaitsForExchangeThenRevokesItsResultWithoutResurrection() = runTest {
        val pending = CompletableDeferred<HostedAuthSession>()
        val api = Api().apply { exchangeResult = { pending.await() } }
        val store = Store()
        val service = service(api, store)
        val exchange = async { service.identityToken() }
        runCurrent()
        val logout = async { service.signOut() }
        runCurrent()
        assertFalse(logout.isCompleted)
        pending.complete(issued())
        assertEquals(token, exchange.await())
        logout.await()
        assertEquals(token, api.revokedCredential)
        assertSame(AuthSessionState.SignedOut, service.currentState())
    }

    @Test fun exchangeRechecksSdkIdentityAfterTheServerReturns() = runTest {
        val legacy = Legacy()
        val store = Store()
        val api = Api().apply { exchangeResult = { legacy.state = AuthSessionState.SignedIn("other-local-key", true); issued() } }
        expectFailure<HostedAuthException> { service(api, store, legacy).identityToken() }
        assertNull(store.state)
    }

    private fun service(api: Api = Api(), store: Store = Store(), legacy: Legacy = Legacy()) =
        HostedAuthService(api, store, { legacy }, { instant })

    private class Store(var state: HostedAuthStoredState? = null) : HostedAuthCredentialStoring {
        var failSave = false
        var failLoad = false
        override fun load(): HostedAuthStoredState? {
            if (failLoad) throw HostedAuthException.CredentialsUnavailable
            return state
        }
        override fun save(state: HostedAuthStoredState) {
            if (failSave) throw HostedAuthException.CredentialsUnavailable
            this.state = state
        }
    }

    private class Legacy : LegacyAuthRestoring {
        var state: AuthSessionState = AuthSessionState.SignedIn("legacy-local-a", true)
        var tokenCalls = 0
        var logoutCalls = 0
        override suspend fun currentState() = state
        override suspend fun identityToken(): String { tokenCalls++; return "legacy-credential" }
        override suspend fun signOut() { logoutCalls++; state = AuthSessionState.SignedOut }
    }

    private inner class Api : HostedAuthServing {
        var exchangeCalls = 0
        var verifyCalls = 0
        var renewCalls = 0
        var logoutCalls = 0
        var exchangedCredential: String? = null
        var revokedCredential: String? = null
        var exchangeResult: suspend () -> HostedAuthSession = { issued() }
        var verification: suspend () -> HostedAuthSession = { issued() }
        var renewal: suspend () -> HostedAuthSessionStatus = { HostedAuthSessionStatus("member-a", instant.plusSeconds(2_592_000)) }
        var revocation: suspend () -> Unit = {}
        override suspend fun sendCode(method: LoginMethod, value: String) {}
        override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession { verifyCalls++; return verification() }
        override suspend fun exchange(legacyCredential: String): HostedAuthSession { exchangeCalls++; exchangedCredential = legacyCredential; return exchangeResult() }
        override suspend fun renew(credential: String): HostedAuthSessionStatus { renewCalls++; return renewal() }
        override suspend fun revoke(credential: String) { logoutCalls++; revokedCredential = credential; revocation() }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: Throwable) {
            assertTrue("Unexpected failure type: ${error.javaClass.simpleName}", error is T)
            return
        }
        fail("Expected a failure")
    }
}
