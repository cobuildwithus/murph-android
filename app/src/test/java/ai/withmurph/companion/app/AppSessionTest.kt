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
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
    fun signedInLaunchStartsNativeConsentRecoveryWhenMurphConsentIsMissing() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(listOf(MEMBER_KEY), fixture.api.launchConsentFetches)
        assertEquals(1, fixture.health.signOutCalls)
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun acceptingLaunchConsentPostsEachMissingScopeAndResumesStartupValidation() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null

        fixture.session.acceptLaunchConsent()

        assertEquals(
            listOf(LaunchConsentScope.Legal, LaunchConsentScope.HealthData),
            fixture.api.launchConsentAcceptances.map { it.second.scope },
        )
        assertEquals(
            listOf(
                mapOf("legal" to "2026-07-01"),
                mapOf("health" to "2026-07-01"),
            ),
            fixture.api.launchConsentAcceptances.map {
                it.second.acceptedDocumentVersions
            },
        )
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
        assertEquals(listOf("health_connect", "health_connect"), fixture.api.statusSources)
    }

    @Test
    fun prePermissionConsentRecoveryResumesExplicitPermissionLaunchOnlyAfterAcceptance() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.api.statusError = CompanionApiException.ConsentRequired

        assertFalse(fixture.session.prepareHealthConnection())
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(null, fixture.session.state.value.pendingHealthPermissionRequestId)

        fixture.api.statusError = null
        fixture.session.acceptLaunchConsent()

        val requestId = fixture.session.state.value.pendingHealthPermissionRequestId
        assertTrue(requestId != null)
        assertTrue(fixture.session.state.value.isConnectingHealth)
        assertTrue(fixture.session.consumeHealthPermissionLaunchRequest(requestId!!))
        assertEquals(null, fixture.session.state.value.pendingHealthPermissionRequestId)
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun postPermissionConsentRecoveryResumesExactConnectContinuation() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.api.signInError = CompanionApiException.ConsentRequired

        assertFalse(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(null, fixture.localState.healthAccessRequestedAt)

        fixture.api.signInError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(
            listOf(ConnectionIntent.Connect, ConnectionIntent.Connect),
            fixture.api.intents,
        )
        assertEquals(1, fixture.health.connectCalls)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun staleConsentDocumentAcceptanceReloadsStatusWithoutResuming() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        fixture.api.launchConsentAcceptError = CompanionApiException.StaleConsentDocuments

        fixture.session.acceptLaunchConsent()

        assertEquals(1, fixture.api.launchConsentAcceptances.size)
        assertEquals(2, fixture.api.launchConsentFetches.size)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertTrue(
            fixture.session.state.value.launchConsentRecovery?.message.orEmpty()
                .contains("changed"),
        )
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
    }

    @Test
    fun healthWorkDoesNotRunWhileLaunchConsentRecoveryIsPending() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.syncNow()
        val statusCount = fixture.api.statusSources.size
        val syncCalls = fixture.health.syncCalls

        fixture.session.syncNow()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
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
            listOf(
                "status",
                "status",
                "token-connect",
                "identify",
                "configure",
                "connect",
            ),
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
    fun olderForegroundRefreshCannotEraseANewerSettingsReturn() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = emptyMap(),
        )
        fixture.session.start()
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        val refreshCalls = fixture.health.refreshCalls
        val statusCount = fixture.api.statusSources.size
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate

        fixture.session.didEnterBackground()
        val firstForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertEquals(refreshCalls + 1, fixture.health.refreshCalls)
        assertEquals(statusCount + 1, fixture.api.statusSources.size)

        fixture.session.didEnterBackground()
        fixture.health.grantedCount = 0
        val secondForeground = async { fixture.session.didBecomeActive() }
        secondForeground.await()
        val syncCallsAfterPermissionRevocation = fixture.health.syncCalls

        assertEquals(refreshCalls + 2, fixture.health.refreshCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            HEALTH_PERMISSION_RECOVERY_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(0, fixture.session.state.value.grantedResourceCount)

        statusGate.complete(Unit)
        firstForeground.await()

        assertEquals(syncCallsAfterPermissionRevocation, fixture.health.syncCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            HEALTH_PERMISSION_RECOVERY_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(0, fixture.session.state.value.grantedResourceCount)

        fixture.session.didBecomeActive()
        assertEquals(refreshCalls + 2, fixture.health.refreshCalls)
    }

    @Test
    fun permissionRecoveryRevokesOldSetupBeforeLaunchingSystemFlow() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = emptyMap(),
        )
        fixture.session.start()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val signOutCalls = fixture.health.signOutCalls
        val tokenCount = fixture.api.intents.size

        assertTrue(fixture.session.prepareHealthConnection())

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertTrue(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun permissionRecoveryStopsBeforeSdkWhenDurableRevocationFails() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        fixture.localState.revokeHealthAuthorizationSucceeds = false
        val signOutCalls = fixture.health.signOutCalls
        val tokenCount = fixture.api.intents.size

        assertFalse(fixture.session.prepareHealthConnection())

        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)

        fixture.localState.revokeHealthAuthorizationSucceeds = true
        assertTrue(fixture.session.prepareHealthConnection())

        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertTrue(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun permissionRecoveryRetriesFailedOldIdentityTeardownBeforeSystemFlow() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        fixture.health.signOutError = IllegalStateException("teardown failed")
        val tokenCount = fixture.api.intents.size

        assertFalse(fixture.session.prepareHealthConnection())

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertFalse(fixture.session.state.value.isConnectingHealth)

        fixture.health.signOutError = null
        assertTrue(fixture.session.prepareHealthConnection())

        assertFalse(fixture.health.signedIn)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertTrue(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun permissionRecoveryConfigureFailureRollsBackFreshIdentity() = runTest {
        assertPermissionRecoveryFailureRollsBack(configureFails = true)
    }

    @Test
    fun permissionRecoveryConnectFailureRollsBackFreshIdentity() = runTest {
        assertPermissionRecoveryFailureRollsBack(configureFails = false)
    }

    @Test
    fun foregroundReturnCannotSyncWhilePermissionRecoveryIsPending() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val syncCalls = fixture.health.syncCalls
        val statusCount = fixture.api.statusSources.size

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(statusCount, fixture.api.statusSources.size)
        assertTrue(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun foregroundReturnPreservesInitialConnectIdentity() = runTest {
        assertForegroundReturnPreservesConnectIdentity(isRecovery = false)
    }

    @Test
    fun foregroundReturnPreservesPermissionRecoveryConnectIdentity() = runTest {
        assertForegroundReturnPreservesConnectIdentity(isRecovery = true)
    }

    @Test
    fun initialConnectStopsAfterStatusWhenSameMemberBecomesUnverified() = runTest {
        assertPendingCompletionStopsAfterStatusOnAuthLoss(
            isRecovery = false,
            authState = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )
    }

    @Test
    fun recoveryConnectStopsAfterStatusWhenSameMemberBecomesUnverified() = runTest {
        assertPendingCompletionStopsAfterStatusOnAuthLoss(
            isRecovery = true,
            authState = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )
    }

    @Test
    fun initialConnectStopsAfterStatusWhenAuthBecomesUnavailable() = runTest {
        assertPendingCompletionStopsAfterStatusOnAuthLoss(
            isRecovery = false,
            authState = AuthSessionState.TemporarilyUnavailable,
        )
    }

    @Test
    fun recoveryConnectStopsAfterStatusWhenAuthBecomesUnavailable() = runTest {
        assertPendingCompletionStopsAfterStatusOnAuthLoss(
            isRecovery = true,
            authState = AuthSessionState.TemporarilyUnavailable,
        )
    }

    @Test
    fun unverifiedAuthRollsBackIdentificationBeforeConfigureOrConnect() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.identifyEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        identifyGate.complete(Unit)
        assertFalse(completion.await())

        assertEquals(listOf(ConnectionIntent.Connect), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun unavailableAuthRollsBackConnectBeforePersistingSetup() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.connectEntered.await()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        connectGate.complete(Unit)
        assertFalse(completion.await())

        assertEquals(listOf(ConnectionIntent.Connect), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun initialConnectRejectsStaleVerifiedReconciliationAfterUnverifiedReturn() = runTest {
        assertStaleVerifiedReconciliationCannotReauthorizePendingConnect(
            isRecovery = false,
            finalAuthState = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )
    }

    @Test
    fun recoveryConnectRejectsStaleVerifiedReconciliationAfterUnverifiedReturn() = runTest {
        assertStaleVerifiedReconciliationCannotReauthorizePendingConnect(
            isRecovery = true,
            finalAuthState = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )
    }

    @Test
    fun initialConnectRejectsStaleVerifiedReconciliationAfterAuthBecomesUnavailable() = runTest {
        assertStaleVerifiedReconciliationCannotReauthorizePendingConnect(
            isRecovery = false,
            finalAuthState = AuthSessionState.TemporarilyUnavailable,
        )
    }

    @Test
    fun recoveryConnectRejectsStaleVerifiedReconciliationAfterAuthBecomesUnavailable() = runTest {
        assertStaleVerifiedReconciliationCannotReauthorizePendingConnect(
            isRecovery = true,
            finalAuthState = AuthSessionState.TemporarilyUnavailable,
        )
    }

    @Test
    fun foregroundReturnWaitsForRecoveryTeardownAndPreservesPermissionLaunch() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val signOutCalls = fixture.health.signOutCalls
        val tokenCount = fixture.api.intents.size
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(foreground.isCompleted)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertEquals(tokenCount, fixture.api.intents.size)
        teardownGate.complete(Unit)
        assertTrue(preparation.await())
        foreground.await()

        assertTrue(fixture.session.state.value.isConnectingHealth)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertTrue(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(ConnectionIntent.Connect, fixture.api.intents.last())
        assertEquals(tokenCount + 1, fixture.api.intents.size)
        assertEquals(1, fixture.health.connectCalls)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun signOutInvalidatesBlockedConnectAndClearsConnectingUi() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.connectEntered.await()

        val signOut = launch { fixture.session.signOut() }
        runCurrent()

        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)

        connectGate.complete(Unit)
        assertFalse(completion.await())
        signOut.join()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
    }

    @Test
    fun memberSwitchInvalidatesBlockedConnectAndClearsConnectingUi() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.connectEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = "did:privy:new-member",
            verifiedOnline = true,
        )
        fixture.session.didEnterBackground()
        val foreground = launch { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(fixture.session.state.value.isConnectingHealth)

        connectGate.complete(Unit)
        assertFalse(completion.await())
        foreground.join()

        assertEquals("did:privy:new-member", fixture.localState.memberKey)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertFalse(fixture.api.intents.contains(ConnectionIntent.Resume))
    }

    @Test
    fun signOutInvalidatesBlockedRecoveryPreparationBeforePermissionLaunch() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()

        val signOut = launch { fixture.session.signOut() }
        runCurrent()

        assertTrue(fixture.localState.signOutPending)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        teardownGate.complete(Unit)
        assertFalse(preparation.await())
        assertFalse(fixture.session.completeHealthPermissionFlow(true))
        signOut.join()

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
    }

    @Test
    fun memberSwitchInvalidatesBlockedRecoveryPreparationBeforePermissionLaunch() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = "did:privy:new-member",
            verifiedOnline = true,
        )

        fixture.session.didEnterBackground()
        val foreground = launch { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(fixture.session.state.value.isConnectingHealth)
        teardownGate.complete(Unit)
        assertFalse(preparation.await())
        foreground.join()
        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals("did:privy:new-member", fixture.localState.memberKey)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun processReconstructionCannotResumeIdentityFromIncompleteRecovery() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = launch {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.connectEntered.await()
        assertEquals(null, fixture.localState.healthAccessRequestedAt)

        val tokenCount = fixture.api.intents.size
        val replacementHealth = FakeHealth(fixture.events).apply {
            signedIn = true
            grantedCount = totalResourceCount
        }
        val replacement = recreatedSession(fixture, replacementHealth)
        replacement.start()

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(0, replacementHealth.identifyCalls)
        assertEquals(0, replacementHealth.configureCalls)
        assertEquals(0, replacementHealth.syncCalls)
        assertEquals(1, replacementHealth.signOutCalls)
        assertEquals(HealthSyncState.NotConnected, replacement.state.value.healthSync)

        completion.cancelAndJoin()
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
    fun terminalReconnectRevocationSurvivesReconstructionDuringSdkTeardown() = runTest {
        listOf(false, true).forEach { authUnavailable ->
            val fixture = fixture()
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt = InstantValue(1)
            fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
            fixture.health.signedIn = true
            fixture.health.grantedCount = fixture.health.totalResourceCount
            fixture.api.signInError = CompanionApiException.ReconnectRequired
            val teardownGate = CompletableDeferred<Unit>()
            fixture.health.signOutGate = teardownGate
            val originalStart = async { fixture.session.start() }
            fixture.health.signOutEntered.await()

            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
            val tokenCount = fixture.api.intents.size
            if (authUnavailable) {
                fixture.auth.state = AuthSessionState.TemporarilyUnavailable
            }
            val replacementHealth = FakeHealth(fixture.events).apply {
                signedIn = true
                grantedCount = totalResourceCount
            }
            val replacement = recreatedSession(fixture, replacementHealth)
            replacement.start()

            assertEquals(tokenCount, fixture.api.intents.size)
            assertEquals(1, replacementHealth.signOutCalls)
            assertFalse(replacementHealth.signedIn)
            assertEquals(0, replacementHealth.identifyCalls)
            assertEquals(0, replacementHealth.configureCalls)
            assertEquals(0, replacementHealth.syncCalls)
            assertEquals(HealthSyncState.NotConnected, replacement.state.value.healthSync)
            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
            if (authUnavailable) {
                assertTrue(replacement.state.value.phase is AppPhase.Failed)
            } else {
                assertEquals(AppPhase.Ready, replacement.state.value.phase)
            }

            originalStart.cancelAndJoin()
        }
    }

    @Test
    fun terminalReconnectCommitFailurePreservesPriorSetupWithoutSdkTeardown() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.localState.revokeHealthAuthorizationSucceeds = false
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.signInError = CompanionApiException.ReconnectRequired

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(InstantValue(2), fixture.localState.lastKnownDataReceivedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(0, fixture.health.signOutCalls)

        val replacement = recreatedSession(fixture)
        replacement.start()

        assertTrue(replacement.state.value.phase is AppPhase.Failed)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(InstantValue(2), fixture.localState.lastKnownDataReceivedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(0, fixture.health.signOutCalls)
    }

    @Test
    fun foregroundReturnResumesALostLiveJunctionSessionBeforeSync() = runTest {
        assertLostLiveJunctionSessionResumes(useForegroundReturn = true)
    }

    @Test
    fun manualSyncResumesALostLiveJunctionSessionBeforeSync() = runTest {
        assertLostLiveJunctionSessionResumes(useForegroundReturn = false)
    }

    @Test
    fun lostLiveJunctionSessionCanReturnToExplicitReconnect() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        fixture.health.loseLiveSession()
        fixture.api.signInError = CompanionApiException.ReconnectRequired
        val syncCalls = fixture.health.syncCalls

        fixture.session.syncNow()

        assertEquals(listOf("token-resume", "sign-out"), fixture.events)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun sessionLostDuringStatusPreflightResumesBeforeVendorSync() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        val statusCount = fixture.api.statusSources.size
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val sync = async { fixture.session.syncNow() }
        runCurrent()
        assertEquals(statusCount + 1, fixture.api.statusSources.size)

        fixture.health.loseLiveSession()
        statusGate.complete(Unit)
        sync.await()

        assertEquals(
            listOf(
                "status",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
            fixture.events,
        )
        assertTrue(fixture.health.signedIn)
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
    }

    @Test
    fun boundedLiveSessionRetryCannotQueueAThirdResume() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = async { fixture.session.start() }
        runCurrent()
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)

        val retryTokenGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = retryTokenGate
        fixture.api.signInGateOnCall = 2
        fixture.api.maximumSignInCalls = 2
        fixture.health.loseLiveSession()
        statusGate.complete(Unit)
        fixture.api.signInGateEntered.await()

        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        fixture.session.syncNow()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertEquals(2, fixture.api.intents.size)

        retryTokenGate.complete(Unit)
        start.await()

        assertEquals(
            listOf(
                "token-resume",
                "identify",
                "configure",
                "status",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
            fixture.events,
        )
        assertEquals(2, fixture.api.intents.size)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.health.signedIn)
    }

    @Test
    fun freshSignedOutStateTearsDownLateResumeIdentification() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val start = async { fixture.session.start() }
        fixture.health.identifyEntered.await()
        fixture.auth.state = AuthSessionState.SignedOut

        identifyGate.complete(Unit)
        start.await()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
    }

    @Test
    fun freshMemberSwitchTearsDownLateResumeIdentificationBeforePublishingNewMember() = runTest {
        val newMemberKey = "did:privy:new-member"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val start = async { fixture.session.start() }
        fixture.health.identifyEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        identifyGate.complete(Unit)
        start.await()

        assertEquals(listOf(MEMBER_KEY), fixture.health.identifiedMemberKeys)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun tokenResponseCannotBindThePreviousMemberAfterPrivySwitches() = runTest {
        val newMemberKey = "did:privy:new-member"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val tokenGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = tokenGate
        fixture.api.signInGateOnCall = 1
        val start = async { fixture.session.start() }
        fixture.api.signInGateEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        tokenGate.complete(Unit)
        start.await()

        assertEquals(listOf(MEMBER_KEY), fixture.api.tokenAuthMemberKeys)
        assertTrue(fixture.health.identifiedMemberKeys.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun foregroundPrivyLogoutWaitsForLateResumeIdentificationBeforeTeardown() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val start = async { fixture.session.start() }
        fixture.health.identifyEntered.await()
        fixture.auth.state = AuthSessionState.SignedOut

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)

        identifyGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
    }

    @Test
    fun staleReconnectRequiredCannotReplaceForegroundPrivyLogout() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val tokenGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = tokenGate
        fixture.api.signInGateOnCall = 1
        val start = async { fixture.session.start() }
        fixture.api.signInGateEntered.await()
        fixture.auth.state = AuthSessionState.SignedOut

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        fixture.api.signInError = CompanionApiException.ReconnectRequired
        tokenGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
    }

    @Test
    fun staleBootstrapFailuresCannotReplaceForegroundPrivyLogout() = runTest {
        listOf(
            CompanionApiException.NoAccount,
            CompanionApiException.ConsentRequired,
            CompanionApiException.Network,
        ).forEach { failure ->
            val fixture = fixture()
            val statusGate = CompletableDeferred<Unit>()
            fixture.api.statusGate = statusGate
            val start = async { fixture.session.start() }
            fixture.api.statusEntered.await()
            fixture.auth.state = AuthSessionState.SignedOut

            fixture.session.didEnterBackground()
            val foreground = async { fixture.session.didBecomeActive() }
            runCurrent()
            fixture.api.statusError = failure
            statusGate.complete(Unit)
            start.await()
            foreground.await()

            assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
            assertFalse(fixture.health.signedIn)
            assertEquals(null, fixture.localState.memberKey)
            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
            assertTrue(fixture.api.intents.isEmpty())
            assertEquals(0, fixture.health.identifyCalls)
            assertEquals(0, fixture.health.configureCalls)
            assertEquals(0, fixture.health.syncCalls)
            assertEquals(1, fixture.health.signOutCalls)
        }
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
    fun foregroundMemberSwitchInvalidatesBlockedPreSyncStatus() = runTest {
        val oldMemberKey = "did:privy:old-member"
        val newMemberKey = "did:privy:new-member"
        val fixture = fixture(memberKey = oldMemberKey)
        fixture.localState.memberKey = oldMemberKey
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = async { fixture.session.start() }
        fixture.api.statusEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertEquals(0, fixture.health.syncCalls)

        statusGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun foregroundMemberSwitchInvalidatesBlockedOldMemberIdentification() = runTest {
        val oldMemberKey = "did:privy:old-member"
        val newMemberKey = "did:privy:new-member"
        val fixture = fixture(memberKey = oldMemberKey)
        fixture.localState.memberKey = oldMemberKey
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate
        val start = async { fixture.session.start() }
        fixture.health.identifyEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)

        identifyGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun foregroundMemberSwitchCannotRetryOldMemberAfterInFlightSessionLoss() = runTest {
        val oldMemberKey = "did:privy:old-member"
        val newMemberKey = "did:privy:new-member"
        val fixture = fixture(memberKey = oldMemberKey)
        fixture.localState.memberKey = oldMemberKey
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 1
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncError = IllegalStateException("vendor session lost")
        fixture.health.syncErrorOnCall = 1
        fixture.health.loseSessionOnSyncError = true
        val start = async { fixture.session.start() }
        fixture.health.syncEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)

        syncGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun sameMemberInFlightSessionLossStillGetsOneBoundedRetry() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 2
        fixture.health.syncError = IllegalStateException("vendor session lost")
        fixture.health.syncErrorOnCall = 1
        fixture.health.loseSessionOnSyncError = true

        fixture.session.start()

        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Resume),
            fixture.api.intents,
        )
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
        assertEquals(2, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun queuedOnlineVerificationOwnsSameMemberSessionLossRecovery() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 2
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncError = IllegalStateException("vendor session lost")
        fixture.health.syncErrorOnCall = 1
        fixture.health.loseSessionOnSyncError = true
        val start = async { fixture.session.start() }
        fixture.health.syncEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        val verifiedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(verifiedForeground.isCompleted)

        syncGate.complete(Unit)
        start.await()
        verifiedForeground.await()

        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Resume),
            fixture.api.intents,
        )
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
        assertEquals(2, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun unavailableAuthDefersSameMemberSessionLossRecoveryUntilVerified() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 2
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncError = IllegalStateException("vendor session lost")
        fixture.health.syncErrorOnCall = 1
        fixture.health.loseSessionOnSyncError = true
        val start = async { fixture.session.start() }
        fixture.health.syncEntered.await()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        syncGate.complete(Unit)
        start.await()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Resume),
            fixture.api.intents,
        )
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
        assertEquals(2, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun delayedStatusCannotRearmOldOwnerBeforeQueuedOnlineReconciliation() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 2
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = async { fixture.session.start() }
        fixture.api.statusEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        val verifiedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(verifiedForeground.isCompleted)
        fixture.health.loseLiveSession()

        statusGate.complete(Unit)
        start.await()
        verifiedForeground.await()

        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Resume),
            fixture.api.intents,
        )
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun delayedStatusCannotStartHealthWorkWhileAuthIsUnavailable() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.maximumSignInCalls = 2
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = async { fixture.session.start() }
        fixture.api.statusEntered.await()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        fixture.health.loseLiveSession()
        statusGate.complete(Unit)
        start.await()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Resume),
            fixture.api.intents,
        )
        assertEquals(2, fixture.health.identifyCalls)
        assertEquals(2, fixture.health.configureCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
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
    fun signOutDurablyRevokesBeforeBlockedStartupCanRelease() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = launch { fixture.session.start() }
        fixture.api.statusEntered.await()
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)

        val signOut = launch { fixture.session.signOut() }
        runCurrent()

        assertTrue(fixture.localState.signOutPending)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertEquals(0, fixture.health.syncCalls)

        statusGate.complete(Unit)
        start.join()
        signOut.join()

        assertFalse(fixture.localState.signOutPending)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertFalse(fixture.health.signedIn)
        assertEquals(listOf("sign-out", "privy-sign-out"), fixture.events.takeLast(2))
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
        assertTrue(fixture.localState.signOutPending)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertEquals(0, fixture.auth.signOutCalls)

        fixture.health.signOutError = null
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertFalse(fixture.localState.signOutPending)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
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
        assertTrue(fixture.localState.signOutPending)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.auth.signOutError = null
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertFalse(fixture.localState.signOutPending)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(2, fixture.auth.signOutCalls)
        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
        assertEquals(
            listOf("sign-out", "privy-sign-out", "sign-out", "privy-sign-out"),
            fixture.events,
        )
    }

    @Test
    fun reconstructedProcessFinishesPendingSignOutWithoutRestoringHealth() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val start = launch { fixture.session.start() }
        fixture.api.statusEntered.await()
        val signOut = launch { fixture.session.signOut() }
        runCurrent()
        assertTrue(fixture.localState.signOutPending)

        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val authStateChecks = fixture.auth.currentStateCalls
        val replacementHealth = FakeHealth(fixture.events).apply {
            signedIn = true
            grantedCount = totalResourceCount
        }
        val replacement = recreatedSession(fixture, replacementHealth)
        replacement.start()

        assertFalse(fixture.localState.signOutPending)
        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(authStateChecks, fixture.auth.currentStateCalls)
        assertEquals(0, replacementHealth.identifyCalls)
        assertEquals(0, replacementHealth.configureCalls)
        assertEquals(0, replacementHealth.syncCalls)
        assertEquals(1, replacementHealth.signOutCalls)
        assertFalse(replacementHealth.signedIn)
        assertEquals(listOf("sign-out", "privy-sign-out"), fixture.events.takeLast(2))

        statusGate.complete(Unit)
        start.join()
        signOut.join()
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun signOutDoesNotCrossSdkBoundariesWhenPendingWriteFails() = runTest {
        val fixture = completedHealthFixture()
        val priorSignOutCalls = fixture.health.signOutCalls
        fixture.localState.beginSignOutSucceeds = false

        fixture.session.signOut()

        assertFalse(fixture.localState.signOutPending)
        assertEquals(priorSignOutCalls, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
        assertTrue(fixture.auth.state is AuthSessionState.SignedIn)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(priorSignOutCalls, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
        assertFalse(fixture.localState.signOutPending)
    }

    @Test
    fun memberStateWriteFailureKeepsPendingSignOutForStartupRetry() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.intents.clear()
        fixture.localState.completeSignOutSucceeds = false

        fixture.session.signOut()

        assertTrue(fixture.localState.signOutPending)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.localState.completeSignOutSucceeds = true
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertFalse(fixture.localState.signOutPending)
        assertEquals(null, fixture.localState.memberKey)
        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun signOutBoundaryStopsStatusAndSyncAfterBlockedAuthCheck() = runTest {
        val fixture = completedHealthFixture()
        val statusCount = fixture.api.statusSources.size
        val syncCount = fixture.health.syncCalls
        val authGate = CompletableDeferred<Unit>()
        fixture.auth.currentStateGate = authGate
        val sync = launch { fixture.session.syncNow() }
        runCurrent()
        assertTrue(fixture.session.state.value.isSyncingHealth)

        val signOut = launch { fixture.session.signOut() }
        runCurrent()
        assertTrue(fixture.localState.signOutPending)
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)

        authGate.complete(Unit)
        sync.join()
        signOut.join()

        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(syncCount, fixture.health.syncCalls)
        assertFalse(fixture.localState.signOutPending)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
    }

    @Test
    fun unavailableRestoredAuthKeepsHealthOperationsReadOnly() = runTest {
        val fixture = fixture()
        val now = Instant.parse("2026-07-25T18:00:00Z")
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(now.minusSeconds(600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true

        fixture.session.start()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertEquals(fixture.health.totalResourceCount, fixture.session.state.value.grantedResourceCount)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.statusSources.isEmpty())
    }

    @Test
    fun unavailableAuthStillReconcilesCompletePermissionRevocation() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.status = CompanionSyncStatus(now.minusSeconds(600), emptyMap())
        fixture.session.start()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.health.grantedCount = 0
        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val syncCalls = fixture.health.syncCalls
        val refreshCalls = fixture.health.refreshCalls

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(HEALTH_PERMISSION_RECOVERY_MESSAGE, fixture.session.state.value.healthMessage)
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(refreshCalls + 1, fixture.health.refreshCalls)
    }

    @Test
    fun unavailableAuthColdRestoreCannotResurrectSyncedAfterRevocation() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(now.minusSeconds(600).toEpochMilli())
        fixture.health.signedIn = true
        fixture.health.grantedCount = 0

        fixture.session.start()

        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(HEALTH_PERMISSION_RECOVERY_MESSAGE, fixture.session.state.value.healthMessage)
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertTrue(fixture.api.intents.isEmpty())
        assertTrue(fixture.api.statusSources.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun noSetupOfflineRecoveryRevalidatesMissingAccountBeforePermissions() = runTest {
        assertNoSetupOfflineRecoveryFailure(
            failure = CompanionApiException.NoAccount,
            expectedMessage = "This sign-in isn't linked to an active Murph account.",
        )
    }

    @Test
    fun noSetupOfflineRecoveryStartsConsentRecoveryBeforePermissions() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(listOf(MEMBER_KEY), fixture.api.launchConsentFetches)
        assertFalse(fixture.session.prepareHealthConnection())
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun noSetupOfflineRecoveryEnablesPermissionsOnlyAfterBackendValidation() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.session.start()
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.api.statusSources.isEmpty())
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertTrue(fixture.session.prepareHealthConnection())
        assertTrue(fixture.api.intents.isEmpty())
        fixture.session.cancelHealthPermissionFlow()
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

        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.start()

        assertEquals(0, fixture.health.syncCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
    }

    @Test
    fun authoritativeResumeTokenFailuresTearDownThePriorJunctionIdentity() = runTest {
        listOf(
            CompanionApiException.Unauthorized to false,
            CompanionApiException.NoAccount to false,
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

        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.api.signInError = CompanionApiException.ConsentRequired

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
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
    fun initialSetupRevalidatesBackendAuthorityBeforeSystemPermissions() = runTest {
        listOf(
            CompanionApiException.NoAccount,
            CompanionApiException.ConsentRequired,
            CompanionApiException.Unauthorized,
        ).forEach { failure ->
            assertPreparationBackendFailureKeepsPermissionsClosed(
                isRecovery = false,
                failure = failure,
            )
        }
    }

    @Test
    fun permissionRecoveryRevalidatesBackendBeforeRevokingPriorSetup() = runTest {
        listOf(
            CompanionApiException.NoAccount,
            CompanionApiException.ConsentRequired,
            CompanionApiException.Unauthorized,
        ).forEach { failure ->
            assertPreparationBackendFailureKeepsPermissionsClosed(
                isRecovery = true,
                failure = failure,
            )
        }
    }

    @Test
    fun changedOrUnavailablePrivySessionCannotLaunchSystemPermissions() = runTest {
        listOf(
            AuthSessionState.SignedIn("did:privy:different-member", verifiedOnline = true),
            AuthSessionState.TemporarilyUnavailable,
            AuthSessionState.SignedOut,
        ).forEach { authState ->
            val fixture = fixture()
            fixture.session.start()
            fixture.auth.state = authState
            val tokenCount = fixture.api.intents.size
            val statusCount = fixture.api.statusSources.size

            assertFalse(fixture.session.prepareHealthConnection())
            assertFalse(fixture.session.completeHealthPermissionFlow(true))

            assertEquals(tokenCount, fixture.api.intents.size)
            assertEquals(statusCount, fixture.api.statusSources.size)
            assertEquals(0, fixture.health.identifyCalls)
            assertEquals(0, fixture.health.configureCalls)
            assertEquals(0, fixture.health.connectCalls)
            assertEquals(0, fixture.health.syncCalls)
            assertFalse(fixture.session.state.value.isConnectingHealth)
        }
    }

    @Test
    fun delayedPreparationRejectsFreshSignedOutState() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedOut,
            )
        }
    }

    @Test
    fun delayedPreparationRejectsFreshMemberSwitch() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedIn(
                    "did:privy:new-member",
                    verifiedOnline = true,
                ),
            )
        }
    }

    @Test
    fun delayedPreparationRejectsFreshSameMemberUnverifiedState() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedIn(
                    MEMBER_KEY,
                    verifiedOnline = false,
                ),
            )
        }
    }

    @Test
    fun delayedPreparationRejectsFreshUnavailableAuth() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.TemporarilyUnavailable,
            )
        }
    }

    @Test
    fun teardownDelayedPreparationRejectsFreshSignedOutState() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertTeardownDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedOut,
            )
        }
    }

    @Test
    fun teardownDelayedPreparationRejectsFreshMemberSwitch() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertTeardownDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedIn(
                    "did:privy:new-member",
                    verifiedOnline = true,
                ),
            )
        }
    }

    @Test
    fun teardownDelayedPreparationRejectsFreshSameMemberUnverifiedState() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertTeardownDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.SignedIn(
                    MEMBER_KEY,
                    verifiedOnline = false,
                ),
            )
        }
    }

    @Test
    fun teardownDelayedPreparationRejectsFreshUnavailableAuth() = runTest {
        listOf(false, true).forEach { isRecovery ->
            assertTeardownDelayedPreparationRejectsAuthLoss(
                isRecovery = isRecovery,
                authState = AuthSessionState.TemporarilyUnavailable,
            )
        }
    }

    @Test
    fun foregroundMemberSwitchOwnsRecoveryAfterInvalidatingBlockedTeardown() = runTest {
        val newMemberKey = "did:privy:new-member"
        val fixture = completedHealthFixture()
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        fixture.api.maximumStatusCalls = statusCount + 2
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        teardownGate.complete(Unit)
        assertFalse(preparation.await())
        foreground.await()

        assertEquals(statusCount + 2, fixture.api.statusSources.size)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun preparationMemberSwitchOwnsBootstrapBeforeForegroundReturn() = runTest {
        val newMemberKey = "did:privy:new-member"
        val fixture = completedHealthFixture()
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        fixture.api.maximumStatusCalls = statusCount + 2
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(newMemberKey, verifiedOnline = true)
        val bootstrapGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = bootstrapGate

        teardownGate.complete(Unit)
        fixture.api.statusGateEntered.await()
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertEquals(newMemberKey, fixture.localState.memberKey)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(foreground.isCompleted)
        assertEquals(statusCount + 2, fixture.api.statusSources.size)
        bootstrapGate.complete(Unit)
        assertFalse(preparation.await())
        foreground.await()

        assertEquals(statusCount + 2, fixture.api.statusSources.size)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(newMemberKey, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
    }

    @Test
    fun foregroundPermissionRefreshWaitsForLaunchingReconciliationOwner() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(now.minusSeconds(600).toEpochMilli())
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.actualGrantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(now.minusSeconds(600), emptyMap())
        fixture.session.start()
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        val resumeGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = resumeGate
        fixture.api.signInGateOnCall = 1
        fixture.api.maximumSignInCalls = 1
        fixture.api.maximumStatusCalls = 2
        val reconciliation = async { fixture.session.syncNow() }
        fixture.api.signInGateEntered.await()
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)

        fixture.health.actualGrantedCount = 0
        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(foreground.isCompleted)
        assertEquals(0, fixture.health.refreshCalls)

        resumeGate.complete(Unit)
        reconciliation.await()
        foreground.await()

        assertEquals(1, fixture.api.intents.size)
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(2, fixture.api.statusSources.size)
        assertEquals(1, fixture.health.refreshCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertEquals(
            HEALTH_PERMISSION_RECOVERY_MESSAGE,
            fixture.session.state.value.healthMessage,
        )

        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls

        fixture.session.syncNow()

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
    }

    @Test
    fun unavailableAuthorityCannotRestoreCachedSyncedAfterCompleteRevocation() = runTest {
        listOf(true, false).forEach { authUnavailable ->
            val now = Instant.parse("2026-07-25T18:00:00Z")
            val fixture = fixture(now = now)
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt =
                InstantValue(now.minusSeconds(3_600).toEpochMilli())
            fixture.health.grantedCount = fixture.health.totalResourceCount
            fixture.api.status = CompanionSyncStatus(
                lastDataReceivedAt = now.minusSeconds(600),
                resources = emptyMap(),
            )
            fixture.session.start()
            assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
            fixture.health.grantedCount = 0
            val tokenCount = fixture.api.intents.size
            val identifyCalls = fixture.health.identifyCalls
            val configureCalls = fixture.health.configureCalls
            val connectCalls = fixture.health.connectCalls
            val syncCalls = fixture.health.syncCalls
            if (authUnavailable) {
                fixture.auth.state = AuthSessionState.TemporarilyUnavailable
            } else {
                fixture.api.statusError = CompanionApiException.Network
            }

            assertFalse(fixture.session.prepareHealthConnection())

            assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
            assertEquals(
                HEALTH_PERMISSION_RECOVERY_MESSAGE,
                fixture.session.state.value.healthMessage,
            )
            assertEquals(0, fixture.session.state.value.grantedResourceCount)
            assertEquals(tokenCount, fixture.api.intents.size)
            assertEquals(identifyCalls, fixture.health.identifyCalls)
            assertEquals(configureCalls, fixture.health.configureCalls)
            assertEquals(connectCalls, fixture.health.connectCalls)
            assertEquals(syncCalls, fixture.health.syncCalls)
        }
    }

    @Test
    fun manualSyncRepairsContradictorySyncedStateWithoutCrossingBoundaries() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            resources = emptyMap(),
        )
        fixture.session.start()
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        fixture.health.grantedCount = 0
        val tokenCount = fixture.api.intents.size
        val statusCount = fixture.api.statusSources.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val syncCalls = fixture.health.syncCalls

        fixture.session.syncNow()

        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            HEALTH_PERMISSION_RECOVERY_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
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
    fun foregroundReturnStillRemovesAGenuinelyOrphanedJunctionIdentity() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.health.signedIn = true
        val signOutCalls = fixture.health.signOutCalls
        val tokenCount = fixture.api.intents.size

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.isConnectingHealth)
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

    @Test
    fun receiptFromPreviousSetupCannotMakeFreshConnectionSynced() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val oldReceipt = now.minusSeconds(600)
        val fixture = fixture(now = now)
        fixture.api.status = CompanionSyncStatus(oldReceipt, emptyMap())
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        assertTrue(fixture.session.completeHealthPermissionFlow(true))
        fixture.health.syncError = IllegalStateException("vendor sync failed")

        fixture.session.syncNow()

        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)

        fixture.api.status = CompanionSyncStatus(now, emptyMap())
        fixture.session.syncNow()

        assertEquals(HealthSyncState.Synced(now), fixture.session.state.value.healthSync)
        assertEquals(InstantValue(now.toEpochMilli()), fixture.localState.lastKnownDataReceivedAt)

        fixture.localState.lastKnownDataReceivedAt = InstantValue(oldReceipt.toEpochMilli())
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertEquals(HealthSyncState.AwaitingFirstData, replacement.state.value.healthSync)
    }

    private suspend fun assertPermissionRecoveryFailureRollsBack(configureFails: Boolean) {
        val fixture = completedHealthFixture()
        fixture.health.grantedCount = 0
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertTrue(fixture.session.prepareHealthConnection())
        if (configureFails) {
            fixture.health.configureError = IllegalStateException("configure failed")
        } else {
            fixture.health.connectError = IllegalStateException("connect failed")
        }

        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.health.signedIn)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        val tokenCount = fixture.api.intents.size
        val syncCalls = fixture.health.syncCalls

        val replacement = recreatedSession(fixture)
        replacement.start()

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(HealthSyncState.NotConnected, replacement.state.value.healthSync)
    }

    private suspend fun assertLostLiveJunctionSessionResumes(useForegroundReturn: Boolean) {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        fixture.health.loseLiveSession()
        val tokenCount = fixture.api.intents.size
        val syncCalls = fixture.health.syncCalls

        if (useForegroundReturn) {
            fixture.session.didEnterBackground()
            fixture.session.didBecomeActive()
        } else {
            fixture.session.syncNow()
        }

        assertEquals(
            listOf("token-resume", "identify", "configure", "status", "sync", "status"),
            fixture.events,
        )
        assertEquals(tokenCount + 1, fixture.api.intents.size)
        assertEquals(ConnectionIntent.Resume, fixture.api.intents.last())
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    private suspend fun assertPreparationBackendFailureKeepsPermissionsClosed(
        isRecovery: Boolean,
        failure: CompanionApiException,
    ) {
        val fixture = if (isRecovery) {
            completedHealthFixture().also {
                it.health.grantedCount = 0
                it.session.didEnterBackground()
                it.session.didBecomeActive()
            }
        } else {
            fixture().also { it.session.start() }
        }
        val requestedAt = fixture.localState.healthAccessRequestedAt
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        fixture.api.statusError = failure

        assertFalse(fixture.session.prepareHealthConnection())
        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        if (failure == CompanionApiException.ConsentRequired) {
            assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
            assertEquals(
                LaunchConsentRecoveryPhase.Required,
                fixture.session.state.value.launchConsentRecovery?.phase,
            )
            assertEquals(listOf(MEMBER_KEY), fixture.api.launchConsentFetches)
        } else {
            assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        }
    }

    private suspend fun assertDelayedPreparationRejectsAuthLoss(
        isRecovery: Boolean,
        authState: AuthSessionState,
    ) = coroutineScope {
        val fixture = if (isRecovery) {
            completedHealthFixture().also {
                it.localState.lastKnownDataReceivedAt = InstantValue(2)
                it.health.grantedCount = 0
                it.session.didEnterBackground()
                it.session.didBecomeActive()
            }
        } else {
            fixture().also { it.session.start() }
        }
        val requestedAt = fixture.localState.healthAccessRequestedAt
        val receipt = fixture.localState.lastKnownDataReceivedAt
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        val signOutCalls = fixture.health.signOutCalls
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.api.statusGateEntered.await()
        fixture.auth.state = authState

        statusGate.complete(Unit)
        assertFalse(preparation.await())

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        when (authState) {
            AuthSessionState.SignedOut -> {
                assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
                assertFalse(fixture.health.signedIn)
                assertEquals(null, fixture.localState.memberKey)
                assertEquals(null, fixture.localState.healthAccessRequestedAt)
                assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
                assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
            }
            AuthSessionState.TemporarilyUnavailable -> {
                assertEquals(signOutCalls, fixture.health.signOutCalls)
                assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
                assertEquals(receipt, fixture.localState.lastKnownDataReceivedAt)
                assertFalse(fixture.session.state.value.authVerifiedOnline)
                assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
            }
            is AuthSessionState.SignedIn -> {
                if (authState.memberKey == MEMBER_KEY) {
                    assertEquals(signOutCalls, fixture.health.signOutCalls)
                    assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
                    assertEquals(receipt, fixture.localState.lastKnownDataReceivedAt)
                    assertFalse(fixture.session.state.value.authVerifiedOnline)
                    assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
                } else {
                    assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
                    assertFalse(fixture.health.signedIn)
                    assertEquals(authState.memberKey, fixture.localState.memberKey)
                    assertEquals(null, fixture.localState.healthAccessRequestedAt)
                    assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
                    assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
                    assertEquals(
                        HealthSyncState.NotConnected,
                        fixture.session.state.value.healthSync,
                    )
                }
            }
        }
    }

    private suspend fun assertTeardownDelayedPreparationRejectsAuthLoss(
        isRecovery: Boolean,
        authState: AuthSessionState,
    ) = coroutineScope {
        val fixture = if (isRecovery) {
            completedHealthFixture().also {
                it.health.grantedCount = 0
                it.session.didEnterBackground()
                it.session.didBecomeActive()
            }
        } else {
            fixture().also {
                it.session.start()
                it.health.signedIn = true
            }
        }
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        val signOutCalls = fixture.health.signOutCalls
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate
        val preparation = async { fixture.session.prepareHealthConnection() }
        fixture.health.signOutEntered.await()
        fixture.auth.state = authState

        teardownGate.complete(Unit)
        assertFalse(preparation.await())

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        when (authState) {
            AuthSessionState.SignedOut -> {
                assertEquals(signOutCalls + 2, fixture.health.signOutCalls)
                assertEquals(null, fixture.localState.memberKey)
                assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
            }
            AuthSessionState.TemporarilyUnavailable -> {
                assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
                assertEquals(MEMBER_KEY, fixture.localState.memberKey)
                assertFalse(fixture.session.state.value.authVerifiedOnline)
                assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
                assertEquals(
                    HealthSyncState.NotConnected,
                    fixture.session.state.value.healthSync,
                )
            }
            is AuthSessionState.SignedIn -> {
                if (authState.memberKey == MEMBER_KEY) {
                    assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
                    assertEquals(MEMBER_KEY, fixture.localState.memberKey)
                    assertFalse(fixture.session.state.value.authVerifiedOnline)
                    assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
                } else {
                    assertEquals(signOutCalls + 2, fixture.health.signOutCalls)
                    assertEquals(authState.memberKey, fixture.localState.memberKey)
                    assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
                    assertEquals(
                        HealthSyncState.NotConnected,
                        fixture.session.state.value.healthSync,
                    )
                }
            }
        }
    }

    private suspend fun assertForegroundReturnPreservesConnectIdentity(
        isRecovery: Boolean,
    ) = coroutineScope {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        if (isRecovery) {
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt =
                InstantValue(now.minusSeconds(3_600).toEpochMilli())
            fixture.health.signedIn = true
            fixture.health.grantedCount = 0
        }
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.connectEntered.await()
        val signOutCalls = fixture.health.signOutCalls
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val refreshCalls = fixture.health.refreshCalls
        val syncCalls = fixture.health.syncCalls

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(refreshCalls, fixture.health.refreshCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertTrue(fixture.session.state.value.isConnectingHealth)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)

        connectGate.complete(Unit)
        assertTrue(completion.await())

        assertEquals(InstantValue(now.toEpochMilli()), fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
    }

    private suspend fun assertPendingCompletionStopsAfterStatusOnAuthLoss(
        isRecovery: Boolean,
        authState: AuthSessionState,
    ) = coroutineScope {
        val fixture = if (isRecovery) {
            completedHealthFixture().also {
                it.health.grantedCount = 0
                it.session.didEnterBackground()
                it.session.didBecomeActive()
            }
        } else {
            fixture().also { it.session.start() }
        }
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        assertTrue(fixture.session.prepareHealthConnection())
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.api.statusGateEntered.await()
        fixture.auth.state = authState

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        statusGate.complete(Unit)
        assertFalse(completion.await())

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    private suspend fun assertStaleVerifiedReconciliationCannotReauthorizePendingConnect(
        isRecovery: Boolean,
        finalAuthState: AuthSessionState,
    ) = coroutineScope {
        val fixture = if (isRecovery) {
            completedHealthFixture().also {
                it.health.grantedCount = 0
                it.session.didEnterBackground()
                it.session.didBecomeActive()
            }
        } else {
            fixture().also { it.session.start() }
        }
        assertTrue(fixture.session.prepareHealthConnection())
        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val connectCalls = fixture.health.connectCalls
        val syncCalls = fixture.health.syncCalls
        val permissionStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = permissionStatusGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.api.statusGateEntered.await()
        val reconciliationStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = reconciliationStatusGate
        fixture.api.statusGateEntered = CompletableDeferred()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false)
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        val verifiedForeground = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()
        fixture.auth.state = finalAuthState
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertFalse(fixture.session.state.value.authVerifiedOnline)

        reconciliationStatusGate.complete(Unit)
        verifiedForeground.await()

        assertFalse(fixture.session.state.value.authVerifiedOnline)
        permissionStatusGate.complete(Unit)
        assertFalse(completion.await())

        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(connectCalls, fixture.health.connectCalls)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    private suspend fun assertNoSetupOfflineRecoveryFailure(
        failure: CompanionApiException,
        expectedMessage: String,
    ) {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.session.start()
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.api.statusSources.isEmpty())
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusError = failure

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val rendered = fixture.session.state.value.phase as AppPhase.Failed
        assertEquals(expectedMessage, rendered.message)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertFalse(fixture.session.prepareHealthConnection())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    private fun fixture(
        now: Instant = Instant.parse("2026-07-25T18:00:00Z"),
        memberKey: String = MEMBER_KEY,
    ): Fixture {
        val events = mutableListOf<String>()
        val auth = FakeAuth(
            state = AuthSessionState.SignedIn(memberKey, verifiedOnline = true),
            events = events,
        )
        val api = FakeApi(events) { auth.state }
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

    private class FakeAuth(
        var state: AuthSessionState,
        private val events: MutableList<String>,
    ) : AuthProvider {
        var currentStateGate: CompletableDeferred<Unit>? = null
        var signOutError: Throwable? = null
        var currentStateCalls = 0
        var signOutCalls = 0

        override suspend fun currentState(): AuthSessionState {
            currentStateCalls += 1
            currentStateGate?.await()
            return state
        }
        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) = Unit
        override suspend fun identityToken(): String = "identity-token"
        override suspend fun signOut() {
            signOutCalls += 1
            events += "privy-sign-out"
            signOutError?.let { throw it }
            state = AuthSessionState.SignedOut
        }
    }

    private class FakeApi(
        private val events: MutableList<String>,
        private val currentAuthState: () -> AuthSessionState,
    ) : CompanionApi {
        val intents = mutableListOf<ConnectionIntent>()
        val tokenAuthMemberKeys = mutableListOf<String?>()
        val statusSources = mutableListOf<String>()
        var status = CompanionSyncStatus(lastDataReceivedAt = null, resources = emptyMap())
        var signInError: Throwable? = null
        var statusError: Throwable? = null
        var statusGate: CompletableDeferred<Unit>? = null
        val statusEntered = CompletableDeferred<Unit>()
        var statusGateEntered = CompletableDeferred<Unit>()
        var maximumStatusCalls: Int? = null
        var signInGate: CompletableDeferred<Unit>? = null
        var signInGateOnCall: Int? = null
        var maximumSignInCalls: Int? = null
        val signInGateEntered = CompletableDeferred<Unit>()
        var launchConsentStatus = launchConsentStatus(granted = false)
        var launchConsentFetchError: Throwable? = null
        var launchConsentAcceptError: Throwable? = null
        val launchConsentFetches = mutableListOf<String>()
        val launchConsentAcceptances =
            mutableListOf<Pair<String, LaunchConsentAcceptanceRequest>>()

        override suspend fun createJunctionSignInToken(
            request: SignInTokenRequest,
        ): SignInTokenResponse {
            intents += request.connectionIntent
            tokenAuthMemberKeys +=
                (currentAuthState() as? AuthSessionState.SignedIn)?.memberKey
            events += "token-${request.connectionIntent.wireValue}"
            maximumSignInCalls?.let { maximum ->
                assertTrue(
                    "Unexpected extra Junction sign-in token request.",
                    intents.size <= maximum,
                )
            }
            if (intents.size == signInGateOnCall) {
                signInGateEntered.complete(Unit)
                signInGate?.await()
            }
            signInError?.let { throw it }
            return SignInTokenResponse("junction-token", "sandbox")
        }

        override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus {
            statusSources += sourceProviderSlug
            events += "status"
            maximumStatusCalls?.let { maximum ->
                assertTrue(
                    "Unexpected extra companion status request.",
                    statusSources.size <= maximum,
                )
            }
            statusEntered.complete(Unit)
            statusGate?.let { gate ->
                statusGateEntered.complete(Unit)
                gate.await()
            }
            statusError?.let { throw it }
            return status
        }

        override suspend fun fetchLaunchConsentStatus(memberKey: String): LaunchConsentStatus {
            launchConsentFetches += memberKey
            events += "consent-get"
            launchConsentFetchError?.let { throw it }
            return launchConsentStatus
        }

        override suspend fun acceptLaunchConsent(
            memberKey: String,
            request: LaunchConsentAcceptanceRequest,
        ): LaunchConsentStatus {
            launchConsentAcceptances += memberKey to request
            events += "consent-post:${request.scope.wireValue}"
            launchConsentAcceptError?.let { throw it }
            val launchScopes = launchConsentStatus.launchScopes.map {
                if (it.scope == request.scope) {
                    it.copy(granted = true, missingDocuments = emptyList())
                } else {
                    it
                }
            }
            launchConsentStatus = launchConsentStatus.copy(
                launchGranted = launchScopes.all { it.granted },
                launchScopes = launchScopes,
            )
            return launchConsentStatus
        }
    }

    private class FakeHealth(private val events: MutableList<String>) : HealthSyncing {
        override val totalResourceCount = 4
        var signedIn = false
        var identifyCalls = 0
        val identifiedMemberKeys = mutableListOf<String>()
        var configureCalls = 0
        var connectCalls = 0
        var syncCalls = 0
        var refreshCalls = 0
        var signOutCalls = 0
        var grantedCount = 0
        var actualGrantedCount: Int? = null
        var identifyGate: CompletableDeferred<Unit>? = null
        val identifyEntered = CompletableDeferred<Unit>()
        var signOutGate: CompletableDeferred<Unit>? = null
        val signOutEntered = CompletableDeferred<Unit>()
        var signOutError: Throwable? = null
        var configureError: Throwable? = null
        var connectError: Throwable? = null
        var syncError: Throwable? = null
        var syncErrorOnCall: Int? = null
        var loseSessionOnSyncError = false
        var connectGate: CompletableDeferred<Unit>? = null
        val connectEntered = CompletableDeferred<Unit>()
        var syncGate: CompletableDeferred<Unit>? = null
        val syncEntered = CompletableDeferred<Unit>()
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
            configureError?.let { throw it }
        }
        override fun grantedResourceCount(): Int = grantedCount

        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) {
            assertTrue(memberKey.isNotBlank())
            assertEquals("junction-token", authenticate())
            identifyCalls += 1
            identifiedMemberKeys += memberKey
            identifiedInCurrentProcess = true
            events += "identify"
            identifyEntered.complete(Unit)
            identifyGate?.await()
            signedIn = true
        }

        override suspend fun connectAfterPermissionRequest() {
            connectCalls += 1
            events += "connect"
            connectEntered.complete(Unit)
            connectGate?.await()
            connectError?.let { throw it }
            grantedCount = totalResourceCount
        }

        override suspend fun refreshPermissionState() {
            refreshCalls += 1
            actualGrantedCount?.let { grantedCount = it }
        }

        override suspend fun syncAllGrantedResources() {
            if (requireCurrentProcessSetupBeforeSync) {
                check(signedIn) {
                    "Junction sync started while the live session was signed out."
                }
                check(identifiedInCurrentProcess) {
                    "Junction sync started before process-local identification."
                }
                check(configuredInCurrentProcess) {
                    "Junction sync started before process-local configuration."
                }
            }
            syncCalls += 1
            events += "sync"
            syncEntered.complete(Unit)
            syncGate?.await()
            syncError?.takeIf {
                syncErrorOnCall == null || syncErrorOnCall == syncCalls
            }?.let { error ->
                if (loseSessionOnSyncError) loseLiveSession()
                throw error
            }
        }

        fun loseLiveSession() {
            signedIn = false
            identifiedInCurrentProcess = false
            configuredInCurrentProcess = false
        }

        override suspend fun signOutSdk() {
            signOutCalls += 1
            signOutEntered.complete(Unit)
            signOutGate?.await()
            signOutError?.let { throw it }
            signedIn = false
            identifiedInCurrentProcess = false
            configuredInCurrentProcess = false
            events += "sign-out"
        }
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation-id"
        override var memberKey: String? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null
        override var signOutPending = false
            private set
        var revokeHealthAuthorizationSucceeds = true
        var beginSignOutSucceeds = true
        var completeSignOutSucceeds = true

        override fun revokeHealthSetupAuthorization(): Boolean {
            if (!revokeHealthAuthorizationSucceeds) return false
            healthAccessRequestedAt = null
            lastKnownDataReceivedAt = null
            return true
        }

        override fun beginSignOut(): Boolean {
            if (!beginSignOutSucceeds) return false
            signOutPending = true
            healthAccessRequestedAt = null
            lastKnownDataReceivedAt = null
            return true
        }

        override fun completeSignOut(): Boolean {
            if (!completeSignOutSucceeds) return false
            signOutPending = false
            clearMemberScopedState()
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
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."

        fun launchConsentStatus(granted: Boolean): LaunchConsentStatus {
            val legal = LaunchConsentDocument(
                id = "legal",
                title = "Terms",
                version = "2026-07-01",
                href = "https://example.test/legal",
                pdfHref = null,
            )
            val health = LaunchConsentDocument(
                id = "health",
                title = "Health Notice",
                version = "2026-07-01",
                href = "https://example.test/health",
                pdfHref = null,
            )
            return LaunchConsentStatus(
                launchGranted = granted,
                documents = listOf(legal, health),
                launchScopes = listOf(
                    LaunchConsentScopeStatus(
                        scope = LaunchConsentScope.Legal,
                        granted = granted,
                        missingDocuments = if (granted) emptyList() else listOf(legal),
                    ),
                    LaunchConsentScopeStatus(
                        scope = LaunchConsentScope.HealthData,
                        granted = granted,
                        missingDocuments = if (granted) emptyList() else listOf(health),
                    ),
                ),
            )
        }
    }
}
