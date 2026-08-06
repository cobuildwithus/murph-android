package ai.withmurph.companion.meal

import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.MealPhotoActionResult
import ai.withmurph.companion.core.MealPhotoCaptureControlling
import ai.withmurph.companion.core.MealPhotoCaptureEnrollment
import ai.withmurph.companion.core.MealPhotoCaptureEnrollmentRequest
import ai.withmurph.companion.core.MealPhotoCaptureRevocationRequest
import ai.withmurph.companion.core.MealPhotoCaptureState
import ai.withmurph.companion.core.MealPhotoReviewItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class MealPhotoCaptureService(
    private val api: CompanionApi,
    private val media: MealPhotoMediaSource,
    private val processor: MealPhotoProcessing,
    private val uploader: MealPhotoUploading,
    private val stateStore: MealPhotoStateStoring,
    private val credentialStore: MealPhotoCredentialStoring,
    private val authorizationStore: MealPhotoAuthorizationStoring,
    private val scheduler: MealPhotoWorkScheduling,
    private val installationId: String,
    private val appVersion: String,
    private val now: () -> Instant = Instant::now,
    private val processorMutex: Mutex = MealPhotoCaptureRuntime.processorMutex,
) : MealPhotoCaptureControlling {
    private val operationMutex = Mutex()
    private val activeOperationLock = Any()
    private var activeOperation: kotlinx.coroutines.Job? = null

    override val automaticCaptureSupported: Boolean
        get() = media.automaticCaptureSupported

    override fun permissionRequest(): Array<String> = media.permissionRequest()

    override suspend fun currentState(memberKey: String?): MealPhotoCaptureState =
        withContext(Dispatchers.IO) { currentStateOnIo(memberKey) }

    private fun currentStateOnIo(memberKey: String?): MealPhotoCaptureState {
        if (!automaticCaptureSupported) return MealPhotoCaptureState.Unavailable
        val configuration = stateStore.load() ?: return if (hasResidualLocalAuthority()) {
            MealPhotoCaptureState.NeedsAttention
        } else {
            MealPhotoCaptureState.Off
        }
        if (memberKey == null || configuration.ownerDigest != ownerDigest(memberKey)) {
            return MealPhotoCaptureState.NeedsAttention
        }
        val authorization = authorizationStore.snapshot()
        if (authorization.generationId != configuration.generationId) {
            return MealPhotoCaptureState.NeedsAttention
        }
        when (authorization.disposition) {
            MealPhotoAuthorizationDisposition.Disabled ->
                return MealPhotoCaptureState.NeedsAttention
            MealPhotoAuthorizationDisposition.ConsentSuspended,
            MealPhotoAuthorizationDisposition.CredentialSuspended,
            MealPhotoAuthorizationDisposition.Suspended ->
                return MealPhotoCaptureState.NeedsAttention
            MealPhotoAuthorizationDisposition.Authorized -> Unit
        }
        return when (media.access()) {
            MealPhotoMediaAccess.None -> MealPhotoCaptureState.NeedsFullAccess
            MealPhotoMediaAccess.Partial -> MealPhotoCaptureState.NeedsFullAccess
            MealPhotoMediaAccess.Full -> {
                if (!credentialStore.hasGenerationKey(configuration.generationId)) {
                    MealPhotoCaptureState.NeedsAttention
                } else {
                    val credential = credentialStore.load(configuration.generationId)
                    if (credential?.expiresAtEpochMillis?.let { it > now().toEpochMilli() } == true) {
                        MealPhotoCaptureState.On
                    } else {
                        MealPhotoCaptureState.NeedsAttention
                    }
                }
            }
        }
    }

    override suspend fun refresh(memberKey: String): MealPhotoCaptureState =
        withContext(Dispatchers.IO) {
        val configuration = stateStore.load()
        if (configuration != null && configuration.ownerDigest != ownerDigest(memberKey)) {
            return@withContext if (removeForeignLocalAuthority()) {
                MealPhotoCaptureState.Off
            } else {
                MealPhotoCaptureState.NeedsAttention
            }
        }
        if (
            configuration != null &&
            !credentialStore.hasGenerationKey(configuration.generationId) &&
            !credentialStore.hasPendingEnrollment(configuration.generationId)
        ) {
            return@withContext if (disable(memberKey)) {
                MealPhotoCaptureState.Off
            } else {
                MealPhotoCaptureState.NeedsAttention
            }
        }
        if (stateStore.load() == null && hasResidualLocalAuthority()) {
            return@withContext if (disable(memberKey)) {
                MealPhotoCaptureState.Off
            } else {
                MealPhotoCaptureState.NeedsAttention
            }
        }
        if (
            authorizationStore.snapshot().disposition ==
                MealPhotoAuthorizationDisposition.Disabled &&
            hasResidualLocalAuthority()
        ) {
            return@withContext if (disable(memberKey)) {
                MealPhotoCaptureState.Off
            } else {
                MealPhotoCaptureState.NeedsAttention
            }
        }
        refreshAuthorized(memberKey)
    }

    private suspend fun refreshAuthorized(memberKey: String): MealPhotoCaptureState = runExclusive {
        var configuration = stateStore.load() ?: return@runExclusive MealPhotoCaptureState.Off
        if (configuration.ownerDigest != ownerDigest(memberKey)) {
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        if (media.access() != MealPhotoMediaAccess.Full) {
            return@runExclusive MealPhotoCaptureState.NeedsFullAccess
        }
        val authorization = authorizationStore.snapshot()
        when {
            authorization.disposition == MealPhotoAuthorizationDisposition.Disabled ->
                return@runExclusive MealPhotoCaptureState.Off
            authorization.disposition != MealPhotoAuthorizationDisposition.Authorized ||
                authorization.generationId != configuration.generationId ->
                return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        if (!credentialStore.hasGenerationKey(configuration.generationId)) {
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }

        val credential = credentialStore.load(configuration.generationId)
        val shouldRefresh = credential == null ||
            credential.expiresAtEpochMillis <= now().plus(CREDENTIAL_REFRESH_WINDOW).toEpochMilli()
        if (shouldRefresh) {
            if (
                rotateCredential(
                    memberKey = memberKey,
                    configuration = configuration,
                    previous = credential,
                    mayAdoptServerSecret = false,
                ) == null
            ) {
                return@runExclusive MealPhotoCaptureState.NeedsAttention
            }
            if (
                !authorizationStore.authorize(
                    generationId = configuration.generationId,
                    expectedEpoch = authorization.epoch,
                    allowedPrevious = setOf(MealPhotoAuthorizationDisposition.Authorized),
                )
            ) {
                val closed = closeLocal(configuration, preserveState = true)
                withContext(NonCancellable) {
                    val revoked = revokeRemoteAuthority(
                        memberKey,
                        closed.uploadToken,
                        closed.generationId,
                    )
                    if (revoked) credentialStore.confirmRevoked(configuration.generationId)
                }
                return@runExclusive MealPhotoCaptureState.NeedsAttention
            }
        }
        configuration = stateStore.load() ?: return@runExclusive MealPhotoCaptureState.Off
        if (!scheduler.schedule()) {
            val closed = closeLocal(configuration, preserveState = true)
            withContext(NonCancellable) {
                val revoked = revokeRemoteAuthority(
                    memberKey,
                    closed.uploadToken,
                    closed.generationId,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        when (processor.process()) {
            MealPhotoProcessingResult.NeedsAttention -> MealPhotoCaptureState.NeedsAttention
            MealPhotoProcessingResult.Inactive -> currentStateOnIo(memberKey)
            MealPhotoProcessingResult.Completed,
            MealPhotoProcessingResult.Pending,
            -> currentStateOnIo(memberKey)
        }
    }

    override suspend fun enable(memberKey: String): MealPhotoCaptureState =
        withContext(Dispatchers.IO) {
        val authorizationLease = authorizationStore.snapshot()
        val existing = stateStore.load()
        if (existing != null && existing.ownerDigest != ownerDigest(memberKey)) {
            if (!removeForeignLocalAuthority()) {
                return@withContext MealPhotoCaptureState.NeedsAttention
            }
        } else if (
            (existing == null && hasResidualLocalAuthority()) ||
            (
                existing != null &&
                    !credentialStore.hasGenerationKey(existing.generationId) &&
                    !credentialStore.hasPendingEnrollment(existing.generationId)
                )
        ) {
            if (!disable(memberKey)) return@withContext MealPhotoCaptureState.NeedsAttention
        }
        if (
            authorizationStore.snapshot().disposition ==
                MealPhotoAuthorizationDisposition.Disabled &&
            hasResidualLocalAuthority() &&
            !disable(memberKey)
        ) return@withContext MealPhotoCaptureState.NeedsAttention
        enableOwned(
            memberKey = memberKey,
            allowedAuthorization = setOf(
                MealPhotoAuthorizationDisposition.Disabled,
                MealPhotoAuthorizationDisposition.Authorized,
            ),
            requiresExistingConfiguration = false,
            authorizationLease = authorizationLease,
        )
    }

    override suspend fun resumeAfterConsent(memberKey: String): MealPhotoCaptureState =
        withContext(Dispatchers.IO) {
        val authorizationLease = authorizationStore.snapshot()
        val configuration = stateStore.load()
        val canResumePendingEnrollment =
            authorizationLease.disposition == MealPhotoAuthorizationDisposition.Authorized &&
                configuration?.generationId == authorizationLease.generationId &&
                configuration?.let {
                    credentialStore.hasPendingEnrollment(it.generationId)
                } == true
        enableOwned(
            memberKey = memberKey,
            allowedAuthorization = if (canResumePendingEnrollment) {
                setOf(
                    MealPhotoAuthorizationDisposition.ConsentSuspended,
                    MealPhotoAuthorizationDisposition.CredentialSuspended,
                    MealPhotoAuthorizationDisposition.Authorized,
                )
            } else {
                setOf(
                    MealPhotoAuthorizationDisposition.ConsentSuspended,
                    MealPhotoAuthorizationDisposition.CredentialSuspended,
                )
            },
            requiresExistingConfiguration = true,
            authorizationLease = authorizationLease,
        )
    }

    private suspend fun enableOwned(
        memberKey: String,
        allowedAuthorization: Set<MealPhotoAuthorizationDisposition>,
        requiresExistingConfiguration: Boolean,
        authorizationLease: MealPhotoAuthorizationSnapshot,
    ): MealPhotoCaptureState = runExclusive {
        if (!automaticCaptureSupported) return@runExclusive MealPhotoCaptureState.Unavailable
        if (media.access() != MealPhotoMediaAccess.Full) {
            return@runExclusive MealPhotoCaptureState.NeedsPhotosAccess
        }

        var configuration = stateStore.load()
        if (requiresExistingConfiguration && configuration == null) {
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        if (
            configuration != null &&
            (
                configuration.ownerDigest != ownerDigest(memberKey) ||
                    (
                        !credentialStore.hasGenerationKey(configuration.generationId) &&
                            !credentialStore.hasPendingEnrollment(
                                configuration.generationId,
                            ) &&
                            authorizationLease.disposition !in setOf(
                                MealPhotoAuthorizationDisposition.Suspended,
                                MealPhotoAuthorizationDisposition.ConsentSuspended,
                                MealPhotoAuthorizationDisposition.CredentialSuspended,
                            )
                        )
                )
        ) {
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }

        val isNewConfiguration = configuration == null
        if (configuration == null) {
            val boundaries = try {
                media.currentBoundaries()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@runExclusive MealPhotoCaptureState.NeedsAttention
            }
            configuration = MealPhotoCaptureConfiguration(
                generationId = UUID.randomUUID().toString(),
                ownerDigest = ownerDigest(memberKey),
                // MediaStore DATE_ADDED is only second-granularity on some devices. The cursor is
                // the future-only boundary; flooring this corroborating timestamp avoids dropping
                // a genuinely new image inserted later in the same second.
                enabledAtEpochMillis = now().epochSecond * 1_000,
                cursors = boundaries,
            )
        }

        if (
            authorizationLease.disposition !in allowedAuthorization ||
            authorizationStore.snapshot() != authorizationLease
        ) {
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }

        if (isNewConfiguration && !stateStore.save(configuration)) {
            authorizationStore.disableAll()
            credentialStore.clear(configuration.generationId, preserveGenerationKey = false)
            stateStore.clear()
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        if (
            !credentialStore.bindOwner(
                configuration.generationId,
                configuration.ownerDigest,
            )
        ) {
            if (isNewConfiguration) stateStore.clear()
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        val authorizeAfterCredentialCommit =
            authorizationLease.disposition ==
                MealPhotoAuthorizationDisposition.CredentialSuspended
        if (
            !authorizeAfterCredentialCommit &&
            !authorizationStore.authorize(
                generationId = configuration.generationId,
                expectedEpoch = authorizationLease.epoch,
                allowedPrevious = allowedAuthorization,
            )
        ) {
            if (isNewConfiguration) stateStore.clear()
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }

        val scopedCredential = rotateCredential(
            memberKey = memberKey,
            configuration = configuration,
            previous = credentialStore.load(configuration.generationId),
            mayAdoptServerSecret = isNewConfiguration,
        ) ?: return@runExclusive MealPhotoCaptureState.NeedsAttention
        if (
            authorizeAfterCredentialCommit &&
            !authorizationStore.authorize(
                generationId = configuration.generationId,
                expectedEpoch = authorizationLease.epoch,
                allowedPrevious = setOf(
                    MealPhotoAuthorizationDisposition.CredentialSuspended,
                ),
            )
        ) {
            val closed = closeLocal(configuration, preserveState = true)
            withContext(NonCancellable) {
                val revoked = revokeRemoteAuthority(
                    memberKey,
                    closed.uploadToken ?: scopedCredential.uploadToken,
                    closed.generationId,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        if (!authorizationStore.isAuthorized(configuration.generationId)) {
            val closed = closeLocal(configuration, preserveState = true)
            withContext(NonCancellable) {
                val revoked = revokeRemoteAuthority(
                    memberKey,
                    closed.uploadToken ?: scopedCredential.uploadToken,
                    closed.generationId,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }

        if (!scheduler.schedule()) {
            val closed = closeLocal(configuration, preserveState = true)
            withContext(NonCancellable) {
                val revoked = revokeRemoteAuthority(
                    memberKey,
                    closed.uploadToken,
                    closed.generationId,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return@runExclusive MealPhotoCaptureState.NeedsAttention
        }
        when (processor.process()) {
            MealPhotoProcessingResult.NeedsAttention -> MealPhotoCaptureState.NeedsAttention
            else -> currentStateOnIo(memberKey)
        }
    }

    override suspend fun pauseForConsentRecovery(memberKey: String): Boolean =
        withContext(Dispatchers.IO) {
        if (hasResidualLocalAuthority() && !localAuthorityOwnedBy(memberKey)) {
            return@withContext removeForeignLocalAuthority()
        }
        val hadLocalAuthority = hasResidualLocalAuthority()
        val fenceClosed = authorizationStore.suspendForConsent()
        cancelActiveOperation()
        val workCancelled = scheduler.cancel()
        val closed = operationMutex.withLock {
            processorMutex.withLock {
                suspendLocalAuthority()
            }
        }
        val remoteRevoked = if (!hadLocalAuthority) {
            true
        } else {
            withContext(NonCancellable) {
                revokeRemoteAuthority(memberKey, closed.uploadToken, closed.generationId)
            }
        }
        val revocationConfirmed = when {
            !remoteRevoked -> false
            closed.generationId == null -> true
            closed.uploadToken == null &&
                credentialStore.hasPendingEnrollment(closed.generationId) ->
                stateStore.load()?.generationId == closed.generationId ||
                    credentialStore.clear(closed.generationId, preserveGenerationKey = false)
            closed.uploadToken == null -> true
            else -> credentialStore.confirmRevoked(closed.generationId)
        }
        fenceClosed && workCancelled && closed.closed && revocationConfirmed
    }

    override suspend fun disable(memberKey: String): Boolean = withContext(Dispatchers.IO) {
        if (hasResidualLocalAuthority() && !localAuthorityOwnedBy(memberKey)) {
            return@withContext removeForeignLocalAuthority()
        }
        val hadLocalAuthority = hasResidualLocalAuthority()
        // Persist explicit Off before the first await so process death cannot rearm capture.
        val fenceClosed = authorizationStore.disableAll()
        cancelActiveOperation()
        val workCancelled = scheduler.cancel()
        val suspended = operationMutex.withLock {
            processorMutex.withLock {
                val closure = suspendLocalAuthority()
                closure.copy(closed = closure.closed && stateStore.clear())
            }
        }
        val remoteRevoked = if (!hadLocalAuthority) {
            true
        } else {
            withContext(NonCancellable) {
                revokeRemoteAuthority(
                    memberKey,
                    suspended.uploadToken,
                    suspended.generationId,
                )
            }
        }
        val removed = if (suspended.closed && remoteRevoked) {
            operationMutex.withLock {
                processorMutex.withLock {
                    removeLocalAuthority(suspended.generationId)
                }
            }
        } else {
            false
        }
        fenceClosed && workCancelled && removed
    }

    override suspend fun suspendAtTrustBoundary(): Boolean = withContext(Dispatchers.IO) {
        val fenceClosed = authorizationStore.suspendAll()
        cancelActiveOperation()
        val workCancelled = scheduler.cancel()
        val drained = operationMutex.withLock {
            processorMutex.withLock { true }
        }
        fenceClosed && workCancelled && drained
    }

    override suspend fun reviewItems(): List<MealPhotoReviewItem> = runExclusive {
        processor.reviewItems()
    }

    override suspend fun approveReviewItem(captureId: String): MealPhotoActionResult =
        runExclusive { processor.approve(captureId) }

    override suspend fun dismissReviewItem(captureId: String): MealPhotoActionResult =
        runExclusive { processor.dismiss(captureId) }

    private suspend fun <T> runExclusive(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val job = currentCoroutineContext().job
                synchronized(activeOperationLock) { activeOperation = job }
                try {
                    processorMutex.withLock { block() }
                } finally {
                    synchronized(activeOperationLock) {
                        if (activeOperation === job) activeOperation = null
                    }
                }
            }
        }

    private fun cancelActiveOperation() {
        synchronized(activeOperationLock) { activeOperation?.cancel() }
    }

    private fun closeLocal(
        configuration: MealPhotoCaptureConfiguration,
        preserveState: Boolean,
    ): LocalClosure {
        val token = credentialStore.load(configuration.generationId)?.uploadToken
            ?: credentialStore.pendingRevocationToken(configuration.generationId)
        val stateClosed = preserveState || stateStore.clear()
        val credentialClosed = if (preserveState) {
            credentialStore.suspend(configuration.generationId)
        } else {
            credentialStore.clear(configuration.generationId, preserveGenerationKey = false)
        }
        val authorizationClosed = preserveState ||
            authorizationStore.clearGeneration(configuration.generationId)
        return LocalClosure(
            generationId = configuration.generationId,
            uploadToken = token,
            closed = stateClosed && credentialClosed && authorizationClosed,
        )
    }

    private fun suspendLocalAuthority(): LocalClosure {
        val generationId = stateStore.load()?.generationId
            ?: credentialStore.currentGenerationId()
            ?: authorizationStore.snapshot().generationId
            ?: return LocalClosure(null, null, stateStore.clear())
        val credentialStateExists = credentialStore.currentGenerationId() == generationId
        val suspended = !credentialStateExists || credentialStore.suspend(generationId)
        return LocalClosure(
            generationId = generationId,
            uploadToken = credentialStore.pendingRevocationToken(generationId),
            closed = suspended,
        )
    }

    private fun removeLocalAuthority(generationId: String?): Boolean {
        val stateCleared = stateStore.clear()
        if (generationId == null) return stateCleared
        val credentialCleared = credentialStore.clear(
            generationId,
            preserveGenerationKey = false,
        )
        val authorizationCleared = authorizationStore.clearGeneration(generationId)
        return stateCleared && credentialCleared && authorizationCleared
    }

    private suspend fun revokeRemoteAuthority(
        memberKey: String,
        uploadToken: String?,
        generationId: String?,
    ): Boolean {
        val scopedRevoked = uploadToken != null &&
            runCatching { uploader.revokeScoped(uploadToken) }
                .getOrDefault(MealPhotoRevocationDisposition.Retry) ==
            MealPhotoRevocationDisposition.Revoked
        val identityRevocation = revokeIdentity(memberKey)
        val identityRequired = generationId != null &&
            credentialStore.hasPendingEnrollment(generationId)
        val identityProvesCleanup = when (identityRevocation) {
            IdentityRevocationDisposition.Revoked -> true
            IdentityRevocationDisposition.AlreadyAbsent -> !identityRequired
            IdentityRevocationDisposition.Retry -> false
        }
        return identityProvesCleanup || (!identityRequired && scopedRevoked)
    }

    private suspend fun removeForeignLocalAuthority(): Boolean {
        val fenceClosed = authorizationStore.disableAll()
        cancelActiveOperation()
        val workCancelled = scheduler.cancel()
        val suspended = operationMutex.withLock {
            processorMutex.withLock {
                suspendLocalAuthority()
            }
        }
        val scopedRevocation = withContext(NonCancellable) {
            revokeScoped(suspended.uploadToken)
        }
        // The authenticated member is not the owner of this authority, so their identity
        // credential can never prove cleanup. Keep the foreign configuration and tombstone
        // until the exact scoped bearer receives a successful DELETE response. In particular,
        // 401/403 is not proof after an enrollment POST may have rotated to an unknown bearer.
        val removed = if (
            suspended.closed &&
            scopedRevocation == MealPhotoRevocationDisposition.Revoked
        ) {
            operationMutex.withLock {
                processorMutex.withLock {
                    removeLocalAuthority(suspended.generationId)
                }
            }
        } else {
            false
        }
        return fenceClosed && workCancelled && removed
    }

    private suspend fun revokeScoped(uploadToken: String?): MealPhotoRevocationDisposition {
        return if (uploadToken == null) {
            MealPhotoRevocationDisposition.Retry
        } else {
            runCatching { uploader.revokeScoped(uploadToken) }
                .getOrDefault(MealPhotoRevocationDisposition.Retry)
        }
    }

    /** A local generation salt stays stable; enrollment rotation only replaces upload authority. */
    private suspend fun rotateCredential(
        memberKey: String,
        configuration: MealPhotoCaptureConfiguration,
        previous: MealPhotoCredential?,
        mayAdoptServerSecret: Boolean,
    ): MealPhotoCredential? {
        val wasPendingEnrollment = credentialStore.hasPendingEnrollment(
            configuration.generationId,
        )
        val retainedIdempotencySecret = previous?.idempotencySecret
            ?: credentialStore.retainedIdempotencySecret(configuration.generationId)
        if (
            retainedIdempotencySecret == null &&
            !mayAdoptServerSecret &&
            !wasPendingEnrollment
        ) return null
        if (!credentialStore.markEnrollmentPending(configuration.generationId)) return null
        val authorityRevision = authorizationStore.allocateAuthorityRevision()
        if (authorityRevision == null) {
            closeAuthorizationForRejectedCredential()
            credentialStore.suspend(configuration.generationId)
            return null
        }
        val enrollment = try {
            api.createMealPhotoCaptureEnrollment(
                memberKey,
                MealPhotoCaptureEnrollmentRequest(
                    appInstallationId = installationId,
                    appVersion = appVersion,
                    authorityRevision = authorityRevision,
                ),
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                credentialStore.suspend(configuration.generationId)
                revokeIdentity(memberKey)
            }
            throw error
        } catch (error: CompanionApiException.ConsentRequired) {
            // The server rejected before issuance. Keep the baseline and pending marker so the
            // exact consent continuation can resume the same future-only generation.
            throw error
        } catch (error: Exception) {
            // A transport/response failure may occur after the server rotated the bearer. Strip
            // local upload authority but retain the generation tombstone until identity cleanup
            // succeeds or a later enrollment deterministically replaces the unknown token.
            val retainedPrevious = withContext(NonCancellable) {
                val identityRevocation = revokeIdentity(memberKey)
                val authorization = authorizationStore.snapshot()
                val stillValidPrevious = previous?.takeIf {
                    identityRevocation != IdentityRevocationDisposition.Revoked &&
                        authorization.disposition ==
                        MealPhotoAuthorizationDisposition.Authorized &&
                        authorization.generationId == configuration.generationId &&
                        it.expiresAtEpochMillis > now().toEpochMilli()
                }
                if (stillValidPrevious == null) {
                    credentialStore.suspend(configuration.generationId)
                }
                stillValidPrevious
            }
            // Renewal is best-effort while the previous bearer remains locally valid and the
            // identity cleanup request could not prove it was revoked. Preserve the On state;
            // an ambiguously rotated old bearer will be rejected by the upload endpoint and then
            // fenced by the normal credential-rejection path.
            if (retainedPrevious != null) return retainedPrevious
            throw error
        }
        val stableSecret = retainedIdempotencySecret ?: enrollment.idempotencySecret
        val issuedCredential = MealPhotoCredential(
            generationId = configuration.generationId,
            uploadToken = enrollment.uploadToken,
            idempotencySecret = stableSecret,
            expiresAtEpochMillis = enrollment.expiresAt.toEpochMilli(),
        )
        val refreshed = runCatching {
            credential(
                generationId = configuration.generationId,
                enrollment = enrollment,
                idempotencySecret = stableSecret,
            )
        }.getOrNull()
        if (refreshed == null) {
            closeAuthorizationForRejectedCredential()
            retainIssuedRevocationTombstone(issuedCredential)
            withContext(NonCancellable) {
                val revoked = revokeKnownIssuedAuthority(
                    memberKey,
                    issuedCredential.uploadToken,
                    hasAmbiguousEnrollment = wasPendingEnrollment,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return null
        }
        if (!runCatching { credentialStore.save(refreshed) }.getOrDefault(false)) {
            closeAuthorizationForRejectedCredential()
            retainIssuedRevocationTombstone(refreshed)
            withContext(NonCancellable) {
                val revoked = revokeKnownIssuedAuthority(
                    memberKey,
                    refreshed.uploadToken,
                    hasAmbiguousEnrollment = wasPendingEnrollment,
                )
                if (revoked) credentialStore.confirmRevoked(configuration.generationId)
            }
            return null
        }
        return refreshed
    }

    private fun retainIssuedRevocationTombstone(credential: MealPhotoCredential): Boolean =
        runCatching { credentialStore.save(credential) }.getOrDefault(false) &&
            credentialStore.suspend(credential.generationId)

    private suspend fun closeAuthorizationForRejectedCredential() {
        authorizationStore.suspendForCredentialRepair()
        withContext(NonCancellable) { scheduler.cancel() }
    }

    private suspend fun revokeKnownIssuedAuthority(
        memberKey: String,
        uploadToken: String,
        hasAmbiguousEnrollment: Boolean,
    ): Boolean {
        val scopedRevocation = runCatching { uploader.revokeScoped(uploadToken) }
            .getOrDefault(MealPhotoRevocationDisposition.Retry)
        if (scopedRevocation == MealPhotoRevocationDisposition.Revoked) return true
        return when (revokeIdentity(memberKey)) {
            IdentityRevocationDisposition.Revoked -> true
            IdentityRevocationDisposition.AlreadyAbsent -> !hasAmbiguousEnrollment
            IdentityRevocationDisposition.Retry -> false
        }
    }

    private suspend fun revokeIdentity(memberKey: String): IdentityRevocationDisposition {
        val authorityRevision = authorizationStore.allocateAuthorityRevision()
            ?: return IdentityRevocationDisposition.Retry
        return try {
            if (
                api.revokeMealPhotoCaptureEnrollment(
                    memberKey,
                    MealPhotoCaptureRevocationRequest(
                        appInstallationId = installationId,
                        authorityRevision = authorityRevision,
                    ),
                )
            ) {
                IdentityRevocationDisposition.Revoked
            } else {
                IdentityRevocationDisposition.AlreadyAbsent
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            IdentityRevocationDisposition.Retry
        }
    }

    private fun credential(
        generationId: String,
        enrollment: MealPhotoCaptureEnrollment,
        idempotencySecret: String,
    ): MealPhotoCredential {
        require(enrollment.expiresAt >= now().plus(MINIMUM_CREDENTIAL_LIFETIME))
        return MealPhotoCredential(
            generationId = generationId,
            uploadToken = enrollment.uploadToken,
            idempotencySecret = idempotencySecret,
            expiresAtEpochMillis = enrollment.expiresAt.toEpochMilli(),
        )
    }

    private fun ownerDigest(memberKey: String): String =
        MealPhotoCaptureConfiguration.ownerDigest(installationId, memberKey)

    private fun localAuthorityOwnedBy(memberKey: String): Boolean {
        val expectedOwner = ownerDigest(memberKey)
        val configuration = stateStore.load()
        if (configuration != null && configuration.ownerDigest != expectedOwner) return false
        val credentialGeneration = credentialStore.currentGenerationId()
        if (
            configuration != null &&
            credentialGeneration != null &&
            credentialGeneration != configuration.generationId
        ) return false
        val generationId = configuration?.generationId
            ?: credentialGeneration
            ?: authorizationStore.snapshot().generationId
            ?: return true
        val persistedOwner = credentialStore.ownerDigest(generationId)
        return when {
            persistedOwner != null -> persistedOwner == expectedOwner
            configuration != null -> configuration.ownerDigest == expectedOwner
            else -> false
        }
    }

    private fun hasResidualLocalAuthority(): Boolean =
        stateStore.load() != null ||
            credentialStore.currentGenerationId() != null ||
            authorizationStore.snapshot().generationId != null

    private data class LocalClosure(
        val generationId: String?,
        val uploadToken: String?,
        val closed: Boolean,
    )

    private enum class IdentityRevocationDisposition {
        Revoked,
        AlreadyAbsent,
        Retry,
    }

    private companion object {
        val CREDENTIAL_REFRESH_WINDOW: Duration = Duration.ofDays(7)
        val MINIMUM_CREDENTIAL_LIFETIME: Duration = Duration.ofHours(1)
    }
}
