package ai.withmurph.companion.app

import android.content.Intent
import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.core.AddressBookDeletionRequest
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.AddressBookPersonContact
import ai.withmurph.companion.core.AddressBookReplacementRequest
import ai.withmurph.companion.core.AddressBookServerStatus
import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.AddressBookWriteCapability
import ai.withmurph.companion.core.AppEnvironment
import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthGrantSnapshot
import ai.withmurph.companion.core.HealthSyncReminderDeadline
import ai.withmurph.companion.core.HealthSyncing
import ai.withmurph.companion.core.HealthSyncAttemptResult
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.core.PendingExternalHandoff
import ai.withmurph.companion.core.PendingHealthSyncFailure
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AddressBookSessionTest {
    @Test
    fun terminalReplacementFailuresResetTheWholeMemberBoundary() = runTest {
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
            fixture.contacts.permissionGranted = true
            fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
            fixture.health.signedIn = true
            assertTrue(fixture.session.prepareAddressBookSharing())
            fixture.api.replaceHandler = { _, _ -> throw rejection }

            assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

            val failure = fixture.session.state.value.phase as AppPhase.Failed
            assertFalse(failure.canRetry)
            assertEquals(signOutLabel, failure.signOutLabel)
            assertNull(fixture.localState.memberKey)
            assertNull(fixture.localState.addressBookRevision)
            assertNull(fixture.localState.pendingAddressBookReplacement)
            assertNull(fixture.localState.pendingAddressBookDeletion)
            assertFalse(fixture.health.signedIn)
        }
    }

    @Test
    fun accountConflictDuringAddressBookStatusClosesMemberAuthority() = runTest {
        val fixture = fixture()
        fixture.api.beforeStatusReturn = { _, _ ->
            throw CompanionApiException.AccountConflict
        }

        fixture.session.start()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals("Try a different sign-in", failure.signOutLabel)
        assertNull(fixture.localState.memberKey)
    }

    @Test
    fun slowFailingForegroundAddressBookCannotGateHealthRefreshOrSync() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val permissionRefreshesBeforeForeground = fixture.health.refreshPermissionCalls
        val syncCallsBeforeForeground = fixture.health.syncCalls
        val addressCallsBeforeForeground = fixture.api.addressStatusMembers.size
        val addressEntered = CompletableDeferred<Unit>()
        val releaseAddressStatus = CompletableDeferred<Unit>()
        fixture.api.beforeStatusReturn = { call, _ ->
            if (call == addressCallsBeforeForeground + 1) {
                addressEntered.complete(Unit)
                releaseAddressStatus.await()
                throw CompanionApiException.Network
            }
        }
        fixture.health.syncEntered = CompletableDeferred()
        fixture.health.syncGate = CompletableDeferred()

        fixture.session.didEnterBackground()
        val foregroundRefresh = async { fixture.session.didBecomeActive() }

        fixture.health.syncEntered.await()
        assertEquals(
            permissionRefreshesBeforeForeground + 1,
            fixture.health.refreshPermissionCalls,
        )
        assertEquals(syncCallsBeforeForeground + 1, fixture.health.syncCalls)
        assertEquals(addressCallsBeforeForeground, fixture.api.addressStatusMembers.size)

        fixture.health.syncGate?.complete(Unit)
        addressEntered.await()
        assertFalse(foregroundRefresh.isCompleted)
        releaseAddressStatus.complete(Unit)
        foregroundRefresh.await()

        assertEquals(
            addressCallsBeforeForeground + 1,
            fixture.api.addressStatusMembers.size,
        )
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
    }

    @Test
    fun authoritativeHealthBoundaryPreventsLaterForegroundAddressBookWork() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val permissionRefreshesBeforeBoundary = fixture.health.refreshPermissionCalls
        val syncCallsBeforeBoundary = fixture.health.syncCalls
        val addressCallsBeforeBoundary = fixture.api.addressStatusMembers.size
        val nextSyncStatusCall = fixture.api.syncStatusMembers.size + 1
        val signOutCallsBeforeBoundary = fixture.health.signOutCalls
        fixture.api.beforeSyncStatusReturn = { call, _ ->
            if (call == nextSyncStatusCall) {
                throw CompanionApiException.Unauthorized
            }
        }

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals(
            permissionRefreshesBeforeBoundary + 1,
            fixture.health.refreshPermissionCalls,
        )
        assertEquals(syncCallsBeforeBoundary, fixture.health.syncCalls)
        assertEquals(addressCallsBeforeBoundary, fixture.api.addressStatusMembers.size)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCallsBeforeBoundary + 1, fixture.health.signOutCalls)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.addressBookRevision)
        assertTrue(fixture.api.replacements.isEmpty())
        assertTrue(fixture.api.deletions.isEmpty())
    }

    @Test
    fun backendRejectionClosesAddressAuthorityBeforeWaitingForHealthTeardown() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.health.syncGate = CompletableDeferred()

        val startup = async { fixture.session.start() }
        fixture.health.syncEntered.await()
        assertEquals(1, fixture.api.addressStatusMembers.size)

        val rejectionObserved = CompletableDeferred<Unit>()
        fixture.api.beforeStatusReturn = { call, _ ->
            if (call == 2) {
                rejectionObserved.complete(Unit)
                throw CompanionApiException.Unauthorized
            }
        }
        val rejectedRefresh = async { fixture.session.refreshAddressBookSharing() }
        rejectionObserved.await()
        runCurrent()

        val secondRefresh = async { fixture.session.refreshAddressBookSharing() }
        runCurrent()
        val addressCallsWhileTeardownWaited = fixture.api.addressStatusMembers.size
        val authorityStayedOpen =
            fixture.session.state.value.phase == AppPhase.Ready &&
                fixture.session.state.value.authVerifiedOnline

        fixture.health.syncGate?.complete(Unit)
        startup.await()
        rejectedRefresh.await()
        secondRefresh.await()

        assertFalse(authorityStayedOpen)
        assertEquals(2, addressCallsWhileTeardownWaited)
        assertFalse(fixture.health.signedIn)
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
    }

    @Test
    fun acceptedConsentReplacementUnauthorizedResetsTheWholeMemberBoundary() = runTest {
        val fixture = fixture(
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 0
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        var syncCallsAtRejection = -1
        var signOutCallsAtRejection = -1
        fixture.api.replaceHandler = { _, _ ->
            when (fixture.api.replacements.size) {
                1 -> throw CompanionApiException.ConsentRequired
                2 -> {
                    syncCallsAtRejection = fixture.health.syncCalls
                    signOutCallsAtRejection = fixture.health.signOutCalls
                    throw CompanionApiException.Unauthorized
                }
                else -> error("Unexpected replacement replay")
            }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val savedMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)

        fixture.session.acceptLaunchConsent()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        assertFalse(failure.canRetry)
        assertEquals(2, fixture.api.replacements.size)
        assertEquals(savedMutation, fixture.api.replacements[0].second.mutation)
        assertEquals(savedMutation.baseRevision, fixture.api.replacements[1].second.mutation.baseRevision)
        assertFalse(savedMutation == fixture.api.replacements[1].second.mutation)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCallsAtRejection + 1, fixture.health.signOutCalls)
        assertEquals(syncCallsAtRejection, fixture.health.syncCalls)
    }

    @Test
    fun explicitDeletionUnauthorizedResetsTheWholeMemberBoundary() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val syncCallsBeforeRejection = fixture.health.syncCalls
        val signOutCallsBeforeRejection = fixture.health.signOutCalls
        fixture.api.deleteHandler = { _, _ ->
            throw CompanionApiException.Unauthorized
        }

        fixture.session.stopAddressBookSharing()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        val attemptedMutation = fixture.api.deletions.single().second.mutation
        assertFalse(failure.canRetry)
        assertEquals(1, fixture.api.deletions.size)
        assertEquals(5, attemptedMutation.baseRevision)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCallsBeforeRejection + 1, fixture.health.signOutCalls)
        assertEquals(syncCallsBeforeRejection, fixture.health.syncCalls)
    }

    @Test
    fun automaticForegroundDeletionUnauthorizedResetsMemberAfterHealthSync() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val syncCallsBeforeRejection = fixture.health.syncCalls
        val signOutCallsBeforeRejection = fixture.health.signOutCalls
        fixture.contacts.permissionGranted = false
        fixture.api.deleteHandler = { _, _ ->
            throw CompanionApiException.Unauthorized
        }

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val failure = fixture.session.state.value.phase as AppPhase.Failed
        val attemptedMutation = fixture.api.deletions.single().second.mutation
        assertFalse(failure.canRetry)
        assertEquals(1, fixture.api.deletions.size)
        assertEquals(5, attemptedMutation.baseRevision)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertTrue(fixture.session.state.value.contactsPermissionDenied)
        assertFalse(fixture.health.signedIn)
        assertEquals(signOutCallsBeforeRejection + 1, fixture.health.signOutCalls)
        assertEquals(syncCallsBeforeRejection + 1, fixture.health.syncCalls)
    }

    @Test
    fun explicitShareFetchesStatusBeforeReadingAndPublishesServerSuccess() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        fixture.events.clear()

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertEquals(listOf("address-get:$MEMBER_ONE"), fixture.events)
        assertEquals(0, fixture.contacts.readCalls)
        assertNull(fixture.localState.pendingAddressBookReplacement)

        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(
            person("Anna", "Smith", " +1 (212) 555-0101 "),
            person("Doctor", null, "+12125550102"),
        )

        assertTrue(fixture.session.completeAddressBookPermissionFlow(permissionGranted = true))

        assertEquals(
            listOf(
                "address-get:$MEMBER_ONE",
                "contacts-read",
                "address-put:$MEMBER_ONE",
            ),
            fixture.events,
        )
        assertEquals(1, fixture.api.replacements.size)
        assertEquals(
            listOf("+12125550101" to "Anna S."),
            fixture.api.replacements.single().second.contacts.map {
                it.phoneNumber to it.advisoryName
            },
        )
        assertEquals(1, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertEquals(
            AddressBookSharingState.Server(
                enabled = true,
                storedContactCount = 1,
                canWrite = true,
                ownedByInstallation = true,
            ),
            fixture.session.state.value.addressBookSharing,
        )
        assertFalse(fixture.session.state.value.isAddressBookBusy)
    }

    @Test
    fun initialShareCompletesFriendlyNamesOnlyAfterTheExactLocalCommit() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))

        assertTrue(fixture.session.prepareInitialAddressBookSharing())
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertTrue(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(1, fixture.localState.addressBookRevision)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
    }

    @Test
    fun initialShareStaysOnFriendlyNamesWhenTheLocalRevisionCommitFails() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.localState.completeAddressBookReplacementSucceeds = false

        assertTrue(fixture.session.prepareInitialAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertTrue(fixture.localState.pendingAddressBookReplacement != null)
    }

    @Test
    fun friendlyNamesUncertainReplacementConvergesBeforeDeferral() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 1, count = 1),
            initializeLocal = {
                memberKey = MEMBER_ONE
                initialSetupStep = InitialSetupStep.FriendlyNames
                revision = 0
                replacement = AddressBookMutation(0, MUTATION_ONE)
            },
        )
        fixture.contacts.permissionGranted = false
        fixture.session.start()

        assertFalse(fixture.session.deferAddressBookSharingInitialSetup())

        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(MUTATION_ONE, fixture.localState.pendingAddressBookReplacement?.mutationId)
        assertTrue(fixture.session.state.value.addressBookHasInterruptedReplacement)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("stop and delete"),
        )

        assertFalse(fixture.session.prepareInitialAddressBookSharing())
        assertEquals(0, fixture.contacts.readCalls)
        assertTrue(fixture.api.deletions.isEmpty())
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertFalse(fixture.session.state.value.addressBookHasInterruptedReplacement)
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertTrue(sharing.enabled)
        assertFalse(sharing.ownedByInstallation)
        assertTrue(fixture.session.deferAddressBookSharingInitialSetup())
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
    }

    @Test
    fun friendlyNamesDeferralStaysVisibleWhileRetryIsPending() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
                replacement = AddressBookMutation(0, MUTATION_ONE)
            },
        )
        fixture.session.start()

        assertFalse(fixture.session.deferAddressBookSharingInitialSetup())

        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(MUTATION_ONE, fixture.localState.pendingAddressBookReplacement?.mutationId)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("stop and delete"),
        )
    }

    @Test
    fun friendlyNamesDeferralSurfacesAPlainSetupChoiceCommitFailure() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
                advanceInitialSetupSucceeds = false
            },
        )
        fixture.session.start()

        assertFalse(fixture.session.deferAddressBookSharingInitialSetup())

        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(
            "Murph couldn't save that Friendly Names setup choice. Try again.",
            fixture.session.state.value.addressBookMessage,
        )
    }

    @Test
    fun permissionDenialIsRecoverableAndDoesNotLeaveTheSession() = runTest {
        val fixture = fixture()
        fixture.session.start()

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(
            fixture.session.completeAddressBookPermissionFlow(permissionGranted = false),
        )

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertTrue(fixture.session.state.value.contactsPermissionDenied)
        assertEquals(0, fixture.contacts.readCalls)
        assertTrue(fixture.api.replacements.isEmpty())
        assertNull(fixture.localState.pendingAddressBookReplacement)

        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        assertTrue(fixture.session.prepareAddressBookSharing())
        assertTrue(fixture.session.completeAddressBookPermissionFlow(permissionGranted = true))

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertFalse(fixture.session.state.value.contactsPermissionDenied)
        assertEquals(1, fixture.contacts.readCalls)
    }

    @Test
    fun updatePermissionDenialDeletesTheExactOwnedProjectionAndKeepsRecoveryVisible() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = false

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(false))

        assertEquals(0, fixture.contacts.readCalls)
        assertEquals(listOf(5), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertEquals(6, fixture.localState.addressBookRevision)
        assertTrue(fixture.session.state.value.contactsPermissionDenied)
        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = true,
            ),
            fixture.session.state.value.addressBookSharing,
        )
    }

    @Test
    fun permissionLossDuringReplacementDeletesTheNewExactRevision() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { memberKey, request ->
            fixture.contacts.permissionGranted = false
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(1, fixture.contacts.readCalls)
        assertEquals(1, fixture.api.replacements.size)
        assertEquals(listOf(1), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertEquals(2, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertTrue(fixture.session.state.value.contactsPermissionDenied)
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertFalse(sharing.enabled)
        assertTrue(sharing.ownedByInstallation)
    }

    @Test
    fun interruptedReplacementConvergesWhenTheServerAlreadyAdvanced() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { memberKey, request ->
            if (fixture.api.replacements.size == 1) {
                fixture.api.statuses[memberKey] = enabledStatus(
                    revision = request.mutation.baseRevision + 1,
                    count = 1,
                )
                throw CompanionApiException.Network
            }
            fixture.api.statuses.getValue(memberKey)
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        val interrupted = fixture.localState.pendingAddressBookReplacement
        assertEquals(0, interrupted?.baseRevision)
        assertTrue(fixture.session.state.value.addressBookHasInterruptedReplacement)

        assertFalse(fixture.session.prepareAddressBookSharing())

        assertEquals(1, fixture.api.replacements.size)
        assertEquals(0, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertEquals(1, sharing.storedContactCount)
        assertFalse(sharing.ownedByInstallation)
    }

    @Test
    fun freshRetryMutationCannotOverwriteALateFirstCommit() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { memberKey, request ->
            if (fixture.api.replacements.size == 1) {
                throw CompanionApiException.Network
            }
            fixture.api.statuses[memberKey] = enabledStatus(revision = 1, count = 1)
            throw CompanionApiException.Conflict
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val uncertainMutation = requireNotNull(
            fixture.localState.pendingAddressBookReplacement,
        )

        fixture.contacts.rows = listOf(person("Ben", "Jones", "+12125550102"))
        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(2, fixture.api.replacements.size)
        val retryMutation = fixture.api.replacements.last().second.mutation
        assertEquals(uncertainMutation.baseRevision, retryMutation.baseRevision)
        assertFalse(uncertainMutation == retryMutation)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(0, fixture.localState.addressBookRevision)
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertEquals(1, sharing.storedContactCount)
        assertFalse(sharing.ownedByInstallation)
    }

    @Test
    fun failedAtomicRetryRotationKeepsTheOldMarkerAndStopsBeforeContactsRead() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { _, _ -> throw CompanionApiException.Network }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val uncertainMutation = requireNotNull(
            fixture.localState.pendingAddressBookReplacement,
        )
        val readsBeforeRetry = fixture.contacts.readCalls
        fixture.localState.replaceAddressBookReplacementSucceeds = false

        assertFalse(fixture.session.prepareAddressBookSharing())

        assertEquals(uncertainMutation, fixture.localState.pendingAddressBookReplacement)
        assertEquals(readsBeforeRetry, fixture.contacts.readCalls)
        assertEquals(1, fixture.api.replacements.size)
    }

    @Test
    fun staleReplayResultNeverReplacesNewerPreflightTruth() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { memberKey, request ->
            if (fixture.api.replacements.size == 1) {
                fixture.api.statuses[memberKey] = enabledStatus(
                    revision = request.mutation.baseRevision + 1,
                    count = 1,
                )
                throw CompanionApiException.Network
            }
            enabledStatus(revision = 1, count = 1)
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        assertTrue(fixture.localState.pendingAddressBookReplacement != null)

        fixture.api.statuses[MEMBER_ONE] = disabledStatus(revision = 2)
        assertFalse(fixture.session.prepareAddressBookSharing())

        assertEquals(2, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("kept the newer server state"),
        )
        assertEquals(
            AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = true,
            ),
            fixture.session.state.value.addressBookSharing,
        )
    }

    @Test
    fun replacementConflictDoesNotOverwriteAndRefreshesRemoteTruth() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { memberKey, _ ->
            fixture.api.statuses[memberKey] = enabledStatus(revision = 1, count = 3)
            throw CompanionApiException.Conflict
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(1, fixture.api.replacements.size)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(0, fixture.localState.addressBookRevision)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("didn't overwrite"),
        )
        assertEquals(
            AddressBookSharingState.Server(
                enabled = true,
                storedContactCount = 3,
                canWrite = true,
                ownedByInstallation = false,
            ),
            fixture.session.state.value.addressBookSharing,
        )
    }

    @Test
    fun stopRefetchesAfterConflictBecauseDeletionOnlyReducesSharing() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.api.deleteHandler = { memberKey, request ->
            if (fixture.api.deletions.size == 1) {
                fixture.api.statuses[memberKey] = enabledStatus(revision = 6, count = 2)
                throw CompanionApiException.Conflict
            }
            disabledStatus(revision = request.mutation.baseRevision + 1).also {
                fixture.api.statuses[memberKey] = it
            }
        }
        fixture.session.start()

        fixture.session.stopAddressBookSharing()

        assertEquals(listOf(5, 6), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertTrue(
            fixture.api.deletions[0].second.mutation.mutationId !=
                fixture.api.deletions[1].second.mutation.mutationId,
        )
        assertEquals(7, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(
            AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = true,
            ),
            fixture.session.state.value.addressBookSharing,
        )
    }

    @Test
    fun stopClearsAnInterruptedReplacementWhenServerIsAlreadyDisabled() = runTest {
        val fixture = fixture(
            initialStatus = disabledStatus(revision = 2),
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 0
                replacement = AddressBookMutation(0, MUTATION_ONE)
            },
        )
        fixture.session.start()
        assertTrue(fixture.localState.pendingAddressBookReplacement != null)

        fixture.session.stopAddressBookSharing()

        assertEquals(2, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertTrue(fixture.api.deletions.isEmpty())
        assertFalse(fixture.session.state.value.addressBookHasInterruptedReplacement)
    }

    @Test
    fun foregroundPermissionLossDeletesOnlyTheExactOwnedRevisionWithoutReading() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = false

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(0, fixture.contacts.readCalls)
        assertEquals(listOf(5), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertEquals(6, fixture.localState.addressBookRevision)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("deleted this installation's shared names"),
        )
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertFalse(sharing.enabled)
        assertTrue(sharing.ownedByInstallation)
    }

    @Test
    fun automaticPermissionLossConflictNeverRetriesAgainstTheLatestRevision() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.api.deleteHandler = { memberKey, _ ->
            fixture.api.statuses[memberKey] = enabledStatus(revision = 6, count = 3)
            throw CompanionApiException.Conflict
        }
        fixture.session.start()
        fixture.contacts.permissionGranted = false

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertEquals(listOf(5), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertEquals(5, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertTrue(fixture.session.state.value.contactsPermissionDenied)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("left the newer revision alone"),
        )
        assertEquals(
            AddressBookSharingState.Server(
                enabled = true,
                storedContactCount = 3,
                canWrite = true,
                ownedByInstallation = false,
            ),
            fixture.session.state.value.addressBookSharing,
        )
    }

    @Test
    fun freshInstallDoesNotAdoptUnknownEnabledProjectionButExplicitStopDeletesLatest() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = false,
            initializeLocal = {
                memberKey = MEMBER_ONE
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )

        fixture.session.start()

        assertNull(fixture.localState.addressBookRevision)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertTrue(fixture.api.deletions.isEmpty())
        assertEquals(
            AddressBookSharingState.Server(
                enabled = true,
                storedContactCount = 2,
                canWrite = true,
                ownedByInstallation = false,
            ),
            fixture.session.state.value.addressBookSharing,
        )

        fixture.session.stopAddressBookSharing()

        assertEquals(listOf(5), fixture.api.deletions.map { it.second.mutation.baseRevision })
        assertEquals(6, fixture.localState.addressBookRevision)
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertFalse(sharing.enabled)
        assertTrue(sharing.ownedByInstallation)
    }

    @Test
    fun ownedServerStatusDoesNotInventInitialShareProvenance() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                initialSetupStep = InitialSetupStep.FriendlyNames
                revision = 5
            },
        )

        fixture.session.start()

        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertTrue(sharing.enabled)
        assertTrue(sharing.ownedByInstallation)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
    }

    @Test
    fun repeatedForegroundRefreshesCoalesceToOneServerRequest() = runTest {
        val fixture = fixture()
        fixture.session.start()
        val targetCall = fixture.api.addressStatusMembers.size + 1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.beforeStatusReturn = { call, _ ->
            if (call == targetCall) {
                entered.complete(Unit)
                release.await()
            }
        }

        val first = async { fixture.session.refreshAddressBookSharing() }
        entered.await()
        val second = async { fixture.session.refreshAddressBookSharing() }
        runCurrent()

        assertTrue(second.isCompleted)
        assertEquals(targetCall, fixture.api.addressStatusMembers.size)
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(targetCall, fixture.api.addressStatusMembers.size)
        assertFalse(fixture.session.state.value.isAddressBookBusy)
    }

    @Test
    fun duplicatePermissionCompletionsCoalesceToOneReadAndReplacement() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.replaceHandler = { memberKey, request ->
            entered.complete(Unit)
            release.await()
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        val first = async { fixture.session.completeAddressBookPermissionFlow(true) }
        entered.await()
        val duplicate = async { fixture.session.completeAddressBookPermissionFlow(true) }
        runCurrent()

        assertFalse(duplicate.isCompleted)
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(duplicate.await())
        assertEquals(1, fixture.contacts.readCalls)
        assertEquals(1, fixture.api.replacements.size)
        assertNull(fixture.localState.pendingAddressBookReplacement)
    }

    @Test
    fun backgroundCancelsContactReadAndRotatesTheUncertainMutationBeforeRetry() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.contacts.readGate = CompletableDeferred()

        assertTrue(fixture.session.prepareAddressBookSharing())
        val completion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        fixture.contacts.readEntered.await()
        val savedMutation = requireNotNull(
            fixture.localState.pendingAddressBookReplacement,
        )

        fixture.session.didEnterBackground()
        assertFalse(completion.await())

        assertTrue(fixture.contacts.readCancellationObserved)
        assertTrue(fixture.api.replacements.isEmpty())
        assertFalse(fixture.session.state.value.isAddressBookBusy)
        assertEquals(savedMutation, fixture.localState.pendingAddressBookReplacement)

        fixture.contacts.readGate = null
        fixture.session.didBecomeActive()
        assertTrue(fixture.session.prepareAddressBookSharing())
        assertTrue(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(1, fixture.api.replacements.size)
        val retriedMutation = fixture.api.replacements.single().second.mutation
        assertEquals(savedMutation.baseRevision, retriedMutation.baseRevision)
        assertFalse(savedMutation == retriedMutation)
        assertNull(fixture.localState.pendingAddressBookReplacement)
    }

    @Test
    fun backgroundCancelsStartedReplacementAndFencesItsLateResult() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val replaceEntered = CompletableDeferred<Unit>()
        val replaceGate = CompletableDeferred<Unit>()
        val replaceCancellationObserved = CompletableDeferred<Unit>()
        fixture.api.replaceHandler = { memberKey, request ->
            val applied = enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
            replaceEntered.complete(Unit)
            try {
                replaceGate.await()
            } catch (error: CancellationException) {
                replaceCancellationObserved.complete(Unit)
                throw error
            }
            applied
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        val completion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        replaceEntered.await()
        val savedMutation = requireNotNull(
            fixture.localState.pendingAddressBookReplacement,
        )

        fixture.session.didEnterBackground()
        replaceCancellationObserved.await()
        assertFalse(completion.await())

        assertEquals(1, fixture.api.replacements.size)
        assertEquals(savedMutation, fixture.api.replacements.single().second.mutation)
        assertEquals(0, fixture.localState.addressBookRevision)
        assertEquals(savedMutation, fixture.localState.pendingAddressBookReplacement)
        assertFalse(fixture.session.state.value.isAddressBookBusy)
        assertEquals(1, fixture.api.statuses.getValue(MEMBER_ONE).revision)

        fixture.session.didBecomeActive()
        assertFalse(fixture.session.prepareAddressBookSharing())

        assertEquals(1, fixture.api.replacements.size)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(0, fixture.localState.addressBookRevision)
        assertTrue(
            fixture.session.state.value.addressBookMessage.orEmpty()
                .contains("already advanced"),
        )
    }

    @Test
    fun explicitStopWaitsForForegroundReconciliationThenDeletesOnce() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        val targetCall = fixture.api.addressStatusMembers.size + 1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.beforeStatusReturn = { call, _ ->
            if (call == targetCall) {
                entered.complete(Unit)
                release.await()
            }
        }

        val refresh = async { fixture.session.refreshAddressBookSharing() }
        entered.await()
        val stop = async { fixture.session.stopAddressBookSharing() }
        runCurrent()

        assertFalse(stop.isCompleted)
        assertTrue(fixture.api.deletions.isEmpty())
        release.complete(Unit)
        refresh.await()
        stop.await()

        assertEquals(listOf(5), fixture.api.deletions.map { it.second.mutation.baseRevision })
        val sharing = fixture.session.state.value.addressBookSharing as AddressBookSharingState.Server
        assertFalse(sharing.enabled)
        assertFalse(fixture.session.state.value.isAddressBookBusy)
    }

    @Test
    fun signOutCancelsAndConvergesACommittedReplacementBeforeAuthLogout() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        var authWasStillOwnedAtCancellation = false
        var authWasStillOwnedAtSettlement = false
        var replacementAttempt = 0
        fixture.api.replaceHandler = { memberKey, request ->
            replacementAttempt += 1
            if (replacementAttempt > 1) {
                authWasStillOwnedAtSettlement =
                    fixture.auth.state == AuthSessionState.SignedIn(
                        MEMBER_ONE,
                        verifiedOnline = true,
                    )
                fixture.api.statuses.getValue(memberKey)
            } else {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (error: CancellationException) {
                    authWasStillOwnedAtCancellation =
                        fixture.auth.state == AuthSessionState.SignedIn(
                            MEMBER_ONE,
                            verifiedOnline = true,
                        )
                    fixture.api.statuses[memberKey] = enabledStatus(
                        request.mutation.baseRevision + 1,
                        request.contacts.size,
                    )
                    cancellationObserved.complete(Unit)
                    throw error
                }
                error("The replacement should be cancelled by sign-out")
            }
        }
        assertTrue(fixture.session.prepareAddressBookSharing())
        val completion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        entered.await()
        assertTrue(fixture.localState.pendingAddressBookReplacement != null)

        fixture.session.signOut()

        cancellationObserved.await()
        assertTrue(completion.isCompleted)
        assertTrue(authWasStillOwnedAtCancellation)
        assertTrue(authWasStillOwnedAtSettlement)
        assertEquals(2, fixture.api.replacements.size)
        assertEquals(
            fixture.api.replacements[0].second,
            fixture.api.replacements[1].second,
        )
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertEquals(AuthSessionState.SignedOut, fixture.auth.state)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)

        assertFalse(completion.await())
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.localState.addressBookRevision)
    }

    @Test
    fun signOutKeepsTheTombstoneAndMutationWhenReplacementSettlementIsUncertain() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        var replacementAttempt = 0
        fixture.api.replaceHandler = { _, _ ->
            replacementAttempt += 1
            if (replacementAttempt == 1) {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (error: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw error
                }
            }
            throw CompanionApiException.Network
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        val completion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        entered.await()
        val pendingMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)

        fixture.session.signOut()

        cancellationObserved.await()
        assertFalse(completion.await())
        assertTrue(fixture.localState.signOutPending)
        assertEquals(MEMBER_ONE, fixture.localState.memberKey)
        assertEquals(pendingMutation, fixture.localState.pendingAddressBookReplacement)
        assertEquals(
            AuthSessionState.SignedIn(MEMBER_ONE, verifiedOnline = true),
            fixture.auth.state,
        )
        assertTrue(fixture.session.state.value.phase is AppPhase.Failed)
    }

    @Test
    fun memberSwitchFencesOldCompletionAndRunsQueuedStatusForNewOwner() = runTest {
        val fixture = fixture()
        fixture.api.statuses[MEMBER_TWO] = disabledStatus(revision = 9)
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var replacementAttempt = 0
        fixture.api.replaceHandler = { memberKey, request ->
            replacementAttempt += 1
            if (replacementAttempt == 1) {
                entered.complete(Unit)
                release.await()
            }
            enabledStatus(
                request.mutation.baseRevision + 1,
                request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        val oldCompletion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        entered.await()

        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_TWO, verifiedOnline = true)
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()
        assertEquals(MEMBER_TWO, fixture.localState.memberKey)

        release.complete(Unit)
        assertFalse(oldCompletion.await())

        assertEquals(MEMBER_TWO, fixture.localState.memberKey)
        assertEquals(9, fixture.localState.addressBookRevision)
        assertEquals(MEMBER_TWO, fixture.api.addressStatusMembers.last())
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(
            AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = true,
            ),
            fixture.session.state.value.addressBookSharing,
        )
        assertNull(fixture.session.state.value.addressBookMessage)
    }

    @Test
    fun memberSwitchBeforePermissionCompletionDoesNotReadContacts() = runTest {
        val fixture = fixture()
        fixture.api.statuses[MEMBER_TWO] = disabledStatus(revision = 9)
        fixture.session.start()

        assertTrue(fixture.session.prepareAddressBookSharing())
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.auth.state = AuthSessionState.SignedIn(MEMBER_TWO, verifiedOnline = true)

        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(0, fixture.contacts.readCalls)
        assertTrue(fixture.api.replacements.isEmpty())
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(MEMBER_TWO, fixture.localState.memberKey)
        assertEquals(9, fixture.localState.addressBookRevision)
        assertFalse(fixture.session.state.value.isAddressBookBusy)
    }

    @Test
    fun contactConsentRequiredRemainsRetryableWithoutSigningOut() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        fixture.api.replaceHandler = { _, _ -> throw CompanionApiException.ConsentRequired }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        assertEquals(AppPhase.Ready, fixture.session.state.value.phase)
        assertEquals(
            AuthSessionState.SignedIn(MEMBER_ONE, verifiedOnline = true),
            fixture.auth.state,
        )
        assertTrue(fixture.localState.pendingAddressBookReplacement != null)
        assertTrue(fixture.session.state.value.addressBookHasInterruptedReplacement)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertEquals(listOf(MEMBER_ONE), fixture.api.launchConsentFetches)
    }

    @Test
    fun preflightConsentRecoveryResumesOnlyTheAddressBookPermissionRequest() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        var blocked = true
        fixture.api.beforeStatusReturn = { _, _ ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
        }

        assertFalse(fixture.session.prepareInitialAddressBookSharing())
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertNull(fixture.session.state.value.pendingAddressBookPermissionRequestId)
        assertEquals(0, fixture.contacts.readCalls)
        assertTrue(fixture.api.replacements.isEmpty())
        assertFalse(fixture.session.deferAddressBookSharingInitialSetup())
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )

        fixture.session.acceptLaunchConsent()

        val requestId = fixture.session.state.value.pendingAddressBookPermissionRequestId
        assertTrue(requestId != null)
        assertTrue(fixture.session.state.value.isAddressBookBusy)
        assertEquals(0, fixture.contacts.readCalls)
        assertTrue(fixture.api.replacements.isEmpty())
        assertTrue(fixture.session.consumeAddressBookPermissionLaunchRequest(requestId!!))
        assertFalse(fixture.session.consumeAddressBookPermissionLaunchRequest(requestId))

        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        assertTrue(fixture.session.completeAddressBookPermissionFlow(true))
        assertEquals(1, fixture.contacts.readCalls)
        assertEquals(1, fixture.api.replacements.size)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
    }

    @Test
    fun settingsPreflightConsentRecoveryLeavesFriendlyNamesSetupUnchanged() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        var blocked = true
        fixture.api.beforeStatusReturn = { _, _ ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
        }

        assertFalse(fixture.session.prepareAddressBookSharing())
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )
        assertNull(fixture.session.state.value.pendingAddressBookPermissionRequestId)

        fixture.session.acceptLaunchConsent()

        val requestId = requireNotNull(
            fixture.session.state.value.pendingAddressBookPermissionRequestId,
        )
        assertTrue(fixture.session.consumeAddressBookPermissionLaunchRequest(requestId))
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        assertTrue(fixture.session.completeAddressBookPermissionFlow(true))
        assertEquals(1, fixture.contacts.readCalls)
        assertEquals(1, fixture.api.replacements.size)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
    }

    @Test
    fun stopRequestedDuringConsentAcceptanceReplacesTheOlderFollowUp() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        var blocked = true
        fixture.api.beforeStatusReturn = { _, _ ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
        }
        assertFalse(fixture.session.prepareAddressBookSharing())

        val enteredAcceptance = CompletableDeferred<Unit>()
        val releaseAcceptance = CompletableDeferred<Unit>()
        fixture.api.launchConsentAcceptHandler = { _, request, current ->
            if (!enteredAcceptance.isCompleted) {
                enteredAcceptance.complete(Unit)
                releaseAcceptance.await()
            }
            grantLaunchConsentScope(current, request.scope)
        }
        val acceptance = async { fixture.session.acceptLaunchConsent() }
        enteredAcceptance.await()

        fixture.session.stopAddressBookSharing()
        releaseAcceptance.complete(Unit)
        acceptance.await()

        assertEquals(1, fixture.api.deletions.size)
        assertEquals(5, fixture.api.deletions.single().second.mutation.baseRevision)
        assertEquals(6, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertNull(fixture.session.state.value.pendingAddressBookPermissionRequestId)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun stopRequestedDuringAcceptedHealthRestoreSupersedesAddressPermission() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        var blocked = true
        fixture.api.beforeStatusReturn = { _, _ ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
        }
        assertFalse(fixture.session.prepareAddressBookSharing())
        val restoreGate = CompletableDeferred<Unit>()
        fixture.health.identifyGate = restoreGate

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        fixture.health.identifyEntered.await()
        fixture.session.stopAddressBookSharing()
        restoreGate.complete(Unit)
        acceptance.await()

        assertEquals(1, fixture.api.deletions.size)
        assertEquals(5, fixture.api.deletions.single().second.mutation.baseRevision)
        assertEquals(6, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertNull(fixture.session.state.value.pendingAddressBookPermissionRequestId)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.health.signedIn)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
    }

    @Test
    fun stopRequestedWhileAcceptedReplacementProjectsContactsPreventsTheUpload() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        var blockedByConsent = true
        fixture.api.replaceHandler = { memberKey, request ->
            if (blockedByConsent) {
                blockedByConsent = false
                throw CompanionApiException.ConsentRequired
            }
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        assertEquals(1, fixture.api.replacements.size)

        val projectedContact = person("Anna", "Smith", "+12125550101")
        val projectionEntered = CountDownLatch(1)
        val projectionGate = CountDownLatch(1)
        fixture.contacts.rows = object : AbstractList<AddressBookPersonContact>() {
            override val size: Int = 1

            override fun get(index: Int): AddressBookPersonContact {
                assertEquals(0, index)
                projectionEntered.countDown()
                check(projectionGate.await(5, TimeUnit.SECONDS))
                return projectedContact
            }
        }
        val acceptance = async { fixture.session.acceptLaunchConsent() }
        runCurrent()
        assertTrue(projectionEntered.await(5, TimeUnit.SECONDS))

        fixture.session.stopAddressBookSharing()
        assertEquals(1, fixture.api.replacements.size)
        projectionGate.countDown()
        acceptance.await()

        assertEquals(1, fixture.api.replacements.size)
        assertEquals(1, fixture.api.deletions.size)
        assertEquals(5, fixture.api.deletions.single().second.mutation.baseRevision)
        assertEquals(6, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun stopRequestedAfterAcceptedReplacementStartsDeletesTheSavedRevision() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val resumedPutEntered = CompletableDeferred<Unit>()
        val resumedPutGate = CompletableDeferred<Unit>()
        var blockedByConsent = true
        fixture.api.replaceHandler = { memberKey, request ->
            if (blockedByConsent) {
                blockedByConsent = false
                throw CompanionApiException.ConsentRequired
            }
            resumedPutEntered.complete(Unit)
            resumedPutGate.await()
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        resumedPutEntered.await()
        fixture.session.stopAddressBookSharing()

        assertTrue(fixture.api.deletions.isEmpty())
        resumedPutGate.complete(Unit)
        acceptance.await()

        assertEquals(2, fixture.api.replacements.size)
        assertEquals(1, fixture.api.deletions.size)
        assertEquals(6, fixture.api.deletions.single().second.mutation.baseRevision)
        assertEquals(7, fixture.localState.addressBookRevision)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertEquals(
            listOf("address-put:$MEMBER_ONE", "address-delete:$MEMBER_ONE"),
            fixture.events.filter { it.startsWith("address-put:") || it.startsWith("address-delete:") }
                .takeLast(2),
        )
    }

    @Test
    fun stopConsentRecoveryReplaysTheExactDurableDeletionMutation() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        var blocked = true
        fixture.api.deleteHandler = { memberKey, request ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
            disabledStatus(request.mutation.baseRevision + 1).also {
                fixture.api.statuses[memberKey] = it
            }
        }

        fixture.session.stopAddressBookSharing()

        val savedMutation = fixture.localState.pendingAddressBookDeletion
        assertTrue(savedMutation != null)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.deletions.size)
        assertEquals(savedMutation, fixture.api.deletions[0].second.mutation)
        assertEquals(savedMutation, fixture.api.deletions[1].second.mutation)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(6, fixture.localState.addressBookRevision)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertTrue(fixture.health.signedIn)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
    }

    @Test
    fun foregroundDefersHealthRestoreUntilAcceptedDeletionFinishes() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
                healthAccessRequestedAt = InstantValue(1)
            },
        )
        fixture.health.signedIn = true
        fixture.health.grantedCount = fixture.health.totalResourceCount
        fixture.session.start()
        val syncCalls = fixture.health.syncCalls
        val resumedDeletionEntered = CompletableDeferred<Unit>()
        val resumedDeletionGate = CompletableDeferred<Unit>()
        var blockedByConsent = true
        fixture.api.deleteHandler = { memberKey, request ->
            if (blockedByConsent) {
                blockedByConsent = false
                throw CompanionApiException.ConsentRequired
            }
            resumedDeletionEntered.complete(Unit)
            resumedDeletionGate.await()
            disabledStatus(request.mutation.baseRevision + 1).also {
                fixture.api.statuses[memberKey] = it
            }
        }
        fixture.session.stopAddressBookSharing()

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        resumedDeletionEntered.await()
        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        assertFalse(fixture.health.signedIn)
        assertEquals(syncCalls, fixture.health.syncCalls)
        assertEquals(
            LaunchConsentRecoveryPhase.Saving,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        resumedDeletionGate.complete(Unit)
        acceptance.await()

        assertTrue(fixture.health.signedIn)
        assertEquals(syncCalls + 1, fixture.health.syncCalls)
        assertEquals(6, fixture.localState.addressBookRevision)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun permissionLossConsentRecoveryReplaysOnlyTheExactOwnedDeletion() = runTest {
        val fixture = fixture(
            initialStatus = enabledStatus(revision = 5, count = 2),
            permissionGranted = true,
            initializeLocal = {
                memberKey = MEMBER_ONE
                revision = 5
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = false
        var blocked = true
        fixture.api.deleteHandler = { memberKey, request ->
            if (blocked) {
                blocked = false
                throw CompanionApiException.ConsentRequired
            }
            disabledStatus(request.mutation.baseRevision + 1).also {
                fixture.api.statuses[memberKey] = it
            }
        }

        fixture.session.didEnterBackground()
        fixture.session.didBecomeActive()

        val savedMutation = fixture.localState.pendingAddressBookDeletion
        assertTrue(savedMutation != null)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.deletions.size)
        assertEquals(savedMutation, fixture.api.deletions[0].second.mutation)
        assertEquals(savedMutation, fixture.api.deletions[1].second.mutation)
        assertNull(fixture.localState.pendingAddressBookDeletion)
        assertEquals(6, fixture.localState.addressBookRevision)
    }

    @Test
    fun lateConsentRequiredAfterSignOutCannotReviveThePreviousMember() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var replacementAttempt = 0
        fixture.api.replaceHandler = { _, _ ->
            replacementAttempt += 1
            if (replacementAttempt == 1) {
                entered.complete(Unit)
                release.await()
            }
            throw CompanionApiException.ConsentRequired
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        val completion = async { fixture.session.completeAddressBookPermissionFlow(true) }
        entered.await()

        fixture.session.signOut()
        release.complete(Unit)

        assertFalse(completion.await())
        assertEquals(AppPhase.NeedsLogin, fixture.session.state.value.phase)
        assertNull(fixture.session.state.value.launchConsentRecovery)
        assertNull(fixture.localState.memberKey)
        assertNull(fixture.localState.pendingAddressBookReplacement)
    }

    @Test
    fun acceptingLaunchConsentResumesSavedAddressBookReplacementMutation() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        var blockedOnce = false
        fixture.api.replaceHandler = { memberKey, request ->
            if (!blockedOnce) {
                blockedOnce = true
                throw CompanionApiException.ConsentRequired
            }
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareInitialAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val savedMutation = fixture.localState.pendingAddressBookReplacement
        assertTrue(savedMutation != null)

        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.replacements.size)
        assertEquals(savedMutation, fixture.api.replacements[0].second.mutation)
        assertEquals(savedMutation?.baseRevision, fixture.api.replacements[1].second.mutation.baseRevision)
        assertFalse(savedMutation == fixture.api.replacements[1].second.mutation)
        assertEquals(null, fixture.localState.pendingAddressBookReplacement)
        assertEquals(1, fixture.localState.addressBookRevision)
        assertEquals(InitialSetupStep.Complete, fixture.localState.initialSetupStep)
        assertEquals(InitialSetupStep.Complete, fixture.session.state.value.initialSetupStep)
        assertEquals(null, fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun backgroundingAcceptedConsentReplacementPublishesRetryableFailure() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        var requiresConsent = true
        fixture.api.replaceHandler = { memberKey, request ->
            if (requiresConsent) {
                requiresConsent = false
                throw CompanionApiException.ConsentRequired
            }
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val savedMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.contacts.readEntered = CompletableDeferred()
        fixture.contacts.readGate = CompletableDeferred()
        val acceptance = async { fixture.session.acceptLaunchConsent() }
        fixture.contacts.readEntered.await()
        val rotatedMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(savedMutation.baseRevision, rotatedMutation.baseRevision)
        assertFalse(savedMutation == rotatedMutation)

        fixture.session.didEnterBackground()
        acceptance.await()

        assertTrue(fixture.contacts.readCancellationObserved)
        assertEquals(rotatedMutation, fixture.localState.pendingAddressBookReplacement)
        assertEquals(1, fixture.api.replacements.size)
        assertFalse(fixture.session.state.value.isAddressBookBusy)
        assertEquals(
            LaunchConsentRecoveryPhase.LoadFailed,
            fixture.session.state.value.launchConsentRecovery?.phase,
        )

        fixture.contacts.readGate = null
        fixture.contacts.readEntered = CompletableDeferred()
        fixture.session.didBecomeActive()
        fixture.session.retryLaunchConsentRecovery()

        assertEquals(2, fixture.api.replacements.size)
        assertEquals(savedMutation, fixture.api.replacements.first().second.mutation)
        val retriedMutation = fixture.api.replacements.last().second.mutation
        assertEquals(savedMutation.baseRevision, retriedMutation.baseRevision)
        assertFalse(savedMutation == retriedMutation)
        assertFalse(rotatedMutation == retriedMutation)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(1, fixture.localState.addressBookRevision)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun acceptedReplacementLateCommitConvergesWithoutReusingItsMutation() = runTest {
        val fixture = fixture()
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        val resumedRequestEntered = CompletableDeferred<Unit>()
        val resumedRequestGate = CompletableDeferred<Unit>()
        var requiresConsent = true
        fixture.api.replaceHandler = { memberKey, request ->
            if (requiresConsent) {
                requiresConsent = false
                throw CompanionApiException.ConsentRequired
            }
            resumedRequestEntered.complete(Unit)
            try {
                resumedRequestGate.await()
                error("The resumed request should be canceled at the foreground boundary.")
            } catch (error: CancellationException) {
                fixture.api.statuses[memberKey] = enabledStatus(
                    revision = request.mutation.baseRevision + 1,
                    count = request.contacts.size,
                )
                throw error
            }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val firstMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)

        val acceptance = async { fixture.session.acceptLaunchConsent() }
        resumedRequestEntered.await()
        val resumedMutation = fixture.api.replacements.last().second.mutation
        assertEquals(firstMutation.baseRevision, resumedMutation.baseRevision)
        assertFalse(firstMutation == resumedMutation)

        fixture.session.didEnterBackground()
        acceptance.await()

        assertEquals(resumedMutation, fixture.localState.pendingAddressBookReplacement)
        fixture.contacts.rows = listOf(person("Ben", "Jones", "+12125550102"))
        fixture.session.didBecomeActive()
        fixture.session.retryLaunchConsentRecovery()

        assertEquals(2, fixture.api.replacements.size)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertFalse(fixture.session.state.value.addressBookHasInterruptedReplacement)
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    @Test
    fun settingsReplacementConsentContinuationLeavesFriendlyNamesSetupUnchanged() = runTest {
        val fixture = fixture(
            initializeLocal = {
                initialSetupStep = InitialSetupStep.FriendlyNames
            },
        )
        fixture.session.start()
        fixture.contacts.permissionGranted = true
        fixture.contacts.rows = listOf(person("Anna", "Smith", "+12125550101"))
        var blockedOnce = false
        fixture.api.replaceHandler = { memberKey, request ->
            if (!blockedOnce) {
                blockedOnce = true
                throw CompanionApiException.ConsentRequired
            }
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { fixture.api.statuses[memberKey] = it }
        }

        assertTrue(fixture.session.prepareAddressBookSharing())
        assertFalse(fixture.session.completeAddressBookPermissionFlow(true))
        val savedMutation = requireNotNull(fixture.localState.pendingAddressBookReplacement)

        fixture.session.acceptLaunchConsent()

        assertEquals(2, fixture.api.replacements.size)
        assertEquals(savedMutation, fixture.api.replacements[0].second.mutation)
        assertEquals(savedMutation.baseRevision, fixture.api.replacements[1].second.mutation.baseRevision)
        assertFalse(savedMutation == fixture.api.replacements[1].second.mutation)
        assertNull(fixture.localState.pendingAddressBookReplacement)
        assertEquals(1, fixture.localState.addressBookRevision)
        assertEquals(InitialSetupStep.FriendlyNames, fixture.localState.initialSetupStep)
        assertEquals(
            InitialSetupStep.FriendlyNames,
            fixture.session.state.value.initialSetupStep,
        )
        assertNull(fixture.session.state.value.launchConsentRecovery)
    }

    private fun fixture(
        initialStatus: AddressBookServerStatus = disabledStatus(revision = 0),
        permissionGranted: Boolean = false,
        initializeLocal: FakeLocalState.() -> Unit = {},
    ): Fixture {
        val events = mutableListOf<String>()
        val auth = FakeAuth(AuthSessionState.SignedIn(MEMBER_ONE, verifiedOnline = true))
        val api = FakeApi(events).apply { statuses[MEMBER_ONE] = initialStatus }
        val contacts = FakeContacts(events).apply {
            this.permissionGranted = permissionGranted
        }
        val localState = FakeLocalState().apply(initializeLocal)
        val health = FakeHealth()
        var mutationSequence = 0
        val session = AppSession(
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
            newMutationId = {
                mutationSequence += 1
                "00000000-0000-4000-8000-${mutationSequence.toString().padStart(12, '0')}"
            },
        )
        session.markForegroundForTest()
        return Fixture(session, auth, api, contacts, localState, health, events)
    }

    private fun AppSession.markForegroundForTest() {
        val field = AppSession::class.java.getDeclaredField("isForeground")
        field.isAccessible = true
        field.setBoolean(this, true)
    }

    private data class Fixture(
        val session: AppSession,
        val auth: FakeAuth,
        val api: FakeApi,
        val contacts: FakeContacts,
        val localState: FakeLocalState,
        val health: FakeHealth,
        val events: MutableList<String>,
    )

    private class FakeAuth(
        var state: AuthSessionState,
    ) : AuthProvider {
        override suspend fun currentState(): AuthSessionState = state
        override suspend fun sendCode(method: LoginMethod, destination: String) = Unit
        override suspend fun confirmCode(
            method: LoginMethod,
            destination: String,
            code: String,
        ) = Unit
        override suspend fun identityToken(): String = "identity-token"
        override suspend fun signOut() {
            state = AuthSessionState.SignedOut
        }
    }

    private class FakeApi(
        private val events: MutableList<String>,
    ) : CompanionApi {
        val statuses = mutableMapOf<String, AddressBookServerStatus>()
        val addressStatusMembers = mutableListOf<String>()
        val syncStatusMembers = mutableListOf<String>()
        val replacements = mutableListOf<Pair<String, AddressBookReplacementRequest>>()
        val deletions = mutableListOf<Pair<String, AddressBookDeletionRequest>>()
        val launchConsentFetches = mutableListOf<String>()
        val launchConsentAcceptances =
            mutableListOf<Pair<String, LaunchConsentAcceptanceRequest>>()
        var launchConsentStatus = launchConsentStatus(granted = false)
        var launchConsentAcceptHandler: (suspend (
            String,
            LaunchConsentAcceptanceRequest,
            LaunchConsentStatus,
        ) -> LaunchConsentStatus)? = null
        var beforeSyncStatusReturn: suspend (Int, String) -> Unit = { _, _ -> }
        var beforeStatusReturn: suspend (Int, String) -> Unit = { _, _ -> }
        var replaceHandler: suspend (
            String,
            AddressBookReplacementRequest,
        ) -> AddressBookServerStatus = { memberKey, request ->
            enabledStatus(
                revision = request.mutation.baseRevision + 1,
                count = request.contacts.size,
            ).also { statuses[memberKey] = it }
        }
        var deleteHandler: suspend (
            String,
            AddressBookDeletionRequest,
        ) -> AddressBookServerStatus = { memberKey, request ->
            disabledStatus(request.mutation.baseRevision + 1).also { statuses[memberKey] = it }
        }

        override suspend fun admitCompanion(memberKey: String, timeZone: String) = Unit

        override suspend fun createJunctionSignInToken(
            memberKey: String,
            request: SignInTokenRequest,
        ): SignInTokenResponse = SignInTokenResponse("token", "sandbox")

        override suspend fun fetchSyncStatus(
            memberKey: String,
            sourceProviderSlug: String,
        ): CompanionSyncStatus {
            syncStatusMembers += memberKey
            beforeSyncStatusReturn(syncStatusMembers.size, memberKey)
            return CompanionSyncStatus(null, Instant.EPOCH, emptyMap())
        }

        override suspend fun fetchInitialOnboarding(memberKey: String) = InitialOnboarding(
            status = InitialOnboardingStatus.Completed,
            completedNow = null,
            preferences = InitialOnboardingPreferences(null, null, null),
            catalog = null,
            contactCard = null,
            contactAction = null,
        )

        override suspend fun fetchLaunchConsentStatus(memberKey: String): LaunchConsentStatus {
            launchConsentFetches += memberKey
            return launchConsentStatus
        }

        override suspend fun acceptLaunchConsent(
            memberKey: String,
            request: LaunchConsentAcceptanceRequest,
        ): LaunchConsentStatus {
            launchConsentAcceptances += memberKey to request
            launchConsentAcceptHandler?.let { handler ->
                return handler(memberKey, request, launchConsentStatus).also {
                    launchConsentStatus = it
                }
            }
            launchConsentStatus = grantLaunchConsentScope(launchConsentStatus, request.scope)
            return launchConsentStatus
        }

        override suspend fun fetchAddressBookStatus(memberKey: String): AddressBookServerStatus {
            addressStatusMembers += memberKey
            events += "address-get:$memberKey"
            beforeStatusReturn(addressStatusMembers.size, memberKey)
            return statuses.getValue(memberKey)
        }

        override suspend fun replaceAddressBook(
            memberKey: String,
            request: AddressBookReplacementRequest,
        ): AddressBookServerStatus {
            replacements += memberKey to request
            events += "address-put:$memberKey"
            return replaceHandler(memberKey, request)
        }

        override suspend fun deleteAddressBook(
            memberKey: String,
            request: AddressBookDeletionRequest,
        ): AddressBookServerStatus {
            deletions += memberKey to request
            events += "address-delete:$memberKey"
            return deleteHandler(memberKey, request)
        }
    }

    private class FakeContacts(
        private val events: MutableList<String>,
    ) : AddressBookContactSource {
        override val readPermission = "android.permission.READ_CONTACTS"
        var permissionGranted = false
        var rows: List<AddressBookPersonContact> = emptyList()
        var readCalls = 0
        var readGate: CompletableDeferred<Unit>? = null
        var readEntered = CompletableDeferred<Unit>()
        var readCancellationObserved = false

        override fun hasPermission(): Boolean = permissionGranted

        override suspend fun readPersonContacts(): List<AddressBookPersonContact> {
            check(permissionGranted)
            readCalls += 1
            events += "contacts-read"
            readEntered.complete(Unit)
            try {
                readGate?.await()
            } catch (error: CancellationException) {
                readCancellationObserved = true
                throw error
            }
            return rows
        }
    }

    private class FakeHealth : HealthSyncing {
        override val totalResourceCount = 4
        var signedIn = false
        var grantedCount = 0
        var refreshPermissionCalls = 0
        var syncCalls = 0
        var signOutCalls = 0
        var identifyGate: CompletableDeferred<Unit>? = null
        val identifyEntered = CompletableDeferred<Unit>()
        var syncGate: CompletableDeferred<Unit>? = null
        var syncEntered = CompletableDeferred<Unit>()
        override fun availability() = HealthConnectAvailability.Available
        override fun openHealthConnectIntent(): Intent? = null
        override fun isSignedIn(): Boolean = signedIn
        override fun pauseAutomaticSync() = Unit
        override fun cancelActiveSync() = Unit
        override fun configure() = Unit
        override fun grantSnapshot(): HealthGrantSnapshot =
            HealthGrantSnapshot.Available(grantedCount, emptySet())
        override fun revokeUnpromotedSyncLaunch() = Unit
        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) {
            authenticate()
            identifyEntered.complete(Unit)
            identifyGate?.await()
            signedIn = true
        }
        override suspend fun connectAfterPermissionRequest() {
            grantedCount = totalResourceCount
        }
        override suspend fun refreshPermissionState() {
            refreshPermissionCalls += 1
        }
        override suspend fun syncAllGrantedResources(
            expectedMemberKey: String,
            beforeSyncEnqueue: () -> Boolean,
            onSyncLaunchRejected: () -> Unit,
        ): HealthSyncAttemptResult {
            syncCalls += 1
            if (!beforeSyncEnqueue()) return HealthSyncAttemptResult.NotStarted
            syncEntered.complete(Unit)
            syncGate?.await()
            return HealthSyncAttemptResult.Complete
        }
        override suspend fun revokeActiveSyncAuthorization() = Unit
        override suspend fun signOutSdk() {
            signOutCalls += 1
            signedIn = false
        }
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation-id"
        override var memberKey: String? = null
        override var initialSetupStep: InitialSetupStep? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var healthReceiptBaselineAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null
        override var lastKnownStatusObservedAt: InstantValue? = null
        override var healthReconnectRequired = false
        override var pendingHealthSyncFailure: PendingHealthSyncFailure? = null
            private set
        override var signOutPending = false
            private set
        override var pendingPrivySignOutMemberKey: String? = null
            private set

        override fun completeHealthSetupAuthorization(
            requestedAt: InstantValue,
            receiptBaselineAt: InstantValue?,
            statusObservedAt: InstantValue,
            reminderDeadline: HealthSyncReminderDeadline?,
            completesInitialSetup: Boolean,
        ): Boolean {
            if (completesInitialSetup && initialSetupStep != InitialSetupStep.HealthConnect) {
                return false
            }
            healthAccessRequestedAt = requestedAt
            healthReceiptBaselineAt = receiptBaselineAt
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = statusObservedAt
            healthReconnectRequired = false
            pendingHealthSyncFailure = null
            if (completesInitialSetup) {
                initialSetupStep = InitialSetupStep.FriendlyNames
            }
            return true
        }

        override fun requireHealthReconnect(): Boolean {
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = true
            pendingHealthSyncFailure = null
            return true
        }

        override fun recordPendingHealthSyncFailure(
            failure: PendingHealthSyncFailure,
        ): Boolean {
            if (healthAccessRequestedAt == null || signOutPending) return false
            pendingHealthSyncFailure =
                pendingHealthSyncFailure?.mergedWith(failure) ?: failure
            return true
        }

        override fun replacePendingHealthSyncFailure(
            failure: PendingHealthSyncFailure?,
        ): Boolean {
            pendingHealthSyncFailure = failure
            return true
        }

        override fun clearPendingHealthSyncFailure(): Boolean {
            pendingHealthSyncFailure = null
            return true
        }
        var revision: Int? = null
        var replacement: AddressBookMutation? = null
        var deletion: AddressBookMutation? = null
        var completeAddressBookReplacementSucceeds = true
        var replaceAddressBookReplacementSucceeds = true
        var advanceInitialSetupSucceeds = true

        override val addressBookRevision: Int?
            get() = revision
        override val pendingAddressBookReplacement: AddressBookMutation?
            get() = replacement
        override val pendingAddressBookDeletion: AddressBookMutation?
            get() = deletion

        override fun advanceInitialSetupStep(
            expected: InitialSetupStep,
            next: InitialSetupStep,
            abandonPendingAddressBookReplacement: Boolean,
        ): Boolean {
            if (!advanceInitialSetupSucceeds || initialSetupStep != expected) return false
            initialSetupStep = next
            if (abandonPendingAddressBookReplacement) replacement = null
            return true
        }

        override fun recordAddressBookRevision(revision: Int): Boolean {
            this.revision = revision
            return true
        }

        override fun recordDisabledAddressBookRevision(revision: Int): Boolean {
            this.revision = revision
            replacement = null
            deletion = null
            return true
        }

        override fun beginAddressBookReplacement(mutation: AddressBookMutation): Boolean {
            replacement = mutation
            deletion = null
            return true
        }

        override fun replaceAddressBookReplacement(
            expectedMutationId: String,
            mutation: AddressBookMutation,
        ): Boolean {
            if (!replaceAddressBookReplacementSucceeds) return false
            val pending = replacement ?: return false
            if (
                pending.mutationId != expectedMutationId ||
                mutation.mutationId == expectedMutationId ||
                mutation.baseRevision != pending.baseRevision
            ) {
                return false
            }
            replacement = mutation
            deletion = null
            return true
        }

        override fun completeAddressBookReplacement(
            mutationId: String,
            revision: Int,
            completesInitialSetup: Boolean,
        ): Boolean {
            if (!completeAddressBookReplacementSucceeds) return false
            val pending = replacement ?: return false
            if (pending.mutationId != mutationId || revision <= pending.baseRevision) return false
            if (completesInitialSetup && initialSetupStep != InitialSetupStep.FriendlyNames) {
                return false
            }
            this.revision = revision
            replacement = null
            deletion = null
            if (completesInitialSetup) initialSetupStep = InitialSetupStep.Complete
            return true
        }

        override fun abandonAddressBookReplacement(mutationId: String): Boolean {
            if (replacement?.mutationId != mutationId) return false
            replacement = null
            return true
        }

        override fun beginAddressBookDeletion(mutation: AddressBookMutation): Boolean {
            deletion = mutation
            replacement = null
            return true
        }

        override fun completeAddressBookDeletion(mutationId: String, revision: Int): Boolean {
            val pending = deletion ?: return false
            if (pending.mutationId != mutationId || revision <= pending.baseRevision) return false
            this.revision = revision
            deletion = null
            replacement = null
            return true
        }

        override fun abandonAddressBookDeletion(mutationId: String): Boolean {
            if (deletion?.mutationId != mutationId) return false
            deletion = null
            return true
        }

        override fun revokeHealthSetupAuthorization(): Boolean {
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            pendingHealthSyncFailure = null
            return true
        }

        override fun beginSignOut(
            expectedMemberKey: String?,
            privySignOutMemberKey: String?,
            preserveMemberState: Boolean,
            pendingExternalHandoff: PendingExternalHandoff?,
        ): Boolean {
            if (memberKey != expectedMemberKey) return false
            signOutPending = true
            pendingPrivySignOutMemberKey = privySignOutMemberKey
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            pendingHealthSyncFailure = null
            if (!preserveMemberState) {
                initialSetupStep = null
                clearAddressBookMetadata()
            }
            return true
        }

        override fun completeSignOut(expectedMemberKey: String?): Boolean {
            if (memberKey != expectedMemberKey) return false
            signOutPending = false
            pendingPrivySignOutMemberKey = null
            clearMemberScopedState()
            return true
        }

        override fun clearMemberScopedState() {
            memberKey = null
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            pendingHealthSyncFailure = null
            initialSetupStep = null
            clearAddressBookMetadata()
        }

        private fun clearAddressBookMetadata() {
            revision = null
            replacement = null
            deletion = null
        }
    }

    private fun person(
        givenName: String?,
        familyName: String?,
        vararg phones: String,
    ) = AddressBookPersonContact(givenName, familyName, phones.toList())

    private companion object {
        const val MEMBER_ONE = "did:privy:member_one"
        const val MEMBER_TWO = "did:privy:member_two"
        const val MUTATION_ONE = "00000000-0000-4000-8000-000000000099"

        fun enabledStatus(revision: Int, count: Int) = AddressBookServerStatus(
            writeCapability = AddressBookWriteCapability.Enabled,
            enabled = true,
            revision = revision,
            storedContactCount = count,
        )

        fun disabledStatus(revision: Int) = AddressBookServerStatus(
            writeCapability = AddressBookWriteCapability.Enabled,
            enabled = false,
            revision = revision,
            storedContactCount = 0,
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
    }
}
