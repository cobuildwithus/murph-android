package ai.withmurph.companion.app

import android.content.Intent
import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.AddressBookPersonContact
import ai.withmurph.companion.core.AddressBookServerStatus
import ai.withmurph.companion.core.AddressBookWriteCapability
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.ConnectionIntent
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthPermissionRequestResult
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.InitialOnboardingCatalog
import ai.withmurph.companion.core.InitialOnboardingCompletionAction
import ai.withmurph.companion.core.InitialOnboardingCompletionRequest
import ai.withmurph.companion.core.InitialOnboardingContactAction
import ai.withmurph.companion.core.InitialOnboardingContactAvatar
import ai.withmurph.companion.core.InitialOnboardingContactAvatarKind
import ai.withmurph.companion.core.InitialOnboardingContactCard
import ai.withmurph.companion.core.InitialOnboardingContactCardHandoff
import ai.withmurph.companion.core.InitialOnboardingContactCardRequest
import ai.withmurph.companion.core.InitialOnboardingContactKind
import ai.withmurph.companion.core.InitialOnboardingPersona
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import ai.withmurph.companion.core.InitialOnboardingTone
import ai.withmurph.companion.core.InitialOnboardingVoice
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import ai.withmurph.companion.core.UnsupportedAddressBookContactSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionTest {
    @Test
    fun freshStartupContainsTransientAuthInspectionFailureAndRetries() = runTest {
        val fixture = fixture()
        fixture.auth.currentStateErrorOnce = IllegalStateException("provider unavailable")

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertNoHealthOrBackendProductWork(fixture)
        val authCallsAfterFailure = fixture.auth.currentStateCalls

        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.auth.currentStateCalls > authCallsAfterFailure)
    }

    @Test
    fun restoredStartupContainsTransientAuthInspectionFailureAsOfflineState() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.auth.currentStateErrorOnce = IllegalStateException("provider unavailable")

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertNoHealthOrBackendProductWork(fixture)
        val authCallsAfterFailure = fixture.auth.currentStateCalls

        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.auth.currentStateCalls > authCallsAfterFailure)
    }

    @Test
    fun startupResolvesFreshLegacyAndInterruptedInitialSetup() = runTest {
        val fresh = fixture()
        fresh.session.start()
        assertEquals(InitialSetupStep.HealthConnect, fresh.localState.initialSetupStep)
        assertEquals(InitialSetupStep.HealthConnect, fresh.session.state.value.initialSetupStep)

        val legacy = fixture()
        legacy.localState.memberKey = MEMBER_KEY
        legacy.localState.healthAccessRequestedAt = InstantValue(1)
        legacy.health.signedIn = true
        legacy.health.grantedCount = legacy.health.totalResourceCount
        legacy.session.start()
        assertEquals(InitialSetupStep.Complete, legacy.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, legacy.session.state.value.initialSetupStep)

        val interrupted = fixture()
        interrupted.localState.memberKey = MEMBER_KEY
        interrupted.localState.initialSetupStep = InitialSetupStep.HealthConnect
        interrupted.localState.healthAccessRequestedAt = InstantValue(1)
        interrupted.health.signedIn = true
        interrupted.health.grantedCount = interrupted.health.totalResourceCount
        interrupted.session.start()
        assertEquals(InitialSetupStep.FriendlyNames, interrupted.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            interrupted.session.state.value.initialSetupStep,
        )

        val deferredHealth = fixture()
        deferredHealth.localState.initialSetupStep = InitialSetupStep.FriendlyNames
        deferredHealth.session.start()
        assertEquals(
            InitialSetupStep.FriendlyNames,
            deferredHealth.session.state.value.initialSetupStep,
        )

        val completeWithoutHealth = fixture()
        completeWithoutHealth.localState.initialSetupStep = InitialSetupStep.Complete
        completeWithoutHealth.session.start()
        assertEquals(
            InitialSetupStep.Complete,
            completeWithoutHealth.session.state.value.initialSetupStep,
        )

        val reconnectLegacy = fixture()
        reconnectLegacy.localState.healthReconnectRequired = true
        reconnectLegacy.session.start()
        assertEquals(InitialSetupStep.Complete, reconnectLegacy.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.Complete,
            reconnectLegacy.session.state.value.initialSetupStep,
        )
    }

    @Test
    fun explicitSetupDeferralsPersistWithoutStartingProviderWork() = runTest {
        val fixture = fixture()
        fixture.session.start()
        val statusCalls = fixture.api.statusSources.size
        val tokenCalls = fixture.api.intents.size

        assertTrue(fixture.session.deferHealthConnectInitialSetup())

        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.session.state.value.initialSetupStep)
        assertEquals(statusCalls, fixture.api.statusSources.size)
        assertEquals(tokenCalls, fixture.api.intents.size)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)

        val recreated = recreatedSession(fixture)
        recreated.start()
        assertEquals(InitialSetupStep.FriendlyNames, recreated.state.value.initialSetupStep)
        val recreatedStatusCalls = fixture.api.statusSources.size
        val recreatedTokenCalls = fixture.api.intents.size

        assertTrue(recreated.deferAddressBookSharingInitialSetup())

        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, recreated.state.value.initialSetupStep)
        assertEquals(recreatedStatusCalls, fixture.api.statusSources.size)
        assertEquals(recreatedTokenCalls, fixture.api.intents.size)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)

        val completed = recreatedSession(fixture)
        completed.start()
        assertEquals(InitialSetupStep.Complete, completed.state.value.initialSetupStep)
    }

    @Test
    fun healthConnectDeferralSurfacesASetupChoiceCommitFailure() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.localState.advanceInitialSetupSucceeds = false

        assertFalse(fixture.session.deferHealthConnectInitialSetup())

        assertEquals(InitialSetupStep.HealthConnect, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.HealthConnect, fixture.session.state.value.initialSetupStep)
        assertEquals(
            "Murph couldn't save that Health Connect setup choice. Try again.",
            fixture.session.state.value.healthMessage,
        )
    }

    @Test
    fun signedInLaunchAdmitsBeforeStatusWithoutCreatingAHealthConnection() = runTest {
        val fixture = fixture()

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(listOf(ZoneId.systemDefault().id), fixture.api.admissionTimeZones)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.events.indexOf("admission") < fixture.events.indexOf("status"))
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
    }

    @Test
    fun freshAdmissionRetryableFailureDoesNotBindOrReachProductWork() = runTest {
        val memberKey = "did:privy:fresh-admission-member"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable
        fixture.localState.memberKeyWrites.clear()

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.currentMemberKeyForTest())
        assertFalse(memberKey in fixture.localState.memberKeyWrites)
        assertEquals(listOf(memberKey), fixture.api.admissionMemberKeys)
        assertEquals(listOf("admission"), fixture.events)
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun freshUnboundAdmissionFailureCanSignOutToLogin() = runTest {
        val memberKey = "did:privy:fresh-sign-out-member"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable
        fixture.session.start()

        fixture.session.signOut()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(1, fixture.auth.signOutCalls)
        assertTrue(fixture.auth.state is AuthSessionState.SignedOut)
    }

    @Test
    fun freshUnboundSignOutWaitsForExactPrivyOwnership() = runTest {
        val fixture = fixture(memberKey = "did:privy:fresh-sign-out-member")
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable
        fixture.session.start()
        val signOutCalls = fixture.health.signOutCalls
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable

        fixture.session.signOut()

        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
        assertNull(fixture.localState.memberKey)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
    }

    @Test
    fun verifiedMemberSwitchAdmissionFailureClearsOldOwnerWithoutBindingCandidate() = runTest {
        val oldMemberKey = "did:privy:old-admission-member"
        val candidateMemberKey = "did:privy:new-admission-member"
        val fixture = fixture(memberKey = candidateMemberKey)
        fixture.localState.memberKey = oldMemberKey
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.healthReceiptBaselineAt = InstantValue(2)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(3)
        fixture.localState.lastKnownStatusObservedAt = InstantValue(4)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.currentMemberKeyForTest())
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.healthReceiptBaselineAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertNull(fixture.localState.lastKnownStatusObservedAt)
        assertEquals(1, fixture.localState.clearMemberScopedStateCalls)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(listOf(candidateMemberKey), fixture.api.admissionMemberKeys)
        assertTrue(fixture.events.indexOf("sign-out") < fixture.events.indexOf("admission"))
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun establishedHealthMemberMustPassAdmissionBeforeResumeWork() = runTest {
        val requestedAt = InstantValue(1)
        val receiptBaselineAt = InstantValue(2)
        val receivedAt = InstantValue(3)
        val observedAt = InstantValue(4)
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.healthAccessRequestedAt = requestedAt
        fixture.localState.healthReceiptBaselineAt = receiptBaselineAt
        fixture.localState.lastKnownDataReceivedAt = receivedAt
        fixture.localState.lastKnownStatusObservedAt = observedAt
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(receiptBaselineAt, fixture.localState.healthReceiptBaselineAt)
        assertEquals(receivedAt, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(observedAt, fixture.localState.lastKnownStatusObservedAt)
        assertTrue(fixture.localState.memberKeyWrites.isEmpty())
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertEquals(0, fixture.health.signOutCalls)
        assertTrue(fixture.health.signedIn)
        assertEquals(MEMBER_KEY, fixture.session.currentMemberKeyForTest())
        assertEquals(listOf(MEMBER_KEY), fixture.api.admissionMemberKeys)
        assertEquals(listOf("admission"), fixture.events)
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun statusCannotReplaceOrRepeatAccountAdmission() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.NoAccount

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals(listOf(ZoneId.systemDefault().id), fixture.api.admissionTimeZones)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
    }

    @Test
    fun freshAdmissionConsentRecoveryBindsOnlyAfterRetriedAdmissionSucceeds() = runTest {
        val memberKey = "did:privy:fresh-consent-member"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.ConsentRequired
        fixture.localState.memberKeyWrites.clear()

        fixture.session.start()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertNull(fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertFalse(memberKey in fixture.localState.memberKeyWrites)
        assertEquals(listOf(memberKey), fixture.api.admissionMemberKeys)
        assertEquals(listOf(memberKey), fixture.api.launchConsentFetches)
        assertEquals(listOf(ZoneId.systemDefault().id), fixture.api.admissionTimeZones)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.intents.isEmpty())
        assertTrue(fixture.api.initialOnboardingFetches.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)

        fixture.api.admissionError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertEquals(1, fixture.localState.memberKeyWrites.count { it == memberKey })
        assertEquals(listOf(memberKey, memberKey), fixture.api.admissionMemberKeys)
        assertEquals(2, fixture.api.admissionTimeZones.size)
        assertEquals(listOf(memberKey), fixture.api.statusAuthMemberKeys)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertEquals(listOf(memberKey), fixture.api.initialOnboardingFetches)
        assertTrue(fixture.events.lastIndexOf("admission") < fixture.events.indexOf("status"))
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
    }

    @Test
    fun acceptedAdmissionCandidateRetriesTransientAdmissionFromTheVisibleFailureAction() = runTest {
        val memberKey = "did:privy:fresh-consent-retry-member"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.ConsentRequired
        fixture.localState.memberKeyWrites.clear()

        fixture.session.start()

        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            grantLaunchConsentScope(current, request.scope).also { updated ->
                if (updated.launchGranted) {
                    fixture.api.admissionError = CompanionApiException.AdmissionRetryable
                }
            }
        }
        fixture.session.acceptLaunchConsent()

        val retryableFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(retryableFailure.canRetry)
        assertNull(fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertFalse(memberKey in fixture.localState.memberKeyWrites)
        assertEquals(listOf(memberKey, memberKey), fixture.api.admissionMemberKeys)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.intents.isEmpty())
        assertTrue(fixture.api.initialOnboardingFetches.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        val acceptedConsentRequests = fixture.api.launchConsentAcceptances.toList()
        assertTrue(acceptedConsentRequests.isNotEmpty())

        fixture.api.admissionError = null
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertEquals(1, fixture.localState.memberKeyWrites.count { it == memberKey })
        assertEquals(listOf(memberKey, memberKey, memberKey), fixture.api.admissionMemberKeys)
        assertEquals(acceptedConsentRequests, fixture.api.launchConsentAcceptances)
        assertEquals(listOf(memberKey), fixture.api.statusAuthMemberKeys)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertEquals(listOf(memberKey), fixture.api.initialOnboardingFetches)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun acceptedAdmissionCandidateSurvivesAnUnverifiedDifferentMemberObservation() = runTest {
        val memberKey = "did:privy:fresh-consent-owner"
        val unverifiedCandidate = "did:privy:unverified-consent-candidate"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.ConsentRequired
        fixture.localState.memberKeyWrites.clear()

        fixture.session.start()

        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            grantLaunchConsentScope(current, request.scope).also { updated ->
                if (updated.launchGranted) {
                    fixture.api.admissionError = CompanionApiException.LocalAuthUnavailable(
                        AuthSessionState.SignedIn(
                            memberKey = unverifiedCandidate,
                            verifiedOnline = false,
                        ),
                    )
                }
            }
        }
        fixture.session.acceptLaunchConsent()

        val retryableFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(retryableFailure.canRetry)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertNull(fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertTrue(fixture.api.admissionMemberKeys.all { it == memberKey })
        assertFalse(unverifiedCandidate in fixture.api.statusAuthMemberKeys)
        assertFalse(unverifiedCandidate in fixture.api.initialOnboardingFetches)
        assertFalse(fixture.health.signedIn)
        val acceptedConsentRequests = fixture.api.launchConsentAcceptances.toList()

        fixture.api.admissionError = null
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(memberKey, fixture.session.currentMemberKeyForTest())
        assertEquals(acceptedConsentRequests, fixture.api.launchConsentAcceptances)
        assertEquals(listOf(memberKey), fixture.api.statusAuthMemberKeys)
        assertEquals(listOf(memberKey), fixture.api.initialOnboardingFetches)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun authSwitchDuringFreshAdmissionCannotLetStaleCandidateCleanupEraseVerifiedOwner() =
        runTest {
            val admissionCandidate = "did:privy:stale-admission-candidate"
            val verifiedOwner = "did:privy:verified-admission-owner"
            val fixture = fixture(memberKey = admissionCandidate)
            fixture.localState.memberKeyWrites.clear()
            fixture.api.admissionHandler = { memberKey ->
                if (memberKey == admissionCandidate) {
                    fixture.auth.state = AuthSessionState.SignedIn(
                        verifiedOwner,
                        verifiedOnline = true,
                    )
                }
            }

            fixture.session.start()

            assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
            assertTrue(fixture.session.state.value.authVerifiedOnline)
            assertEquals(verifiedOwner, fixture.localState.memberKey)
            assertEquals(verifiedOwner, fixture.session.currentMemberKeyForTest())
            assertFalse(admissionCandidate in fixture.localState.memberKeyWrites)
            assertEquals(1, fixture.localState.memberKeyWrites.count { it == verifiedOwner })
            assertEquals(
                listOf(admissionCandidate, verifiedOwner),
                fixture.api.admissionMemberKeys,
            )
            assertEquals(listOf(verifiedOwner), fixture.api.statusAuthMemberKeys)
            assertEquals(listOf(verifiedOwner), fixture.api.initialOnboardingFetches)
            assertNull(fixture.session.state.value.launchConsentRecovery)
        }

    @Test
    fun acceptedAdmissionConsentSwitchBeforeReconcileClearsRecoveryAndMountsVerifiedOwner() =
        runTest {
            val consentCandidate = "did:privy:stale-consent-candidate"
            val verifiedOwner = "did:privy:verified-consent-owner"
            val fixture = fixture(memberKey = consentCandidate)
            fixture.api.admissionError = CompanionApiException.ConsentRequired
            fixture.localState.memberKeyWrites.clear()

            fixture.session.start()

            assertEquals(
                LaunchConsentRecoveryPhase.Required,
                fixture.session.state.value.launchConsentRecovery?.phase,
            )
            assertEquals(consentCandidate, fixture.session.currentMemberKeyForTest())
            assertNull(fixture.localState.memberKey)

            fixture.api.admissionError = null
            fixture.api.launchConsentAcceptHandler = { _, request, current ->
                grantLaunchConsentScope(current, request.scope).also { updated ->
                    if (updated.launchGranted) {
                        fixture.auth.state = AuthSessionState.SignedIn(
                            verifiedOwner,
                            verifiedOnline = true,
                        )
                    }
                }
            }

            fixture.session.acceptLaunchConsent()

            assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
            assertTrue(fixture.session.state.value.authVerifiedOnline)
            assertEquals(verifiedOwner, fixture.localState.memberKey)
            assertEquals(verifiedOwner, fixture.session.currentMemberKeyForTest())
            assertFalse(consentCandidate in fixture.localState.memberKeyWrites)
            assertEquals(1, fixture.localState.memberKeyWrites.count { it == verifiedOwner })
            assertEquals(
                listOf(consentCandidate, verifiedOwner),
                fixture.api.admissionMemberKeys,
            )
            assertTrue(
                fixture.api.launchConsentAcceptances.all { (memberKey, _) ->
                    memberKey == consentCandidate
                },
            )
            assertEquals(listOf(verifiedOwner), fixture.api.statusAuthMemberKeys)
            assertEquals(listOf(verifiedOwner), fixture.api.initialOnboardingFetches)
            assertNull(fixture.session.state.value.launchConsentRecovery)
        }

    @Test
    fun acceptedAdmissionConsentIsClearedWhenPrivySignsOutBeforeReconcile() = runTest {
        val memberKey = "did:privy:signed-out-consent-candidate"
        val fixture = fixture(memberKey = memberKey)
        fixture.api.admissionError = CompanionApiException.ConsentRequired

        fixture.session.start()

        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            grantLaunchConsentScope(current, request.scope).also { updated ->
                if (updated.launchGranted) {
                    fixture.auth.state = AuthSessionState.SignedOut
                    fixture.api.admissionError = null
                }
            }
        }
        fixture.session.acceptLaunchConsent()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.currentMemberKeyForTest())
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertFalse(fixture.health.signedIn)
        assertEquals(listOf(memberKey), fixture.api.admissionMemberKeys)
    }

    @Test
    fun establishedTerminalAdmissionFencesWorkersBeforeTearingDownJunction() = runTest {
        val requestedAt = InstantValue(1)
        val receiptBaselineAt = InstantValue(2)
        val receivedAt = InstantValue(3)
        val observedAt = InstantValue(4)
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.healthAccessRequestedAt = requestedAt
        fixture.localState.healthReceiptBaselineAt = receiptBaselineAt
        fixture.localState.lastKnownDataReceivedAt = receivedAt
        fixture.localState.lastKnownStatusObservedAt = observedAt
        fixture.localState.recordAddressBookRevision(5)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signOutGate = CompletableDeferred()
        fixture.api.admissionError = CompanionApiException.AccessRequired

        val startup = async { fixture.session.start() }
        fixture.health.signOutEntered.await()
        runCurrent()

        assertFalse(startup.isCompleted)
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.health.signedIn)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(receiptBaselineAt, fixture.localState.healthReceiptBaselineAt)
        assertEquals(receivedAt, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(observedAt, fixture.localState.lastKnownStatusObservedAt)
        assertEquals(5, fixture.localState.addressBookRevision)
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertTrue(fixture.localState.memberKeyWrites.isEmpty())
        assertEquals(listOf(MEMBER_KEY), fixture.api.admissionMemberKeys)
        assertEquals(listOf("admission"), fixture.events)
        assertNoPostAdmissionProductWork(fixture)

        fixture.session.syncNow()
        assertEquals(0, fixture.health.syncCalls)
        fixture.health.signOutGate?.complete(Unit)
        startup.await()

        val terminalFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(terminalFailure.canRetry)
        assertEquals("Try a different sign-in", terminalFailure.signOutLabel)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(listOf("admission", "sign-out"), fixture.events)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.currentMemberKeyForTest())
        assertNull(fixture.localState.initialSetupStep)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.healthReceiptBaselineAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertNull(fixture.localState.lastKnownStatusObservedAt)
        assertNull(fixture.localState.addressBookRevision)
        assertEquals(1, fixture.localState.clearMemberScopedStateCalls)
        assertEquals(listOf(null), fixture.localState.memberKeyWrites)
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun terminalReadyStatusFailureClosesRuntimeAndRevokesHealthAuthorization() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.start()
        val syncCallsBeforeRejection = fixture.health.syncCalls
        fixture.api.statusError = CompanionApiException.MemberSuspended

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertEquals(FailureSupplementalActions.Support, failure.supplementalActions)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertEquals(syncCallsBeforeRejection, fixture.health.syncCalls)
    }

    @Test
    fun terminalResumeTokenFailureClosesRuntimeAndRevokesHealthAuthorization() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.signInError = CompanionApiException.AdmissionSupportRequired

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertEquals(FailureSupplementalActions.Support, failure.supplementalActions)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun terminalAdmissionTeardownFailureKeepsHealthAuthorityFencedForRetry() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.signOutError = IllegalStateException("teardown failed")
        fixture.api.admissionError = CompanionApiException.MemberSuspended

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(1, fixture.health.signOutCalls)
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun terminalAdmissionStopsBeforeJunctionWhenBoundaryWriteFails() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.beginSignOutSucceeds = false
        fixture.health.signedIn = true
        fixture.api.admissionError = CompanionApiException.AccessRequired

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(0, fixture.health.signOutCalls)
        assertFalse(fixture.localState.signOutPending)
        assertNoPostAdmissionProductWork(fixture)
    }

    @Test
    fun cancelledTerminalAdmissionTeardownCannotRestoreOfflineReady() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.signOutGate = CompletableDeferred()
        fixture.api.admissionError = CompanionApiException.MemberSuspended

        val startup = launch { fixture.session.start() }
        fixture.health.signOutEntered.await()
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.localState.signOutPending)
        startup.cancelAndJoin()

        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        val replacementHealth = FakeHealth(fixture.events).apply {
            signedIn = true
            grantedCount = totalResourceCount
            signOutError = IllegalStateException("teardown still failing")
        }
        val blockedReplacement = recreatedSession(fixture, replacementHealth)
        blockedReplacement.start()

        assertTrue(blockedReplacement.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(1, replacementHealth.signOutCalls)
        assertEquals(0, replacementHealth.syncCalls)

        replacementHealth.signOutError = null
        val unavailableReplacement = recreatedSession(fixture, replacementHealth)
        unavailableReplacement.start()

        assertTrue(unavailableReplacement.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(0, replacementHealth.syncCalls)
        assertEquals(0, fixture.auth.signOutCalls)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        val recoveredReplacement = recreatedSession(fixture, replacementHealth)
        recoveredReplacement.start()

        assertEquals(AppPhase.NeedsLogin, recoveredReplacement.state.value.phase)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.memberKey)
        assertFalse(replacementHealth.signedIn)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertEquals(0, replacementHealth.syncCalls)
        assertEquals(1, fixture.auth.signOutCalls)
    }

    @Test
    fun terminalAdmissionDifferentSignInActionLogsOutTheRejectedUnboundMember() = runTest {
        val fixture = fixture()
        fixture.api.admissionError = CompanionApiException.AccessRequired
        fixture.session.start()
        assertNull(fixture.localState.memberKey)

        fixture.session.signOut()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(1, fixture.auth.signOutCalls)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
    }

    @Test
    fun terminalInitialProjectionFailureClosesRuntimeAfterAdmission() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboardingFetchError = CompanionApiException.AccessRequired

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.health.signedIn)
        assertEquals(listOf(MEMBER_KEY), fixture.api.admissionMemberKeys)
        assertEquals(listOf(MEMBER_KEY), fixture.api.initialOnboardingFetches)
    }

    @Test
    fun admissionFailuresStopBeforeStatusOrHealthWithTypedRecovery() = runTest {
        val cases = listOf(
            Triple(
                CompanionApiException.AccessRequired,
                false,
                "This sign-in doesn't have access to the Murph companion app.",
            ),
            Triple(
                CompanionApiException.MemberSuspended,
                false,
                "This Murph account is paused. Try a different sign-in or contact Murph support.",
            ),
            Triple(
                CompanionApiException.AdmissionRetryable,
                true,
                "Murph account setup is temporarily unavailable. Try again.",
            ),
            Triple(
                CompanionApiException.AdmissionSupportRequired,
                false,
                "Murph support needs to finish setting up this account. Try a different sign-in or contact support.",
            ),
        )

        cases.forEach { (error, canRetry, message) ->
            val fixture = fixture()
            fixture.api.admissionError = error

            fixture.session.start()

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertEquals(message, failure.message)
            assertEquals(canRetry, failure.canRetry)
            assertTrue(failure.canSignOut)
            assertEquals(FailureSupplementalActions.Support, failure.supplementalActions)
            assertEquals(
                if (error == CompanionApiException.AdmissionRetryable) {
                    "Sign out and start fresh"
                } else {
                    "Try a different sign-in"
                },
                failure.signOutLabel,
            )
            assertTrue(fixture.api.statusSources.isEmpty())
            assertTrue(fixture.api.intents.isEmpty())
            assertEquals(0, fixture.health.identifyCalls)
            assertEquals(0, fixture.health.connectCalls)
        }
    }

    @Test
    fun accountConflictClosesMemberAndHealthAuthority() = runTest {
        val fixture = fixture()
        fixture.api.admissionError = CompanionApiException.AccountConflict

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.health.signedIn)
    }

    @Test
    fun accountConflictFencesHealthWorkersBeforeCancelableTeardownAndReconstruction() =
        runTest {
            val fixture = completedHealthFixture()
            fixture.api.statusError = CompanionApiException.AccountConflict
            fixture.health.signOutGate = CompletableDeferred()

            val sync = launch { fixture.session.syncNow() }
            fixture.health.signOutEntered.await()

            assertFalse(sync.isCompleted)
            assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
            assertEquals(MEMBER_KEY, fixture.localState.memberKey)
            assertTrue(fixture.localState.signOutPending)
            sync.cancelAndJoin()

            fixture.auth.state = AuthSessionState.TemporarilyUnavailable
            fixture.api.statusError = null
            val replacementHealth = FakeHealth(fixture.events).apply {
                signedIn = true
                grantedCount = totalResourceCount
                signOutError = IllegalStateException("teardown still failing")
            }
            val blockedReplacement = recreatedSession(fixture, replacementHealth)

            blockedReplacement.start()

            assertTrue(blockedReplacement.state.value.phase is AppPhase.Failed)
            assertTrue(fixture.localState.signOutPending)
            assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
            assertEquals(1, replacementHealth.signOutCalls)
            assertEquals(0, replacementHealth.syncCalls)

            replacementHealth.signOutError = null
            val unavailableReplacement = recreatedSession(fixture, replacementHealth)
            unavailableReplacement.start()

            assertTrue(unavailableReplacement.state.value.phase is AppPhase.Failed)
            assertTrue(fixture.localState.signOutPending)
            assertEquals(MEMBER_KEY, fixture.localState.memberKey)
            assertEquals(0, replacementHealth.syncCalls)
            assertEquals(0, fixture.auth.signOutCalls)

            fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
            val recoveredReplacement = recreatedSession(fixture, replacementHealth)

            recoveredReplacement.start()

            assertEquals(AppPhase.NeedsLogin, recoveredReplacement.state.value.phase)
            assertFalse(fixture.localState.signOutPending)
            assertNull(fixture.localState.memberKey)
            assertFalse(replacementHealth.signedIn)
            assertNull(fixture.localState.healthAccessRequestedAt)
            assertEquals(0, replacementHealth.syncCalls)
            assertEquals(1, fixture.auth.signOutCalls)
        }

    @Test
    fun accountConflictStopsBeforeJunctionWhenBoundaryWriteFails() = runTest {
        val fixture = completedHealthFixture()
        val signOutCalls = fixture.health.signOutCalls
        fixture.localState.beginSignOutSucceeds = false
        fixture.api.statusError = CompanionApiException.AccountConflict

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertFalse(fixture.localState.signOutPending)
    }

    @Test
    fun accountConflictTeardownFailureKeepsHealthAuthorityFencedForRetry() = runTest {
        val fixture = completedHealthFixture()
        val signOutCalls = fixture.health.signOutCalls
        fixture.health.signOutError = IllegalStateException("teardown failed")
        fixture.api.statusError = CompanionApiException.AccountConflict

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
    }

    @Test
    fun signedInLaunchMountsServerOwnedInitialOnboardingWithoutStartingHealth() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(InitialOnboardingStage.Contact, fixture.session.state.value.initialOnboardingStage)
        assertEquals("classic", fixture.session.state.value.initialOnboardingDraft?.mainPersonaId)
        assertEquals("murph", fixture.session.state.value.initialOnboardingDraft?.voiceId)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
    }

    @Test
    fun saveInitialOnboardingPersistsExactDraftAndShowsWelcomeOnlyForFirstWriter() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.session.selectInitialOnboardingSupportingPersona("classic")
        fixture.session.selectInitialOnboardingVoice("murph")
        fixture.session.selectInitialOnboardingTone("casual")

        fixture.session.saveInitialOnboarding()

        val request = fixture.api.initialOnboardingCompletions.single().second
        assertEquals(InitialOnboardingCompletionAction.Save, request.action)
        assertEquals("coach-with-classic", request.preferences?.persona)
        assertEquals("casual", request.preferences?.tone)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertTrue(fixture.session.state.value.initialOnboardingCompletedNow)

        fixture.session.dismissCompletedInitialOnboarding()
        assertNull(fixture.session.state.value.initialOnboarding)
    }

    @Test
    fun staleInitialOnboardingSaveClosesQuietlyWithoutWelcome() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.api.initialOnboardingCompletedNow = false
        fixture.session.start()

        fixture.session.saveInitialOnboarding()

        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertNull(fixture.session.state.value.initialOnboarding)
        assertFalse(fixture.session.state.value.initialOnboardingCompletedNow)
    }

    @Test
    fun skipInitialOnboardingNeverShowsWelcome() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()

        fixture.session.skipInitialOnboarding()

        val request = fixture.api.initialOnboardingCompletions.single().second
        assertEquals(InitialOnboardingCompletionAction.Skip, request.action)
        assertNull(request.preferences)
        assertNull(fixture.session.state.value.initialOnboarding)
        assertFalse(fixture.session.state.value.initialOnboardingCompletedNow)
    }

    @Test
    fun failedInitialOnboardingSaveRetainsDraftAndOffersFullSignOut() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.Network

        fixture.session.saveInitialOnboarding()

        assertEquals("coach", fixture.session.state.value.initialOnboardingDraft?.mainPersonaId)
        assertTrue(fixture.session.state.value.initialOnboardingMessage.orEmpty().contains("choices"))
        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
    }

    @Test
    fun localAuthFailureDuringOnboardingSavePreservesExactDraftAndMemberSetup() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        val draft = fixture.session.state.value.initialOnboardingDraft
        val memberKey = fixture.localState.memberKey
        val initialSetupStep = fixture.localState.initialSetupStep
        val signOutCalls = fixture.health.signOutCalls
        fixture.api.initialOnboardingCompletionError =
            CompanionApiException.LocalAuthUnavailable(
                AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
            )

        fixture.session.saveInitialOnboarding()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(draft, fixture.session.state.value.initialOnboardingDraft)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(initialSetupStep, fixture.localState.initialSetupStep)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)

        fixture.api.initialOnboardingCompletionError = null
        fixture.session.saveInitialOnboarding()

        assertTrue(fixture.session.state.value.initialOnboardingCompletedNow)
    }

    @Test
    fun failedInitialOnboardingMemberBoundaryResetReleasesSavingState() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.api.initialOnboardingCompletionError = CompanionApiException.Unauthorized
        fixture.health.signOutError = IllegalStateException("teardown failed")

        fixture.session.saveInitialOnboarding()

        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.localState.signOutPending)
    }

    @Test
    fun terminalOnboardingSaveFailuresCloseRuntimeWithoutDiscardingTheDraft() = runTest {
        val cases = listOf(
            CompanionApiException.Unauthorized to "Sign in again",
            CompanionApiException.NoAccount to "Try a different sign-in",
            CompanionApiException.AccessRequired to "Try a different sign-in",
            CompanionApiException.MemberSuspended to "Try a different sign-in",
            CompanionApiException.AdmissionSupportRequired to "Try a different sign-in",
        )

        cases.forEach { (rejection, signOutLabel) ->
            val fixture = fixture()
            fixture.api.initialOnboarding = pendingInitialOnboarding()
            fixture.session.start()
            fixture.session.selectInitialOnboardingMainPersona("coach")
            val exactDraft = fixture.session.state.value.initialOnboardingDraft
            fixture.health.signedIn = true
            fixture.api.initialOnboardingCompletionError = rejection

            fixture.session.saveInitialOnboarding()

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertFalse(failure.canRetry)
            assertEquals(signOutLabel, failure.signOutLabel)
            assertNull(fixture.localState.memberKey)
            assertEquals(exactDraft, fixture.session.state.value.initialOnboardingDraft)
            assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
            assertFalse(fixture.health.signedIn)
        }
    }

    @Test
    fun contactCardHandoffAdvancesOnlyAfterTheExternalLaunchIsConfirmed() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()

        fixture.session.prepareInitialOnboardingContactCard()

        val firstEvent = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        assertTrue(fixture.api.initialOnboardingContactCards.isEmpty())
        assertEquals(InitialOnboardingStage.Contact, fixture.session.state.value.initialOnboardingStage)
        assertFalse(
            fixture.session.launchInitialOnboardingContactCardHandoff(firstEvent.id) { false },
        )
        assertEquals(InitialOnboardingStage.Contact, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)

        fixture.session.prepareInitialOnboardingContactCard()
        val secondEvent = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        var launchedUrl: String? = null
        assertTrue(
            fixture.session.launchInitialOnboardingContactCardHandoff(secondEvent.id) { url ->
                launchedUrl = url
                true
            },
        )

        assertEquals(2, fixture.api.initialOnboardingContactCards.size)
        assertEquals("classic", fixture.api.initialOnboardingContactCards.last().second.avatarId)
        assertEquals("https://example.test/contact-card", launchedUrl)
        assertEquals(InitialOnboardingStage.MainPersona, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)
    }

    @Test
    fun failedContactCardMemberBoundaryResetReleasesSavingState() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.api.initialOnboardingContactCardError = CompanionApiException.Unauthorized
        fixture.health.signOutError = IllegalStateException("teardown failed")

        fixture.session.prepareInitialOnboardingContactCard()
        val event = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        assertFalse(fixture.session.launchInitialOnboardingContactCardHandoff(event.id) { true })

        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.localState.signOutPending)
    }

    @Test
    fun queuedSecondConsentAcceptanceCannotReplayTheContactCardContinuation() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.api.initialOnboardingContactCardError = CompanionApiException.ConsentRequired
        fixture.session.prepareInitialOnboardingContactCard()
        val initialEvent = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        assertFalse(
            fixture.session.launchInitialOnboardingContactCardHandoff(initialEvent.id) { true },
        )
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.api.initialOnboardingContactCardError = null
        val acceptanceEntered = CompletableDeferred<Unit>()
        val acceptanceGate = CompletableDeferred<Unit>()
        fixture.api.launchConsentAcceptHandler = { _, request, status ->
            if (!acceptanceEntered.isCompleted) {
                acceptanceEntered.complete(Unit)
                acceptanceGate.await()
            }
            grantLaunchConsentScope(status, request.scope)
        }

        val firstAcceptance = async { fixture.session.acceptLaunchConsent() }
        acceptanceEntered.await()
        val secondAcceptance = async { fixture.session.acceptLaunchConsent() }
        runCurrent()

        acceptanceGate.complete(Unit)
        firstAcceptance.await()
        secondAcceptance.await()

        assertEquals(1, fixture.api.initialOnboardingContactCards.size)
        val resumedEvent = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(
            fixture.session.launchInitialOnboardingContactCardHandoff(resumedEvent.id) { true },
        )
        assertEquals(2, fixture.api.initialOnboardingContactCards.size)
        assertEquals(InitialOnboardingStage.MainPersona, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun contactCardHandoffRequiresExactVerifiedAuthBeforeMinting() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.prepareInitialOnboardingContactCard()
        val event = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        var launchCalls = 0

        assertFalse(
            fixture.session.launchInitialOnboardingContactCardHandoff(event.id) {
                launchCalls += 1
                true
            },
        )

        assertEquals(0, launchCalls)
        assertTrue(fixture.api.initialOnboardingContactCards.isEmpty())
        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)
        assertEquals(InitialOnboardingStage.Contact, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun contactCardHandoffRechecksExactAuthAfterMinting() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.prepareInitialOnboardingContactCard()
        val event = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        val mintGate = CompletableDeferred<Unit>()
        fixture.api.initialOnboardingContactCardGate = mintGate
        var launchCalls = 0

        val launch = async {
            fixture.session.launchInitialOnboardingContactCardHandoff(event.id) {
                launchCalls += 1
                true
            }
        }
        fixture.api.initialOnboardingContactCardEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(
            "did:privy:verified-member-switch",
            verifiedOnline = true,
        )
        mintGate.complete(Unit)

        assertFalse(launch.await())
        assertEquals(0, launchCalls)
        assertEquals(1, fixture.api.initialOnboardingContactCards.size)
        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)
        assertFalse(fixture.session.state.value.initialOnboardingStage == InitialOnboardingStage.MainPersona)
    }

    @Test
    fun cancelledContactCardMintRemainsQueuedForTheNextResume() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.prepareInitialOnboardingContactCard()
        val event = fixture.session.state.value.initialOnboardingContactCardHandoff!!
        val mintGate = CompletableDeferred<Unit>()
        fixture.api.initialOnboardingContactCardGate = mintGate

        val firstLaunch = launch {
            fixture.session.launchInitialOnboardingContactCardHandoff(event.id) { true }
        }
        fixture.api.initialOnboardingContactCardEntered.await()
        firstLaunch.cancelAndJoin()

        assertEquals(event, fixture.session.state.value.initialOnboardingContactCardHandoff)
        assertTrue(fixture.session.state.value.isInitialOnboardingSaving)

        fixture.api.initialOnboardingContactCardGate = null
        assertTrue(
            fixture.session.launchInitialOnboardingContactCardHandoff(event.id) { true },
        )
        assertEquals(2, fixture.api.initialOnboardingContactCards.size)
        assertEquals(InitialOnboardingStage.MainPersona, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun signOutClearsQueuedContactCardBeforeJunctionTeardownCompletes() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.prepareInitialOnboardingContactCard()
        fixture.health.signOutGate = CompletableDeferred()

        val signOut = async { fixture.session.signOut() }
        fixture.health.signOutEntered.await()

        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)
        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
        fixture.health.signOutGate?.complete(Unit)
        signOut.await()
    }

    @Test
    fun verifiedMemberSwitchClearsQueuedContactCardBeforeJunctionTeardownCompletes() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.prepareInitialOnboardingContactCard()
        fixture.health.signedIn = true
        fixture.health.signOutGate = CompletableDeferred()
        fixture.auth.state = AuthSessionState.SignedIn(
            "did:privy:verified-contact-card-switch",
            verifiedOnline = true,
        )

        val reconcile = async { fixture.session.retry() }
        fixture.health.signOutEntered.await()

        assertNull(fixture.session.state.value.initialOnboardingContactCardHandoff)
        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
        fixture.health.signOutGate?.complete(Unit)
        reconcile.await()
    }

    @Test
    fun foregroundPendingRefreshIsRemovalOnlyButCompletedRefreshClosesFlow() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding()
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboarding = pendingInitialOnboarding().copy(
            preferences = InitialOnboardingPreferences("classic", "formal", "murph"),
        )

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals("coach", fixture.session.state.value.initialOnboardingDraft?.mainPersonaId)

        fixture.api.initialOnboarding = completedInitialOnboarding()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertNull(fixture.session.state.value.initialOnboarding)
    }

    @Test
    fun duplicateInitialOnboardingCompletionHasOneWriter() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        val gate = CompletableDeferred<Unit>()
        fixture.api.initialOnboardingCompletionGate = gate

        val first = async { fixture.session.saveInitialOnboarding() }
        fixture.api.initialOnboardingCompletionEntered.await()
        val second = async { fixture.session.saveInitialOnboarding() }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun healthConsentWhileOnboardingSaveIsInFlightPreservesTheExactRequest() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        val completionGate = CompletableDeferred<Unit>()
        fixture.api.initialOnboardingCompletionGate = completionGate
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired

        val save = async { fixture.session.saveInitialOnboarding() }
        fixture.api.initialOnboardingCompletionEntered.await()
        val requested = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.syncNow()
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        completionGate.complete(Unit)
        save.await()

        assertFalse(fixture.session.state.value.isInitialOnboardingSaving)
        fixture.api.initialOnboardingCompletionGate = null
        fixture.api.initialOnboardingCompletionError = null
        fixture.api.statusError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(requested, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun healthConsentClaimCannotDeadlockWithAConcurrentConsentRetry() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        val availabilityEntered = CountDownLatch(1)
        val releaseAvailability = CountDownLatch(1)
        fixture.health.availabilityHook = {
            availabilityEntered.countDown()
            check(releaseAvailability.await(2, TimeUnit.SECONDS))
        }

        val sync = async(Dispatchers.Default) { fixture.session.syncNow() }
        assertTrue(availabilityEntered.await(2, TimeUnit.SECONDS))
        val retry = async(
            context = Dispatchers.Default,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            fixture.session.retryLaunchConsentRecovery()
        }

        releaseAvailability.countDown()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                sync.await()
                retry.await()
            }
        }
        fixture.health.availabilityHook = null

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertFalse(fixture.health.signedIn)
        assertTrue(fixture.api.launchConsentFetches.isNotEmpty())
    }

    @Test
    fun consentInterruptedSaveResumesTheExactInitialOnboardingDraft() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired

        fixture.session.saveInitialOnboarding()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.api.initialOnboardingCompletionError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            fixture.api.initialOnboardingCompletions[0].second,
            fixture.api.initialOnboardingCompletions[1].second,
        )
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun durableHealthRestoresBeforeConsentInterruptedOnboardingSave() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired

        fixture.session.saveInitialOnboarding()
        assertFalse(fixture.health.signedIn)

        fixture.api.initialOnboardingCompletionError = null
        fixture.session.acceptLaunchConsent()

        assertTrue(fixture.health.signedIn)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertEquals(ConnectionIntent.Resume, fixture.api.intents.last())
        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            fixture.api.initialOnboardingCompletions[0].second,
            fixture.api.initialOnboardingCompletions[1].second,
        )
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
    }

    @Test
    fun acceptedOnboardingContinuationSurvivesTransientHealthRestoreFailure() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        val requested = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.initialOnboardingCompletionError = null
        fixture.api.signInError = CompanionApiException.Network

        fixture.session.acceptLaunchConsent()

        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.api.signInError = null

        fixture.session.retryLaunchConsentRecovery()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(requested, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun acceptedOnboardingContinuationRetriesTheWholeFailedHealthRestore() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        val requested = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.initialOnboardingCompletionError = null
        fixture.health.configureError = IllegalStateException("configure failed")

        fixture.session.acceptLaunchConsent()

        assertTrue(fixture.health.signedIn)
        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.health.configureError = null

        fixture.session.retryLaunchConsentRecovery()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(requested, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun secondConsentDuringHealthRestoreRetainsTheExactOnboardingRequest() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        val requested = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.initialOnboardingCompletionError = null
        fixture.api.signInError = CompanionApiException.ConsentRequired

        fixture.session.acceptLaunchConsent()

        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.api.signInError = null

        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(requested, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun secondConsentPreservesTheDeferredForegroundRefresh() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        fixture.api.initialOnboardingCompletionError = null
        val restoreGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = restoreGate
        fixture.api.signInGateOnCall = fixture.api.intents.size + 1
        fixture.api.signInError = CompanionApiException.ConsentRequired

        val firstAcceptance = async { fixture.session.acceptLaunchConsent() }
        fixture.api.signInGateEntered.await()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        restoreGate.complete(Unit)
        firstAcceptance.await()
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.api.signInError = null

        fixture.session.acceptLaunchConsent()

        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        fixture.session.didBecomeActive()
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun acceptedOnboardingContinuationKeepsTheDraftLockedDuringHealthRestore() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        val requested = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.initialOnboardingCompletionError = null
        val restoreGate = CompletableDeferred<Unit>()
        fixture.api.signInGate = restoreGate
        fixture.api.signInGateOnCall = fixture.api.intents.size + 1

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        fixture.api.signInGateEntered.await()
        assertEquals(
            LaunchConsentRecoveryPhase.Saving,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.session.selectInitialOnboardingMainPersona("murph")
        assertEquals("coach", fixture.session.state.value.initialOnboardingDraft?.mainPersonaId)
        restoreGate.complete(Unit)
        acceptance.await()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(requested, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(InitialOnboardingStage.Welcome, fixture.session.state.value.initialOnboardingStage)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun sameGenerationConsentDeferralsKeepTheEarliestSyncBoundary() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired
        fixture.session.saveInitialOnboarding()
        fixture.api.initialOnboardingCompletionError = null
        val preSyncStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = preSyncStatusGate
        fixture.api.statusGateOnCall = fixture.api.statusSources.size + 1

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        fixture.api.statusGateEntered.await()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate

        preSyncStatusGate.complete(Unit)
        runCurrent()
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        fixture.session.didBecomeActive()

        syncGate.complete(Unit)
        acceptance.await()

        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertNull(fixture.session.state.value.launchConsentRecovery)
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
    fun accountConflictWhileLoadingConsentClosesMemberAndHealthAuthority() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.api.launchConsentFetchError = CompanionApiException.AccountConflict

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.health.signedIn)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun terminalConsentLoadFailuresRevokeEstablishedHealthAuthorization() = runTest {
        val cases = listOf(
            CompanionApiException.Unauthorized to "Sign in again",
            CompanionApiException.NoAccount to "Try a different sign-in",
            CompanionApiException.AccessRequired to "Try a different sign-in",
            CompanionApiException.MemberSuspended to "Try a different sign-in",
            CompanionApiException.AdmissionSupportRequired to "Try a different sign-in",
        )

        cases.forEach { (rejection, signOutLabel) ->
            val fixture = completedHealthFixture()
            fixture.api.statusError = CompanionApiException.ConsentRequired
            fixture.api.launchConsentFetchError = rejection

            fixture.session.syncNow()

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertFalse(failure.canRetry)
            assertEquals(signOutLabel, failure.signOutLabel)
            assertNull(fixture.localState.memberKey)
            assertNull(fixture.localState.healthAccessRequestedAt)
            assertFalse(fixture.health.signedIn)
            assertNull(fixture.session.state.value.launchConsentRecovery)
        }
    }

    @Test
    fun terminalConsentAcceptanceRevokesEstablishedHealthAuthorization() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.syncNow()
        fixture.api.statusError = null
        fixture.api.launchConsentAcceptError = CompanionApiException.AccessRequired

        fixture.session.acceptLaunchConsent()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertNull(fixture.session.state.value.launchConsentRecovery)

        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        val replacementHealth = FakeHealth(fixture.events).apply {
            grantedCount = totalResourceCount
        }
        val replacement = recreatedSession(fixture, replacementHealth)

        replacement.start()

        assertTrue(replacement.state.value.phase is AppPhase.Failed)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertEquals(0, replacementHealth.identifyCalls)
        assertEquals(0, replacementHealth.configureCalls)
        assertEquals(0, replacementHealth.syncCalls)
    }

    @Test
    fun terminalStaleConsentReloadRevokesEstablishedHealthAuthorization() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.syncNow()
        fixture.api.statusError = null
        fixture.api.launchConsentAcceptError = CompanionApiException.StaleConsentDocuments
        fixture.api.launchConsentFetchError = CompanionApiException.MemberSuspended

        fixture.session.acceptLaunchConsent()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun terminalConsentBoundaryWriteFailureStopsBeforeProviderTeardownUntilRetry() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.syncNow()
        val signOutCalls = fixture.health.signOutCalls
        fixture.api.statusError = null
        fixture.api.launchConsentAcceptError = CompanionApiException.AccessRequired
        fixture.localState.beginSignOutSucceeds = false

        fixture.session.acceptLaunchConsent()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.localState.signOutPending)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCalls, fixture.health.signOutCalls)

        fixture.localState.beginSignOutSucceeds = true
        fixture.auth.state = AuthSessionState.SignedOut
        fixture.session.retry()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.localState.signOutPending)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
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
    fun revisedConsentSubmitsEveryCurrentDocumentForTheScope() = runTest {
        val fixture = fixture()
        val previous = LaunchConsentDocument(
            id = "privacy",
            title = "Privacy",
            version = "2026-06-01",
            href = "https://example.test/privacy",
            pdfHref = null,
        )
        val revised = previous.copy(version = "2026-08-01")
        val legal = fixture.api.launchConsentStatus.launchScopes.first {
            it.scope == LaunchConsentScope.Legal
        }
        fixture.api.launchConsentStatus = fixture.api.launchConsentStatus.copy(
            launchScopes = fixture.api.launchConsentStatus.launchScopes.map { scope ->
                when (scope.scope) {
                    LaunchConsentScope.Legal -> scope.copy(
                        documents = legal.documents + revised,
                        missingDocuments = listOf(revised),
                    )
                    LaunchConsentScope.HealthData -> scope.copy(
                        granted = true,
                        missingDocuments = emptyList(),
                    )
                }
            },
        )
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null

        fixture.session.acceptLaunchConsent()

        assertEquals(
            mapOf("legal" to "2026-07-01", "privacy" to "2026-08-01"),
            fixture.api.launchConsentAcceptances.single().second.acceptedDocumentVersions,
        )
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
        assertFalse(fixture.session.consumeHealthPermissionLaunchRequest(requestId))
        assertEquals(null, fixture.session.state.value.pendingHealthPermissionRequestId)
        assertTrue(fixture.api.intents.isEmpty())
    }

    @Test
    fun postPermissionConsentRecoveryResumesExactConnectContinuation() = runTest {
        val firstObservation = Instant.parse("2026-07-25T18:00:00Z")
        val firstReceipt = firstObservation.minusSeconds(300)
        val preConsentObservation = firstObservation.plusSeconds(60)
        val preConsentReceipt = firstReceipt.plusSeconds(30)
        val finalObservation = firstObservation.plusSeconds(120)
        val finalReceipt = preConsentReceipt.plusSeconds(30)
        val fixture = fixture(now = firstObservation)
        fixture.session.start()
        fixture.api.statusHandler = { call ->
            when (call) {
                2 -> CompanionSyncStatus(firstReceipt, firstObservation, emptyMap())
                3 -> CompanionSyncStatus(
                    preConsentReceipt,
                    preConsentObservation,
                    emptyMap(),
                )
                else -> CompanionSyncStatus(finalReceipt, finalObservation, emptyMap())
            }
        }
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.grantedCount = fixture.health.totalResourceCount
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
        assertEquals(
            InstantValue(finalObservation.toEpochMilli()),
            fixture.localState.healthAccessRequestedAt,
        )
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)

        assertEquals(
            InstantValue(finalObservation.toEpochMilli()),
            fixture.localState.healthAccessRequestedAt,
        )
        assertEquals(
            InstantValue(finalReceipt.toEpochMilli()),
            fixture.localState.healthReceiptBaselineAt,
        )
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(listOf(MEMBER_KEY), fixture.health.syncMemberKeys)
    }

    @Test
    fun postPermissionConsentRecoveryAdvancesToSyncBeforeASecondChallenge() = runTest {
        val initialObservation = Instant.parse("2026-07-25T18:00:00Z")
        val initialReceipt = initialObservation.minusSeconds(300)
        val preConsentObservation = initialObservation.plusSeconds(60)
        val preConsentReceipt = initialReceipt.plusSeconds(30)
        val setupObservation = initialObservation.plusSeconds(120)
        val setupReceipt = preConsentReceipt.plusSeconds(30)
        val qualifyingReceipt = setupObservation.plusSeconds(60)
        val fixture = fixture(now = initialObservation)
        fixture.session.start()
        fixture.api.statusHandler = { call ->
            when (call) {
                2 -> CompanionSyncStatus(initialReceipt, initialObservation, emptyMap())
                3 -> CompanionSyncStatus(
                    preConsentReceipt,
                    preConsentObservation,
                    emptyMap(),
                )
                6 -> {
                    fixture.api.launchConsentStatus = launchConsentStatus(granted = false)
                    throw CompanionApiException.ConsentRequired
                }
                in 7..Int.MAX_VALUE -> CompanionSyncStatus(
                    qualifyingReceipt,
                    setupObservation.plusSeconds(120),
                    emptyMap(),
                )
                else -> CompanionSyncStatus(setupReceipt, setupObservation, emptyMap())
            }
        }
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.signInError = CompanionApiException.ConsentRequired

        assertFalse(fixture.session.completeHealthPermissionFlow(true))
        fixture.api.signInError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(2, fixture.api.intents.count { it == ConnectionIntent.Connect })
        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(
            InstantValue(setupReceipt.toEpochMilli()),
            fixture.localState.healthReceiptBaselineAt,
        )
        assertNull(fixture.localState.lastKnownDataReceivedAt)

        fixture.session.acceptLaunchConsent()

        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertEquals(2, fixture.api.intents.count { it == ConnectionIntent.Connect })
        assertEquals(1, fixture.health.connectCalls)
        assertEquals(2, fixture.health.syncCalls)
        assertEquals(
            InstantValue(setupReceipt.toEpochMilli()),
            fixture.localState.healthReceiptBaselineAt,
        )
        assertEquals(
            InstantValue(qualifyingReceipt.toEpochMilli()),
            fixture.localState.lastKnownDataReceivedAt,
        )
        assertEquals(
            HealthSyncState.Synced(qualifyingReceipt),
            fixture.session.state.value.healthSync,
        )
    }

    @Test
    fun consentSheetDismissalDuringAcceptanceKeepsRecoveryReachable() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        assertFalse(fixture.session.prepareHealthConnection())
        fixture.api.statusError = null
        val acceptanceEntered = CompletableDeferred<Unit>()
        val acceptanceGate = CompletableDeferred<Unit>()
        fixture.api.launchConsentAcceptHandler = { _, _, _ ->
            acceptanceEntered.complete(Unit)
            acceptanceGate.await()
            launchConsentStatus(granted = true)
        }

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        acceptanceEntered.await()
        assertEquals(
            LaunchConsentRecoveryPhase.Saving,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.session.dismissLaunchConsentRecovery()

        assertFalse(fixture.session.state.value.launchConsentRecovery?.showSheet ?: true)
        fixture.session.showLaunchConsentRecovery()
        assertTrue(fixture.session.state.value.launchConsentRecovery?.showSheet == true)
        fixture.session.dismissLaunchConsentRecovery()
        acceptanceGate.complete(Unit)
        acceptance.await()

        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.session.state.value.pendingHealthPermissionRequestId != null)
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
    fun launchConsentAcceptanceStopsAfterAValidButNonProgressingResponse() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        fixture.api.launchConsentAcceptHandler = { _, _, current -> current }

        fixture.session.acceptLaunchConsent()

        assertEquals(1, fixture.api.launchConsentAcceptances.size)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertTrue(
            fixture.session.state.value.launchConsentRecovery?.message.orEmpty()
                .contains("confirm consent progress"),
        )
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
    }

    @Test
    fun launchConsentRetryRetainsFirstScopeSuccessAndDoesNotRepostIt() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        var call = 0
        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            call += 1
            if (call == 2) throw CompanionApiException.Network
            grantLaunchConsentScope(current, request.scope)
        }

        fixture.session.acceptLaunchConsent()

        assertEquals(
            listOf(LaunchConsentScope.Legal, LaunchConsentScope.HealthData),
            fixture.api.launchConsentAcceptances.map { it.second.scope },
        )
        assertEquals(
            listOf(LaunchConsentScope.HealthData),
            fixture.session.state.value.launchConsentRecovery
                ?.status
                ?.missingLaunchScopes
                ?.map { it.scope },
        )

        fixture.api.launchConsentAcceptHandler = null
        fixture.session.acceptLaunchConsent()

        assertEquals(
            listOf(
                LaunchConsentScope.Legal,
                LaunchConsentScope.HealthData,
                LaunchConsentScope.HealthData,
            ),
            fixture.api.launchConsentAcceptances.map { it.second.scope },
        )
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun localAuthFailureDuringConsentAcceptancePreservesRecoveryForRetry() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        val status = fixture.session.state.value.launchConsentRecovery?.status
        val memberKey = fixture.localState.memberKey
        val initialSetupStep = fixture.localState.initialSetupStep
        fixture.api.launchConsentAcceptError =
            CompanionApiException.LocalAuthUnavailable(
                AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
            )

        fixture.session.acceptLaunchConsent()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(status, fixture.session.state.value.launchConsentRecovery?.status)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(initialSetupStep, fixture.localState.initialSetupStep)
        assertEquals(1, fixture.api.launchConsentAcceptances.size)

        fixture.api.launchConsentAcceptError = null
        fixture.session.acceptLaunchConsent()

        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun transientPrivyUnavailabilityKeepsConsentRetryRecoverable() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        assertEquals(1, fixture.api.launchConsentFetches.size)
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable

        fixture.session.retryLaunchConsentRecovery()

        assertEquals(1, fixture.api.launchConsentFetches.size)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.retryLaunchConsentRecovery()

        assertEquals(2, fixture.api.launchConsentFetches.size)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
    }

    @Test
    fun transientPrivyUnavailabilityDoesNotDiscardConsentBeforeAcceptance() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false)

        fixture.session.acceptLaunchConsent()

        assertTrue(fixture.api.launchConsentAcceptances.isEmpty())
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusError = null
        fixture.session.retryLaunchConsentRecovery()
        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.launchConsentAcceptances.size)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun foregroundReturnReloadsActiveConsentAndHonorsAccountRemoval() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        assertEquals(1, fixture.api.launchConsentFetches.size)
        fixture.api.launchConsentFetchError = CompanionApiException.NoAccount

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(2, fixture.api.launchConsentFetches.size)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
        assertEquals(
            "This sign-in isn't linked to an active Murph account.",
            (fixture.session.state.value.phase as AppPhase.Failed).message,
        )
    }

    @Test
    fun lateLaunchConsentAcceptanceFailureCannotReplaceCompletedSignOut() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.launchConsentAcceptHandler = { _, _, _ ->
            entered.complete(Unit)
            release.await()
            throw CompanionApiException.Unauthorized
        }

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        entered.await()
        fixture.session.signOut()
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)

        release.complete(Unit)
        acceptance.await()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertNull(fixture.localState.memberKey)
    }

    @Test
    fun lateLaunchConsentReloadFailureCannotReplaceCompletedSignOut() = runTest {
        val fixture = fixture()
        fixture.api.statusError = CompanionApiException.ConsentRequired
        fixture.session.start()
        fixture.api.statusError = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.launchConsentFetchHandler = { _ ->
            entered.complete(Unit)
            release.await()
            throw CompanionApiException.NoAccount
        }

        val retry = async { fixture.session.retryLaunchConsentRecovery() }
        entered.await()
        fixture.session.signOut()
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)

        release.complete(Unit)
        retry.await()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertNull(fixture.localState.memberKey)
    }

    @Test
    fun postPermissionConsentRecoveryRechecksPermissionBeforeConnecting() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.signInError = CompanionApiException.ConsentRequired

        assertFalse(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.api.signInError = null
        fixture.health.actualGrantedCount = 0
        fixture.session.acceptLaunchConsent()

        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
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
    fun connectRetryProjectsCurrentHealthAvailabilityBeforeChoosingRecovery() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.events.clear()
        val statusCalls = fixture.api.statusSources.size
        fixture.health.availabilityState = HealthConnectAvailability.InstallOrUpdateRequired

        assertFalse(fixture.session.prepareHealthConnection())

        assertEquals(
            HealthConnectAvailability.InstallOrUpdateRequired,
            fixture.session.state.value.healthAvailability,
        )
        assertEquals(
            "Install or update Health Connect, then try again.",
            fixture.session.state.value.healthMessage,
        )
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertNull(fixture.session.state.value.pendingHealthPermissionRequestId)
        assertEquals(statusCalls, fixture.api.statusSources.size)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun explicitConnectOwnsTheFirstHealthConnection() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertEquals(InitialSetupStep.HealthConnect, fixture.session.state.value.initialSetupStep)
        fixture.events.clear()

        assertTrue(fixture.session.prepareHealthConnection())
        assertFalse(fixture.session.deferHealthConnectInitialSetup())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)

        assertTrue(fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true))
        assertEquals(listOf(ConnectionIntent.Connect), fixture.api.intents)
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.connectCalls)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.session.state.value.initialSetupStep)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(
            listOf(
                "status",
                "status",
                "token-connect",
                "identify",
                "configure",
                "connect",
                "status",
                "sync",
                "status",
            ),
            fixture.events,
        )
    }

    @Test
    fun backgroundedCompletionDefersAppOwnedSyncUntilForeground() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()
        fixture.session.didEnterBackground()
        val statusCallsBeforeCompletion = fixture.api.statusSources.size
        connectGate.complete(Unit)

        assertTrue(completion.await())
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.session.state.value.initialSetupStep)
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeCompletion, fixture.api.statusSources.size)
        fixture.session.didBecomeActive()

        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeCompletion + 2, fixture.api.statusSources.size)
    }

    @Test
    fun terminalConnectTokenFailuresCloseRuntimeAndClearTheEstablishedMember() = runTest {
        val cases = listOf(
            CompanionApiException.Unauthorized to "Sign in again",
            CompanionApiException.NoAccount to "Try a different sign-in",
            CompanionApiException.AccessRequired to "Try a different sign-in",
            CompanionApiException.MemberSuspended to "Try a different sign-in",
            CompanionApiException.AdmissionSupportRequired to "Try a different sign-in",
        )

        cases.forEach { (rejection, signOutLabel) ->
            val fixture = fixture()
            fixture.session.start()
            fixture.api.signInError = rejection

            assertTrue(fixture.session.prepareHealthConnection())
            fixture.health.signOutGate = CompletableDeferred()
            val completion = async {
                fixture.session.completeHealthPermissionFlow(true)
            }
            fixture.health.signOutEntered.await()
            runCurrent()

            assertFalse(completion.isCompleted)
            assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
            assertFalse(fixture.session.state.value.authVerifiedOnline)
            fixture.health.signOutGate?.complete(Unit)
            assertFalse(completion.await())

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertFalse(failure.canRetry)
            assertEquals(signOutLabel, failure.signOutLabel)
            assertNull(fixture.localState.memberKey)
            assertFalse(fixture.health.signedIn)
            assertEquals(1, fixture.health.signOutCalls)
            assertEquals(0, fixture.health.configureCalls)
            assertEquals(0, fixture.health.connectCalls)
            assertEquals(0, fixture.health.syncCalls)
        }
    }

    @Test
    fun accountConflictDuringConnectTokenClosesRuntimeBeforeJunctionTeardown() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.api.signInError = CompanionApiException.AccountConflict

        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.signOutGate = CompletableDeferred()
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.signOutEntered.await()
        runCurrent()

        assertFalse(completion.isCompleted)
        assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)

        fixture.health.signOutGate?.complete(Unit)
        assertFalse(completion.await())

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
        assertFalse(fixture.health.signedIn)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun connectionCompletionDuringForegroundAuthRunsOneAppOwnedSync() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()
        val statusCallsBeforeCompletion = fixture.api.statusSources.size
        val authGate = CompletableDeferred<Unit>()
        fixture.auth.currentStateGate = authGate
        fixture.auth.currentStateEntered = CompletableDeferred()
        fixture.api.addressStatusEntered = CompletableDeferred()
        fixture.api.addressStatusError = CompanionApiException.ConsentRequired

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        fixture.auth.currentStateEntered.await()
        fixture.auth.currentStateGate = null

        connectGate.complete(Unit)
        assertTrue(completion.await())
        assertEquals(1, fixture.health.syncCalls)
        authGate.complete(Unit)
        foreground.await()

        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeCompletion + 2, fixture.api.statusSources.size)
        assertFalse(fixture.api.addressStatusEntered.isCompleted)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun sameGenerationForegroundRefreshesRunAnOrdinarySyncOnce() = runTest {
        val fixture = completedHealthFixture()
        val syncCallsBeforeRefresh = fixture.health.syncCalls
        val statusCallsBeforeRefresh = fixture.api.statusSources.size
        val authCallsBeforeRefresh = fixture.auth.currentStateCalls
        val authGate = CompletableDeferred<Unit>()
        fixture.auth.currentStateGate = authGate

        fixture.session.didEnterBackground()
        val firstForeground = async { fixture.session.didBecomeActive() }
        val recreatedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertEquals(authCallsBeforeRefresh + 1, fixture.auth.currentStateCalls)

        authGate.complete(Unit)
        firstForeground.await()
        recreatedForeground.await()

        assertEquals(syncCallsBeforeRefresh + 1, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeRefresh + 2, fixture.api.statusSources.size)
    }

    @Test
    fun sameGenerationOfflineRecoveryReconcilesOnce() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.session.start()
        assertOfflineRestoreDidNotReachHealth(fixture)
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        val syncCallsBeforeRefresh = fixture.health.syncCalls
        val statusCallsBeforeRefresh = fixture.api.statusSources.size
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        fixture.api.statusGateEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val firstForeground = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()
        val recreatedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()

        statusGate.complete(Unit)
        firstForeground.await()
        recreatedForeground.await()

        assertEquals(syncCallsBeforeRefresh + 1, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeRefresh + 2, fixture.api.statusSources.size)
    }

    @Test
    fun initialForegroundCallbacksShareOneOfflineRecovery() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        fixture.api.statusGateEntered = CompletableDeferred()

        val firstForeground = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()
        val recreatedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(recreatedForeground.isCompleted)

        statusGate.complete(Unit)
        firstForeground.await()
        recreatedForeground.await()

        assertEquals(1, fixture.health.syncCalls)
        assertEquals(2, fixture.api.statusSources.size)
    }

    @Test
    fun newerForegroundOwnsOfflineRecoverySync() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        fixture.api.statusGateEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val staleForeground = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()

        fixture.session.didEnterBackground()
        val currentForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(currentForeground.isCompleted)

        statusGate.complete(Unit)
        staleForeground.await()
        currentForeground.await()

        assertEquals(1, fixture.health.syncCalls)
        assertEquals(3, fixture.api.statusSources.size)
    }

    @Test
    fun queuedForegroundCallbackCannotConsumeANewerBackgroundGeneration() = runTest {
        val fixture = completedHealthFixture()
        val syncCalls = fixture.health.syncCalls
        val authGate = CompletableDeferred<Unit>()
        fixture.auth.currentStateGate = authGate
        fixture.auth.currentStateEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val firstForeground = async { fixture.session.didBecomeActive() }
        fixture.auth.currentStateEntered.await()
        val queuedForeground = async { fixture.session.didBecomeActive() }
        runCurrent()

        fixture.session.didEnterBackground()
        authGate.complete(Unit)
        firstForeground.await()
        queuedForeground.await()
        assertEquals(syncCalls, fixture.health.syncCalls)

        fixture.auth.currentStateGate = null
        fixture.session.didBecomeActive()
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
    }

    @Test
    fun foregroundOnlineRecoveryRefreshesAStaleMissingPermissionCacheBeforeSync() = runTest {
        val fixture = offlineRestoredFixture()
        fixture.health.grantedCount = 0
        fixture.health.actualGrantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        fixture.events.clear()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(1, fixture.health.syncCalls)
        assertEquals(
            fixture.health.totalResourceCount,
            fixture.session.state.value.grantedResourceCount,
        )
        assertEquals(
            HealthSyncState.NeedsAttention(lastDataReceivedAt = null),
            fixture.session.state.value.healthSync,
        )
        assertNull(fixture.session.state.value.healthMessage)
        assertEquals(
            listOf(
                "admission",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
            fixture.events,
        )
    }

    @Test
    fun pendingHealthSetupSkipsForegroundAddressRefreshAndCompletesFirstSync() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()
        val statusCallsBeforeCompletion = fixture.api.statusSources.size
        fixture.api.addressStatusEntered = CompletableDeferred()
        fixture.api.addressStatusError = CompanionApiException.ConsentRequired

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertFalse(fixture.api.addressStatusEntered.isCompleted)

        connectGate.complete(Unit)
        assertTrue(completion.await())

        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(statusCallsBeforeCompletion + 2, fixture.api.statusSources.size)
    }

    @Test
    fun staleAddressConsentDuringHealthSetupCannotReplaceTheFirstSync() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.session.start()
        val addressStatusGate = CompletableDeferred<Unit>()
        fixture.api.addressStatusGate = addressStatusGate
        fixture.api.addressStatusEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        fixture.api.addressStatusEntered.await()
        fixture.api.addressStatusGate = null

        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()

        fixture.api.addressStatusError = CompanionApiException.ConsentRequired
        addressStatusGate.complete(Unit)
        runCurrent()
        connectGate.complete(Unit)
        assertTrue(completion.await())
        foreground.await()

        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertEquals(1, fixture.health.connectCalls)
        assertEquals(1, fixture.health.syncCalls)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun addressConsentRecoveryReconcilesHealthBeforeResuming() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.session.start()
        val syncCallsBeforeRecovery = fixture.health.syncCalls
        fixture.api.addressStatusError = CompanionApiException.ConsentRequired

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertFalse(fixture.health.signedIn)
        assertEquals(syncCallsBeforeRecovery, fixture.health.syncCalls)

        fixture.api.addressStatusError = null
        fixture.session.acceptLaunchConsent()

        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.health.signedIn)
        assertEquals(syncCallsBeforeRecovery + 1, fixture.health.syncCalls)
    }

    @Test
    fun durableHealthRestoresBeforeConsentInterruptedAddressPermission() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        fixture.api.addressStatusError = CompanionApiException.ConsentRequired

        assertFalse(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.health.signedIn)
        assertNull(fixture.session.state.value.pendingAddressBookPermissionRequestId)

        fixture.api.addressStatusError = null
        fixture.session.acceptLaunchConsent()

        assertTrue(fixture.health.signedIn)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertEquals(ConnectionIntent.Resume, fixture.api.intents.last())
        assertNull(fixture.session.state.value.addressBookMessage)
        assertTrue(fixture.session.state.value.pendingAddressBookPermissionRequestId != null)
        assertTrue(fixture.session.state.value.isAddressBookBusy)
    }

    @Test
    fun reconnectDuringAddressBookRefreshRunsTheReplacementSyncOnce() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.session.start()
        val syncCallsBeforeReconnect = fixture.health.syncCalls
        val addressStatusGate = CompletableDeferred<Unit>()
        fixture.api.addressStatusGate = addressStatusGate
        fixture.api.addressStatusEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        fixture.api.addressStatusEntered.await()
        fixture.api.addressStatusGate = null

        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()
        connectGate.complete(Unit)
        assertTrue(completion.await())
        assertEquals(syncCallsBeforeReconnect + 1, fixture.health.syncCalls)

        addressStatusGate.complete(Unit)
        foreground.await()

        assertEquals(1, fixture.health.connectCalls)
        assertEquals(syncCallsBeforeReconnect + 1, fixture.health.syncCalls)
    }

    @Test
    fun staleForegroundRefreshCannotQueueASecondAppOwnedSync() = runTest {
        val fixture = fixture()
        fixture.session.start()
        val staleRefreshGate = CompletableDeferred<Unit>()
        fixture.health.refreshGate = staleRefreshGate
        fixture.health.refreshGateOnCall = 1

        fixture.session.didEnterBackground()
        val staleForeground = async { fixture.session.didBecomeActive() }
        fixture.health.refreshEntered.await()

        assertTrue(fixture.session.prepareHealthConnection())
        fixture.session.didEnterBackground()
        assertTrue(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(0, fixture.health.syncCalls)

        val currentForeground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(currentForeground.isCompleted)
        assertEquals(0, fixture.health.syncCalls)

        staleRefreshGate.complete(Unit)
        staleForeground.await()
        currentForeground.await()

        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun staleForegroundWaitingForHealthOwnerCannotStartAppSync() = runTest {
        val fixture = completedHealthFixture()
        val syncCallsBefore = fixture.health.syncCalls
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val activeSync = async { fixture.session.syncNow() }
        fixture.api.statusGateEntered.await()

        fixture.session.didEnterBackground()
        val staleForeground = async { fixture.session.didBecomeActive() }
        runCurrent()

        fixture.session.didEnterBackground()
        val currentForeground = async { fixture.session.didBecomeActive() }
        runCurrent()

        statusGate.complete(Unit)
        activeSync.await()
        staleForeground.await()
        currentForeground.await()

        assertEquals(syncCallsBefore + 1, fixture.health.syncCalls)
    }

    @Test
    fun backgroundWhilePostCommitSyncWaitsForHealthOwnerDefersItUntilForeground() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val connectGate = CompletableDeferred<Unit>()
        fixture.health.connectGate = connectGate
        val completion = async {
            fixture.session.completeHealthPermissionFlow(true)
        }
        fixture.health.connectEntered.await()

        // Queue an authorized app sync behind the connect owner so the
        // post-commit attempt must wait for the same health mutex.
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        val activeSync = async { fixture.session.syncNow() }
        runCurrent()
        fixture.localState.healthAccessRequestedAt = null
        connectGate.complete(Unit)
        fixture.api.statusGateEntered.await()
        runCurrent()
        assertFalse(completion.isCompleted)

        fixture.session.didEnterBackground()
        statusGate.complete(Unit)
        activeSync.await()
        assertTrue(completion.await())

        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(1, fixture.health.syncCalls)
        fixture.session.didBecomeActive()
        assertEquals(2, fixture.health.syncCalls)
    }

    @Test
    fun failedFinalPreConnectStatusRefreshKeepsJunctionUntouchedAndStatusStale() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.api.statusError = CompanionApiException.Network

        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertFalse(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertFalse(fixture.session.state.value.isConnectingHealth)
    }

    @Test
    fun duplicateBasePermissionCompletionCannotRestartConnectedSetup() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())

        assertTrue(fixture.session.completeHealthPermissionFlow(true))

        assertFalse(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(1, fixture.health.identifyCalls)
        assertEquals(1, fixture.health.connectCalls)
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun failedDurableSetupCompletionRollsBackBeforeAuthorizingRestart() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        val statusObservationBeforeCompletion = fixture.localState.lastKnownStatusObservedAt
        val signOutCalls = fixture.health.signOutCalls
        fixture.localState.completeHealthAuthorizationSucceeds = false

        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.healthReceiptBaselineAt)
        assertEquals(
            statusObservationBeforeCompletion,
            fixture.localState.lastKnownStatusObservedAt,
        )
        assertFalse(fixture.session.state.value.isConnectingHealth)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(InitialSetupStep.HealthConnect, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.HealthConnect, fixture.session.state.value.initialSetupStep)
        assertEquals(
            "Murph couldn't save Health Connect setup. Try again.",
            fixture.session.state.value.healthMessage,
        )
    }

    @Test
    fun failedReconnectSetupCommitPreservesTypedReconnectAuthority() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.api.signInError = CompanionApiException.ReconnectRequired
        fixture.session.start()
        fixture.api.signInError = null
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.localState.completeHealthAuthorizationSucceeds = false

        assertFalse(fixture.session.completeHealthPermissionFlow(true))

        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.localState.healthReconnectRequired)
        assertTrue(fixture.session.state.value.healthReconnectRequired)
        val replacement = recreatedSession(fixture)
        replacement.start()
        assertTrue(replacement.state.value.healthReconnectRequired)
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
        assertEquals(InitialSetupStep.HealthConnect, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.HealthConnect, fixture.session.state.value.initialSetupStep)
        assertEquals(
            "Choose at least one Health Connect category to connect Murph.",
            fixture.session.state.value.healthMessage,
        )
    }

    @Test
    fun incompleteAggregateHealthGrantsNameTheRequiredBaseCategories() = runTest {
        val cases = listOf(
            HealthPermissionRequestResult.MissingWorkoutBase to
                "Power, speed, and elevation require Workouts. Allow Workouts in Health Connect, then try again.",
            HealthPermissionRequestResult.MissingMenstrualBase to
                "Reproductive details require Menstruation. Allow Menstruation in Health Connect, then try again.",
            HealthPermissionRequestResult.MissingWorkoutAndMenstrualBases to
                "Power, speed, and elevation require Workouts; reproductive details require Menstruation. Update Health Connect permissions, then try again.",
        )

        cases.forEach { (result, message) ->
            val fixture = fixture()
            fixture.session.start()
            assertTrue(fixture.session.prepareHealthConnection())

            assertFalse(fixture.session.completeHealthPermissionFlow(result))

            assertEquals(message, fixture.session.state.value.healthMessage)
            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertEquals(0, fixture.health.connectCalls)
        }
    }

    @Test
    fun localAuthFailureDuringResumePreservesEstablishedMemberAndHealthState() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.signInError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )
        val signOutCalls = fixture.health.signOutCalls

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertTrue(fixture.health.signedIn)

        fixture.api.signInError = null
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
    }

    @Test
    fun localAuthFailureDuringHealthStatusKeepsCachedStateReadOnlyAndRetryable() = runTest {
        val fixture = completedHealthFixture()
        val memberKey = fixture.localState.memberKey
        val requestedAt = fixture.localState.healthAccessRequestedAt
        val initialSetupStep = fixture.localState.initialSetupStep
        val signOutCalls = fixture.health.signOutCalls
        fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = false),
        )

        fixture.session.syncNow()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(initialSetupStep, fixture.localState.initialSetupStep)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertTrue(fixture.health.signedIn)

        fixture.api.statusError = null
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.authVerifiedOnline)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
    }

    @Test
    fun changedMemberDuringHealthTokenCaptureCannotRetainThePreviousOnboardingDraft() = runTest {
        val fixture = fixture()
        val changedMemberKey = "member-b"
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        assertEquals(
            "coach",
            fixture.session.state.value.initialOnboardingDraft?.mainPersonaId,
        )
        val signOutCalls = fixture.health.signOutCalls
        fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn(changedMemberKey, verifiedOnline = true),
        )

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(null, fixture.localState.memberKey)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.initialSetupStep)
        assertEquals(null, fixture.session.state.value.initialOnboarding)
        assertEquals(null, fixture.session.state.value.initialOnboardingDraft)

        val changedMemberOnboarding = pendingInitialOnboarding(contactCard = null).copy(
            preferences = InitialOnboardingPreferences(
                persona = "classic",
                tone = "casual",
                voice = "murph",
            ),
        )
        fixture.api.statusError = null
        fixture.api.initialOnboarding = changedMemberOnboarding
        fixture.auth.state = AuthSessionState.SignedIn(
            changedMemberKey,
            verifiedOnline = true,
        )
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(changedMemberKey, fixture.localState.memberKey)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(changedMemberOnboarding, fixture.session.state.value.initialOnboarding)
        assertEquals(
            "classic",
            fixture.session.state.value.initialOnboardingDraft?.mainPersonaId,
        )
        assertEquals(
            "casual",
            fixture.session.state.value.initialOnboardingDraft?.toneId,
        )

        fixture.session.saveInitialOnboarding()

        assertEquals(
            "classic",
            fixture.api.initialOnboardingCompletions.single().second.preferences?.persona,
        )
    }

    @Test
    fun signedOutLocalAuthDuringHealthStatusClearsTheMountedMember() = runTest {
        val fixture = pendingOnboardingHealthFixture()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        val signOutCalls = fixture.health.signOutCalls
        fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedOut,
        )

        fixture.session.syncNow()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.initialSetupStep)
        assertNull(fixture.localState.addressBookRevision)
        assertNull(fixture.session.state.value.initialOnboarding)
        assertNull(fixture.session.state.value.initialOnboardingDraft)
    }

    @Test
    fun failedSignedOutLocalAuthTeardownKeepsHealthAuthorityFencedUntilRetry() = runTest {
        val fixture = pendingOnboardingHealthFixture()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        val onboarding = fixture.session.state.value.initialOnboarding
        val draft = fixture.session.state.value.initialOnboardingDraft
        val memberKey = fixture.localState.memberKey
        val initialSetupStep = fixture.localState.initialSetupStep
        val addressBookRevision = fixture.localState.addressBookRevision
        val healthRequestedAt = fixture.localState.healthAccessRequestedAt
        val healthBaselineAt = fixture.localState.healthReceiptBaselineAt
        val healthObservedAt = fixture.localState.lastKnownStatusObservedAt
        val signOutCalls = fixture.health.signOutCalls
        fixture.health.signOutError = IllegalStateException("teardown failed")
        fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedOut,
        )

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertTrue(fixture.health.signedIn)
        assertEquals(memberKey, fixture.localState.memberKey)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(healthRequestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(healthBaselineAt, fixture.localState.healthReceiptBaselineAt)
        assertEquals(healthObservedAt, fixture.localState.lastKnownStatusObservedAt)
        assertEquals(initialSetupStep, fixture.localState.initialSetupStep)
        assertEquals(addressBookRevision, fixture.localState.addressBookRevision)
        assertEquals(onboarding, fixture.session.state.value.initialOnboarding)
        assertEquals(draft, fixture.session.state.value.initialOnboardingDraft)

        fixture.health.signOutError = null
        fixture.api.statusError = null
        fixture.auth.state = AuthSessionState.SignedOut
        fixture.session.retry()

        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(signOutCalls + 2, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.state.value.initialOnboarding)
        assertNull(fixture.session.state.value.initialOnboardingDraft)
    }

    @Test
    fun failedChangedMemberLocalAuthTeardownKeepsHealthAuthorityFencedUntilRetry() =
        runTest {
            val fixture = pendingOnboardingHealthFixture()
            val changedMemberKey = "member-b"
            fixture.session.selectInitialOnboardingMainPersona("coach")
            val onboarding = fixture.session.state.value.initialOnboarding
            val draft = fixture.session.state.value.initialOnboardingDraft
            val initialSetupStep = fixture.localState.initialSetupStep
            val addressBookRevision = fixture.localState.addressBookRevision
            val healthRequestedAt = fixture.localState.healthAccessRequestedAt
            val signOutCalls = fixture.health.signOutCalls
            fixture.health.signOutError = IllegalStateException("teardown failed")
            fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
                AuthSessionState.SignedIn(changedMemberKey, verifiedOnline = true),
            )

            fixture.session.syncNow()

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertTrue(failure.canRetry)
            assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
            assertTrue(fixture.health.signedIn)
            assertEquals(MEMBER_KEY, fixture.localState.memberKey)
            assertTrue(fixture.localState.signOutPending)
            assertEquals(healthRequestedAt, fixture.localState.healthAccessRequestedAt)
            assertEquals(initialSetupStep, fixture.localState.initialSetupStep)
            assertEquals(addressBookRevision, fixture.localState.addressBookRevision)
            assertEquals(onboarding, fixture.session.state.value.initialOnboarding)
            assertEquals(draft, fixture.session.state.value.initialOnboardingDraft)

            val changedMemberOnboarding = pendingInitialOnboarding(contactCard = null).copy(
                preferences = InitialOnboardingPreferences(
                    persona = "classic",
                    tone = "casual",
                    voice = "murph",
                ),
            )
            fixture.health.signOutError = null
            fixture.api.statusError = null
            fixture.api.initialOnboarding = changedMemberOnboarding
            fixture.auth.state = AuthSessionState.SignedIn(
                changedMemberKey,
                verifiedOnline = true,
            )
            fixture.session.retry()

            assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
            assertEquals(signOutCalls + 2, fixture.health.signOutCalls)
            assertFalse(fixture.health.signedIn)
            assertFalse(fixture.localState.signOutPending)
            assertEquals(changedMemberKey, fixture.localState.memberKey)
            assertEquals(changedMemberOnboarding, fixture.session.state.value.initialOnboarding)
            assertEquals(
                "classic",
                fixture.session.state.value.initialOnboardingDraft?.mainPersonaId,
            )
            assertEquals(
                "casual",
                fixture.session.state.value.initialOnboardingDraft?.toneId,
            )
        }

    @Test
    fun changedMemberStopsBeforeJunctionUntilBoundaryWriteCanBeRetried() = runTest {
        val fixture = pendingOnboardingHealthFixture()
        val signOutCalls = fixture.health.signOutCalls
        fixture.localState.beginSignOutSucceeds = false
        fixture.auth.state = AuthSessionState.SignedIn("member-b", verifiedOnline = true)
        fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn("member-b", verifiedOnline = true),
        )

        fixture.session.syncNow()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertTrue(fixture.health.signedIn)
        assertEquals(signOutCalls, fixture.health.signOutCalls)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertFalse(fixture.localState.signOutPending)
        assertEquals(0, fixture.auth.signOutCalls)

        fixture.localState.beginSignOutSucceeds = true
        fixture.api.statusError = null
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertEquals("member-b", fixture.localState.memberKey)
        assertFalse(fixture.localState.signOutPending)
        assertEquals(0, fixture.auth.signOutCalls)
    }

    @Test
    fun changedMemberFencesHealthWorkersBeforeCancelableTeardownAndReconstruction() =
        runTest {
            val fixture = pendingOnboardingHealthFixture()
            fixture.auth.state = AuthSessionState.SignedIn(
                "member-b",
                verifiedOnline = true,
            )
            fixture.api.statusError = CompanionApiException.LocalAuthUnavailable(
                AuthSessionState.SignedIn("member-b", verifiedOnline = true),
            )
            fixture.health.signOutGate = CompletableDeferred()

            val sync = launch { fixture.session.syncNow() }
            fixture.health.signOutEntered.await()

            assertFalse(sync.isCompleted)
            assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
            assertEquals(MEMBER_KEY, fixture.localState.memberKey)
            assertTrue(fixture.localState.signOutPending)
            sync.cancelAndJoin()

            fixture.api.statusError = null
            val replacementHealth = FakeHealth(fixture.events).apply {
                signedIn = true
                grantedCount = totalResourceCount
                signOutError = IllegalStateException("teardown still failing")
            }
            val blockedReplacement = recreatedSession(fixture, replacementHealth)

            blockedReplacement.start()

            assertTrue(blockedReplacement.state.value.phase is AppPhase.Failed)
            assertTrue(fixture.localState.signOutPending)
            assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
            assertEquals(1, replacementHealth.signOutCalls)
            assertEquals(0, replacementHealth.syncCalls)

            replacementHealth.signOutError = null
            fixture.events.clear()
            fixture.auth.recordCurrentStateEvents = true
            val recoveredReplacement = recreatedSession(fixture, replacementHealth)

            recoveredReplacement.start()

            assertEquals(listOf("sign-out", "auth-state"), fixture.events.take(2))
            assertEquals(AppPhase.Ready, recoveredReplacement.state.value.phase)
            assertFalse(fixture.localState.signOutPending)
            assertEquals("member-b", fixture.localState.memberKey)
            assertFalse(replacementHealth.signedIn)
            assertNull(fixture.localState.healthAccessRequestedAt)
            assertEquals(0, replacementHealth.syncCalls)
            assertEquals(0, fixture.auth.signOutCalls)
        }

    @Test
    fun signedOutLocalAuthDuringStartupOnboardingFetchReconcilesToLogin() = runTest {
        val fixture = fixture()
        fixture.api.initialOnboardingFetchError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedOut,
        )

        fixture.session.start()

        assertEquals(listOf(MEMBER_KEY), fixture.api.initialOnboardingFetches)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(1, fixture.health.signOutCalls)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.session.state.value.initialOnboarding)
        assertNull(fixture.session.state.value.initialOnboardingDraft)
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
            observedAt = now,
            resources = mapOf(
                "sleep" to CompanionSyncStatus.ResourceStatus(now.minusSeconds(600)),
            ),
        )

        fixture.session.start()

        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertEquals(listOf("health_connect", "health_connect"), fixture.api.statusSources)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
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
            observedAt = now,
            resources = mapOf(
                "sleep" to CompanionSyncStatus.ResourceStatus(now.minusSeconds(600)),
            ),
        )
        fixture.session.start()
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)

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
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
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
            observedAt = now,
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
        runCurrent()
        assertFalse(secondForeground.isCompleted)

        statusGate.complete(Unit)
        firstForeground.await()
        secondForeground.await()
        val syncCallsAfterPermissionRevocation = fixture.health.syncCalls

        assertEquals(refreshCalls + 2, fixture.health.refreshCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertEquals(
            HEALTH_PERMISSION_RECOVERY_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(0, fixture.session.state.value.grantedResourceCount)

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
            observedAt = now,
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
        val syncCalls = fixture.health.syncCalls
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
        assertTrue(fixture.localState.healthAccessRequestedAt != null)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
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
        assertTrue(fixture.localState.healthReconnectRequired)
        assertTrue(fixture.session.state.value.healthReconnectRequired)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
        assertEquals(
            "Health Connect needs to reconnect before syncing can resume.",
            fixture.session.state.value.healthMessage,
        )

        fixture.session.retry()
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertTrue(fixture.session.state.value.healthReconnectRequired)

        fixture.api.signInError = null
        assertTrue(fixture.session.prepareHealthConnection())
        assertEquals(listOf(ConnectionIntent.Resume), fixture.api.intents)
        assertTrue(fixture.session.completeHealthPermissionFlow(true))
        assertEquals(
            listOf(ConnectionIntent.Resume, ConnectionIntent.Connect),
            fixture.api.intents,
        )
        assertFalse(fixture.localState.healthReconnectRequired)
        assertFalse(fixture.session.state.value.healthReconnectRequired)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun friendlyNamesSurvivesTypedReconnectRevocationRestartAndCompletion() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.FriendlyNames
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.signInError = CompanionApiException.ReconnectRequired

        fixture.session.start()

        assertTrue(fixture.localState.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )

        val replacement = recreatedSession(fixture)
        replacement.start()
        assertTrue(replacement.state.value.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.FriendlyNames, replacement.state.value.initialSetupStep)

        fixture.api.signInError = null
        assertTrue(replacement.prepareHealthConnection())
        assertTrue(replacement.completeHealthPermissionFlow(true))

        assertFalse(fixture.localState.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.FriendlyNames, replacement.state.value.initialSetupStep)
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
            assertTrue(replacement.state.value.healthReconnectRequired)
            assertTrue(fixture.localState.healthReconnectRequired)
            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
            assertEquals(AppPhase.Ready, replacement.state.value.phase)
            assertEquals(!authUnavailable, replacement.state.value.authVerifiedOnline)

            originalStart.cancelAndJoin()
        }
    }

    @Test
    fun terminalReconnectCommitFailurePreservesPriorSetupWithoutSdkTeardown() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.lastKnownDataReceivedAt = InstantValue(2)
        fixture.localState.requireHealthReconnectSucceeds = false
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
    fun reconnectAuthoritySurvivesConnectionFailuresAndReconstruction() = runTest {
        listOf("identify", "configure", "connect").forEach { failurePoint ->
            val fixture = fixture()
            fixture.localState.memberKey = MEMBER_KEY
            fixture.localState.healthAccessRequestedAt = InstantValue(1)
            fixture.health.signedIn = true
            fixture.api.signInError = CompanionApiException.ReconnectRequired
            fixture.session.start()
            fixture.api.signInError = null
            assertTrue(fixture.session.prepareHealthConnection())
            when (failurePoint) {
                "identify" -> fixture.health.identifyError = IllegalStateException("identify failed")
                "configure" -> fixture.health.configureError = IllegalStateException("configure failed")
                "connect" -> fixture.health.connectError = IllegalStateException("connect failed")
            }

            assertFalse(fixture.session.completeHealthPermissionFlow(true))

            assertFalse(fixture.health.signedIn)
            assertEquals(null, fixture.localState.healthAccessRequestedAt)
            assertTrue(fixture.localState.healthReconnectRequired)
            assertTrue(fixture.session.state.value.healthReconnectRequired)
            val replacement = recreatedSession(fixture)
            replacement.start()
            assertTrue(fixture.localState.healthReconnectRequired)
            assertTrue(replacement.state.value.healthReconnectRequired)
            assertEquals(AppPhase.Ready, replacement.state.value.phase)
        }
    }

    @Test
    fun foregroundReturnResumesALostLiveJunctionSessionBeforeSync() = runTest {
        assertLostLiveJunctionSessionResumes(useForegroundReturn = true)
    }

    @Test
    fun newerForegroundOwnsLostSessionRecoverySync() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        fixture.health.loseLiveSession()
        val syncCalls = fixture.health.syncCalls
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        fixture.api.statusGateEntered = CompletableDeferred()

        fixture.session.didEnterBackground()
        val staleRefresh = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()

        fixture.session.didEnterBackground()
        val currentRefresh = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(currentRefresh.isCompleted)

        statusGate.complete(Unit)
        staleRefresh.await()
        currentRefresh.await()

        assertEquals(syncCalls + 1, fixture.health.syncCalls)
    }

    @Test
    fun newerForegroundWaitsForLostSessionRecoveryBeforeSync() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        fixture.health.loseLiveSession()
        val syncCalls = fixture.health.syncCalls
        val identifyCalls = fixture.health.identifyCalls
        val identifyGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = identifyGate

        fixture.session.didEnterBackground()
        val staleRefresh = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertEquals(identifyCalls + 1, fixture.health.identifyCalls)

        fixture.session.didEnterBackground()
        val currentRefresh = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(currentRefresh.isCompleted)

        identifyGate.complete(Unit)
        staleRefresh.await()
        currentRefresh.await()

        assertEquals(syncCalls + 1, fixture.health.syncCalls)
    }

    @Test
    fun startupSyncCompletingAfterBackgroundSatisfiesForegroundRefresh() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate

        val startup = async { fixture.session.start() }
        fixture.api.statusGateEntered.await()

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        statusGate.complete(Unit)
        startup.await()
        foreground.await()

        assertEquals(1, fixture.health.syncCalls)
        assertEquals(2, fixture.api.statusSources.size)
    }

    @Test
    fun startupSyncCompletedInBackgroundDoesNotSatisfyLaterForegroundRefresh() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        val statusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = statusGate
        fixture.api.statusGateOnCall = 1

        val startup = async { fixture.session.start() }
        fixture.api.statusGateEntered.await()
        fixture.session.didEnterBackground()
        statusGate.complete(Unit)
        startup.await()

        assertEquals(1, fixture.health.syncCalls)
        fixture.session.didBecomeActive()

        assertEquals(2, fixture.health.syncCalls)
        assertEquals(4, fixture.api.statusSources.size)
    }

    @Test
    fun foregroundStartingDuringPostSyncStatusRunsAnotherSync() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        val postSyncStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = postSyncStatusGate
        fixture.api.statusGateOnCall = 2

        val startup = async { fixture.session.start() }
        fixture.api.statusGateEntered.await()
        assertEquals(1, fixture.health.syncCalls)

        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        postSyncStatusGate.complete(Unit)
        startup.await()
        foreground.await()

        assertEquals(2, fixture.health.syncCalls)
        assertEquals(4, fixture.api.statusSources.size)
    }

    @Test
    fun failedPostSyncStatusDoesNotSatisfyWaitingForeground() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        val preSyncStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = preSyncStatusGate
        fixture.api.statusGateOnCall = 1
        fixture.api.statusError = CompanionApiException.Network
        fixture.api.statusErrorOnCall = 2

        val startup = async { fixture.session.start() }
        fixture.api.statusGateEntered.await()
        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        preSyncStatusGate.complete(Unit)
        startup.await()
        foreground.await()

        assertEquals(2, fixture.health.syncCalls)
        assertEquals(4, fixture.api.statusSources.size)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
    }

    @Test
    fun failedVendorSyncDoesNotSatisfyWaitingForeground() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        val preSyncStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = preSyncStatusGate
        fixture.api.statusGateOnCall = 1
        fixture.health.syncError = IllegalStateException("vendor sync failed")
        fixture.health.syncErrorOnCall = 1

        val startup = async { fixture.session.start() }
        fixture.api.statusGateEntered.await()
        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        preSyncStatusGate.complete(Unit)
        startup.await()
        foreground.await()

        assertEquals(2, fixture.health.syncCalls)
        assertEquals(4, fixture.api.statusSources.size)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
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

        assertEquals(listOf("admission", "token-resume", "sign-out"), fixture.events)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertTrue(fixture.session.state.value.healthReconnectRequired)
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
                "admission",
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
    fun sessionLostDuringPostSyncStatusResumesAndRunsAValidatedSync() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.requireCurrentProcessSetupBeforeSync = true
        val statusCalls = fixture.api.statusSources.size
        val syncCalls = fixture.health.syncCalls
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        val postSyncStatusGate = CompletableDeferred<Unit>()
        fixture.api.statusGate = postSyncStatusGate
        fixture.api.statusGateOnCall = statusCalls + 2

        val sync = async { fixture.session.syncNow() }
        fixture.api.statusGateEntered.await()
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        fixture.health.loseLiveSession()

        postSyncStatusGate.complete(Unit)
        sync.await()

        assertTrue(fixture.health.signedIn)
        assertEquals(identifyCalls + 1, fixture.health.identifyCalls)
        assertEquals(configureCalls + 1, fixture.health.configureCalls)
        assertEquals(syncCalls + 2, fixture.health.syncCalls)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
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
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()
        assertFalse(foreground.isCompleted)
        assertEquals(2, fixture.api.intents.size)

        retryTokenGate.complete(Unit)
        start.await()
        foreground.await()

        assertEquals(
            listOf(
                "admission",
                "token-resume",
                "identify",
                "configure",
                "status",
                "admission",
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
    fun preAcceptConsentOwnerSurvivesUnverifiedCandidateUntilEstablishedMemberReturns() = runTest {
        val candidateMemberKey = "did:privy:unverified-consent-candidate"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.api.statusError = CompanionApiException.ConsentRequired

        fixture.session.start()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(listOf(MEMBER_KEY), fixture.api.launchConsentFetches)
        assertFalse(candidateMemberKey in fixture.api.admissionMemberKeys)
        assertFalse(candidateMemberKey in fixture.api.statusAuthMemberKeys)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusError = null
        fixture.session.retryLaunchConsentRecovery()

        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(listOf(MEMBER_KEY, MEMBER_KEY), fixture.api.launchConsentFetches)

        fixture.session.acceptLaunchConsent()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertTrue(fixture.api.admissionMemberKeys.all { it == MEMBER_KEY })
        assertTrue(fixture.api.statusAuthMemberKeys.all { it == MEMBER_KEY })
    }

    @Test
    fun genericRetryResumesAcceptedConsentContinuationAfterUnverifiedCandidateClears() = runTest {
        val candidateMemberKey = "did:privy:unverified-accepted-consent-candidate"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired

        fixture.session.saveInitialOnboarding()

        val exactRequest = fixture.api.initialOnboardingCompletions.single().second
        fixture.api.initialOnboardingCompletionError = null
        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            grantLaunchConsentScope(current, request.scope).also { updated ->
                if (updated.launchGranted) {
                    fixture.auth.state = AuthSessionState.SignedIn(
                        memberKey = candidateMemberKey,
                        verifiedOnline = false,
                    )
                }
            }
        }

        fixture.session.acceptLaunchConsent()

        val retryableFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(retryableFailure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(1, fixture.api.initialOnboardingCompletions.size)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals("coach", fixture.session.state.value.initialOnboardingDraft?.mainPersonaId)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.retry()

        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(exactRequest, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(
            InitialOnboardingStage.Welcome,
            fixture.session.state.value.initialOnboardingStage,
        )
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.api.initialOnboardingCompletions.all { it.first == MEMBER_KEY })
    }

    @Test
    fun acceptedOnboardingReplaySurvivesUnverifiedCandidateDuringExactDispatch() = runTest {
        val candidateMemberKey = "did:privy:unverified-dispatch-candidate"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        fixture.api.initialOnboardingCompletionError = CompanionApiException.ConsentRequired

        fixture.session.saveInitialOnboarding()

        val exactRequest = fixture.api.initialOnboardingCompletions.single().second
        val exactDraft = fixture.session.state.value.initialOnboardingDraft
        fixture.api.initialOnboardingCompletionError =
            CompanionApiException.LocalAuthUnavailable(
                AuthSessionState.SignedIn(
                    memberKey = candidateMemberKey,
                    verifiedOnline = false,
                ),
            )

        fixture.session.acceptLaunchConsent()

        val retryableFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(retryableFailure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(exactDraft, fixture.session.state.value.initialOnboardingDraft)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(2, fixture.api.initialOnboardingCompletions.size)
        assertEquals(exactRequest, fixture.api.initialOnboardingCompletions.last().second)
        assertTrue(fixture.api.initialOnboardingCompletions.all { it.first == MEMBER_KEY })
        assertFalse(candidateMemberKey in fixture.api.initialOnboardingFetches)
        assertFalse(fixture.health.signedIn)

        fixture.api.initialOnboardingCompletionError = null
        fixture.session.retry()

        assertEquals(3, fixture.api.initialOnboardingCompletions.size)
        assertEquals(exactRequest, fixture.api.initialOnboardingCompletions.last().second)
        assertEquals(
            InitialOnboardingStage.Welcome,
            fixture.session.state.value.initialOnboardingStage,
        )
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.health.signedIn)
        assertTrue(fixture.api.initialOnboardingCompletions.all { it.first == MEMBER_KEY })
    }

    @Test
    fun mountedOnboardingDraftSurvivesUnverifiedCandidateAndEstablishedMemberRecovery() = runTest {
        val candidateMemberKey = "did:privy:unverified-onboarding-candidate"
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
        fixture.session.start()
        fixture.session.selectInitialOnboardingMainPersona("coach")
        val exactDraft = fixture.session.state.value.initialOnboardingDraft

        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val retryableFailure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(retryableFailure.canRetry)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(exactDraft, fixture.session.state.value.initialOnboardingDraft)
        assertTrue(fixture.session.state.value.initialOnboarding != null)
        assertFalse(candidateMemberKey in fixture.api.initialOnboardingFetches)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.retry()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(exactDraft, fixture.session.state.value.initialOnboardingDraft)
        assertTrue(fixture.session.state.value.initialOnboarding != null)
        assertTrue(fixture.api.initialOnboardingFetches.all { it == MEMBER_KEY })
    }

    @Test
    fun unverifiedDifferentMemberPreservesTheDurableOwnerAndBlocksAllProductWork() = runTest {
        val candidateMemberKey = "did:privy:candidate-member"
        val requestedAt = InstantValue(1)
        val receiptBaselineAt = InstantValue(2)
        val receivedAt = InstantValue(3)
        val observedAt = InstantValue(4)
        val pendingReplacement = AddressBookMutation(
            baseRevision = 7,
            mutationId = "00000000-0000-4000-8000-000000000001",
        )
        val fixture = fixture(memberKey = candidateMemberKey)
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.healthAccessRequestedAt = requestedAt
        fixture.localState.healthReceiptBaselineAt = receiptBaselineAt
        fixture.localState.lastKnownDataReceivedAt = receivedAt
        fixture.localState.lastKnownStatusObservedAt = observedAt
        fixture.localState.recordAddressBookRevision(7)
        fixture.localState.beginAddressBookReplacement(pendingReplacement)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(failure.canSignOut)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(receiptBaselineAt, fixture.localState.healthReceiptBaselineAt)
        assertEquals(receivedAt, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(observedAt, fixture.localState.lastKnownStatusObservedAt)
        assertFalse(fixture.localState.healthReconnectRequired)
        assertEquals(7, fixture.localState.addressBookRevision)
        assertEquals(pendingReplacement, fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertNoHealthOrBackendProductWork(fixture)
    }

    @Test
    fun unverifiedDifferentMemberFencesUnexpectedLiveHealthBeforeDurableEnforcement() = runTest {
        val candidateMemberKey = "did:privy:candidate-member"
        val pendingReplacement = AddressBookMutation(
            baseRevision = 9,
            mutationId = "00000000-0000-4000-8000-000000000002",
        )
        val fixture = fixture(memberKey = candidateMemberKey)
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.recordAddressBookRevision(9)
        fixture.localState.beginAddressBookReplacement(pendingReplacement)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(failure.canSignOut)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.healthReceiptBaselineAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertNull(fixture.localState.lastKnownStatusObservedAt)
        assertFalse(fixture.localState.healthReconnectRequired)
        assertEquals(9, fixture.localState.addressBookRevision)
        assertEquals(pendingReplacement, fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertNoHealthOrBackendProductWork(fixture)
    }

    @Test
    fun unverifiedDifferentMemberWithoutADurableOwnerIsNeverPersisted() = runTest {
        val candidateMemberKey = "did:privy:candidate-member"
        val fixture = fixture(memberKey = candidateMemberKey)
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(failure.canSignOut)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertNull(fixture.localState.memberKey)
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertTrue(fixture.health.signOutCalls >= 1)
        assertFalse(fixture.health.signedIn)
        assertNoHealthOrBackendProductWork(fixture)
    }

    @Test
    fun unverifiedDifferentMemberObservedDuringTokenAuthPreservesTheDurableOwner() = runTest {
        val candidateMemberKey = "did:privy:candidate-member"
        val requestedAt = InstantValue(1)
        val receiptBaselineAt = InstantValue(2)
        val receivedAt = InstantValue(3)
        val observedAt = InstantValue(4)
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.localState.healthAccessRequestedAt = requestedAt
        fixture.localState.healthReceiptBaselineAt = receiptBaselineAt
        fixture.localState.lastKnownDataReceivedAt = receivedAt
        fixture.localState.lastKnownStatusObservedAt = observedAt
        fixture.localState.recordAddressBookRevision(7)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.api.signInError = CompanionApiException.LocalAuthUnavailable(
            AuthSessionState.SignedIn(candidateMemberKey, verifiedOnline = false),
        )

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(failure.canSignOut)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(requestedAt, fixture.localState.healthAccessRequestedAt)
        assertEquals(receiptBaselineAt, fixture.localState.healthReceiptBaselineAt)
        assertEquals(receivedAt, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(observedAt, fixture.localState.lastKnownStatusObservedAt)
        assertEquals(7, fixture.localState.addressBookRevision)
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertEquals(listOf(MEMBER_KEY), fixture.api.tokenAuthMemberKeys)
        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.addressStatusMemberKeys.isEmpty())
        assertTrue(fixture.api.initialOnboardingFetches.isEmpty())
        assertTrue(fixture.api.launchConsentFetches.isEmpty())
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
    fun signOutDurablyFencesWorkersBeforeBlockedStartupCanRelease() = runTest {
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
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(InstantValue(2), fixture.localState.lastKnownDataReceivedAt)
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

        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
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
    fun unboundExplicitSignOutTargetSurvivesJunctionFailureAndReconstruction() = runTest {
        val fixture = fixture()
        fixture.api.admissionError = CompanionApiException.AdmissionRetryable
        fixture.session.start()
        fixture.health.signOutError = IllegalStateException("teardown failed")

        fixture.session.signOut()

        assertTrue(fixture.localState.signOutPending)
        assertNull(fixture.localState.memberKey)
        assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(0, fixture.auth.signOutCalls)

        fixture.health.signOutError = null
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(1, fixture.auth.signOutCalls)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun failedPrivyLogoutCannotResumeAfterProcessReconstruction() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.intents.clear()
        val identifyCalls = fixture.health.identifyCalls
        val syncCalls = fixture.health.syncCalls
        fixture.auth.signOutError = IllegalStateException("logout failed")

        fixture.session.signOut()

        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertFalse(fixture.health.signedIn)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)

        fixture.auth.signOutError = null
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
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
        assertEquals(authStateChecks + 1, fixture.auth.currentStateCalls)
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
    fun reconstructedPendingSignOutCompletesLocallyWhenPrivyIsAlreadySignedOut() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        assertTrue(fixture.localState.beginSignOut(MEMBER_KEY))
        fixture.auth.state = AuthSessionState.SignedOut
        fixture.health.signedIn = true

        val replacement = recreatedSession(fixture)
        replacement.start()

        assertEquals(AppPhase.NeedsLogin, replacement.state.value.phase)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.memberKey)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
        assertNoHealthOrBackendProductWork(fixture)
    }

    @Test
    fun reconstructedPendingSignOutWaitsForPrivyOwnershipBeforeClearingTheFence() = runTest {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        assertTrue(fixture.localState.beginSignOut(MEMBER_KEY))
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.health.signedIn = true

        val replacement = recreatedSession(fixture)
        replacement.start()

        assertTrue(replacement.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(1, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
        assertNoHealthOrBackendProductWork(fixture)
    }

    @Test
    fun explicitSignOutPreservesAndReconcilesANewerPrivyMember() = runTest {
        val fixture = completedHealthFixture()
        fixture.api.admissionMemberKeys.clear()
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate

        val signOut = async { fixture.session.signOut() }
        fixture.health.signOutEntered.await()
        try {
            assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
            fixture.auth.state = AuthSessionState.SignedIn("member-b", verifiedOnline = true)
        } finally {
            teardownGate.complete(Unit)
        }
        signOut.await()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals("member-b", fixture.localState.memberKey)
        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(0, fixture.auth.signOutCalls)
        assertEquals(listOf("member-b"), fixture.api.admissionMemberKeys)
    }

    @Test
    fun explicitSignOutFencesMountedMemberBeforePrivyOwnershipReturns() = runTest {
        val fixture = completedHealthFixture()
        val signOutCalls = fixture.health.signOutCalls
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.auth.recordCurrentStateEvents = true

        fixture.session.signOut()

        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
        assertEquals(signOutCalls + 1, fixture.health.signOutCalls)
        assertEquals(0, fixture.auth.signOutCalls)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(listOf("sign-out", "auth-state"), fixture.events)
    }

    @Test
    fun signOutDoesNotCrossSdkBoundariesWhenPendingWriteFails() = runTest {
        val fixture = completedHealthFixture()
        val priorSignOutCalls = fixture.health.signOutCalls
        val priorAuthStateCalls = fixture.auth.currentStateCalls
        fixture.localState.beginSignOutSucceeds = false

        fixture.session.signOut()

        assertFalse(fixture.localState.signOutPending)
        assertNull(fixture.localState.pendingPrivySignOutMemberKey)
        assertEquals(priorSignOutCalls, fixture.health.signOutCalls)
        assertEquals(priorAuthStateCalls, fixture.auth.currentStateCalls)
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
    fun signOutBoundaryStopsStatusAndSyncBeforeBlockedAuthCheckReturns() = runTest {
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

        try {
            assertTrue(fixture.localState.signOutPending)
            assertEquals(MEMBER_KEY, fixture.localState.pendingPrivySignOutMemberKey)
            assertEquals(InstantValue(1), fixture.localState.healthAccessRequestedAt)
            assertEquals(AppPhase.Launching, fixture.session.state.value.phase)
        } finally {
            authGate.complete(Unit)
        }
        sync.join()
        signOut.join()

        assertEquals(statusCount, fixture.api.statusSources.size)
        assertEquals(syncCount, fixture.health.syncCalls)
        assertFalse(fixture.localState.signOutPending)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
    }

    @Test
    fun permissionPreparationPausesSdkBeforeConnectCanStartAResourceChain() = runTest {
        val fixture = fixture()
        fixture.health.startAutomaticSyncOnConnect = true
        fixture.session.start()

        assertTrue(fixture.session.prepareHealthConnection())
        assertEquals(1, fixture.health.pauseAutomaticSyncCalls)

        assertTrue(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(1, fixture.health.automaticConnectSyncAttempts)
        assertEquals(0, fixture.health.automaticConnectResourceStarts)
        assertEquals(1, fixture.health.syncCalls)
    }

    @Test
    fun signOutWaitsForFirstSetupSyncToFinishBeforeSdkTeardown() = runTest {
        val fixture = fixture()
        fixture.session.start()
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.syncResourceCount = fixture.health.totalResourceCount
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncEntered = CompletableDeferred()

        val setup = async {
            fixture.session.completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
        fixture.health.syncEntered.await()
        val signOut = async { fixture.session.signOut() }
        runCurrent()

        assertTrue(fixture.localState.signOutPending)
        assertNotNull(fixture.localState.healthAccessRequestedAt)
        assertFalse(signOut.isCompleted)
        assertFalse(fixture.health.signOutEntered.isCompleted)
        assertEquals(1, fixture.health.syncResourceStarts)

        syncGate.complete(Unit)
        assertTrue(setup.await())
        signOut.await()

        assertEquals(fixture.health.totalResourceCount, fixture.health.syncResourceStarts)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(listOf("sign-out", "privy-sign-out"), fixture.events.takeLast(2))
    }

    @Test
    fun consentRecoveryWaitsForForegroundSyncToFinishBeforeSdkTeardown() = runTest {
        val fixture = fixture(contacts = SupportedContacts)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.session.start()
        fixture.health.syncResourceCount = fixture.health.totalResourceCount
        fixture.health.syncResourceStarts = 0
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncEntered = CompletableDeferred()
        fixture.api.addressStatusError = CompanionApiException.ConsentRequired

        val sync = async { fixture.session.syncNow() }
        fixture.health.syncEntered.await()
        val addressBook = async { fixture.session.prepareAddressBookSharing() }
        runCurrent()

        assertFalse(addressBook.isCompleted)
        assertFalse(fixture.health.signOutEntered.isCompleted)
        assertEquals(1, fixture.health.syncResourceStarts)
        assertTrue(fixture.api.launchConsentFetches.isEmpty())
        assertEquals(
            LaunchConsentRecoveryPhase.Pausing,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        syncGate.complete(Unit)
        sync.await()
        assertFalse(addressBook.await())

        assertEquals(fixture.health.totalResourceCount, fixture.health.syncResourceStarts)
        assertTrue(fixture.health.signOutEntered.isCompleted)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
    }

    @Test
    fun signOutWaitsForForegroundSyncToFinishBeforeSdkTeardown() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.syncResourceCount = fixture.health.totalResourceCount
        fixture.health.syncResourceStarts = 0
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncEntered = CompletableDeferred()

        val sync = async { fixture.session.syncNow() }
        fixture.health.syncEntered.await()
        val signOut = async { fixture.session.signOut() }
        runCurrent()

        assertTrue(fixture.localState.signOutPending)
        assertFalse(signOut.isCompleted)
        assertFalse(fixture.health.signOutEntered.isCompleted)
        assertEquals(1, fixture.health.syncResourceStarts)

        syncGate.complete(Unit)
        sync.await()
        signOut.await()

        assertEquals(fixture.health.totalResourceCount, fixture.health.syncResourceStarts)
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(listOf("sign-out", "privy-sign-out"), fixture.events.takeLast(2))
    }

    @Test
    fun memberSwitchWaitsForForegroundSyncToFinishBeforeSdkTeardown() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.syncResourceCount = fixture.health.totalResourceCount
        fixture.health.syncResourceStarts = 0
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncEntered = CompletableDeferred()

        val sync = async { fixture.session.syncNow() }
        fixture.health.syncEntered.await()
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = "did:privy:replacement-member",
            verifiedOnline = true,
        )
        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertTrue(fixture.localState.signOutPending)
        assertFalse(foreground.isCompleted)
        assertFalse(fixture.health.signOutEntered.isCompleted)
        assertEquals(1, fixture.health.syncResourceStarts)
        assertTrue(fixture.health.identifiedMemberKeys.none { it == "did:privy:replacement-member" })

        syncGate.complete(Unit)
        sync.await()
        foreground.await()

        assertEquals(fixture.health.totalResourceCount, fixture.health.syncResourceStarts)
        assertEquals("did:privy:replacement-member", fixture.localState.memberKey)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun sameMemberResumeWaitsForForegroundSyncBeforeSdkIdentityReset() = runTest {
        val fixture = completedHealthFixture()
        fixture.health.resetOnSameMemberIdentify = true
        fixture.health.syncResourceCount = fixture.health.totalResourceCount
        fixture.health.syncResourceStarts = 0
        val syncGate = CompletableDeferred<Unit>()
        fixture.health.syncGate = syncGate
        fixture.health.syncEntered = CompletableDeferred()

        val sync = async { fixture.session.syncNow() }
        fixture.health.syncEntered.await()

        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertFalse(fixture.session.state.value.authVerifiedOnline)

        val tokenCount = fixture.api.intents.size
        val identifyCalls = fixture.health.identifyCalls
        val configureCalls = fixture.health.configureCalls
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        val foreground = async { fixture.session.didBecomeActive() }
        runCurrent()

        assertFalse(foreground.isCompleted)
        assertEquals(tokenCount, fixture.api.intents.size)
        assertEquals(identifyCalls, fixture.health.identifyCalls)
        assertEquals(configureCalls, fixture.health.configureCalls)
        assertEquals(0, fixture.health.sameMemberIdentifyResetCalls)

        syncGate.complete(Unit)
        sync.await()
        foreground.await()

        assertEquals(tokenCount + 1, fixture.api.intents.size)
        assertEquals(ConnectionIntent.Resume, fixture.api.intents.last())
        assertEquals(identifyCalls + 1, fixture.health.identifyCalls)
        assertEquals(configureCalls + 1, fixture.health.configureCalls)
        assertEquals(1, fixture.health.sameMemberIdentifyResetCalls)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
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
        fixture.localState.lastKnownStatusObservedAt = InstantValue(now.toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true

        fixture.session.start()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(fixture.health.totalResourceCount, fixture.session.state.value.grantedResourceCount)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.statusSources.isEmpty())
    }

    @Test
    fun failedStatusRefreshMarksCachedSnapshotStaleUntilSuccessfulCheck() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            observedAt = now,
            resources = emptyMap(),
        )
        fixture.session.start()
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertFalse(fixture.session.state.value.healthStatusIsStale)

        fixture.api.statusError = CompanionApiException.Network
        fixture.session.syncNow()

        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(
            "Murph couldn't verify your account. Saved status is still shown.",
            fixture.session.state.value.healthMessage,
        )

        fixture.api.statusError = null
        fixture.session.syncNow()

        assertFalse(fixture.session.state.value.healthStatusIsStale)
        assertEquals(null, fixture.session.state.value.healthMessage)
    }

    @Test
    fun failedForegroundPermissionReloadKeepsCachedStatusStaleAndSkipsSync() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(now.minusSeconds(600).toEpochMilli())
        fixture.localState.lastKnownStatusObservedAt = InstantValue(now.toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(600),
            observedAt = now,
            resources = emptyMap(),
        )
        fixture.session.start()
        val statusCalls = fixture.api.statusSources.size
        val syncCalls = fixture.health.syncCalls
        fixture.health.actualGrantedCount = 0
        fixture.health.refreshError = IllegalStateException("permission reload unavailable")

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(
            HEALTH_PERMISSION_VERIFICATION_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(statusCalls, fixture.api.statusSources.size)
        assertEquals(syncCalls, fixture.health.syncCalls)

        fixture.session.syncNow()

        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(statusCalls, fixture.api.statusSources.size)
        assertEquals(syncCalls, fixture.health.syncCalls)

        fixture.health.refreshError = null
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertFalse(fixture.session.state.value.healthStatusIsStale)
        assertEquals(0, fixture.session.state.value.grantedResourceCount)
        assertEquals(HEALTH_PERMISSION_RECOVERY_MESSAGE, fixture.session.state.value.healthMessage)
        assertEquals(syncCalls, fixture.health.syncCalls)
    }

    @Test
    fun failedStartupPermissionReloadKeepsReadyStatusStaleUntilVerified() = runTest {
        val now = Instant.parse("2026-07-25T18:00:00Z")
        val fixture = fixture(now = now)
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt =
            InstantValue(now.minusSeconds(3_600).toEpochMilli())
        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(now.minusSeconds(600).toEpochMilli())
        fixture.localState.lastKnownStatusObservedAt = InstantValue(now.toEpochMilli())
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.actualGrantedCount = fixture.health.totalResourceCount
        fixture.health.refreshError = IllegalStateException("permission reload unavailable")
        fixture.api.status = CompanionSyncStatus(
            lastDataReceivedAt = now.minusSeconds(300),
            observedAt = now,
            resources = emptyMap(),
        )

        fixture.session.start()

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.healthSync is HealthSyncState.Synced)
        assertTrue(fixture.session.state.value.healthStatusIsStale)
        assertEquals(
            HEALTH_PERMISSION_VERIFICATION_MESSAGE,
            fixture.session.state.value.healthMessage,
        )
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.statusSources.isEmpty())

        fixture.health.refreshError = null
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.session.state.value.healthStatusIsStale)
        assertEquals(null, fixture.session.state.value.healthMessage)
        assertEquals(1, fixture.health.syncCalls)
        assertEquals(2, fixture.api.statusSources.size)
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
        fixture.api.status = CompanionSyncStatus(now.minusSeconds(600), now, emptyMap())
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
        fixture.localState.lastKnownStatusObservedAt = InstantValue(now.toEpochMilli())
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
    fun noSetupOfflineRecoveryDoesNotUseStatusAsAdmission() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.session.start()
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusError = CompanionApiException.NoAccount

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals(listOf(ZoneId.systemDefault().id), fixture.api.admissionTimeZones)
        assertEquals(listOf("health_connect"), fixture.api.statusSources)
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.connectCalls)
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
            listOf(
                "admission",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
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
            listOf(
                "admission",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
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

        assertEquals(listOf("admission", "token-resume", "sign-out"), fixture.events)
        assertEquals(null, fixture.localState.healthAccessRequestedAt)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertEquals(HealthSyncState.NotConnected, fixture.session.state.value.healthSync)
        assertTrue(fixture.session.state.value.healthReconnectRequired)
    }

    @Test
    fun revokedHealthSetupTearsDownWithoutDiscardingTheOfflineOwner() = runTest {
        val fixture = fixture()
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        fixture.localState.memberKey = MEMBER_KEY
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount

        fixture.session.start()

        assertEquals(1, fixture.health.signOutCalls)
        assertFalse(fixture.health.signedIn)
        assertEquals(1, fixture.auth.currentStateCalls)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.intents.isEmpty())
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.syncCalls)
    }

    @Test
    fun orphanJunctionTeardownPreservesOwnerWhenAuthChangesAfterInitialSample() = runTest {
        val candidateMemberKey = "did:privy:unverified-candidate"
        val pendingReplacement = AddressBookMutation(
            baseRevision = 11,
            mutationId = "00000000-0000-4000-8000-000000000003",
        )
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.initialSetupStep = InitialSetupStep.FriendlyNames
        fixture.localState.recordAddressBookRevision(11)
        fixture.localState.beginAddressBookReplacement(pendingReplacement)
        fixture.localState.memberKeyWrites.clear()
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        val teardownGate = CompletableDeferred<Unit>()
        fixture.health.signOutGate = teardownGate

        val startup = async { fixture.session.start() }
        fixture.health.signOutEntered.await()
        assertEquals(1, fixture.auth.currentStateCalls)
        fixture.auth.state = AuthSessionState.SignedIn(
            memberKey = candidateMemberKey,
            verifiedOnline = false,
        )

        teardownGate.complete(Unit)
        startup.await()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertTrue(failure.canRetry)
        assertTrue(failure.canSignOut)
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        assertEquals(MEMBER_KEY, fixture.localState.memberKey)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertNull(fixture.localState.healthAccessRequestedAt)
        assertNull(fixture.localState.healthReceiptBaselineAt)
        assertNull(fixture.localState.lastKnownDataReceivedAt)
        assertNull(fixture.localState.lastKnownStatusObservedAt)
        assertEquals(11, fixture.localState.addressBookRevision)
        assertEquals(pendingReplacement, fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(0, fixture.localState.clearMemberScopedStateCalls)
        assertFalse(candidateMemberKey in fixture.localState.memberKeyWrites)
        assertNull(fixture.session.currentMemberKeyForTest())
        assertTrue(fixture.health.signOutCalls >= 1)
        assertFalse(fixture.health.signedIn)
        assertNoHealthOrBackendProductWork(fixture)
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
        assertEquals(1, fixture.auth.currentStateCalls)
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
    fun permissionRecoveryRevokesPriorSetupOnlyForTerminalBackendRejection() = runTest {
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
        fixture.localState.lastKnownStatusObservedAt = InstantValue(now.toEpochMilli())
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.actualGrantedCount = fixture.health.totalResourceCount
        fixture.api.status = CompanionSyncStatus(now.minusSeconds(600), now, emptyMap())
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
        assertEquals(0, fixture.api.statusSources.size)
        assertEquals(2, fixture.health.refreshCalls)
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
                observedAt = now,
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
            observedAt = now,
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
        val finalPreConnectReceipt = now.minusSeconds(300)
        val finalPreConnectObservation = now.plusSeconds(60)
        val fixture = fixture(now = now)
        fixture.api.status = CompanionSyncStatus(oldReceipt, now, emptyMap())
        fixture.session.start()
        fixture.api.statusHandler = { call ->
            if (call == 2) {
                CompanionSyncStatus(oldReceipt, now, emptyMap())
            } else {
                CompanionSyncStatus(
                    finalPreConnectReceipt,
                    finalPreConnectObservation,
                    emptyMap(),
                )
            }
        }
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.syncError = IllegalStateException("vendor sync failed")
        assertTrue(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(
            InstantValue(finalPreConnectReceipt.toEpochMilli()),
            fixture.localState.healthReceiptBaselineAt,
        )
        assertEquals(
            InstantValue(finalPreConnectObservation.toEpochMilli()),
            fixture.localState.healthAccessRequestedAt,
        )

        fixture.api.statusHandler = null
        val advancedReceipt = finalPreConnectObservation.plusSeconds(60)
        fixture.api.status = CompanionSyncStatus(
            advancedReceipt,
            finalPreConnectObservation.plusSeconds(60),
            emptyMap(),
        )
        fixture.session.syncNow()

        assertEquals(
            HealthSyncState.Synced(advancedReceipt),
            fixture.session.state.value.healthSync,
        )
        assertEquals(
            InstantValue(advancedReceipt.toEpochMilli()),
            fixture.localState.lastKnownDataReceivedAt,
        )

        fixture.localState.lastKnownDataReceivedAt =
            InstantValue(finalPreConnectReceipt.toEpochMilli())
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        val replacement = recreatedSession(fixture)
        replacement.start()

        assertEquals(HealthSyncState.AwaitingFirstData, replacement.state.value.healthSync)
    }

    @Test
    fun setupObservationIsTheStrictReceiptFloorWhenBaselineIsEmpty() = runTest {
        val initialObservation = Instant.parse("2026-07-25T18:00:00Z")
        val setupBoundary = initialObservation.plusSeconds(60)
        val fixture = fixture(now = initialObservation)
        fixture.session.start()
        fixture.api.statusHandler = { call ->
            when (call) {
                2 -> CompanionSyncStatus(null, initialObservation, emptyMap())
                3 -> CompanionSyncStatus(null, setupBoundary, emptyMap())
                else -> CompanionSyncStatus(
                    setupBoundary,
                    setupBoundary.plusSeconds(60),
                    emptyMap(),
                )
            }
        }
        assertTrue(fixture.session.prepareHealthConnection())
        fixture.health.syncError = IllegalStateException("vendor sync failed")
        assertTrue(fixture.session.completeHealthPermissionFlow(true))

        assertEquals(null, fixture.localState.healthReceiptBaselineAt)
        assertEquals(
            InstantValue(setupBoundary.toEpochMilli()),
            fixture.localState.healthAccessRequestedAt,
        )
        assertEquals(null, fixture.localState.lastKnownDataReceivedAt)
        assertEquals(HealthSyncState.AwaitingFirstData, fixture.session.state.value.healthSync)

        fixture.localState.lastKnownDataReceivedAt = InstantValue(setupBoundary.toEpochMilli())
        fixture.auth.state = AuthSessionState.TemporarilyUnavailable
        val replacement = recreatedSession(fixture)
        replacement.start()
        assertEquals(HealthSyncState.AwaitingFirstData, replacement.state.value.healthSync)

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.api.statusHandler = null
        val qualifyingReceipt = setupBoundary.plusSeconds(1)
        fixture.api.status = CompanionSyncStatus(
            qualifyingReceipt,
            setupBoundary.plusSeconds(120),
            emptyMap(),
        )
        fixture.health.syncError = null
        replacement.retry()
        assertEquals(
            HealthSyncState.Synced(qualifyingReceipt),
            replacement.state.value.healthSync,
        )
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
            listOf(
                "admission",
                "token-resume",
                "identify",
                "configure",
                "status",
                "sync",
                "status",
            ),
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

        val terminalBoundary = when (failure) {
            CompanionApiException.Unauthorized,
            CompanionApiException.NoAccount,
            CompanionApiException.AccessRequired,
            CompanionApiException.MemberSuspended,
            CompanionApiException.AdmissionSupportRequired -> true
            else -> false
        }
        assertEquals(
            if (isRecovery && terminalBoundary) null else requestedAt,
            fixture.localState.healthAccessRequestedAt,
        )
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
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
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
        val unverifiedForeground = async { fixture.session.didBecomeActive() }
        yield()
        assertTrue(unverifiedForeground.isCompleted)
        unverifiedForeground.await()
        assertFalse(fixture.session.state.value.authVerifiedOnline)
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_KEY, verifiedOnline = true)
        fixture.session.didEnterBackground()
        val verifiedForeground = async { fixture.session.didBecomeActive() }
        fixture.api.statusGateEntered.await()
        fixture.auth.state = finalAuthState
        fixture.session.didEnterBackground()
        val finalForeground = async { fixture.session.didBecomeActive() }
        yield()
        assertFalse(finalForeground.isCompleted)

        reconciliationStatusGate.complete(Unit)
        verifiedForeground.await()
        finalForeground.await()
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

    private fun fixture(
        now: Instant = Instant.parse("2026-07-25T18:00:00Z"),
        memberKey: String = MEMBER_KEY,
        contacts: AddressBookContactSource = UnsupportedAddressBookContactSource,
    ): Fixture {
        val events = mutableListOf<String>()
        val auth = FakeAuth(
            state = AuthSessionState.SignedIn(memberKey, verifiedOnline = true),
            events = events,
        )
        val api = FakeApi(events, now)
        val health = FakeHealth(events)
        val localState = FakeLocalState()
        val session = createSession(auth, api, health, localState, contacts)
        return Fixture(session, auth, api, health, localState, events)
    }

    private fun recreatedSession(
        fixture: Fixture,
        health: FakeHealth = fixture.health,
    ): AppSession = createSession(
        auth = fixture.auth,
        api = fixture.api,
        health = health,
        localState = fixture.localState,
    )

    private fun createSession(
        auth: FakeAuth,
        api: FakeApi,
        health: FakeHealth,
        localState: FakeLocalState,
        contacts: AddressBookContactSource = UnsupportedAddressBookContactSource,
    ) = AppSession(
        auth = auth,
        api = api,
        health = health,
        contacts = contacts,
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

    private suspend fun pendingOnboardingHealthFixture(): Fixture {
        val fixture = fixture()
        fixture.localState.memberKey = MEMBER_KEY
        fixture.localState.healthAccessRequestedAt = InstantValue(1)
        fixture.localState.initialSetupStep = InitialSetupStep.Complete
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.signedIn = true
        fixture.api.initialOnboarding = pendingInitialOnboarding(contactCard = null)
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

    private fun assertNoHealthOrBackendProductWork(fixture: Fixture) {
        assertEquals(0, fixture.auth.identityTokenCalls)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(0, fixture.health.refreshCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.admissionTimeZones.isEmpty())
        assertTrue(fixture.api.signInRequests.isEmpty())
        assertTrue(fixture.api.tokenAuthMemberKeys.isEmpty())
        assertTrue(fixture.api.statusAuthMemberKeys.isEmpty())
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.addressStatusMemberKeys.isEmpty())
        assertTrue(fixture.api.launchConsentFetches.isEmpty())
        assertTrue(fixture.api.launchConsentAcceptances.isEmpty())
        assertTrue(fixture.api.initialOnboardingFetches.isEmpty())
        assertTrue(fixture.api.initialOnboardingCompletions.isEmpty())
        assertTrue(fixture.api.initialOnboardingContactCards.isEmpty())
    }

    private fun assertNoPostAdmissionProductWork(fixture: Fixture) {
        assertEquals(0, fixture.auth.identityTokenCalls)
        assertEquals(0, fixture.health.identifyCalls)
        assertEquals(0, fixture.health.configureCalls)
        assertEquals(0, fixture.health.connectCalls)
        assertEquals(0, fixture.health.refreshCalls)
        assertEquals(0, fixture.health.syncCalls)
        assertTrue(fixture.api.signInRequests.isEmpty())
        assertTrue(fixture.api.tokenAuthMemberKeys.isEmpty())
        assertTrue(fixture.api.statusAuthMemberKeys.isEmpty())
        assertTrue(fixture.api.statusSources.isEmpty())
        assertTrue(fixture.api.addressStatusMemberKeys.isEmpty())
        assertTrue(fixture.api.launchConsentFetches.isEmpty())
        assertTrue(fixture.api.launchConsentAcceptances.isEmpty())
        assertTrue(fixture.api.initialOnboardingFetches.isEmpty())
        assertTrue(fixture.api.initialOnboardingCompletions.isEmpty())
        assertTrue(fixture.api.initialOnboardingContactCards.isEmpty())
    }

    private fun AppSession.currentMemberKeyForTest(): String? {
        val field = AppSession::class.java.getDeclaredField("currentMemberKey")
        field.isAccessible = true
        return field.get(this) as String?
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
        var currentStateEntered = CompletableDeferred<Unit>()
        var currentStateErrorOnce: Throwable? = null
        var signOutError: Throwable? = null
        var currentStateCalls = 0
        var identityTokenCalls = 0
        var signOutCalls = 0
        var recordCurrentStateEvents = false

        override suspend fun currentState(): AuthSessionState {
            currentStateCalls += 1
            if (recordCurrentStateEvents) events += "auth-state"
            currentStateEntered.complete(Unit)
            currentStateGate?.await()
            currentStateErrorOnce?.let { error ->
                currentStateErrorOnce = null
                throw error
            }
            return state
        }
        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) = Unit
        override suspend fun identityToken(): String {
            identityTokenCalls += 1
            return "identity-token"
        }
        override suspend fun signOut() {
            signOutCalls += 1
            events += "privy-sign-out"
            signOutError?.let { throw it }
            state = AuthSessionState.SignedOut
        }
    }

    private class FakeApi(
        private val events: MutableList<String>,
        observedAt: Instant,
    ) : CompanionApi {
        val admissionMemberKeys = mutableListOf<String>()
        val admissionTimeZones = mutableListOf<String>()
        var admissionError: Throwable? = null
        var admissionHandler: (suspend (String) -> Unit)? = null
        val intents = mutableListOf<ConnectionIntent?>()
        val signInRequests = mutableListOf<SignInTokenRequest>()
        val tokenAuthMemberKeys = mutableListOf<String>()
        val statusAuthMemberKeys = mutableListOf<String>()
        val statusSources = mutableListOf<String>()
        var status = CompanionSyncStatus(
            lastDataReceivedAt = null,
            observedAt = observedAt,
            resources = emptyMap(),
        )
        var signInError: Throwable? = null
        var statusError: Throwable? = null
        var statusErrorOnCall: Int? = null
        var statusHandler: (suspend (Int) -> CompanionSyncStatus)? = null
        var statusGate: CompletableDeferred<Unit>? = null
        var statusGateOnCall: Int? = null
        val statusEntered = CompletableDeferred<Unit>()
        var statusGateEntered = CompletableDeferred<Unit>()
        var addressStatusGate: CompletableDeferred<Unit>? = null
        var addressStatusEntered = CompletableDeferred<Unit>()
        var addressStatusError: Throwable? = null
        val addressStatusMemberKeys = mutableListOf<String>()
        var maximumStatusCalls: Int? = null
        var signInGate: CompletableDeferred<Unit>? = null
        var signInGateOnCall: Int? = null
        var maximumSignInCalls: Int? = null
        val signInGateEntered = CompletableDeferred<Unit>()
        var launchConsentStatus = launchConsentStatus(granted = false)
        var launchConsentFetchError: Throwable? = null
        var launchConsentFetchHandler: (suspend (String) -> LaunchConsentStatus)? = null
        var launchConsentAcceptError: Throwable? = null
        var launchConsentAcceptHandler: (suspend (
            String,
            LaunchConsentAcceptanceRequest,
            LaunchConsentStatus,
        ) -> LaunchConsentStatus)? = null
        val launchConsentFetches = mutableListOf<String>()
        val launchConsentAcceptances =
            mutableListOf<Pair<String, LaunchConsentAcceptanceRequest>>()
        var initialOnboarding = completedInitialOnboarding()
        var initialOnboardingFetchError: Throwable? = null
        val initialOnboardingFetches = mutableListOf<String>()
        var initialOnboardingCompletionError: Throwable? = null
        var initialOnboardingCompletionGate: CompletableDeferred<Unit>? = null
        val initialOnboardingCompletionEntered = CompletableDeferred<Unit>()
        var initialOnboardingCompletedNow = true
        var initialOnboardingContactCardError: Throwable? = null
        var initialOnboardingContactCardGate: CompletableDeferred<Unit>? = null
        var initialOnboardingContactCardEntered = CompletableDeferred<Unit>()
        val initialOnboardingCompletions =
            mutableListOf<Pair<String, InitialOnboardingCompletionRequest>>()
        val initialOnboardingContactCards =
            mutableListOf<Pair<String, InitialOnboardingContactCardRequest>>()

        override suspend fun admitCompanion(memberKey: String, timeZone: String) {
            admissionMemberKeys += memberKey
            admissionTimeZones += timeZone
            events += "admission"
            admissionHandler?.invoke(memberKey)
            admissionError?.let { throw it }
        }

        override suspend fun createJunctionSignInToken(
            memberKey: String,
            request: SignInTokenRequest,
        ): SignInTokenResponse {
            signInRequests += request
            intents += request.connectionIntent
            tokenAuthMemberKeys += memberKey
            events += "token-${request.connectionIntent?.wireValue ?: "omitted"}"
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

        override suspend fun fetchSyncStatus(
            memberKey: String,
            sourceProviderSlug: String,
        ): CompanionSyncStatus {
            statusAuthMemberKeys += memberKey
            statusSources += sourceProviderSlug
            events += "status"
            maximumStatusCalls?.let { maximum ->
                assertTrue(
                    "Unexpected extra companion status request.",
                    statusSources.size <= maximum,
                )
            }
            statusEntered.complete(Unit)
            statusGate?.takeIf {
                statusGateOnCall == null || statusGateOnCall == statusSources.size
            }?.let { gate ->
                statusGateEntered.complete(Unit)
                gate.await()
            }
            statusError?.takeIf {
                statusErrorOnCall == null || statusErrorOnCall == statusSources.size
            }?.let { throw it }
            statusHandler?.let { handler -> return handler(statusSources.size) }
            return status
        }

        override suspend fun fetchAddressBookStatus(
            memberKey: String,
        ): AddressBookServerStatus {
            addressStatusMemberKeys += memberKey
            addressStatusEntered.complete(Unit)
            addressStatusGate?.await()
            addressStatusError?.let { throw it }
            return AddressBookServerStatus(
                writeCapability = AddressBookWriteCapability.Enabled,
                enabled = false,
                revision = 0,
                storedContactCount = 0,
            )
        }

        override suspend fun fetchLaunchConsentStatus(memberKey: String): LaunchConsentStatus {
            launchConsentFetches += memberKey
            events += "consent-get"
            launchConsentFetchHandler?.let { handler ->
                return handler(memberKey).also { launchConsentStatus = it }
            }
            launchConsentFetchError?.let { throw it }
            return launchConsentStatus
        }

        override suspend fun fetchInitialOnboarding(memberKey: String): InitialOnboarding {
            initialOnboardingFetches += memberKey
            initialOnboardingFetchError?.let { throw it }
            return initialOnboarding
        }

        override suspend fun completeInitialOnboarding(
            memberKey: String,
            request: InitialOnboardingCompletionRequest,
        ): InitialOnboarding {
            initialOnboardingCompletions += memberKey to request
            initialOnboardingCompletionEntered.complete(Unit)
            initialOnboardingCompletionGate?.await()
            initialOnboardingCompletionError?.let { throw it }
            return completedInitialOnboarding(completedNow = initialOnboardingCompletedNow)
        }

        override suspend fun prepareInitialOnboardingContactCard(
            memberKey: String,
            request: InitialOnboardingContactCardRequest,
        ): InitialOnboardingContactCardHandoff {
            initialOnboardingContactCards += memberKey to request
            initialOnboardingContactCardEntered.complete(Unit)
            initialOnboardingContactCardGate?.await()
            initialOnboardingContactCardError?.let { throw it }
            return InitialOnboardingContactCardHandoff("https://example.test/contact-card")
        }

        override suspend fun acceptLaunchConsent(
            memberKey: String,
            request: LaunchConsentAcceptanceRequest,
        ): LaunchConsentStatus {
            launchConsentAcceptances += memberKey to request
            events += "consent-post:${request.scope.wireValue}"
            launchConsentAcceptError?.let { throw it }
            launchConsentAcceptHandler?.let { handler ->
                return handler(memberKey, request, launchConsentStatus).also {
                    launchConsentStatus = it
                }
            }
            launchConsentStatus = grantLaunchConsentScope(launchConsentStatus, request.scope)
            return launchConsentStatus
        }
    }

    private class FakeHealth(private val events: MutableList<String>) : HealthSyncing {
        override val totalResourceCount = 11
        var signedIn = false
        var identifyCalls = 0
        val identifiedMemberKeys = mutableListOf<String>()
        var configureCalls = 0
        var pauseAutomaticSyncCalls = 0
        var connectCalls = 0
        var startAutomaticSyncOnConnect = false
        var automaticConnectSyncAttempts = 0
        var automaticConnectResourceStarts = 0
        var syncCalls = 0
        val syncMemberKeys = mutableListOf<String>()
        var syncResourceCount = 1
        var syncResourceStarts = 0
        var refreshCalls = 0
        var signOutCalls = 0
        var grantedCount = 0
        var actualGrantedCount: Int? = null
        var identifyGate: CompletableDeferred<Unit>? = null
        val identifyEntered = CompletableDeferred<Unit>()
        var signOutGate: CompletableDeferred<Unit>? = null
        val signOutEntered = CompletableDeferred<Unit>()
        var signOutError: Throwable? = null
        var identifyError: Throwable? = null
        var configureError: Throwable? = null
        var connectError: Throwable? = null
        var syncError: Throwable? = null
        var refreshError: Throwable? = null
        var syncErrorOnCall: Int? = null
        var loseSessionOnSyncError = false
        var resetOnSameMemberIdentify = false
        var sameMemberIdentifyResetCalls = 0
        var connectGate: CompletableDeferred<Unit>? = null
        val connectEntered = CompletableDeferred<Unit>()
        var syncGate: CompletableDeferred<Unit>? = null
        var syncEntered = CompletableDeferred<Unit>()
        var refreshGate: CompletableDeferred<Unit>? = null
        var refreshGateOnCall: Int? = null
        val refreshEntered = CompletableDeferred<Unit>()
        var requireCurrentProcessSetupBeforeSync = false
        var availabilityState = HealthConnectAvailability.Available
        var availabilityHook: (() -> Unit)? = null
        private var identifiedInCurrentProcess = false
        private var configuredInCurrentProcess = false
        private var automaticSyncPaused = false
        private var identifiedMemberKey: String? = null

        override fun availability(): HealthConnectAvailability {
            availabilityHook?.invoke()
            return availabilityState
        }
        override fun openHealthConnectIntent(): Intent? = null
        override fun isSignedIn(): Boolean = signedIn
        override fun pauseAutomaticSync() {
            pauseAutomaticSyncCalls += 1
            automaticSyncPaused = true
        }
        override fun configure() {
            configureCalls += 1
            configuredInCurrentProcess = true
            automaticSyncPaused = true
            events += "configure"
            configureError?.let { throw it }
        }
        override fun grantedResourceCount(): Int = grantedCount

        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) {
            assertTrue(memberKey.isNotBlank())
            assertEquals("junction-token", authenticate())
            if (
                resetOnSameMemberIdentify &&
                signedIn &&
                identifiedMemberKey == memberKey
            ) {
                sameMemberIdentifyResetCalls += 1
                events += "same-member-identify-reset"
                signedIn = false
            }
            identifyCalls += 1
            identifiedMemberKeys += memberKey
            identifiedMemberKey = memberKey
            identifiedInCurrentProcess = true
            events += "identify"
            identifyEntered.complete(Unit)
            identifyGate?.await()
            signedIn = true
            identifyError?.let { throw it }
        }

        override suspend fun connectAfterPermissionRequest() {
            connectCalls += 1
            events += "connect"
            connectEntered.complete(Unit)
            connectGate?.await()
            connectError?.let { throw it }
            grantedCount = totalResourceCount
            if (startAutomaticSyncOnConnect) {
                automaticConnectSyncAttempts += 1
                if (!automaticSyncPaused) {
                    repeat(totalResourceCount) {
                        if (!signedIn) return
                        automaticConnectResourceStarts += 1
                    }
                }
            }
        }

        override suspend fun refreshPermissionState() {
            refreshCalls += 1
            if (refreshCalls == refreshGateOnCall) {
                refreshEntered.complete(Unit)
                refreshGate?.await()
            }
            refreshError?.let { throw it }
            actualGrantedCount?.let { grantedCount = it }
        }

        override suspend fun syncAllGrantedResources(expectedMemberKey: String) {
            syncMemberKeys += expectedMemberKey
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
            repeat(syncResourceCount) { resourceIndex ->
                if (!signedIn) return
                syncResourceStarts += 1
                if (resourceIndex == 0) {
                    syncEntered.complete(Unit)
                    syncGate?.await()
                }
            }
            syncError?.takeIf {
                syncErrorOnCall == null || syncErrorOnCall == syncCalls
            }?.let { error ->
                if (loseSessionOnSyncError) loseLiveSession()
                throw error
            }
        }

        fun loseLiveSession() {
            signedIn = false
            identifiedMemberKey = null
            identifiedInCurrentProcess = false
            configuredInCurrentProcess = false
        }

        override suspend fun signOutSdk() {
            signOutCalls += 1
            signOutEntered.complete(Unit)
            signOutGate?.await()
            signOutError?.let { throw it }
            signedIn = false
            identifiedMemberKey = null
            identifiedInCurrentProcess = false
            configuredInCurrentProcess = false
            events += "sign-out"
        }
    }

    private object SupportedContacts : AddressBookContactSource {
        override val readPermission = "android.permission.READ_CONTACTS"
        override fun hasPermission(): Boolean = true
        override suspend fun readPersonContacts(): List<AddressBookPersonContact> = emptyList()
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation-id"
        val memberKeyWrites = mutableListOf<String?>()
        override var memberKey: String? = null
            set(value) {
                field = value
                memberKeyWrites += value
            }
        override var initialSetupStep: InitialSetupStep? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var healthReceiptBaselineAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null
        override var lastKnownStatusObservedAt: InstantValue? = null
        override var healthReconnectRequired = false
        override var signOutPending = false
            private set
        override var pendingPrivySignOutMemberKey: String? = null
            private set
        var revokeHealthAuthorizationSucceeds = true
        var completeHealthAuthorizationSucceeds = true
        var requireHealthReconnectSucceeds = true
        var advanceInitialSetupSucceeds = true
        var beginSignOutSucceeds = true
        var completeSignOutSucceeds = true
        var clearMemberScopedStateCalls = 0
        private var storedAddressBookRevision: Int? = null
        private var storedAddressBookReplacement: AddressBookMutation? = null
        private var storedAddressBookDeletion: AddressBookMutation? = null

        override val addressBookRevision: Int?
            get() = storedAddressBookRevision
        override val pendingAddressBookReplacement: AddressBookMutation?
            get() = storedAddressBookReplacement
        override val pendingAddressBookDeletion: AddressBookMutation?
            get() = storedAddressBookDeletion

        override fun advanceInitialSetupStep(
            expected: InitialSetupStep,
            next: InitialSetupStep,
            abandonPendingAddressBookReplacement: Boolean,
        ): Boolean {
            if (!advanceInitialSetupSucceeds || initialSetupStep != expected) return false
            initialSetupStep = next
            if (abandonPendingAddressBookReplacement) {
                storedAddressBookReplacement = null
            }
            return true
        }

        override fun recordAddressBookRevision(revision: Int): Boolean {
            storedAddressBookRevision = revision
            return true
        }

        override fun recordDisabledAddressBookRevision(revision: Int): Boolean {
            storedAddressBookRevision = revision
            storedAddressBookReplacement = null
            storedAddressBookDeletion = null
            return true
        }

        override fun beginAddressBookReplacement(mutation: AddressBookMutation): Boolean {
            storedAddressBookReplacement = mutation
            storedAddressBookDeletion = null
            return true
        }

        override fun completeHealthSetupAuthorization(
            requestedAt: InstantValue,
            receiptBaselineAt: InstantValue?,
            statusObservedAt: InstantValue,
            completesInitialSetup: Boolean,
        ): Boolean {
            if (!completeHealthAuthorizationSucceeds) return false
            if (completesInitialSetup && initialSetupStep != InitialSetupStep.HealthConnect) {
                return false
            }
            healthAccessRequestedAt = requestedAt
            healthReceiptBaselineAt = receiptBaselineAt
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = statusObservedAt
            healthReconnectRequired = false
            if (completesInitialSetup) {
                initialSetupStep = InitialSetupStep.FriendlyNames
            }
            return true
        }

        override fun requireHealthReconnect(): Boolean {
            if (!requireHealthReconnectSucceeds) return false
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = true
            return true
        }

        override fun revokeHealthSetupAuthorization(): Boolean {
            if (!revokeHealthAuthorizationSucceeds) return false
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            return true
        }

        override fun beginSignOut(
            expectedMemberKey: String?,
            privySignOutMemberKey: String?,
            preserveMemberState: Boolean,
        ): Boolean {
            if (memberKey != expectedMemberKey) return false
            if (!beginSignOutSucceeds) return false
            signOutPending = true
            pendingPrivySignOutMemberKey = privySignOutMemberKey
            if (!preserveMemberState) {
                initialSetupStep = null
                storedAddressBookRevision = null
                storedAddressBookReplacement = null
                storedAddressBookDeletion = null
            }
            return true
        }

        override fun completeSignOut(expectedMemberKey: String?): Boolean {
            if (memberKey != expectedMemberKey) return false
            if (!completeSignOutSucceeds) return false
            signOutPending = false
            pendingPrivySignOutMemberKey = null
            clearMemberScopedState()
            return true
        }

        override fun clearMemberScopedState() {
            clearMemberScopedStateCalls += 1
            memberKey = null
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            initialSetupStep = null
            storedAddressBookRevision = null
            storedAddressBookReplacement = null
            storedAddressBookDeletion = null
        }
    }

    private companion object {
        const val MEMBER_KEY = "did:privy:user_123"
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
        const val HEALTH_PERMISSION_VERIFICATION_MESSAGE =
            "Murph couldn't verify current Health Connect permissions. Saved status is still shown."

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
                        documents = listOf(legal),
                        missingDocuments = if (granted) emptyList() else listOf(legal),
                    ),
                    LaunchConsentScopeStatus(
                        scope = LaunchConsentScope.HealthData,
                        granted = granted,
                        documents = listOf(health),
                        missingDocuments = if (granted) emptyList() else listOf(health),
                    ),
                ),
            )
        }

        fun completedInitialOnboarding(completedNow: Boolean? = null) = InitialOnboarding(
            status = InitialOnboardingStatus.Completed,
            completedNow = completedNow,
            preferences = InitialOnboardingPreferences(null, null, null),
            catalog = null,
            contactCard = null,
            contactAction = null,
        )

        fun pendingInitialOnboarding(
            contactCard: InitialOnboardingContactCard? = InitialOnboardingContactCard(
                avatars = listOf(
                    InitialOnboardingContactAvatar(
                        id = "classic",
                        kind = InitialOnboardingContactAvatarKind.Logo,
                        label = "Classic",
                        imageUrl = null,
                    ),
                ),
                defaultAvatarId = "classic",
            ),
        ) = InitialOnboarding(
            status = InitialOnboardingStatus.Pending,
            completedNow = null,
            preferences = InitialOnboardingPreferences(null, null, null),
            catalog = InitialOnboardingCatalog(
                personas = listOf(
                    InitialOnboardingPersona(
                        id = "classic",
                        label = "Classic",
                        description = "Warm and clear",
                        supportDescription = "Adds warmth",
                        defaultTone = "formal",
                        defaultVoiceId = "murph",
                        recommendedVoiceIds = listOf("murph"),
                    ),
                    InitialOnboardingPersona(
                        id = "coach",
                        label = "Coach",
                        description = "Direct and motivating",
                        supportDescription = "Adds momentum",
                        defaultTone = "formal",
                        defaultVoiceId = "murph",
                        recommendedVoiceIds = listOf("murph"),
                    ),
                ),
                voices = listOf(
                    InitialOnboardingVoice(
                        id = "murph",
                        label = "Murph",
                        description = "Warm and direct",
                        previewUrl = "https://example.test/audio/murph.mp3",
                    ),
                ),
                tones = listOf(
                    InitialOnboardingTone("formal", "Formal", "Want to work on sleep first?"),
                    InitialOnboardingTone("casual", "Casual", "wanna fix sleep first?"),
                ),
            ),
            contactCard = contactCard,
            contactAction = InitialOnboardingContactAction(
                href = "sms:+15555550123",
                kind = InitialOnboardingContactKind.Text,
                label = "Text Murph",
            ),
        )

        fun grantLaunchConsentScope(
            status: LaunchConsentStatus,
            scope: LaunchConsentScope,
        ): LaunchConsentStatus {
            val launchScopes = status.launchScopes.map {
                if (it.scope == scope) {
                    it.copy(granted = true, missingDocuments = emptyList())
                } else {
                    it
                }
            }
            return status.copy(
                launchGranted = launchScopes.all { it.granted },
                launchScopes = launchScopes,
            )
        }
    }
}
