package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProviderException
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApiException
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

    @Test fun storedTransitionSessionRestoresItsLocalOwner() = runTest {
        val service = HostedAuthService(Api(), Store(active()), { instant })
        assertEquals(AuthSessionState.SignedIn("legacy-local-a", true), service.currentState())
        assertEquals(token, service.identityTokenForMember("legacy-local-a"))
    }




    @Test fun absentCredentialNeedsLoginAndAcceptsNormalOtp() = runTest {
        val store = Store()
        val api = Api()
        val service = service(api, store)
        assertSame(AuthSessionState.SignedOut, service.currentState())
        expectFailure<HostedAuthException> { service.identityToken() }
        service.confirmCode(LoginMethod.Email, "member@example.test", "123456")
        val restarted = service(api, store)
        assertEquals(AuthSessionState.SignedIn("member-a", true), restarted.currentState())
        assertEquals(token, restarted.identityToken())
        assertEquals(1, api.verifyCalls)
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
        val service = HostedAuthService(api, store, { instant })
        assertEquals(AuthSessionState.SignedIn("legacy-local-a", false), service.currentState())
        expectFailure<CompanionApiException.LocalAuthUnavailable> { service.identityTokenForMember("legacy-local-a") }
    }

    @Test fun rejectedSessionIsDurablyRetiredWithoutSdkFallback() = runTest {
        val api = Api().apply { renewal = { throw HostedAuthException.Response(401) } }
        val store = Store(active(verifiedAt = instant.minusSeconds(86_401)))
        val service = HostedAuthService(api, store, { instant })
        expectFailure<HostedAuthException.Response> { service.identityToken() }
        assertEquals(HostedAuthStoredState.SignedOut(binding), store.state)
        assertSame(AuthSessionState.SignedOut, service.currentState())
    }

    @Test fun unreadableStorageDoesNotMeanAbsentStorage() = runTest {
        val store = Store().apply { failLoad = true }
        val service = HostedAuthService(Api(), store, { instant })
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
        val relaunched = HostedAuthService(api, store, { instant })
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

    @Test fun logoutWaitsForRenewalThenRevokesWithoutResurrection() = runTest {
        val pending = CompletableDeferred<HostedAuthSessionStatus>()
        val api = Api().apply { renewal = { pending.await() } }
        val store = Store(active(verifiedAt = instant.minusSeconds(86_401)))
        val service = service(api, store)
        val renewal = async { service.identityToken() }
        runCurrent()
        val logout = async { service.signOut() }
        runCurrent()
        assertFalse(logout.isCompleted)
        pending.complete(HostedAuthSessionStatus("member-a", instant.plusSeconds(86_400)))
        assertEquals(token, renewal.await())
        logout.await()
        assertEquals(token, api.revokedCredential)
        assertSame(AuthSessionState.SignedOut, service.currentState())
    }


    private fun service(api: Api = Api(), store: Store = Store()) =
        HostedAuthService(api, store, { instant })

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

    private inner class Api : HostedAuthServing {
        var verifyCalls = 0
        var renewCalls = 0
        var logoutCalls = 0
        var revokedCredential: String? = null
        var verification: suspend () -> HostedAuthSession = { issued() }
        var renewal: suspend () -> HostedAuthSessionStatus = { HostedAuthSessionStatus("member-a", instant.plusSeconds(2_592_000)) }
        var revocation: suspend () -> Unit = {}
        override suspend fun sendCode(method: LoginMethod, value: String) {}
        override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession { verifyCalls++; return verification() }
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
