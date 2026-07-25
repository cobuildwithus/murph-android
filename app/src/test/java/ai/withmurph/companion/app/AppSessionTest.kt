package ai.withmurph.companion.app

import android.content.Intent
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.ConnectionIntent
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppSessionTest {
    @Test
    fun signedInLaunchVerifiesMembershipWithoutCreatingAHealthConnectionBeforeConsent() = runTest {
        val fixture = fixture()

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
    }

    @Test
    fun signedInLaunchStopsBeforeHealthSetupWhenMurphAccountIsMissing() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.NoAccount

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertEquals("This sign-in isn't linked to an active Murph account.", failure.message)
        assertEquals(false, failure.canRetry)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
    }

    @Test
    fun signedInLaunchExplainsHowToRepairMissingMurphConsent() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertEquals(
            "Murph needs your latest health consent. Complete it at withmurph.ai, then try again.",
            failure.message,
        )
        assertTrue(failure.canRetry)
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun explicitConnectOwnsTheFirstHealthConnection() = runTest {
        val fixture = fixture()
        fixture.session.start()

        assertTrue(fixture.session.prepareHealthConnection())
        assertEquals(listOf(ConnectionIntent.Connect), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)

        assertTrue(fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true))
        assertEquals(1, fixture.health.connectCalls)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
    }

    @Test
    fun sharingNoHealthCategoryDoesNotMarkSetupComplete() = runTest {
        val fixture = fixture()
        fixture.session.start()

        assertTrue(fixture.session.prepareHealthConnection())
        assertEquals(false, fixture.session.completeHealthPermissionFlow(false))

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            "Choose at least one Health Connect category to connect Murph.",
            fixture.session.state.value.healthMessage,
        )
    }

    @Test
    fun completedSetupResumesAndUsesBackendReceiptAsSyncTruth() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = mapOf(
                "sleep" to CompanionSyncStatus.ResourceStatus(now.minusSeconds(600)),
            ),
        )

        fixture.session.start()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun terminalServerConnectionReturnsToExplicitReconnectState() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.signedIn = true
        fixture.api.signInError = CompanionApiException.ReconnectRequired

        fixture.session.start()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun memberSwitchClosesTheOldSdkBoundaryBeforePublishingReady() = runTest {
        val fixture = fixture(memberKey = "did:privy:new-member")
        fixture.localState.memberKey = "did:privy:old-member"
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertEquals("did:privy:new-member", fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.api.intents.isEmpty())
    }

    private fun fixture(
        now: Instant = Instant.parse("2026-07-25T18:00:00Z"),
        memberKey: String = MEMBER_KEY,
    ): Fixture {
        val auth = FakeAuth(AuthSessionState.SignedIn(memberKey, verifiedOnline = true))
        val api = FakeApi()
        val health = FakeHealth()
        val localState = FakeLocalState()
        val session = AppSession(
            auth = auth,
            api = api,
            health = health,
            localState = localState,
            config = AppConfig(
                backendBaseUrl = "https://example.test",
                environment = AppEnvironment.Sandbox,
                privyAppId = "privy-app",
                privyAppClientId = "privy-client",
                appVersion = "0.1.0",
                junctionSdkVersion = "5.0.2",
                privySdkVersion = "0.12.0",
            ),
            now = { now },
        )
        return Fixture(session, api, health, localState)
    }

    private data class Fixture(
        val session: AppSession,
        val api: FakeApi,
        val health: FakeHealth,
        val localState: FakeLocalState,
    )

    private class FakeAuth(private var state: AuthSessionState) : AuthProvider {
        override suspend fun currentState(): AuthSessionState = state
        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) = Unit
        override suspend fun identityToken(): String = "identity-token"
        override suspend fun signOut() {
            state = AuthSessionState.SignedOut
        }
    }

    private class FakeApi : CompanionApi {
        val intents = mutableListOf<ConnectionIntent>()
        val statusSources = mutableListOf<String>()
        var status = CompanionSyncStatus(lastDataReceivedAt = null, resources = emptyMap())
        var signInError: Throwable? = null
        var statusError: Throwable? = null

        override suspend fun createJunctionSignInToken(
            request: SignInTokenRequest,
        ): SignInTokenResponse {
            intents += request.connectionIntent
            signInError?.let { throw it }
            return SignInTokenResponse("junction-token", "sandbox")
        }

        override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus {
            statusSources += sourceProviderSlug
            statusError?.let { throw it }
            return status
        }
    }

    private class FakeHealth : HealthSyncing {
        override val totalResourceCount = 4
        var signedIn = false
        var identifyCalls = 0
        var configureCalls = 0
        var connectCalls = 0
        var syncCalls = 0
        var signOutCalls = 0

        override fun availability() = HealthConnectAvailability.Available
        override fun openHealthConnectIntent(): Intent? = null
        override fun isSignedIn(): Boolean = signedIn
        override fun configure() {
            configureCalls += 1
        }
        override fun isBackgroundSyncEnabled(): Boolean = false
        override fun grantedResourceCount(): Int = if (connectCalls > 0) totalResourceCount else 0

        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) {
            assertTrue(memberKey.isNotBlank())
            assertEquals("junction-token", authenticate())
            identifyCalls += 1
            signedIn = true
        }

        override suspend fun connectAfterPermissionRequest() {
            connectCalls += 1
        }

        override suspend fun refreshPermissionState() = Unit

        override suspend fun syncAllGrantedResources() {
            syncCalls += 1
        }

        override suspend fun disableBackgroundSync() = Unit

        override suspend fun signOutSdk() {
            signOutCalls += 1
            signedIn = false
        }
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation-id"
        override var memberKey: String? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null

        override fun clearMemberScopedState() {
            memberKey = null
            healthAccessRequestedAt = null
            lastKnownDataReceivedAt = null
        }
    }

    private companion object {
        const val MEMBER_KEY = "did:privy:user_123"
    }
}
