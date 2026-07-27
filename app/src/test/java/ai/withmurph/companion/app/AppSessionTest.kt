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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
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
        fixture.events.clear()

        assertTrue(fixture.session.prepareHealthConnection())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)

        assertTrue(fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true))
        assertEquals(listOf(ConnectionIntent.Connect), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.connectCalls)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
        assertEquals(
            listOf("status", "token-connect", "identify", "configure", "connect"),
            fixture.events,
        )
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
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = mapOf(
                "sleep" to CompanionSyncStatus.ResourceStatus(now.minusSeconds(600)),
            ),
        )

        fixture.session.start()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(listOf("health_connect", "health_connect"), fixture.api.statusSources)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun revokedPermissionsOverrideARecentBackendReceiptAfterReturningToTheApp() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = mapOf(
                "sleep" to CompanionSyncStatus.ResourceStatus(now.minusSeconds(600)),
            ),
        )
        fixture.session.start()
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)

        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            "Health Connect access is off. Reconnect and choose at least one category.",
            fixture.session.state.value.healthMessage,
        )
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(listOf("health_connect", "health_connect"), fixture.api.statusSources)
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

    @Test
    fun processScopedStartDoesNotRebootTheSessionForActivityRecreation() = runTest {
        val fixture = fixture()

        fixture.session.start()
        fixture.session.start()

        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun cancelledBootstrapCanBeReconciledByAReplacementActivity() = runTest {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.auth.currentStateGate = gate
        val firstStart = launch { fixture.session.start() }
        runCurrent()
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)

        firstStart.cancelAndJoin()
        fixture.auth.currentStateGate = null
        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
    }

    @Test
    fun cancelledSyncAlwaysClearsTheBusyFlag() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val gate = CompletableDeferred<Unit>()
        fixture.api.statusGate = gate
        val sync = launch { fixture.session.syncNow() }
        runCurrent()
        assertTrue(fixture.session.state.value.isSyncingHealth)

        sync.cancelAndJoin()

        assertFalse(fixture.session.state.value.isSyncingHealth)
    }

    @Test
    fun signOutWaitsForResumeThenTearsDownTheFinalJunctionIdentity() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val start = launch { fixture.session.start() }
        fixture.health.identifyEntered.await()

        val signOut = launch { fixture.session.signOut() }
        runCurrent()
        identifyGate.complete(Unit)
        start.join()
        signOut.join()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertFalse(fixture.health.signedIn)
        assertEquals("sign-out", fixture.events.last())
    }

    @Test
    fun failedJunctionTeardownCannotResumeAfterProcessReconstruction() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.intents.clear()
        val identifyCalls = fixture.health.identifyCalls
        val syncCalls = fixture.health.syncCalls
        fixture.health.signOutError = IllegalStateException("teardown failed")

        fixture.session.signOut()

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.health.signOutError = null
        recreatedSession(fixture).start()

        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
    }

    @Test
    fun failedPrivyLogoutCannotResumeAfterProcessReconstruction() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.intents.clear()
        val identifyCalls = fixture.health.identifyCalls
        val syncCalls = fixture.health.syncCalls
        fixture.auth.signOutError = IllegalStateException("logout failed")

        fixture.session.signOut()

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.auth.signOutError = null
        recreatedSession(fixture).start()

        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
    }

    @Test
    fun processDeathAfterDurableInvalidationCannotResumeJunction() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.intents.clear()
        val gate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = gate
        val signOut = launch { fixture.session.signOut() }
        fixture.health.signOutEntered.await()
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)

        val replacementHealth = FakeHealth(mutableListOf()).apply {
            signedIn = true
            grantedCount = totalResourceCount
        }
        recreatedSession(fixture, replacementHealth).start()

        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, replacementHealth.identifyCalls)
        assertEquals(0, replacementHealth.syncCalls)
        assertFalse(replacementHealth.signedIn)

        gate.complete(Unit)
        signOut.join()
    }

    @Test
    fun signOutDoesNotCrossSdkBoundariesWhenDurableInvalidationFails() = runTest {
        val fixture = completedHealthFixture()
        val priorSignOutCalls = fixture.health.signOutCalls
        fixture.localState.clearHealthAuthorizationSucceeds = false

        fixture.session.signOut()

        assertEquals(priorSignOutCalls, fixture.health.signOutCalls)
        assertTrue(fixture.auth.state is AuthSessionState.SignedIn)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
    }

    @Test
    fun unavailableRestoredAuthKeepsHealthOperationsReadOnly() = runTest {
        val fixture = fixture()
        val now = Instant.parse("2026-07-25T18:00:00Z")
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true

        fixture.session.start()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.statusSources.isEmpty())
    }

    @Test
    fun foregroundRecoveryResumesConfiguresAndIdentifiesBeforeSync() = runTest {
        val fixture = offlineRestoredFixture()

        fixture.session.start()
        assertOfflineRestoreDidNotReachHealth(fixture)
        fixture.events.clear()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(
            listOf("token-resume", "identify", "configure", "status", "sync", "status"),
            fixture.events,
        )
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)

        fixture.session.start()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
    }

    @Test
    fun explicitSyncRecoveryResumesConfiguresAndIdentifiesBeforeSync() = runTest {
        val fixture = offlineRestoredFixture()

        fixture.session.start()
        assertOfflineRestoreDidNotReachHealth(fixture)
        fixture.events.clear()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)

        fixture.session.syncNow()

        assertEquals(
            listOf("token-resume", "identify", "configure", "status", "sync", "status"),
            fixture.events,
        )
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun offlineRecoveryReconnectRequirementTearsDownBeforeSync() = runTest {
        val fixture = offlineRestoredFixture()

        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.signInError = CompanionApiException.ReconnectRequired
        fixture.events.clear()

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(listOf("token-resume", "sign-out"), fixture.events)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun revokedHealthSetupTearsDownBeforeUnavailableAuthCanRestoreReady() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(1, fixture.auth.currentStateCalls)
        assertEquals(null, fixture.localState.memberKey)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun revokedHealthSetupTeardownFailureStopsBeforeAuthRestore() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signOutError = IllegalStateException("teardown failed")

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.currentStateCalls)
        assertTrue(fixture.health.signedIn)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun backendTrustFailuresTearDownBeforeAnyHealthSync() = runTest {
        listOf(
            CompanionApiException.Unauthorized,
            CompanionApiException.NoAccount,
            CompanionApiException.ConsentRequired,
        ).forEach { failure ->
            val fixture = fixture()
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt = InstantValue(1)
            fixture.health.grantedCount = fixture.health.totalResourceCount
            fixture.api.statusError = failure

            fixture.session.start()

            assertEquals(0, fixture.health.syncCalls)
            assertEquals(1, fixture.health.signOutCalls)
            assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        }
    }

    @Test
    fun authoritativeResumeTokenFailuresTearDownThePriorJunctionIdentity() = runTest {
        listOf(
            CompanionApiException.Unauthorized to false,
            CompanionApiException.NoAccount to false,
            CompanionApiException.ConsentRequired to true,
        ).forEach { (failure, canRetry) ->
            val fixture = fixture()
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt = InstantValue(1)
            fixture.health.signedIn = true
            fixture.api.signInError = failure

            fixture.session.start()

            assertEquals(1, fixture.health.signOutCalls)
            assertFalse(fixture.health.signedIn)
            assertEquals(0, fixture.health.configureCalls)
            assertEquals(0, fixture.health.syncCalls)
            val rendered = fixture.session.state.value.phase as AppPhase.Failed
            assertEquals(canRetry, rendered.canRetry)
        }
    }

    @Test
    fun returningFromAccountDeletionChecksBackendBeforeAnotherSync() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        assertEquals(1, fixture.health.syncCalls)

        fixture.api.statusError = CompanionApiException.NoAccount
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(1, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
    }

    @Test
    fun deniedOrCancelledPermissionFlowNeverCreatesAProvisionalIdentity() = runTest {
        val denied = fixture()
        denied.session.start()
        assertTrue(denied.session.prepareHealthConnection())
        assertFalse(denied.session.completeHealthPermissionFlow(false))
        assertEquals(0, denied.health.identifyCalls)
        assertFalse(denied.health.signedIn)

        val cancelled = fixture()
        cancelled.session.start()
        assertTrue(cancelled.session.prepareHealthConnection())
        cancelled.session.cancelHealthPermissionFlow()
        assertFalse(cancelled.session.state.value.isConnectingHealth)
        assertEquals(0, cancelled.health.identifyCalls)
        assertFalse(cancelled.health.signedIn)
    }

    @Test
    fun incompletePersistedJunctionIdentityIsRemovedBeforeBootstrap() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.health.signedIn = true

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun missingFirstReceiptBecomesActionableAfterSeventyTwoHours() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(72 * 3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount

        fixture.session.start()

        assertEquals(
            HealthSyncState.NeedsAttention(lastDataReceivedAt = null),
            fixture.session.state.value.healthSync,
        )
    }

    private fun fixture(
        now: Instant = Instant.parse("2026-07-25T18:00:00Z"),
        memberKey: String = MEMBER_KEY,
    ): Fixture {
        val auth = FakeAuth(AuthSessionState.SignedIn(memberKey, verifiedOnline = true))
        val events = mutableListOf<String>()
        val api = FakeApi(events)
        val health = FakeHealth(events)
        val localState = FakeLocalState()
        val session = createSession(auth, api, health, localState, now)
        return Fixture(session, auth, api, health, localState, events)
    }

    private fun recreatedSession(
        fixture: Fixture,
        health: FakeHealth = fixture.health,
        now: Instant = Instant.parse("2026-07-25T18:00:00Z"),
    ): AppSession = createSession(
        auth = fixture.auth,
        api = fixture.api,
        health = health,
        localState = fixture.localState,
        now = now,
    )

    private fun createSession(
        auth: FakeAuth,
        api: FakeApi,
        health: FakeHealth,
        localState: FakeLocalState,
        now: Instant,
    ) = AppSession(
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

    private suspend fun completedHealthFixture(): Fixture {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.session.start()
        fixture.events.clear()
        return fixture
    }

    private fun offlineRestoredFixture(): Fixture {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        return fixture
    }

    private fun assertOfflineRestoreDidNotReachHealth(fixture: Fixture) {
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.api.intents.isEmpty())
        assertTrue(fixture.api.statusSources.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    private data class Fixture(
        val session: AppSession,
        val auth: FakeAuth,
        val api: FakeApi,
        val health: FakeHealth,
        val localState: FakeLocalState,
        val events: MutableList<String>,
    )

    private class FakeAuth(var state: AuthSessionState) : AuthProvider {
        var currentStateGate: CompletableDeferred<Unit>? = null
        var signOutError: Throwable? = null
        var currentStateCalls = 0

        override suspend fun currentState(): AuthSessionState {
            currentStateCalls += 1
            currentStateGate?.await()
            return state
        }
        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) = Unit
        override suspend fun identityToken(): String = "identity-token"
        override suspend fun signOut() {
            signOutError?.let { throw it }
            state = AuthSessionState.SignedOut
        }
    }

    private class FakeApi(private val events: MutableList<String>) : CompanionApi {
        val intents = mutableListOf<ConnectionIntent>()
        val statusSources = mutableListOf<String>()
        var status = CompanionSyncStatus(lastDataReceivedAt = null, resources = emptyMap())
        var signInError: Throwable? = null
        var statusError: Throwable? = null
        var statusGate: CompletableDeferred<Unit>? = null

        override suspend fun createJunctionSignInToken(
            request: SignInTokenRequest,
        ): SignInTokenResponse {
            intents += request.connectionIntent
            events += "token-${request.connectionIntent.wireValue}"
            signInError?.let { throw it }
            return SignInTokenResponse("junction-token", "sandbox")
        }

        override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus {
            statusSources += sourceProviderSlug
            events += "status"
            statusGate?.await()
            statusError?.let { throw it }
            return status
        }
    }

    private class FakeHealth(private val events: MutableList<String>) : HealthSyncing {
        override val totalResourceCount = 4
        var signedIn = false
        var identifyCalls = 0
        var configureCalls = 0
        var connectCalls = 0
        var syncCalls = 0
        var signOutCalls = 0
        var grantedCount = 0
        var identifyGate: CompletableDeferred<Unit>? = null
        val identifyEntered = CompletableDeferred<Unit>()
        var signOutGate: CompletableDeferred<Unit>? = null
        val signOutEntered = CompletableDeferred<Unit>()
        var signOutError: Throwable? = null
        var requireCurrentProcessSetupBeforeSync = false
        private var identifiedInCurrentProcess = false
        private var configuredInCurrentProcess = false

        override fun availability() = HealthConnectAvailability.Available
        override fun openHealthConnectIntent(): Intent? = null
        override fun isSignedIn(): Boolean = signedIn
        override fun configure() {
            configureCalls += 1
            configuredInCurrentProcess = true
            events += "configure"
        }
        override fun grantedResourceCount(): Int = grantedCount

        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) {
            assertTrue(memberKey.isNotBlank())
            assertEquals("junction-token", authenticate())
            identifyCalls += 1
            identifiedInCurrentProcess = true
            events += "identify"
            identifyEntered.complete(Unit)
            identifyGate?.await()
            signedIn = true
        }

        override suspend fun connectAfterPermissionRequest() {
            connectCalls += 1
            grantedCount = totalResourceCount
            events += "connect"
        }

        override suspend fun refreshPermissionState() = Unit

        override suspend fun syncAllGrantedResources() {
            if (requireCurrentProcessSetupBeforeSync) {
                check(identifiedInCurrentProcess) {
                    "Junction sync started before process-local identification."
                }
                check(configuredInCurrentProcess) {
                    "Junction sync started before process-local configuration."
                }
            }
            syncCalls += 1
            events += "sync"
        }

        override suspend fun signOutSdk() {
            signOutCalls += 1
            signOutEntered.complete(Unit)
            signOutGate?.await()
            signOutError?.let { throw it }
            signedIn = false
            events += "sign-out"
        }
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation-id"
        override var memberKey: String? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null
        var clearHealthAuthorizationSucceeds = true

        override fun clearHealthSetupAuthorization(): Boolean {
            if (!clearHealthAuthorizationSucceeds) return false
            healthAccessRequestedAt = null
            lastKnownDataReceivedAt = null
            return true
        }

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
