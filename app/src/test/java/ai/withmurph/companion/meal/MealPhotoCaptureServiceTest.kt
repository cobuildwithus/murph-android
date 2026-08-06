package ai.withmurph.companion.meal

import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.MealPhotoActionResult
import ai.withmurph.companion.core.MealPhotoCaptureEnrollment
import ai.withmurph.companion.core.MealPhotoCaptureEnrollmentRequest
import ai.withmurph.companion.core.MealPhotoCaptureRevocationRequest
import ai.withmurph.companion.core.MealPhotoCaptureState
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MealPhotoCaptureServiceTest {
    @Test
    fun oneApprovalCallRunsTheProcessorActionExactlyOnce() = runTest {
        val fixture = fixture()

        assertEquals(MealPhotoActionResult.Sent, fixture.service.approveReviewItem("capture"))

        assertEquals(1, fixture.processor.approveCalls)
    }

    @Test
    fun repeatedEnrollmentReliesOnAtomicServerRotationAndRetainsTheFirstSecret() = runTest {
        val fixture = fixture()

        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val firstToken = fixture.api.activeToken
        val firstSecret = fixture.credentials.credential?.idempotencySecret

        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val secondToken = fixture.api.activeToken

        assertTrue(firstToken != secondToken)
        assertFalse(fixture.api.accepts(firstToken))
        assertTrue(fixture.api.accepts(secondToken))
        assertEquals("S".repeat(43), firstSecret)
        assertEquals(firstSecret, fixture.credentials.credential?.idempotencySecret)
        assertTrue(fixture.uploader.revokedTokens.isEmpty())
        assertEquals(2, fixture.scheduler.scheduleCalls)
        assertEquals(listOf(1L, 2L), fixture.api.enrollmentRequests.map { it.authorityRevision })
        assertTrue(fixture.api.enrollmentRequests.all { it.schemaVersion == 2 })
    }

    @Test
    fun disablePersistsDisabledBeforeAwaitAndUsesTheExactMemberForRevocation() = runTest {
        val fixture = fixture(blockProcessor = true, blockCancellation = true)
        val enabling = async { fixture.service.enable(MEMBER) }
        fixture.processor.entered.await()

        val disabling = async { fixture.service.disable(MEMBER) }
        fixture.scheduler.cancelEntered.await()
        runCurrent()

        assertFalse(disabling.isCompleted)
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertTrue(fixture.state.state != null)

        fixture.scheduler.releaseCancel.complete(Unit)
        assertTrue(disabling.await())
        enabling.join()

        assertNull(fixture.state.state)
        assertNull(fixture.credentials.credential)
        assertFalse(fixture.authorization.isAuthorized(fixture.authorization.lastGenerationId))
        assertEquals(listOf(MEMBER), fixture.api.revokedMembers)
        assertEquals(INSTALLATION_ID, fixture.api.revocationRequests.single().appInstallationId)
        assertEquals(2L, fixture.api.revocationRequests.single().authorityRevision)
        assertEquals(2, fixture.api.revocationRequests.single().schemaVersion)
        assertBefore(fixture.events, "authority-revision-2", "identity-wire-2")
        assertBefore(fixture.events, "fence-disabled", "scheduler-cancel-entered")
        assertBefore(fixture.events, "scheduler-cancel-finished", "credential-suspend")
        assertBefore(fixture.events, "processor-closed", "credential-suspend")
        assertBefore(fixture.events, "credential-suspend", "scoped-revoke")
        assertBefore(fixture.events, "scoped-revoke", "credential-clear")
    }

    @Test
    fun failedScheduleCannotReportCaptureAsOn() = runTest {
        val fixture = fixture(scheduleResult = false)

        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.enable(MEMBER))

        assertEquals(1, fixture.scheduler.scheduleCalls)
        assertEquals(0, fixture.processor.processCalls)
        assertNull(fixture.credentials.credential)
        assertEquals("S".repeat(43), fixture.credentials.retainedSecret)
        assertEquals(1, fixture.uploader.revokedTokens.size)
    }

    @Test
    fun failedCancellationMakesConsentPauseFailClosed() = runTest {
        val fixture = fixture(cancelResult = false)
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))

        assertFalse(fixture.service.pauseForConsentRecovery(MEMBER))

        assertEquals(
            MealPhotoAuthorizationDisposition.ConsentSuspended,
            fixture.authorization.snapshot().disposition,
        )
        assertNull(fixture.credentials.credential)
    }

    @Test
    fun revokedFalseConfirmsOwnerDisableWhenNoEnrollmentIsPending() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.AlreadyInvalid,
            identityRevocationResult = false,
        )
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val generationId = checkNotNull(fixture.state.state).generationId

        assertTrue(fixture.service.disable(MEMBER))

        assertEquals(1, fixture.uploader.revokedTokens.size)
        assertEquals(listOf(MEMBER), fixture.api.revokedMembers)
        assertNull(fixture.credentials.pendingRevocationToken(generationId))
        assertNull(fixture.credentials.currentGenerationId())
        assertNull(fixture.state.state)
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertNull(fixture.authorization.snapshot().generationId)
    }

    @Test
    fun revokedFalseRetainsOwnerBoundAuthorityWhenEnrollmentIsPending() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.AlreadyInvalid,
            identityRevocationResult = false,
        )
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val configuration = checkNotNull(fixture.state.state)
        assertTrue(fixture.credentials.markEnrollmentPending(configuration.generationId))

        assertFalse(fixture.service.disable(MEMBER))

        assertEquals(listOf(MEMBER), fixture.api.revokedMembers)
        assertEquals(configuration.generationId, fixture.credentials.currentGenerationId())
        assertEquals(
            configuration.ownerDigest,
            fixture.credentials.ownerDigest(configuration.generationId),
        )
        assertTrue(fixture.credentials.hasPendingEnrollment(configuration.generationId))
        assertTrue(fixture.credentials.pendingRevocationToken(configuration.generationId) != null)
        assertNull(fixture.state.state)
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertEquals(configuration.generationId, fixture.authorization.snapshot().generationId)
    }

    @Test
    fun scopedRevocationCannotClosePendingEnrollmentWhenIdentityReportsAlreadyAbsent() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.Revoked,
            identityRevocationResult = false,
        )
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val configuration = checkNotNull(fixture.state.state)
        assertTrue(fixture.credentials.markEnrollmentPending(configuration.generationId))

        assertFalse(fixture.service.disable(MEMBER))

        assertEquals(listOf(MEMBER), fixture.api.revokedMembers)
        assertEquals(1, fixture.uploader.revokedTokens.size)
        assertEquals(configuration.generationId, fixture.credentials.currentGenerationId())
        assertEquals(
            configuration.ownerDigest,
            fixture.credentials.ownerDigest(configuration.generationId),
        )
        assertTrue(fixture.credentials.hasPendingEnrollment(configuration.generationId))
        assertTrue(fixture.credentials.pendingRevocationToken(configuration.generationId) != null)
        assertNull(fixture.state.state)
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertEquals(configuration.generationId, fixture.authorization.snapshot().generationId)
    }

    @Test
    fun enrollmentConsentPauseAndResumeReuseTheSameFutureOnlyBaseline() = runTest {
        val fixture = fixture(consentFailures = 1)

        val failure = runCatching { fixture.service.enable(MEMBER) }.exceptionOrNull()
        assertEquals(CompanionApiException.ConsentRequired, failure)
        val generationId = fixture.state.state?.generationId
        val baseline = fixture.state.state?.cursors
        assertTrue(fixture.credentials.hasPendingEnrollment(checkNotNull(generationId)))

        assertTrue(fixture.service.pauseForConsentRecovery(MEMBER))
        assertEquals(
            MealPhotoAuthorizationDisposition.ConsentSuspended,
            fixture.authorization.snapshot().disposition,
        )
        assertEquals(MealPhotoCaptureState.On, fixture.service.resumeAfterConsent(MEMBER))

        assertEquals(generationId, fixture.state.state?.generationId)
        assertEquals(baseline, fixture.state.state?.cursors)
        assertFalse(fixture.credentials.hasPendingEnrollment(generationId))
        assertEquals(
            MealPhotoAuthorizationDisposition.Authorized,
            fixture.authorization.snapshot().disposition,
        )
    }

    @Test
    fun reconstructedPendingEnrollmentDoesNotPassivelyReauthorize() = runTest {
        val fixture = fixture()
        val configuration = configuration()
        fixture.state.state = configuration
        fixture.credentials.restorePendingEnrollment(
            configuration.generationId,
            configuration.ownerDigest,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 3,
                generationId = configuration.generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            ),
        )

        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.refresh(MEMBER))

        assertTrue(fixture.credentials.hasPendingEnrollment(configuration.generationId))
        assertEquals(0, fixture.api.issuance)
        assertEquals(0, fixture.scheduler.scheduleCalls)
    }

    @Test
    fun delayedEnrollmentCannotReopenAuthorityAfterDisableAdvancesTheEpoch() = runTest {
        val enrollmentGate = CompletableDeferred<Unit>()
        val fixture = fixture(enrollmentGate = enrollmentGate)
        val enabling = async { fixture.service.enable(MEMBER) }
        fixture.api.enrollmentEntered.await()
        val leaseBeforeDisable = fixture.authorization.snapshot()

        val disabling = async { fixture.service.disable(MEMBER) }
        fixture.scheduler.cancelEntered.await()
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertTrue(fixture.authorization.snapshot().epoch > leaseBeforeDisable.epoch)

        enrollmentGate.complete(Unit)
        joinAll(enabling, disabling)

        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
        assertEquals(0, fixture.scheduler.scheduleCalls)
        assertNull(fixture.credentials.credential)
        assertNull(fixture.state.state)
        assertEquals(1L, fixture.api.enrollmentRequests.single().authorityRevision)
        assertTrue(fixture.api.revocationRequests.isNotEmpty())
        assertTrue(fixture.api.revocationRequests.all { it.authorityRevision > 1L })
        assertEquals(
            fixture.api.revocationRequests.map { it.authorityRevision }.sorted(),
            fixture.api.revocationRequests.map { it.authorityRevision },
        )
        assertBefore(fixture.events, "authority-revision-1", "enrollment-wire-1")
        assertBefore(fixture.events, "authority-revision-2", "identity-wire-2")
    }

    @Test
    fun onlyConsentSpecificSuspensionCanUseTheConsentResumeEntryPoint() = runTest {
        val fixture = fixture()
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val issuance = fixture.api.issuance

        assertTrue(fixture.service.suspendAtTrustBoundary())
        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.resumeAfterConsent(MEMBER))

        assertEquals(issuance, fixture.api.issuance)
        assertEquals(
            MealPhotoAuthorizationDisposition.Suspended,
            fixture.authorization.snapshot().disposition,
        )
    }

    @Test
    fun foreignOwnerRetryOrAlreadyInvalidKeepsStateWithoutUsingTheWrongIdentity() = runTest {
        listOf(
            MealPhotoRevocationDisposition.Retry,
            MealPhotoRevocationDisposition.AlreadyInvalid,
        ).forEach { disposition ->
            val fixture = fixture(scopedRevocation = disposition)
            val foreign = configuration(FOREIGN_MEMBER)
            fixture.state.state = foreign
            fixture.credentials.restoreCredential(
                credential(foreign.generationId),
                foreign.ownerDigest,
            )
            fixture.authorization.restore(
                MealPhotoAuthorizationSnapshot(
                    epoch = 2,
                    generationId = foreign.generationId,
                    disposition = MealPhotoAuthorizationDisposition.Authorized,
                ),
            )

            assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.enable(MEMBER))

            assertEquals(foreign, fixture.state.state)
            assertTrue(fixture.credentials.pendingRevocationToken(foreign.generationId) != null)
            assertTrue(fixture.api.revokedMembers.isEmpty())
            assertEquals(1, fixture.uploader.revokedTokens.size)
        }
    }

    @Test
    fun foreignPendingEnrollmentScoped401RetainsTheOwnerTombstone() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.AlreadyInvalid,
        )
        val foreign = configuration(FOREIGN_MEMBER)
        fixture.state.state = foreign
        fixture.credentials.restoreCredential(
            credential(foreign.generationId),
            foreign.ownerDigest,
            pendingEnrollment = true,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 4,
                generationId = foreign.generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            ),
        )

        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.enable(MEMBER))

        assertEquals(foreign, fixture.state.state)
        assertTrue(fixture.credentials.hasPendingEnrollment(foreign.generationId))
        assertEquals(1, fixture.uploader.revokedTokens.size)
        assertTrue(fixture.api.revokedMembers.isEmpty())
    }

    @Test
    fun directDisableCannotUseTheCurrentMemberIdentityToEraseForeignPendingAuthority() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.AlreadyInvalid,
        )
        val foreign = configuration(FOREIGN_MEMBER)
        fixture.state.state = foreign
        fixture.credentials.restoreCredential(
            credential(foreign.generationId),
            foreign.ownerDigest,
            pendingEnrollment = true,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 5,
                generationId = foreign.generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            ),
        )

        assertFalse(fixture.service.disable(MEMBER))

        assertEquals(foreign, fixture.state.state)
        assertTrue(fixture.credentials.hasPendingEnrollment(foreign.generationId))
        assertEquals(1, fixture.uploader.revokedTokens.size)
        assertTrue(fixture.api.revokedMembers.isEmpty())
    }

    @Test
    fun ownerlessResidualAuthorityNeverUsesTheCurrentMemberIdentity() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.AlreadyInvalid,
        )
        fixture.credentials.restoreCredential(
            credential(GENERATION_ID),
            ownerDigest = null,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 6,
                generationId = GENERATION_ID,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            ),
        )

        assertFalse(fixture.service.disable(MEMBER))

        assertTrue(fixture.api.revokedMembers.isEmpty())
        assertEquals(1, fixture.uploader.revokedTokens.size)
        assertEquals(GENERATION_ID, fixture.credentials.currentGenerationId())
        assertNull(fixture.credentials.ownerDigest(GENERATION_ID))
        assertTrue(fixture.credentials.pendingRevocationToken(GENERATION_ID) != null)
        assertEquals(
            MealPhotoAuthorizationDisposition.Disabled,
            fixture.authorization.snapshot().disposition,
        )
    }

    @Test
    fun successfulForeignCleanupCannotReuseThePreCleanupEnableLease() = runTest {
        val fixture = fixture(scopedRevocation = MealPhotoRevocationDisposition.Revoked)
        val foreign = configuration(FOREIGN_MEMBER)
        fixture.state.state = foreign
        fixture.credentials.restoreCredential(
            credential(foreign.generationId),
            foreign.ownerDigest,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 7,
                generationId = foreign.generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            ),
        )

        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.enable(MEMBER))
        assertNull(fixture.state.state)
        assertEquals(0, fixture.api.issuance)

        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        assertEquals(1, fixture.api.issuance)
    }

    @Test
    fun proactiveRenewalFailureKeepsAStillValidCredentialWhenIdentityRevokeIsOffline() = runTest {
        val fixture = fixture(
            enrollmentFailureCall = 2,
            identityRevocationFails = true,
        )
        assertEquals(MealPhotoCaptureState.On, fixture.service.enable(MEMBER))
        val previous = fixture.credentials.credential

        assertEquals(MealPhotoCaptureState.On, fixture.service.refresh(MEMBER))

        assertEquals(previous, fixture.credentials.credential)
        assertEquals(2, fixture.api.enrollmentAttempts)
        assertEquals(1, fixture.api.issuance)
        assertEquals(
            MealPhotoAuthorizationDisposition.Authorized,
            fixture.authorization.snapshot().disposition,
        )
    }

    @Test
    fun issuedCredentialSaveAndTombstoneFailureCloseDurableAuthorization() = runTest {
        val fixture = fixture(
            scopedRevocation = MealPhotoRevocationDisposition.Retry,
            identityRevocationFails = true,
            credentialSaveResults = listOf(false, false),
        )

        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.enable(MEMBER))

        assertEquals(
            MealPhotoAuthorizationDisposition.CredentialSuspended,
            fixture.authorization.snapshot().disposition,
        )
        assertNull(fixture.credentials.credential)
        assertTrue(fixture.credentials.hasPendingEnrollment(checkNotNull(fixture.state.state).generationId))
        assertEquals(0, fixture.scheduler.scheduleCalls)
        assertEquals(1, fixture.scheduler.cancelCalls)

        val enrollmentAttempts = fixture.api.enrollmentAttempts
        assertEquals(MealPhotoCaptureState.NeedsAttention, fixture.service.refresh(MEMBER))
        assertEquals(enrollmentAttempts, fixture.api.enrollmentAttempts)
        assertEquals(MealPhotoCaptureState.On, fixture.service.resumeAfterConsent(MEMBER))
        assertEquals(enrollmentAttempts + 1, fixture.api.enrollmentAttempts)
        assertEquals(
            MealPhotoAuthorizationDisposition.Authorized,
            fixture.authorization.snapshot().disposition,
        )
    }

    @Test
    fun credentialRepairPersistsPendingEnrollmentBeforeWaitingForTheServer() = runTest {
        val enrollmentGate = CompletableDeferred<Unit>()
        val fixture = fixture(enrollmentGate = enrollmentGate)
        val configuration = configuration()
        fixture.state.state = configuration
        fixture.credentials.restorePendingEnrollment(
            configuration.generationId,
            configuration.ownerDigest,
        )
        fixture.authorization.restore(
            MealPhotoAuthorizationSnapshot(
                epoch = 9,
                generationId = configuration.generationId,
                disposition = MealPhotoAuthorizationDisposition.CredentialSuspended,
            ),
        )

        val repairing = async { fixture.service.resumeAfterConsent(MEMBER) }
        fixture.api.enrollmentEntered.await()

        assertEquals(
            MealPhotoAuthorizationDisposition.CredentialSuspended,
            fixture.authorization.snapshot().disposition,
        )
        assertTrue(fixture.credentials.hasPendingEnrollment(configuration.generationId))
        assertNull(fixture.credentials.credential)
        assertEquals(configuration, fixture.state.state)

        enrollmentGate.complete(Unit)
        assertEquals(MealPhotoCaptureState.On, repairing.await())
        assertEquals(
            MealPhotoAuthorizationDisposition.Authorized,
            fixture.authorization.snapshot().disposition,
        )
    }

    private fun fixture(
        blockProcessor: Boolean = false,
        blockCancellation: Boolean = false,
        scheduleResult: Boolean = true,
        cancelResult: Boolean = true,
        consentFailures: Int = 0,
        enrollmentGate: CompletableDeferred<Unit>? = null,
        scopedRevocation: MealPhotoRevocationDisposition = MealPhotoRevocationDisposition.Revoked,
        enrollmentFailureCall: Int? = null,
        identityRevocationFails: Boolean = false,
        identityRevocationResult: Boolean = true,
        credentialSaveResults: List<Boolean> = emptyList(),
    ): Fixture {
        val events = mutableListOf<String>()
        val api = FakeApi(
            events = events,
            consentFailures = consentFailures,
            enrollmentGate = enrollmentGate,
            enrollmentFailureCall = enrollmentFailureCall,
            identityRevocationFails = identityRevocationFails,
            identityRevocationResult = identityRevocationResult,
        )
        val state = FakeStateStore(events)
        val credentials = FakeCredentialStore(events, credentialSaveResults)
        val authorization = FakeAuthorizationStore(events)
        val processor = FakeProcessor(events, blockProcessor)
        val scheduler = FakeScheduler(
            events = events,
            blocksCancellation = blockCancellation,
            scheduleResult = scheduleResult,
            cancelResult = cancelResult,
        )
        val uploader = FakeUploader(api, events, scopedRevocation)
        val service = MealPhotoCaptureService(
            api = api,
            media = FakeMediaSource(),
            processor = processor,
            uploader = uploader,
            stateStore = state,
            credentialStore = credentials,
            authorizationStore = authorization,
            scheduler = scheduler,
            installationId = INSTALLATION_ID,
            appVersion = "0.1.0",
            now = { NOW },
            processorMutex = Mutex(),
        )
        return Fixture(
            service,
            api,
            state,
            credentials,
            authorization,
            processor,
            scheduler,
            uploader,
            events,
        )
    }

    private data class Fixture(
        val service: MealPhotoCaptureService,
        val api: FakeApi,
        val state: FakeStateStore,
        val credentials: FakeCredentialStore,
        val authorization: FakeAuthorizationStore,
        val processor: FakeProcessor,
        val scheduler: FakeScheduler,
        val uploader: FakeUploader,
        val events: MutableList<String>,
    )

    private class FakeApi(
        private val events: MutableList<String>,
        consentFailures: Int,
        private val enrollmentGate: CompletableDeferred<Unit>?,
        private val enrollmentFailureCall: Int?,
        private val identityRevocationFails: Boolean,
        private val identityRevocationResult: Boolean,
    ) : CompanionApi {
        var activeToken: String? = null
        var issuance = 0
        var enrollmentAttempts = 0
        private var remainingConsentFailures = consentFailures
        val enrollmentEntered = CompletableDeferred<Unit>()
        val enrollmentRequests = mutableListOf<MealPhotoCaptureEnrollmentRequest>()
        val revokedMembers = mutableListOf<String>()
        val revocationRequests = mutableListOf<MealPhotoCaptureRevocationRequest>()

        override suspend fun createMealPhotoCaptureEnrollment(
            memberKey: String,
            request: MealPhotoCaptureEnrollmentRequest,
        ): MealPhotoCaptureEnrollment {
            enrollmentRequests += request
            events += "enrollment-wire-${request.authorityRevision}"
            enrollmentEntered.complete(Unit)
            enrollmentGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            enrollmentAttempts += 1
            if (enrollmentAttempts == enrollmentFailureCall) {
                throw IllegalStateException("enrollment unavailable")
            }
            if (remainingConsentFailures > 0) {
                remainingConsentFailures -= 1
                throw CompanionApiException.ConsentRequired
            }
            issuance += 1
            activeToken = "murph_meal_photo_${"T".repeat(42)}$issuance"
            events += "enrollment-$issuance"
            return MealPhotoCaptureEnrollment(
                uploadToken = checkNotNull(activeToken),
                idempotencySecret = if (issuance == 1) "S".repeat(43) else "Q".repeat(43),
                expiresAt = NOW.plusSeconds(2 * 24 * 60 * 60),
            )
        }

        override suspend fun revokeMealPhotoCaptureEnrollment(
            memberKey: String,
            request: MealPhotoCaptureRevocationRequest,
        ): Boolean {
            events += "identity-wire-${request.authorityRevision}"
            revokedMembers += memberKey
            revocationRequests += request
            if (identityRevocationFails) throw IllegalStateException("identity revoke unavailable")
            if (identityRevocationResult) activeToken = null
            return identityRevocationResult
        }

        fun accepts(token: String?): Boolean = token != null && token == activeToken

        override suspend fun createJunctionSignInToken(
            request: SignInTokenRequest,
        ): SignInTokenResponse = error("not used")

        override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus =
            error("not used")
    }

    private class FakeMediaSource : MealPhotoMediaSource {
        override val automaticCaptureSupported = true
        override fun access() = MealPhotoMediaAccess.Full
        override fun permissionRequest(): Array<String> = emptyArray()
        override suspend fun currentBoundaries() = listOf(BASELINE)
        override suspend fun candidatesAfter(
            cursor: MealPhotoVolumeCursor,
            limit: Int,
        ): List<MealPhotoCandidate> = emptyList()

        override suspend fun revalidatedCandidate(
            record: MealPhotoReviewRecord,
        ): MealPhotoCandidateValidation = MealPhotoCandidateValidation.Unavailable

        override suspend fun sanitizedImage(
            contentUri: String,
            maximumDimension: Int,
        ): MealPhotoImageAsset? = null
    }

    private class FakeProcessor(
        private val events: MutableList<String>,
        private val blocks: Boolean,
    ) : MealPhotoProcessing {
        val entered = CompletableDeferred<Unit>()
        var processCalls = 0
        var approveCalls = 0

        override suspend fun process(): MealPhotoProcessingResult {
            processCalls += 1
            events += "processor-entered"
            entered.complete(Unit)
            if (!blocks) return MealPhotoProcessingResult.Completed
            return try {
                CompletableDeferred<Unit>().await()
                MealPhotoProcessingResult.Completed
            } finally {
                events += "processor-closed"
            }
        }

        override suspend fun reviewItems(maximum: Int) =
            emptyList<ai.withmurph.companion.core.MealPhotoReviewItem>()

        override suspend fun approve(captureId: String): MealPhotoActionResult {
            approveCalls += 1
            return MealPhotoActionResult.Sent
        }
        override suspend fun dismiss(captureId: String) = MealPhotoActionResult.Dismissed
    }

    private class FakeStateStore(
        private val events: MutableList<String>,
    ) : MealPhotoStateStoring {
        var state: MealPhotoCaptureConfiguration? = null
        override fun load() = state
        override fun save(configuration: MealPhotoCaptureConfiguration): Boolean {
            state = configuration
            events += "state-save"
            return true
        }

        override fun clear(): Boolean {
            state = null
            events += "state-clear"
            return true
        }
    }

    private class FakeCredentialStore(
        private val events: MutableList<String>,
        saveResults: List<Boolean>,
    ) : MealPhotoCredentialStoring {
        var credential: MealPhotoCredential? = null
        var retainedSecret: String? = null
        private var pendingToken: String? = null
        private var generationId: String? = null
        private var boundOwnerDigest: String? = null
        private var pendingEnrollment = false
        private val generationKeys = mutableSetOf<String>()
        private val saveResults = ArrayDeque(saveResults)

        override fun currentGenerationId() = generationId

        override fun bindOwner(generationId: String, ownerDigest: String): Boolean {
            if (this.generationId != null && this.generationId != generationId) return false
            if (boundOwnerDigest != null && boundOwnerDigest != ownerDigest) return false
            this.generationId = generationId
            boundOwnerDigest = ownerDigest
            return true
        }

        override fun ownerDigest(generationId: String) =
            boundOwnerDigest?.takeIf { this.generationId == generationId }

        override fun load(generationId: String) = credential?.takeIf {
            it.generationId == generationId
        }

        override fun retainedIdempotencySecret(generationId: String) =
            retainedSecret?.takeIf { this.generationId == generationId }

        override fun pendingRevocationToken(generationId: String) =
            pendingToken?.takeIf { this.generationId == generationId }

        override fun markEnrollmentPending(generationId: String): Boolean {
            if (this.generationId != null && this.generationId != generationId) return false
            this.generationId = generationId
            pendingEnrollment = true
            events += "enrollment-pending"
            return true
        }

        override fun hasPendingEnrollment(generationId: String) =
            this.generationId == generationId && pendingEnrollment

        override fun clearPendingEnrollment(generationId: String): Boolean {
            if (this.generationId != generationId) return false
            pendingEnrollment = false
            return true
        }

        override fun save(credential: MealPhotoCredential): Boolean {
            if (saveResults.removeFirstOrNull() == false) {
                events += "credential-save-failed"
                return false
            }
            this.credential = credential
            generationId = credential.generationId
            retainedSecret = credential.idempotencySecret
            pendingToken = null
            pendingEnrollment = false
            generationKeys += credential.generationId
            events += "credential-save"
            return true
        }

        override fun suspend(generationId: String): Boolean {
            if (this.generationId != generationId) return false
            credential?.let {
                retainedSecret = it.idempotencySecret
                pendingToken = it.uploadToken
            }
            credential = null
            events += "credential-suspend"
            return pendingEnrollment || retainedSecret != null
        }

        override fun confirmRevoked(generationId: String): Boolean {
            if (this.generationId != generationId) return false
            pendingToken = null
            return true
        }

        override fun hasGenerationKey(generationId: String) = generationId in generationKeys

        override fun clear(generationId: String, preserveGenerationKey: Boolean): Boolean {
            if (this.generationId != null && this.generationId != generationId) return false
            credential = null
            retainedSecret = null
            pendingToken = null
            pendingEnrollment = false
            boundOwnerDigest = null
            this.generationId = null
            if (!preserveGenerationKey) generationKeys -= generationId
            events += "credential-clear"
            return true
        }

        fun restorePendingEnrollment(generationId: String, ownerDigest: String?) {
            this.generationId = generationId
            boundOwnerDigest = ownerDigest
            pendingEnrollment = true
        }

        fun restoreCredential(
            credential: MealPhotoCredential,
            ownerDigest: String?,
            pendingEnrollment: Boolean = false,
        ) {
            this.credential = credential
            generationId = credential.generationId
            boundOwnerDigest = ownerDigest
            retainedSecret = credential.idempotencySecret
            generationKeys += credential.generationId
            this.pendingEnrollment = pendingEnrollment
        }
    }

    private class FakeAuthorizationStore(
        private val events: MutableList<String>,
    ) : MealPhotoAuthorizationStoring {
        private var current = MealPhotoAuthorizationSnapshot(
            epoch = 0,
            generationId = null,
            disposition = MealPhotoAuthorizationDisposition.Disabled,
        )
        var lastGenerationId = ""
            private set

        override fun snapshot() = current

        override fun isAuthorized(generationId: String) =
            current.generationId == generationId &&
                current.disposition == MealPhotoAuthorizationDisposition.Authorized

        private var authorityRevision = 0L

        override fun allocateAuthorityRevision(): Long {
            authorityRevision += 1
            events += "authority-revision-$authorityRevision"
            return authorityRevision
        }

        override fun suspendForConsent(): Boolean {
            if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
            return advance(
                if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
                    MealPhotoAuthorizationDisposition.Disabled
                } else {
                    MealPhotoAuthorizationDisposition.ConsentSuspended
                },
                "fence-consent-suspended",
            )
        }

        override fun suspendForCredentialRepair(): Boolean {
            if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
            return advance(
                if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
                    MealPhotoAuthorizationDisposition.Disabled
                } else {
                    MealPhotoAuthorizationDisposition.CredentialSuspended
                },
                "fence-credential-suspended",
            )
        }

        override fun suspendAll(): Boolean = advance(
            if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
                MealPhotoAuthorizationDisposition.Disabled
            } else {
                MealPhotoAuthorizationDisposition.Suspended
            },
            "fence-suspended",
        )

        override fun disableAll(): Boolean = advance(
            MealPhotoAuthorizationDisposition.Disabled,
            "fence-disabled",
        )

        override fun authorize(
            generationId: String,
            expectedEpoch: Long,
            allowedPrevious: Set<MealPhotoAuthorizationDisposition>,
        ): Boolean {
            if (current.epoch != expectedEpoch || current.disposition !in allowedPrevious) {
                events += "fence-authorize-rejected"
                return false
            }
            lastGenerationId = generationId
            current = current.copy(
                generationId = generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            )
            events += "fence-authorized"
            return true
        }

        override fun clearGeneration(generationId: String): Boolean {
            if (current.generationId == null) return true
            if (current.generationId != generationId) return false
            current = current.copy(generationId = null)
            events += "fence-generation-cleared"
            return true
        }

        fun restore(snapshot: MealPhotoAuthorizationSnapshot) {
            current = snapshot
            lastGenerationId = snapshot.generationId.orEmpty()
        }

        private fun advance(
            disposition: MealPhotoAuthorizationDisposition,
            event: String,
        ): Boolean {
            current = current.copy(epoch = current.epoch + 1, disposition = disposition)
            events += event
            return true
        }
    }

    private class FakeScheduler(
        private val events: MutableList<String>,
        blocksCancellation: Boolean,
        private val scheduleResult: Boolean,
        private val cancelResult: Boolean,
    ) : MealPhotoWorkScheduling {
        val cancelEntered = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>().also {
            if (!blocksCancellation) it.complete(Unit)
        }
        var scheduleCalls = 0
        var cancelCalls = 0

        override suspend fun schedule(): Boolean {
            scheduleCalls += 1
            events += "scheduler-schedule"
            return scheduleResult
        }

        override suspend fun cancel(): Boolean {
            cancelCalls += 1
            events += "scheduler-cancel-entered"
            cancelEntered.complete(Unit)
            releaseCancel.await()
            events += "scheduler-cancel-finished"
            return cancelResult
        }
    }

    private class FakeUploader(
        private val api: FakeApi,
        private val events: MutableList<String>,
        private val scopedRevocation: MealPhotoRevocationDisposition,
    ) : MealPhotoUploading {
        val revokedTokens = mutableListOf<String>()

        override suspend fun upload(
            jpeg: ByteArray,
            credential: MealPhotoCredential,
            captureId: String,
            capturedAt: Instant,
        ) = MealPhotoUploadDisposition.Uploaded

        override suspend fun revokeScoped(
            uploadToken: String,
        ): MealPhotoRevocationDisposition {
            events += "scoped-revoke"
            revokedTokens += uploadToken
            if (
                scopedRevocation == MealPhotoRevocationDisposition.Revoked &&
                api.accepts(uploadToken)
            ) api.activeToken = null
            return scopedRevocation
        }
    }

    private fun configuration(memberKey: String = MEMBER) = MealPhotoCaptureConfiguration(
        generationId = GENERATION_ID,
        ownerDigest = MealPhotoCaptureConfiguration.ownerDigest(INSTALLATION_ID, memberKey),
        enabledAtEpochMillis = NOW.epochSecond * 1_000,
        cursors = listOf(BASELINE),
    )

    private fun credential(generationId: String) = MealPhotoCredential(
        generationId = generationId,
        uploadToken = "murph_meal_photo_${"A".repeat(43)}",
        idempotencySecret = "S".repeat(43),
        expiresAtEpochMillis = NOW.plusSeconds(2 * 24 * 60 * 60).toEpochMilli(),
    )

    private fun assertBefore(events: List<String>, first: String, second: String) {
        assertTrue("Missing $first in $events", first in events)
        assertTrue("Missing $second in $events", second in events)
        assertTrue(
            "Expected $first before $second in $events",
            events.indexOf(first) < events.indexOf(second),
        )
    }

    private companion object {
        const val MEMBER = "did:privy:member"
        const val FOREIGN_MEMBER = "did:privy:foreign-member"
        const val INSTALLATION_ID = "00000000-0000-4000-8000-000000000001"
        const val GENERATION_ID = "00000000-0000-4000-8000-000000000002"
        val NOW: Instant = Instant.parse("2026-08-05T12:00:00Z")
        val BASELINE = MealPhotoVolumeCursor(
            volumeName = "external",
            version = "v1",
            generation = 10,
            mediaId = Long.MAX_VALUE,
        )
    }
}
