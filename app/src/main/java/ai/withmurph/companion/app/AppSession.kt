package ai.withmurph.companion.app

import ai.withmurph.companion.contacts.AddressBookProjector
import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.core.AddressBookDeletionRequest
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.AddressBookReplacementRequest
import ai.withmurph.companion.core.AddressBookServerStatus
import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.AddressBookWriteCapability
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
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.MealPhotoActionResult
import ai.withmurph.companion.core.MealPhotoCaptureControlling
import ai.withmurph.companion.core.MealPhotoCaptureState
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.UnsupportedAddressBookContactSource
import ai.withmurph.companion.core.UnsupportedMealPhotoCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class AppSession(
    private val auth: AuthProvider,
    private val api: CompanionApi,
    private val health: HealthSyncing,
    private val contacts: AddressBookContactSource = UnsupportedAddressBookContactSource,
    private val meals: MealPhotoCaptureControlling = UnsupportedMealPhotoCapture,
    private val localState: LocalState,
    private val config: AppConfig,
    private val now: () -> Instant = Instant::now,
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val startMutex = Mutex()
    private val healthMutex = Mutex()
    private val addressBookMutex = Mutex()
    private val mealPhotoMutex = Mutex()
    private val launchConsentMutex = Mutex()
    private var hasCompletedStartup = false
    private var needsForegroundRefresh = false
    private var sessionEpoch = 0
    private var currentMemberKey: String? = null
    private var pendingHealthConnection: PendingHealthConnection? = null
    private var pendingAddressBookPermissionFlow: PendingAddressBookPermissionFlow? = null
    private var pendingMealPhotoPermissionFlow: PendingMealPhotoPermissionFlow? = null
    private val pendingAddressBookReconcileLock = Any()
    private var pendingAddressBookReconcile: PendingAddressBookReconcile? = null
    private var pendingLaunchConsentRecovery: PendingLaunchConsentRecovery? = null
    private var nextHealthPermissionRequestId = 1
    private var nextAddressBookPermissionRequestId = 1
    private var nextMealPhotoPermissionRequestId = 1

    private val _state = MutableStateFlow(
        AppUiState(totalResourceCount = health.totalResourceCount),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    suspend fun start() {
        reconcile(force = false)
    }

    suspend fun didLogin() {
        reconcile(force = true)
    }

    suspend fun retry() {
        reconcile(force = true)
    }

    suspend fun refreshAddressBookSharing() {
        if (hasActiveLaunchConsentRecovery()) return
        reconcileAddressBookForeground(showBusy = true)
    }

    fun showLaunchConsentRecovery() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!ownsLaunchConsentRecovery(pending)) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(showSheet = true),
            )
        }
    }

    fun dismissLaunchConsentRecovery() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!ownsLaunchConsentRecovery(pending)) return
        val recovery = _state.value.launchConsentRecovery ?: return
        if (!recovery.canDismiss) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(showSheet = false),
            )
        }
    }

    suspend fun retryLaunchConsentRecovery() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        launchConsentMutex.withLock {
            if (!ownsLaunchConsentRecovery(pending)) return@withLock
            loadLaunchConsentStatus(pending, healthLockHeld = false)
        }
    }

    suspend fun acceptLaunchConsent() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        val followUp = launchConsentMutex.withLock {
            if (!ownsLaunchConsentRecovery(pending)) return@withLock null
            val status = _state.value.launchConsentRecovery?.status
                ?: return@withLock null
            acceptLaunchConsentLocked(pending, status)
        } ?: return
        resumeLaunchConsentFollowUp(followUp, pending.resumeMealCapture)
    }

    fun consumeHealthPermissionLaunchRequest(requestId: Int): Boolean {
        while (true) {
            val current = _state.value
            if (current.pendingHealthPermissionRequestId != requestId) return false
            if (
                _state.compareAndSet(
                    current,
                    current.copy(pendingHealthPermissionRequestId = null),
                )
            ) {
                return true
            }
        }
    }

    fun consumeAddressBookPermissionLaunchRequest(requestId: Int): Boolean {
        while (true) {
            val current = _state.value
            if (current.pendingAddressBookPermissionRequestId != requestId) return false
            if (
                _state.compareAndSet(
                    current,
                    current.copy(pendingAddressBookPermissionRequestId = null),
                )
            ) {
                return true
            }
        }
    }

    fun consumeMealPhotoPermissionLaunchRequest(requestId: Int): Boolean {
        while (true) {
            val current = _state.value
            if (current.pendingMealPhotoPermissionRequestId != requestId) return false
            if (
                _state.compareAndSet(
                    current,
                    current.copy(pendingMealPhotoPermissionRequestId = null),
                )
            ) {
                return true
            }
        }
    }

    suspend fun prepareAutomaticMealPhotoCapture(): Boolean {
        if (hasActiveLaunchConsentRecovery()) {
            showLaunchConsentRecovery()
            return false
        }
        if (!meals.automaticCaptureSupported) return false
        return mealPhotoMutex.withLock {
            if (pendingMealPhotoPermissionFlow != null) return@withLock false
            val memberKey = currentMemberKey ?: return@withLock false
            val epoch = sessionEpoch
            if (!ownsMealPhotoWork(memberKey, epoch)) return@withLock false
            pendingMealPhotoPermissionFlow = PendingMealPhotoPermissionFlow(
                epoch = epoch,
                memberKey = memberKey,
                previousState = _state.value.mealPhotoCapture,
            )
            val requestId = nextMealPhotoPermissionRequestId++
            _state.update {
                it.copy(
                    mealPhotoCapture = MealPhotoCaptureState.Enabling,
                    isMealPhotoBusy = true,
                    mealPhotoMessage = null,
                    pendingMealPhotoPermissionRequestId = requestId,
                )
            }
            true
        }
    }

    suspend fun completeMealPhotoPermissionFlow(fullAccessGranted: Boolean): Boolean {
        val pending = pendingMealPhotoPermissionFlow ?: return false
        return mealPhotoMutex.withLock {
            if (pendingMealPhotoPermissionFlow !== pending) return@withLock false
            pendingMealPhotoPermissionFlow = null
            _state.update { it.copy(pendingMealPhotoPermissionRequestId = null) }
            try {
                if (!ownsMealPhotoWork(pending.memberKey, pending.epoch)) return@withLock false
                if (!fullAccessGranted) {
                    publishMealPhotoState(
                        pending.memberKey,
                        pending.epoch,
                        MealPhotoCaptureState.NeedsFullAccess,
                        "Meal photo suggestions need full Photos access. You can keep texting meal photos in your existing Murph conversation instead.",
                    )
                    return@withLock false
                }
                val captureState = meals.enable(pending.memberKey)
                if (!ownsMealPhotoWork(pending.memberKey, pending.epoch)) return@withLock false
                publishMealPhotoState(
                    pending.memberKey,
                    pending.epoch,
                    captureState,
                    mealPhotoMessage(captureState),
                )
                refreshMealPhotoReviews(pending.memberKey, pending.epoch)
                captureState == MealPhotoCaptureState.On
            } catch (_: CompanionApiException.ConsentRequired) {
                if (ownsMealPhotoWork(pending.memberKey, pending.epoch)) {
                    beginLaunchConsentRecovery(
                        expectedEpoch = pending.epoch,
                        memberKey = pending.memberKey,
                        followUp = LaunchConsentFollowUp.EnableMealPhotoCapture,
                        healthLockHeld = false,
                    )
                }
                false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publishMealPhotoState(
                    pending.memberKey,
                    pending.epoch,
                    MealPhotoCaptureState.NeedsAttention,
                    "Murph couldn't turn on meal photo suggestions. Try again.",
                )
                false
            } finally {
                if (ownsMealPhotoWork(pending.memberKey, pending.epoch)) {
                    _state.update { it.copy(isMealPhotoBusy = false) }
                }
            }
        }
    }

    fun cancelMealPhotoPermissionFlow(message: String? = null) {
        val pending = pendingMealPhotoPermissionFlow
        pendingMealPhotoPermissionFlow = null
        _state.update { current ->
            current.copy(
                mealPhotoCapture = pending?.previousState ?: current.mealPhotoCapture,
                isMealPhotoBusy = false,
                mealPhotoMessage = message ?: current.mealPhotoMessage,
                pendingMealPhotoPermissionRequestId = null,
            )
        }
    }

    suspend fun refreshMealPhotoCapture(showBusy: Boolean = true) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        if (!ownsMealPhotoWork(memberKey, epoch)) return
        val local = meals.currentState(memberKey)
        if (local == MealPhotoCaptureState.Off || local == MealPhotoCaptureState.Unavailable) {
            publishMealPhotoState(memberKey, epoch, local, null)
            return
        }
        mealPhotoMutex.withLock {
            if (!ownsMealPhotoWork(memberKey, epoch)) return@withLock
            if (showBusy) _state.update { it.copy(isMealPhotoBusy = true, mealPhotoMessage = null) }
            try {
                var captureState = meals.refresh(memberKey)
                if (captureState == MealPhotoCaptureState.NeedsAttention) {
                    val consent = api.fetchLaunchConsentStatus(memberKey)
                    if (!consent.launchGranted) {
                        beginLaunchConsentRecovery(
                            expectedEpoch = epoch,
                            memberKey = memberKey,
                            followUp = LaunchConsentFollowUp.RefreshMealPhotoCapture,
                            healthLockHeld = false,
                        )
                        return@withLock
                    }
                    captureState = meals.resumeAfterConsent(memberKey)
                }
                publishMealPhotoState(memberKey, epoch, captureState, mealPhotoMessage(captureState))
                refreshMealPhotoReviews(memberKey, epoch)
            } catch (_: CompanionApiException.ConsentRequired) {
                beginLaunchConsentRecovery(
                    expectedEpoch = epoch,
                    memberKey = memberKey,
                    followUp = LaunchConsentFollowUp.RefreshMealPhotoCapture,
                    healthLockHeld = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publishMealPhotoState(
                    memberKey,
                    epoch,
                    MealPhotoCaptureState.NeedsAttention,
                    "Meal photo suggestions need attention. Try again when you're online.",
                )
            } finally {
                if (ownsMealPhotoWork(memberKey, epoch)) {
                    _state.update { it.copy(isMealPhotoBusy = false) }
                }
            }
        }
    }

    suspend fun turnOffMealPhotoCapture(): Boolean {
        val memberKey = currentMemberKey ?: return false
        val epoch = sessionEpoch
        _state.update { it.copy(isMealPhotoBusy = true, mealPhotoMessage = null) }
        // Reach the service's durable fence immediately; an upload/refresh may currently own the
        // UI mutex, and explicit Off must cancel it rather than wait behind it.
        val disabled = try {
            meals.disable(memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        mealPhotoMutex.withLock {
            if (ownsMealPhotoWork(memberKey, epoch)) {
                _state.update {
                    it.copy(
                        mealPhotoCapture = if (disabled) {
                            MealPhotoCaptureState.Off
                        } else {
                            MealPhotoCaptureState.NeedsAttention
                        },
                        isMealPhotoBusy = false,
                        mealPhotoMessage = if (disabled) {
                            "Meal photo suggestions are off."
                        } else {
                            "Meal photo suggestions are off on this phone, but Murph still needs to finish removing remote access. Try again when you're online."
                        },
                        mealPhotoReviewItems = emptyList(),
                    )
                }
            }
        }
        return disabled
    }

    suspend fun approveMealPhoto(captureId: String) {
        updateMealPhotoReview(captureId, approve = true)
    }

    suspend fun dismissMealPhoto(captureId: String) {
        updateMealPhotoReview(captureId, approve = false)
    }

    suspend fun prepareAddressBookSharing(): Boolean {
        if (hasActiveLaunchConsentRecovery()) return false
        if (!contacts.isSupported) return false
        addressBookMutex.lock()
        var prepared = false
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        try {
            if (pendingAddressBookPermissionFlow != null) return false
            val memberKey = currentMemberKey ?: return false
            val epoch = sessionEpoch
            ownerMemberKey = memberKey
            ownerEpoch = epoch
            if (!ownsAddressBookWork(memberKey, epoch)) return false
            _state.update {
                it.copy(
                    isAddressBookBusy = true,
                    contactsPermissionDenied =
                        it.contactsPermissionDenied && !contacts.hasPermission(),
                    addressBookMessage = null,
                )
            }

            val status = fetchAddressBookStatusLocked(
                memberKey = memberKey,
                epoch = epoch,
                consentFollowUp = LaunchConsentFollowUp.PrepareAddressBookPermission,
            ) ?: return false
            if (!ownsAddressBookWork(memberKey, epoch)) return false
            if (status.writeCapability != AddressBookWriteCapability.Enabled) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Address-book sharing isn't available for this account right now.",
                )
                return false
            }

            val interrupted = localState.pendingAddressBookReplacement
            if (
                interrupted == null &&
                status.enabled &&
                localState.addressBookRevision != status.revision
            ) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "This address-book projection was changed by another installation. Stop and delete it before sharing a new list.",
                )
                return false
            }
            if (
                interrupted == null &&
                !status.enabled &&
                localState.addressBookRevision != status.revision
            ) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Murph couldn't safely remember the current sharing revision. Try again.",
                )
                return false
            }

            val mutation = interrupted ?: createAddressBookMutation(status.revision)
            pendingAddressBookPermissionFlow = PendingAddressBookPermissionFlow(
                epoch = epoch,
                memberKey = memberKey,
                mutation = mutation,
                preflightStatus = status,
                ownedRevisionForPermissionLoss = status.revision.takeIf {
                    status.enabled && localState.addressBookRevision == status.revision
                },
            )
            requestAddressBookPermissionLaunch()
            prepared = true
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val memberKey = ownerMemberKey
            val epoch = ownerEpoch
            if (memberKey != null && epoch != null) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Murph couldn't prepare address-book sharing. Try again.",
                )
            }
            return false
        } finally {
            addressBookMutex.unlock()
            if (
                !prepared &&
                ownerEpoch == sessionEpoch &&
                ownerMemberKey == currentMemberKey
            ) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            drainAddressBookReconcile(ownerMemberKey, ownerEpoch)
        }
    }

    suspend fun completeAddressBookPermissionFlow(permissionGranted: Boolean): Boolean {
        val pending = pendingAddressBookPermissionFlow ?: return false
        _state.update { it.copy(pendingAddressBookPermissionRequestId = null) }
        var authStateToReconcile: AuthSessionState? = null
        val completed = try {
            addressBookMutex.withLock {
                try {
                    if (pendingAddressBookPermissionFlow != pending) {
                        return@withLock false
                    }
                    if (!ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                        return@withLock false
                    }
                    if (!permissionGranted || !contacts.hasPermission()) {
                        publishAddressBookPermissionDenied(pending.memberKey, pending.epoch)
                        deleteOwnedAddressBookAfterPermissionLossLocked(pending)
                        return@withLock false
                    }
                    currentAuthOwnershipLoss(pending.memberKey)?.let { authState ->
                        authStateToReconcile = authState
                        return@withLock false
                    }

                    val existingMutation = localState.pendingAddressBookReplacement
                    val mutation = when {
                        existingMutation == null -> {
                            if (!localState.beginAddressBookReplacement(pending.mutation)) {
                                publishAddressBookMessage(
                                    pending.memberKey,
                                    pending.epoch,
                                    "Murph couldn't safely save the retry marker. Try again.",
                                )
                                return@withLock false
                            }
                            pending.mutation
                        }
                        existingMutation == pending.mutation -> existingMutation
                        else -> {
                            publishAddressBookMessage(
                                pending.memberKey,
                                pending.epoch,
                                "Another address-book change is already pending. Try again.",
                            )
                            return@withLock false
                        }
                    }

                    val contactRows = try {
                        contacts.readPersonContacts()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (!contacts.hasPermission()) {
                            publishAddressBookPermissionDenied(pending.memberKey, pending.epoch)
                            deleteOwnedAddressBookAfterPermissionLossLocked(pending)
                        } else {
                            publishAddressBookMessage(
                                pending.memberKey,
                                pending.epoch,
                                "Murph couldn't read contacts for this update. Try again.",
                            )
                        }
                        return@withLock false
                    }
                    if (
                        !ownsAddressBookWork(pending.memberKey, pending.epoch) ||
                        !contacts.hasPermission()
                    ) {
                        if (ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                            publishAddressBookPermissionDenied(pending.memberKey, pending.epoch)
                            deleteOwnedAddressBookAfterPermissionLossLocked(pending)
                        }
                        return@withLock false
                    }

                    val projections = withContext(Dispatchers.Default) {
                        AddressBookProjector.project(contactRows)
                    }
                    if (
                        !ownsAddressBookWork(pending.memberKey, pending.epoch) ||
                        !contacts.hasPermission()
                    ) {
                        if (ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                            publishAddressBookPermissionDenied(pending.memberKey, pending.epoch)
                            deleteOwnedAddressBookAfterPermissionLossLocked(pending)
                        }
                        return@withLock false
                    }
                    val status = try {
                        api.replaceAddressBook(
                            memberKey = pending.memberKey,
                            request = AddressBookReplacementRequest(mutation, projections),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: CompanionApiException.Conflict) {
                        if (ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                            localState.abandonAddressBookReplacement(mutation.mutationId)
                            fetchAddressBookStatusLocked(
                                memberKey = pending.memberKey,
                                epoch = pending.epoch,
                                consentFollowUp = LaunchConsentFollowUp.ReconcileAddressBook,
                            )
                            publishAddressBookMessage(
                                pending.memberKey,
                                pending.epoch,
                                "Sharing changed elsewhere, so Murph didn't overwrite it. Review the current status and try again.",
                            )
                        }
                        return@withLock false
                    } catch (_: CompanionApiException.ConsentRequired) {
                        if (ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                            beginLaunchConsentRecovery(
                                expectedEpoch = pending.epoch,
                                memberKey = pending.memberKey,
                                followUp = LaunchConsentFollowUp.AddressBookReplacement(pending),
                                healthLockHeld = false,
                            )
                        }
                        return@withLock false
                    } catch (_: CompanionApiException.Unauthorized) {
                        publishAddressBookMessage(
                            pending.memberKey,
                            pending.epoch,
                            "Your session needs a refresh before sharing contacts. Try again.",
                        )
                        return@withLock false
                    } catch (_: Exception) {
                        publishAddressBookMessage(
                            pending.memberKey,
                            pending.epoch,
                            "Murph couldn't finish the address-book update. Tap Retry to use the saved mutation safely.",
                        )
                        return@withLock false
                    }

                    if (!ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                        return@withLock false
                    }
                    if (replacementResultPredatesPreflight(pending.preflightStatus, status)) {
                        val cleared = localState.abandonAddressBookReplacement(
                            mutation.mutationId,
                        )
                        fetchAddressBookStatusLocked(
                            memberKey = pending.memberKey,
                            epoch = pending.epoch,
                            consentFollowUp = LaunchConsentFollowUp.ReconcileAddressBook,
                        )
                        publishAddressBookMessage(
                            pending.memberKey,
                            pending.epoch,
                            if (cleared) {
                                "The saved mutation replayed an older server revision, so Murph kept the newer server state."
                            } else {
                                "The saved mutation replayed an older server revision. Murph kept the newer server state but couldn't clear the retry marker."
                            },
                        )
                        return@withLock false
                    }
                    if (!contacts.hasPermission()) {
                        publishAddressBookPermissionDenied(pending.memberKey, pending.epoch)
                        deleteOwnedAddressBookAfterPermissionLossLocked(
                            pending = pending,
                            exactRevision = status.revision,
                        )
                        return@withLock false
                    }
                    if (!localState.completeAddressBookReplacement(mutation.mutationId, status.revision)) {
                        publishAddressBookStatus(
                            pending.memberKey,
                            pending.epoch,
                            status,
                            "The server saved this update, but Murph couldn't confirm it locally. Tap Retry.",
                        )
                        return@withLock false
                    }
                    publishAddressBookStatus(pending.memberKey, pending.epoch, status, null)
                    true
                } finally {
                    if (pendingAddressBookPermissionFlow == pending) {
                        pendingAddressBookPermissionFlow = null
                    }
                    if (
                        pending.epoch == sessionEpoch &&
                        pending.memberKey == currentMemberKey
                    ) {
                        _state.update { it.copy(isAddressBookBusy = false) }
                    }
                }
            }
        } finally {
            drainAddressBookReconcile(pending.memberKey, pending.epoch)
        }
        authStateToReconcile?.let { reconcileAfterMemberScopedWorkAuthLoss(it) }
        return completed
    }

    fun cancelAddressBookPermissionFlow() {
        val pending = pendingAddressBookPermissionFlow
        if (pending == null) {
            _state.update { it.copy(pendingAddressBookPermissionRequestId = null) }
            return
        }
        pendingAddressBookPermissionFlow = null
        if (
            pending.epoch == sessionEpoch &&
            pending.memberKey == currentMemberKey
        ) {
            _state.update {
                it.copy(
                    isAddressBookBusy = false,
                    pendingAddressBookPermissionRequestId = null,
                )
            }
        }
    }

    suspend fun stopAddressBookSharing() {
        if (!contacts.isSupported) return
        if (
            prioritizeActiveLaunchConsentFollowUp(
                LaunchConsentFollowUp.StopAddressBookSharing,
            )
        ) {
            showLaunchConsentRecovery()
            return
        }
        addressBookMutex.lock()
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        var markedBusy = false
        try {
            if (pendingAddressBookPermissionFlow != null) return
            val memberKey = currentMemberKey ?: return
            val epoch = sessionEpoch
            ownerMemberKey = memberKey
            ownerEpoch = epoch
            if (!ownsAddressBookWork(memberKey, epoch)) return
            markedBusy = true
            _state.update {
                it.copy(
                    isAddressBookBusy = true,
                    contactsPermissionDenied =
                        it.contactsPermissionDenied && !contacts.hasPermission(),
                    addressBookMessage = null,
                )
            }

            var status = fetchAddressBookStatusLocked(
                memberKey = memberKey,
                epoch = epoch,
                consentFollowUp = LaunchConsentFollowUp.StopAddressBookSharing,
            ) ?: return
            if (!ownsAddressBookWork(memberKey, epoch)) return
            if (!status.enabled) {
                if (localState.pendingAddressBookReplacement != null) {
                    val cleared = localState.recordDisabledAddressBookRevision(status.revision)
                    publishAddressBookStatus(
                        memberKey,
                        epoch,
                        status,
                        if (cleared) {
                            null
                        } else {
                            "Sharing is already stopped, but Murph couldn't clear the saved retry marker. Try again."
                        },
                    )
                }
                return
            }

            val matchingPending = localState.pendingAddressBookDeletion
                ?.takeIf { it.baseRevision == status.revision }
            var mutation = if (matchingPending != null) {
                matchingPending
            } else {
                localState.pendingAddressBookDeletion?.let { stale ->
                    if (!localState.abandonAddressBookDeletion(stale.mutationId)) {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Murph couldn't safely refresh the pending deletion. Try again.",
                        )
                        return
                    }
                }
                createAddressBookMutation(status.revision).also { created ->
                    if (!localState.beginAddressBookDeletion(created)) {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Murph couldn't safely save the deletion retry marker. Try again.",
                        )
                        return
                    }
                }
            }

            for (attempt in 0..1) {
                try {
                    val deleted = api.deleteAddressBook(
                        memberKey = memberKey,
                        request = AddressBookDeletionRequest(mutation),
                    )
                    if (!ownsAddressBookWork(memberKey, epoch)) return
                    if (!localState.completeAddressBookDeletion(mutation.mutationId, deleted.revision)) {
                        publishAddressBookStatus(
                            memberKey,
                            epoch,
                            deleted,
                            "The server deleted the projection, but Murph couldn't confirm it locally. Try again.",
                        )
                        return
                    }
                    publishAddressBookStatus(memberKey, epoch, deleted, null)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (_: CompanionApiException.Conflict) {
                    if (!ownsAddressBookWork(memberKey, epoch)) return
                    localState.abandonAddressBookDeletion(mutation.mutationId)
                    status = fetchAddressBookStatusLocked(
                        memberKey = memberKey,
                        epoch = epoch,
                        consentFollowUp = LaunchConsentFollowUp.StopAddressBookSharing,
                    ) ?: return
                    if (!status.enabled) return
                    if (attempt == 1) {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Sharing changed again before deletion. Review the status and tap Stop again.",
                        )
                        return
                    }
                    mutation = createAddressBookMutation(status.revision)
                    if (!localState.beginAddressBookDeletion(mutation)) {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Murph couldn't safely save the deletion retry marker. Try again.",
                        )
                        return
                    }
                } catch (_: CompanionApiException.ConsentRequired) {
                    beginLaunchConsentRecovery(
                        expectedEpoch = epoch,
                        memberKey = memberKey,
                        followUp = LaunchConsentFollowUp.StopAddressBookSharing,
                        healthLockHeld = false,
                    )
                    return
                } catch (_: CompanionApiException.Unauthorized) {
                    publishAddressBookMessage(
                        memberKey,
                        epoch,
                        "Your session needs a refresh before deleting shared names. Try again.",
                    )
                    return
                } catch (_: Exception) {
                    publishAddressBookMessage(
                        memberKey,
                        epoch,
                        "Murph couldn't delete the shared names yet. It will keep the exact deletion retry marker.",
                    )
                    return
                }
            }
        } finally {
            addressBookMutex.unlock()
            if (
                markedBusy &&
                ownerEpoch == sessionEpoch &&
                ownerMemberKey == currentMemberKey
            ) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            drainAddressBookReconcile(ownerMemberKey, ownerEpoch)
        }
    }

    private suspend fun reconcile(force: Boolean) = startMutex.withLock {
        if (hasActiveLaunchConsentRecovery()) return@withLock
        if (hasCompletedStartup && !force) return@withLock
        try {
            _state.update { current ->
                current.copy(
                    phase = AppPhase.Launching,
                    healthAvailability = health.availability(),
                    healthMessage = null,
                )
            }
            if (localState.signOutPending) {
                finishPendingSignOut()
                hasCompletedStartup = _state.value.phase != AppPhase.Launching
                return@withLock
            }
            if (!enforceHealthSetupAuthorization()) {
                hasCompletedStartup = true
                return@withLock
            }
            when (val authState = auth.currentState()) {
                AuthSessionState.SignedOut -> enterSignedOut()
                AuthSessionState.TemporarilyUnavailable -> restoreOfflineIfPossible()
                is AuthSessionState.SignedIn -> reconcileSignedIn(authState)
            }
            hasCompletedStartup = _state.value.phase != AppPhase.Launching
        } catch (error: CancellationException) {
            hasCompletedStartup = false
            throw error
        }
    }

    suspend fun prepareHealthConnection(): Boolean {
        if (hasActiveLaunchConsentRecovery()) return false
        if (
            _state.value.phase == AppPhase.Ready &&
            !_state.value.authVerifiedOnline
        ) {
            reconcile(force = true)
        }
        var authStateToReconcile: AuthSessionState? = null
        val prepared = healthMutex.withLock {
            if (
                _state.value.phase != AppPhase.Ready ||
                _state.value.isConnectingHealth ||
                !_state.value.authVerifiedOnline
            ) {
                return false
            }
            val memberKey = currentMemberKey ?: return false
            when (health.availability()) {
                HealthConnectAvailability.Available -> {
                    val validatedEpoch = sessionEpoch
                    val receiptBeforePreflight = localState.lastKnownDataReceivedAt
                    if (
                        fetchValidatedHealthStatus(
                            validatedEpoch,
                            LaunchConsentFollowUp.PrepareHealthPermission,
                        ) == null
                    ) {
                        return false
                    }
                    if (validatedEpoch != sessionEpoch) return false
                    currentAuthOwnershipLoss(memberKey)?.let { authState ->
                        localState.lastKnownDataReceivedAt = receiptBeforePreflight
                        publishPermissionAwareHealthState(
                            status = cachedHealthStatus(),
                            message = _state.value.healthMessage,
                        )
                        if (validatedEpoch == sessionEpoch) {
                            authStateToReconcile = authState
                        }
                        return@withLock false
                    }
                    if (!ownsHealthConnectionPreparation(memberKey, validatedEpoch)) {
                        return false
                    }
                    val hadCompletedSetup = healthWasRequested()
                    if (hadCompletedSetup) {
                        if (!localState.revokeHealthSetupAuthorization()) {
                            _state.update {
                                it.copy(
                                    healthMessage =
                                        "Murph couldn't safely start reconnecting Health Connect. Try again.",
                                )
                            }
                            return false
                        }
                    }
                    var preparationEpoch = validatedEpoch
                    if (hadCompletedSetup || health.isSignedIn()) {
                        invalidateSessionEpoch()
                        preparationEpoch = sessionEpoch
                        _state.update {
                            it.copy(
                                healthSync = HealthSyncState.NotConnected,
                                healthMessage = null,
                            )
                        }
                        try {
                            health.signOutSdk()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            _state.update {
                                it.copy(
                                    healthMessage =
                                        "Murph couldn't safely reset health sync. Keep the app open and sign out.",
                                )
                            }
                            return false
                        }
                        if (preparationEpoch != sessionEpoch) return false
                        currentAuthOwnershipLoss(memberKey)?.let { authState ->
                            if (preparationEpoch == sessionEpoch) {
                                authStateToReconcile = authState
                            }
                            return@withLock false
                        }
                        if (!ownsHealthConnectionPreparation(memberKey, preparationEpoch)) {
                            return false
                        }
                    }
                    pendingHealthConnection = PendingHealthConnection(
                        epoch = preparationEpoch,
                        memberKey = memberKey,
                        requestedAt = now(),
                    )
                    _state.update { it.copy(isConnectingHealth = true, healthMessage = null) }
                    true
                }
                HealthConnectAvailability.InstallOrUpdateRequired -> {
                    _state.update {
                        it.copy(
                            healthMessage = "Install or update Health Connect, then try again.",
                        )
                    }
                    false
                }
                HealthConnectAvailability.OnboardingRequired -> {
                    _state.update {
                        it.copy(
                            healthMessage = "Finish setting up Health Connect, then try again.",
                        )
                    }
                    false
                }
                HealthConnectAvailability.AppNotAllowed -> {
                    _state.update {
                        it.copy(
                            healthMessage = "This build isn't authorized for Health Connect. Contact Murph support.",
                        )
                    }
                    false
                }
                HealthConnectAvailability.Unsupported -> {
                    _state.update {
                        it.copy(
                            healthMessage = "Health Connect isn't supported on this device.",
                        )
                    }
                    false
                }
                HealthConnectAvailability.TemporarilyUnavailable -> {
                    _state.update {
                        it.copy(
                            healthMessage = "Health Connect isn't ready yet. Try again in a moment.",
                        )
                    }
                    false
                }
            }
        }
        authStateToReconcile?.let { reconcileAfterMemberScopedWorkAuthLoss(it) }
        if (prepared) requestHealthPermissionLaunch()
        return prepared
    }

    suspend fun completeHealthPermissionFlow(permissionRequestCompleted: Boolean): Boolean {
        _state.update { it.copy(pendingHealthPermissionRequestId = null) }
        var authStateToReconcile: AuthSessionState? = null
        val completed = healthMutex.withLock {
            val pending = pendingHealthConnection
            if (
                pending == null ||
                !ownsPendingHealthConnection(pending.memberKey)
            ) {
                pendingHealthConnection = null
                _state.update { it.copy(isConnectingHealth = false) }
                return false
            }
            val epoch = pending.epoch
            try {
                if (!permissionRequestCompleted) {
                    pendingHealthConnection = null
                    _state.update { current ->
                        current.copy(
                            isConnectingHealth = false,
                            grantedResourceCount = health.grantedResourceCount(),
                            healthMessage = "Choose at least one Health Connect category to connect Murph.",
                        )
                    }
                    return false
                }
                if (
                    fetchValidatedHealthStatus(
                        epoch,
                        LaunchConsentFollowUp.CompleteHealthPermission(pending.requestedAt),
                    ) == null
                ) {
                    pendingHealthConnection = null
                    _state.update { it.copy(isConnectingHealth = false) }
                    return false
                }
                if (!ownsPendingHealthConnection(pending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(pending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                try {
                    identifyJunction(
                        memberKey = pending.memberKey,
                        intent = ConnectionIntent.Connect,
                        epoch = epoch,
                    )?.let { authState ->
                        authStateToReconcile = authState
                        return@withLock abortPendingHealthConnection(epoch)
                    }
                } catch (error: CompanionApiException.ConsentRequired) {
                    if (epoch == sessionEpoch) {
                        beginLaunchConsentRecovery(
                            expectedEpoch = epoch,
                            memberKey = pending.memberKey,
                            followUp = LaunchConsentFollowUp.CompleteHealthPermission(
                                pending.requestedAt,
                            ),
                            healthLockHeld = true,
                        )
                    }
                    pendingHealthConnection = null
                    return@withLock false
                }
                if (!ownsPendingHealthConnection(pending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(pending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                health.configure()
                health.connectAfterPermissionRequest()
                if (!ownsPendingHealthConnection(pending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(pending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                localState.healthAccessRequestedAt =
                    InstantValue(pending.requestedAt.toEpochMilli())
                pendingHealthConnection = null
                _state.update { current ->
                    current.copy(
                        isConnectingHealth = false,
                        healthSync = HealthSyncState.AwaitingFirstData,
                        grantedResourceCount = health.grantedResourceCount(),
                        healthMessage = null,
                    )
                }
                true
            } catch (error: CancellationException) {
                pendingHealthConnection = null
                withContext(NonCancellable) {
                    rollbackIncompleteHealthSetup(epoch)
                }
                if (epoch == sessionEpoch) {
                    _state.update { it.copy(isConnectingHealth = false) }
                }
                throw error
            } catch (error: Exception) {
                pendingHealthConnection = null
                val rollbackSucceeded = rollbackIncompleteHealthSetup(epoch)
                if (epoch == sessionEpoch) {
                    _state.update { current ->
                        current.copy(
                            isConnectingHealth = false,
                            healthMessage = if (rollbackSucceeded) {
                                connectionErrorMessage(error)
                            } else {
                                "Murph couldn't safely reset health sync. Keep the app open and sign out."
                            },
                        )
                    }
                }
                false
            }
        }
        authStateToReconcile?.let { reconcileAfterMemberScopedWorkAuthLoss(it) }
        return completed
    }

    fun cancelHealthPermissionFlow() {
        val pending = pendingHealthConnection
        if (pending == null) {
            _state.update { it.copy(pendingHealthPermissionRequestId = null) }
            return
        }
        if (pending.epoch != sessionEpoch) return
        pendingHealthConnection = null
        _state.update {
            it.copy(
                isConnectingHealth = false,
                pendingHealthPermissionRequestId = null,
            )
        }
    }

    suspend fun syncNow() {
        if (hasActiveLaunchConsentRecovery()) return
        if (
            _state.value.phase != AppPhase.Ready ||
            !healthWasRequested()
        ) return
        if (!_state.value.authVerifiedOnline) {
            reconcile(force = true)
            return
        }
        if (!health.isSignedIn()) {
            reconcile(force = true)
            return
        }
        if (health.grantedResourceCount() == 0) {
            publishPermissionAwareHealthState(
                status = cachedHealthStatus(),
                message = _state.value.healthMessage,
            )
            return
        }
        val needsHealthReconciliation = healthMutex.withLock {
            val epoch = sessionEpoch
            syncAndRefresh(epoch)
        }
        if (needsHealthReconciliation) reconcile(force = true)
    }

    suspend fun didBecomeActive() {
        if (hasActiveLaunchConsentRecovery()) {
            if (!needsForegroundRefresh) return
            needsForegroundRefresh = false
            refreshActiveLaunchConsentAfterForeground()
            return
        }
        if (!needsForegroundRefresh) {
            if (
                _state.value.phase == AppPhase.Ready &&
                !_state.value.authVerifiedOnline
            ) {
                reconcile(force = true)
            } else if (
                _state.value.phase == AppPhase.Ready &&
                _state.value.authVerifiedOnline
            ) {
                refreshMealPhotoCapture(showBusy = false)
            }
            return
        }
        needsForegroundRefresh = false
        val authAllowsSync = reconcileForegroundAuth()
        if (
            (authAllowsSync || ownsPendingHealthConnection()) &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline
        ) {
            reconcileAddressBookForeground(showBusy = false)
            refreshMealPhotoCapture(showBusy = false)
            if (hasActiveLaunchConsentRecovery()) return
        }
        if (ownsPendingHealthConnection()) {
            return
        }
        if (_state.value.phase != AppPhase.Ready) {
            return
        }
        try {
            health.refreshPermissionState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Availability and backend receipt status remain independently useful.
        }
        val availability = health.availability()
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery = healthWasRequested() && grantedResourceCount == 0
        _state.update { current ->
            current.copy(
                healthAvailability = availability,
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    current.healthSync
                },
                grantedResourceCount = grantedResourceCount,
                healthMessage = if (
                    needsPermissionRecovery &&
                    availability == HealthConnectAvailability.Available
                ) {
                    HEALTH_PERMISSION_RECOVERY_MESSAGE
                } else {
                    current.healthMessage
                },
            )
        }
        if (
            authAllowsSync &&
            _state.value.phase == AppPhase.Ready &&
            healthWasRequested() &&
            grantedResourceCount > 0
        ) {
            syncNow()
        }
    }

    private suspend fun refreshActiveLaunchConsentAfterForeground() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        launchConsentMutex.withLock {
            if (ownsLaunchConsentRecovery(pending)) {
                loadLaunchConsentStatus(pending, healthLockHeld = false)
            }
        }
    }

    private suspend fun revalidateLaunchConsentMember(
        pending: PendingLaunchConsentRecovery,
    ): Boolean {
        if (!ownsLaunchConsentRecovery(pending)) return false
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        if (!ownsLaunchConsentRecovery(pending)) return false
        return when (authState) {
            AuthSessionState.SignedOut -> {
                invalidateSessionEpoch()
                _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
                startMutex.withLock { enterSignedOut() }
                false
            }
            AuthSessionState.TemporarilyUnavailable -> {
                _state.update { it.copy(authVerifiedOnline = false) }
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't verify your session. Check your connection and try again.",
                )
                false
            }
            is AuthSessionState.SignedIn -> {
                if (
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey
                ) {
                    invalidateSessionEpoch()
                    _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
                    reconcile(force = true)
                    false
                } else if (!authState.verifiedOnline) {
                    _state.update { it.copy(authVerifiedOnline = false) }
                    publishLaunchConsentLoadFailure(
                        pending,
                        "Murph couldn't verify your session. Check your connection and try again.",
                    )
                    false
                } else {
                    _state.update { it.copy(authVerifiedOnline = true) }
                    true
                }
            }
        }
    }

    fun didEnterBackground() {
        needsForegroundRefresh = true
    }

    suspend fun signOut() = withContext(NonCancellable) {
        // Close upload authority before the durable sign-out tombstone can survive process death.
        if (!meals.suspendAtTrustBoundary()) {
            publishPendingSignOutFailure(
                "We couldn't safely pause meal photo suggestions. Keep Murph open and try again.",
            )
            return@withContext
        }
        if (!localState.beginSignOut()) {
            _state.update {
                it.copy(
                    phase = AppPhase.Failed(
                        message = "We couldn't safely start signing out. Keep Murph open and try again.",
                        canRetry = false,
                        canSignOut = true,
                    ),
                )
            }
            return@withContext
        }
        invalidateSessionEpoch()
        _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
        startMutex.withLock {
            if (localState.signOutPending) finishPendingSignOut()
        }
    }

    fun reportHealthConnectLaunchFailure(message: String) {
        _state.update { it.copy(healthMessage = message) }
    }

    fun reportAddressBookPermissionLaunchFailure(message: String) {
        _state.update { it.copy(addressBookMessage = message) }
    }

    private suspend fun enforceHealthSetupAuthorization(): Boolean {
        if (!health.isSignedIn() || healthWasRequested()) return true
        if (!resetMemberScopedServicesAtTrustBoundary()) return false
        currentMemberKey = null
        localState.clearMemberScopedState()
        return true
    }

    private suspend fun reconcileSignedIn(
        authState: AuthSessionState.SignedIn,
        canRetryLostHealthSession: Boolean = true,
    ) {
        val previousMemberKey = localState.memberKey
        val mustDistrustPersistedHealthSession =
            (previousMemberKey == null && health.isSignedIn()) ||
                (previousMemberKey != null && previousMemberKey != authState.memberKey)
        if (mustDistrustPersistedHealthSession) {
            if (!resetMemberScopedServicesAtTrustBoundary()) return
            localState.clearMemberScopedState()
        }
        localState.memberKey = authState.memberKey
        currentMemberKey = authState.memberKey
        val epoch = sessionEpoch
        val requested = healthWasRequested()
        if (authState.verifiedOnline) {
            currentAuthOwnershipLoss(authState.memberKey)?.let { observed ->
                reconcileObservedAuthState(authState.memberKey, observed)
                return
            }
        }
        if (!requested && authState.verifiedOnline && !verifyBackendMember(epoch)) {
            return
        }
        if (requested && authState.verifiedOnline) {
            try {
                identifyJunction(
                    memberKey = authState.memberKey,
                    intent = ConnectionIntent.Resume,
                    epoch = epoch,
                )?.let { observed ->
                    reconcileObservedAuthState(authState.memberKey, observed)
                    return
                }
                if (epoch != sessionEpoch) return
                currentAuthOwnershipLoss(authState.memberKey)?.let { observed ->
                    reconcileObservedAuthState(authState.memberKey, observed)
                    return
                }
                health.configure()
            } catch (error: CompanionApiException.ReconnectRequired) {
                if (epoch != sessionEpoch) return
                if (!localState.revokeHealthSetupAuthorization()) {
                    _state.update { current ->
                        current.copy(
                            phase = AppPhase.Failed(
                                message = "Murph couldn't safely prepare Health Connect to reconnect. Try again.",
                                canRetry = true,
                                canSignOut = true,
                            ),
                        )
                    }
                    return
                }
                if (!resetHealthSdkAtTrustBoundary()) return
            } catch (error: CompanionApiException.Unauthorized) {
                if (epoch != sessionEpoch) return
                publishAuthoritativeResumeFailure(
                    message = connectionErrorMessage(error),
                    canRetry = false,
                )
                return
            } catch (error: CompanionApiException.NoAccount) {
                if (epoch != sessionEpoch) return
                publishAuthoritativeResumeFailure(
                    message = connectionErrorMessage(error),
                    canRetry = false,
                )
                return
            } catch (error: CompanionApiException.ConsentRequired) {
                if (epoch != sessionEpoch) return
                beginLaunchConsentRecovery(
                    expectedEpoch = epoch,
                    memberKey = authState.memberKey,
                    followUp = LaunchConsentFollowUp.Reconcile,
                    healthLockHeld = false,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch != sessionEpoch) return
                _state.update {
                    it.copy(
                        phase = AppPhase.Failed(
                            message = connectionErrorMessage(error),
                            canRetry = true,
                            canSignOut = true,
                        ),
                    )
                }
                return
            }
        }

        if (authState.verifiedOnline) {
            currentAuthOwnershipLoss(authState.memberKey)?.let { observed ->
                reconcileObservedAuthState(authState.memberKey, observed)
                return
            }
        }
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery = healthWasRequested() && grantedResourceCount == 0
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                authVerifiedOnline = authState.verifiedOnline,
                healthAvailability = health.availability(),
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    deriveCachedHealthState()
                },
                grantedResourceCount = grantedResourceCount,
                healthMessage = when {
                    needsPermissionRecovery -> HEALTH_PERMISSION_RECOVERY_MESSAGE
                    authState.verifiedOnline -> null
                    else -> "You're offline. Murph will verify the session and resume sync when the connection returns."
                },
                addressBookSharing = if (contacts.isSupported && authState.verifiedOnline) {
                    AddressBookSharingState.Loading
                } else {
                    AddressBookSharingState.Unavailable
                },
                isAddressBookBusy = false,
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                contactsPermissionDenied = false,
                addressBookMessage = if (contacts.isSupported && !authState.verifiedOnline) {
                    "You're offline. Murph can't verify address-book sharing right now."
                } else {
                    null
                },
                mealPhotoCapture = meals.currentState(authState.memberKey),
                isMealPhotoBusy = false,
                mealPhotoMessage = if (authState.verifiedOnline) {
                    null
                } else {
                    "You're offline. Meal photo suggestions will resume after Murph verifies your session."
                },
            )
        }
        if (
            authState.verifiedOnline &&
            ownsAddressBookWork(authState.memberKey, epoch)
        ) {
            reconcileAddressBookForeground(showBusy = false)
        }
        if (authState.verifiedOnline && ownsMealPhotoWork(authState.memberKey, epoch)) {
            refreshMealPhotoCapture(showBusy = false)
            if (hasActiveLaunchConsentRecovery()) return
        }
        if (
            healthWasRequested() &&
            authState.verifiedOnline &&
            grantedResourceCount > 0
        ) {
            val needsHealthReconciliation =
                healthMutex.withLock { syncAndRefresh(epoch) }
            if (needsHealthReconciliation) {
                if (
                    !ownsVerifiedHealthWork(epoch) ||
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey
                ) {
                    return
                }
                if (canRetryLostHealthSession) {
                    _state.update { current ->
                        current.copy(phase = AppPhase.Launching, healthMessage = null)
                    }
                    reconcileSignedIn(authState, canRetryLostHealthSession = false)
                } else {
                    _state.update { current ->
                        current.copy(
                            phase = AppPhase.Failed(
                                message = "Murph couldn't restore Health Connect. Try again.",
                                canRetry = true,
                                canSignOut = true,
                            ),
                        )
                    }
                }
                return
            }
        }
    }

    private suspend fun enterSignedOut() {
        val mealState = meals.currentState(localState.memberKey)
        if (
            health.isSignedIn() ||
            localState.memberKey != null ||
            mealState != MealPhotoCaptureState.Off &&
            mealState != MealPhotoCaptureState.Unavailable
        ) {
            if (!resetMemberScopedServicesAtTrustBoundary()) return
        } else {
            invalidateSessionEpoch()
        }
        currentMemberKey = null
        localState.clearMemberScopedState()
        _state.value = AppUiState(
            phase = AppPhase.NeedsLogin,
            healthAvailability = health.availability(),
            totalResourceCount = health.totalResourceCount,
        )
    }

    private suspend fun restoreOfflineIfPossible() {
        val memberKey = localState.memberKey
        if (memberKey == null) {
            _state.update {
                it.copy(
                    phase = AppPhase.Failed(
                        message = "Murph couldn't check your saved sign-in. Check your connection and try again.",
                    ),
                )
            }
            return
        }
        currentMemberKey = memberKey
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery = healthWasRequested() && grantedResourceCount == 0
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                authVerifiedOnline = false,
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    deriveCachedHealthState()
                },
                grantedResourceCount = grantedResourceCount,
                healthMessage = if (needsPermissionRecovery) {
                    HEALTH_PERMISSION_RECOVERY_MESSAGE
                } else {
                    "You're offline. Saved sync status is shown until Murph reconnects."
                },
                addressBookSharing = AddressBookSharingState.Unavailable,
                isAddressBookBusy = false,
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                contactsPermissionDenied = false,
                addressBookMessage = if (contacts.isSupported) {
                    "You're offline. Murph can't verify address-book sharing right now."
                } else {
                    null
                },
                mealPhotoCapture = meals.currentState(memberKey),
                mealPhotoMessage = "You're offline. Meal photo suggestions will resume after Murph verifies your session.",
            )
        }
    }

    private suspend fun reconcileForegroundAuth(): Boolean {
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        return when (authState) {
            AuthSessionState.SignedOut -> {
                invalidateSessionEpoch()
                _state.update {
                    it.copy(phase = AppPhase.Launching, healthMessage = null)
                }
                startMutex.withLock { enterSignedOut() }
                false
            }
            AuthSessionState.TemporarilyUnavailable -> {
                _state.update { current ->
                    current.copy(
                        authVerifiedOnline = false,
                        healthMessage = "You're offline. Saved sync status is shown until Murph reconnects.",
                        addressBookSharing = AddressBookSharingState.Unavailable,
                        isAddressBookBusy = false,
                        addressBookMessage = if (contacts.isSupported) {
                            "You're offline. Murph can't verify address-book sharing right now."
                        } else {
                            null
                        },
                    )
                }
                false
            }
            is AuthSessionState.SignedIn -> {
                val pendingOwnsHealthIdentity =
                    ownsPendingHealthConnection(authState.memberKey)
                if (
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey
                ) {
                    invalidateSessionEpoch()
                    _state.update {
                        it.copy(phase = AppPhase.Launching, healthMessage = null)
                    }
                    reconcile(force = true)
                    false
                } else if (!authState.verifiedOnline) {
                    _state.update { current ->
                        current.copy(
                            authVerifiedOnline = false,
                            healthMessage = "You're offline. Saved sync status is shown until Murph reconnects.",
                            addressBookSharing = AddressBookSharingState.Unavailable,
                            isAddressBookBusy = false,
                            addressBookMessage = if (contacts.isSupported) {
                                "You're offline. Murph can't verify address-book sharing right now."
                            } else {
                                null
                            },
                        )
                    }
                    false
                } else if (
                    health.isSignedIn() &&
                    !healthWasRequested() &&
                    !pendingOwnsHealthIdentity
                ) {
                    val stillOrphaned = healthMutex.withLock {
                        health.isSignedIn() &&
                            !healthWasRequested() &&
                            !ownsPendingHealthConnection(authState.memberKey) &&
                            authState.memberKey == currentMemberKey &&
                            authState.memberKey == localState.memberKey &&
                            _state.value.phase == AppPhase.Ready &&
                            !localState.signOutPending
                    }
                    if (stillOrphaned) reconcile(force = true)
                    false
                } else if (pendingOwnsHealthIdentity) {
                    false
                } else if (
                    !_state.value.authVerifiedOnline
                ) {
                    if (_state.value.phase == AppPhase.Launching) {
                        startMutex.withLock {
                            // Wait for the existing reconciliation owner without
                            // scheduling another backend bootstrap.
                        }
                    } else {
                        reconcile(force = true)
                    }
                    false
                } else {
                    _state.update { it.copy(authVerifiedOnline = true) }
                    true
                }
            }
        }
    }

    private suspend fun identifyJunction(
        memberKey: String,
        intent: ConnectionIntent,
        epoch: Int,
    ): AuthSessionState? {
        if (epoch != sessionEpoch) throw CancellationException()
        val response = api.createJunctionSignInToken(
            SignInTokenRequest(
                appInstallationId = localState.installationId,
                appVersion = config.appVersion,
                connectionIntent = intent,
                sdkVersions = mapOf(
                    "vital" to config.junctionSdkVersion,
                    "privy" to config.privySdkVersion,
                ),
            ),
        )
        if (epoch != sessionEpoch) throw CancellationException()
        if (response.environment != config.environment.wireValue) {
            throw CompanionApiException.InvalidResponse
        }
        _state.update { current ->
            current.copy(backendEnvironment = response.environment)
        }
        currentAuthOwnershipLoss(memberKey)?.let { return it }
        health.identify(memberKey = memberKey) {
            response.signInToken
        }
        return null
    }

    private suspend fun syncAndRefresh(epoch: Int): Boolean {
        if (!ownsVerifiedHealthWork(epoch)) return false
        _state.update { it.copy(isSyncingHealth = true, healthMessage = null) }
        try {
            if (
                fetchValidatedHealthStatus(
                    epoch,
                    LaunchConsentFollowUp.SyncHealth,
                ) == null
            ) return false
            if (!ownsVerifiedHealthWork(epoch)) return false
            if (health.grantedResourceCount() == 0) {
                publishPermissionAwareHealthState(
                    status = cachedHealthStatus(),
                    message = _state.value.healthMessage,
                )
                return false
            }
            if (!health.isSignedIn()) return true
            try {
                health.syncAllGrantedResources()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!ownsVerifiedHealthWork(epoch)) return false
                if (!health.isSignedIn()) return true
                // Status refresh below still reports the last backend-confirmed receipt.
            }
            if (!ownsVerifiedHealthWork(epoch)) return false
            if (!health.isSignedIn()) return true
            fetchValidatedHealthStatus(epoch, LaunchConsentFollowUp.SyncHealth)
            return false
        } finally {
            _state.update { it.copy(isSyncingHealth = false) }
        }
    }

    private fun ownsVerifiedHealthWork(epoch: Int): Boolean =
        epoch == sessionEpoch &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline

    /** Called only while [startMutex] is held. */
    private suspend fun finishPendingSignOut() {
        if (!localState.signOutPending) return
        invalidateSessionEpoch()
        _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
        if (!meals.suspendAtTrustBoundary()) {
            publishPendingSignOutFailure(
                "We couldn't safely pause meal photo suggestions. Keep Murph open and try again.",
            )
            return
        }
        try {
            healthMutex.withLock { health.signOutSdk() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPendingSignOutFailure(
                "We couldn't safely reset health sync. Keep Murph open and try again.",
            )
            return
        }
        val mealsDisabled = try {
            meals.disable(localState.memberKey ?: currentMemberKey ?: return)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!mealsDisabled) {
            publishPendingSignOutFailure(
                "We couldn't safely remove meal-photo suggestion access. Keep Murph open and try again.",
            )
            return
        }
        try {
            auth.signOut()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPendingSignOutFailure("We couldn't finish signing out. Try once more.")
            return
        }
        if (!localState.completeSignOut()) {
            publishPendingSignOutFailure(
                "We couldn't safely finish signing out. Keep Murph open and try again.",
            )
            return
        }
        currentMemberKey = null
        _state.value = AppUiState(
            phase = AppPhase.NeedsLogin,
            healthAvailability = health.availability(),
            totalResourceCount = health.totalResourceCount,
        )
    }

    private fun publishPendingSignOutFailure(message: String) {
        _state.update {
            it.copy(
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = false,
                    canSignOut = true,
                ),
            )
        }
    }

    /** Called only while [healthMutex] is held. */
    private suspend fun fetchValidatedHealthStatus(
        epoch: Int,
        consentFollowUp: LaunchConsentFollowUp,
    ): CompanionSyncStatus? {
        if (epoch != sessionEpoch || _state.value.phase != AppPhase.Ready) return null
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        when (authState) {
            AuthSessionState.SignedOut -> {
                failCurrentSessionWhileHealthLocked(
                    message = "Your session needs a refresh. Sign in again.",
                    canRetry = false,
                )
                return null
            }
            AuthSessionState.TemporarilyUnavailable -> {
                publishReadOnlyHealthState(
                    message = "You're offline. Saved sync status is shown until Murph reconnects.",
                )
                return null
            }
            is AuthSessionState.SignedIn -> {
                if (
                    !authState.verifiedOnline ||
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey
                ) {
                    if (
                        authState.memberKey != currentMemberKey ||
                        authState.memberKey != localState.memberKey
                    ) {
                        failCurrentSessionWhileHealthLocked(
                            message = "Your signed-in account changed. Try again to continue.",
                            canRetry = true,
                        )
                        currentMemberKey = null
                        localState.clearMemberScopedState()
                    } else {
                        publishReadOnlyHealthState(
                            message = "You're offline. Saved sync status is shown until Murph reconnects.",
                        )
                    }
                    return null
                }
            }
        }

        if (epoch != sessionEpoch || _state.value.phase != AppPhase.Ready) return null
        val status = try {
            api.fetchSyncStatus(HEALTH_CONNECT_SOURCE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.Unauthorized) {
            failCurrentSessionWhileHealthLocked(
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
            return null
        } catch (error: CompanionApiException.NoAccount) {
            failCurrentSessionWhileHealthLocked(
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
            return null
        } catch (error: CompanionApiException.ConsentRequired) {
            val memberKey = currentMemberKey ?: return null
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = consentFollowUp,
                healthLockHeld = true,
            )
            return null
        } catch (_: Exception) {
            publishPermissionAwareHealthState(
                status = cachedHealthStatus(),
                message = "Murph couldn't verify your account. Saved status is still shown.",
                clearSyncing = true,
            )
            return null
        }
        if (epoch != sessionEpoch) return null
        val requestedAt = healthRequestedAt()
        val qualifyingReceipt = status.lastDataReceivedAt?.takeIf { receivedAt ->
            requestedAt != null && !receivedAt.isBefore(requestedAt)
        }
        localState.lastKnownDataReceivedAt = qualifyingReceipt?.let {
            InstantValue(it.toEpochMilli())
        }
        publishPermissionAwareHealthState(
            status = status.copy(lastDataReceivedAt = qualifyingReceipt),
            message = null,
        )
        return status
    }

    private fun publishReadOnlyHealthState(message: String) {
        publishPermissionAwareHealthState(
            status = cachedHealthStatus(),
            message = message,
            authVerifiedOnline = false,
            clearSyncing = true,
        )
    }

    private fun publishPermissionAwareHealthState(
        status: CompanionSyncStatus?,
        message: String?,
        authVerifiedOnline: Boolean? = null,
        clearSyncing: Boolean = false,
    ) {
        val requestedAt = healthRequestedAt()
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery = requestedAt != null && grantedResourceCount == 0
        _state.update { current ->
            current.copy(
                authVerifiedOnline = authVerifiedOnline ?: current.authVerifiedOnline,
                isSyncingHealth = if (clearSyncing) false else current.isSyncingHealth,
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    HealthSyncState.derive(
                        requestedAt = requestedAt,
                        status = status,
                        now = now(),
                    )
                },
                grantedResourceCount = grantedResourceCount,
                healthMessage = if (needsPermissionRecovery) {
                    HEALTH_PERMISSION_RECOVERY_MESSAGE
                } else {
                    message
                },
            )
        }
    }

    /** Called only while [healthMutex] is held. */
    private suspend fun failCurrentSessionWhileHealthLocked(
        message: String,
        canRetry: Boolean,
    ) {
        invalidateSessionEpoch()
        val resetSucceeded = try {
            health.signOutSdk()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        _state.update { current ->
            current.copy(
                isConnectingHealth = false,
                isSyncingHealth = false,
                phase = AppPhase.Failed(
                    message = if (resetSucceeded) {
                        message
                    } else {
                        "Murph couldn't safely reset health sync. Keep the app open and try signing out again."
                    },
                    canRetry = canRetry && resetSucceeded,
                    canSignOut = true,
                ),
            )
        }
    }

    private suspend fun verifyBackendMember(epoch: Int): Boolean {
        return try {
            api.fetchSyncStatus(HEALTH_CONNECT_SOURCE)
            epoch == sessionEpoch
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.NoAccount) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
            false
        } catch (error: CompanionApiException.ConsentRequired) {
            if (epoch != sessionEpoch) return false
            val memberKey = currentMemberKey ?: return false
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = LaunchConsentFollowUp.Reconcile,
                healthLockHeld = false,
            )
            false
        } catch (error: CompanionApiException.Unauthorized) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
            false
        } catch (_: Exception) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "Murph couldn't verify your account. Check your connection and try again.",
                canRetry = true,
            )
            false
        }
    }

    private fun publishBackendBootstrapFailure(message: String, canRetry: Boolean) {
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                ),
            )
        }
    }

    private suspend fun publishAuthoritativeResumeFailure(
        message: String,
        canRetry: Boolean,
    ) {
        if (!resetMemberScopedServicesAtTrustBoundary()) return
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                ),
            )
        }
    }

    private suspend fun currentAuthOwnershipLoss(
        memberKey: String,
    ): AuthSessionState? {
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        val ownsMember =
            authState is AuthSessionState.SignedIn &&
                authState.verifiedOnline &&
                authState.memberKey == memberKey &&
                memberKey == currentMemberKey &&
                memberKey == localState.memberKey &&
                !localState.signOutPending
        if (ownsMember) return null
        _state.update { current -> current.copy(authVerifiedOnline = false) }
        return authState
    }

    /** Called only while [startMutex] is held. */
    private suspend fun reconcileObservedAuthState(
        expectedMemberKey: String,
        observed: AuthSessionState,
    ) {
        when (observed) {
            AuthSessionState.SignedOut -> enterSignedOut()
            AuthSessionState.TemporarilyUnavailable -> restoreOfflineIfPossible()
            is AuthSessionState.SignedIn -> {
                if (observed.memberKey == expectedMemberKey) {
                    restoreOfflineIfPossible()
                } else {
                    reconcileSignedIn(observed)
                }
            }
        }
    }

    private suspend fun reconcileAfterMemberScopedWorkAuthLoss(observed: AuthSessionState) {
        val authoritativeChange =
            observed == AuthSessionState.SignedOut ||
                (
                    observed is AuthSessionState.SignedIn &&
                        (
                            observed.memberKey != currentMemberKey ||
                                observed.memberKey != localState.memberKey
                        )
                )
        if (!authoritativeChange) return
        invalidateSessionEpoch()
        _state.update {
            it.copy(phase = AppPhase.Launching, healthMessage = null)
        }
        reconcile(force = true)
    }

    private suspend fun abortPendingHealthConnection(epoch: Int): Boolean {
        pendingHealthConnection = null
        val rollbackSucceeded = rollbackIncompleteHealthSetup(epoch)
        if (epoch == sessionEpoch) {
            _state.update { current ->
                current.copy(
                    isConnectingHealth = false,
                    healthMessage = if (rollbackSucceeded) {
                        current.healthMessage
                    } else {
                        "Murph couldn't safely reset health sync. Keep the app open and sign out."
                    },
                )
            }
        }
        return false
    }

    private suspend fun rollbackIncompleteHealthSetup(epoch: Int): Boolean {
        if (
            epoch != sessionEpoch ||
            healthWasRequested() ||
            !health.isSignedIn()
        ) {
            return true
        }
        return try {
            health.signOutSdk()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resetHealthSdkAtTrustBoundary(): Boolean {
        invalidateSessionEpoch()
        return try {
            healthMutex.withLock { health.signOutSdk() }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { current ->
                current.copy(
                    phase = AppPhase.Failed(
                        message = "Murph couldn't safely reset health sync. Keep the app open and try again.",
                        canRetry = true,
                        canSignOut = true,
                    ),
                )
            }
            false
        }
    }

    /** The fence blocks new meal work; Junction remains the first service actually torn down. */
    private suspend fun resetMemberScopedServicesAtTrustBoundary(): Boolean {
        if (!meals.suspendAtTrustBoundary()) {
            _state.update { current ->
                current.copy(
                    phase = AppPhase.Failed(
                        message = "Murph couldn't safely pause meal photo suggestions. Keep the app open and try again.",
                        canRetry = true,
                        canSignOut = true,
                    ),
                )
            }
            return false
        }
        if (!resetHealthSdkAtTrustBoundary()) return false
        val disabled = try {
            meals.disable(localState.memberKey ?: currentMemberKey ?: return false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (disabled) return true
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = "Murph couldn't safely remove meal-photo suggestion access. Keep the app open and try again.",
                    canRetry = true,
                    canSignOut = true,
                ),
            )
        }
        return false
    }

    private suspend fun beginLaunchConsentRecovery(
        expectedEpoch: Int,
        memberKey: String,
        followUp: LaunchConsentFollowUp,
        healthLockHeld: Boolean,
    ) {
        if (
            expectedEpoch != sessionEpoch ||
            memberKey != currentMemberKey ||
            memberKey != localState.memberKey ||
            localState.signOutPending
        ) {
            return
        }
        val existing = pendingLaunchConsentRecovery
        if (existing != null && ownsLaunchConsentRecovery(existing)) {
            prioritizeActiveLaunchConsentFollowUp(followUp)
            return
        }
        val resumeMealCapture = followUp == LaunchConsentFollowUp.EnableMealPhotoCapture ||
            followUp == LaunchConsentFollowUp.RefreshMealPhotoCapture ||
            meals.currentState(memberKey) !in setOf(
                MealPhotoCaptureState.Off,
                MealPhotoCaptureState.Unavailable,
            )
        invalidateSessionEpoch()
        currentMemberKey = memberKey
        localState.memberKey = memberKey
        val pending = PendingLaunchConsentRecovery(
            epoch = sessionEpoch,
            memberKey = memberKey,
            followUp = followUp,
            resumeMealCapture = resumeMealCapture,
        )
        pendingLaunchConsentRecovery = pending
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                authVerifiedOnline = true,
                healthAvailability = health.availability(),
                healthSync = deriveCachedHealthState(),
                isConnectingHealth = false,
                isSyncingHealth = false,
                healthMessage = "Murph paused health sync while you review the latest launch consent.",
                grantedResourceCount = health.grantedResourceCount(),
                addressBookSharing = if (contacts.isSupported) {
                    current.addressBookSharing
                } else {
                    AddressBookSharingState.Unavailable
                },
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                isAddressBookBusy = false,
                launchConsentRecovery = LaunchConsentRecoveryUiState(
                    phase = LaunchConsentRecoveryPhase.Pausing,
                    message = "Pausing health sync before loading consent.",
                    showSheet = true,
                ),
            )
        }
        launchConsentMutex.withLock {
            if (!ownsLaunchConsentRecovery(pending)) return@withLock
            loadLaunchConsentStatus(pending, healthLockHeld)
        }
    }

    private fun prioritizeActiveLaunchConsentFollowUp(
        requested: LaunchConsentFollowUp,
    ): Boolean {
        val existing = pendingLaunchConsentRecovery ?: return false
        val changed = synchronized(existing) {
            if (!ownsLaunchConsentRecovery(existing)) return false
            val selected = when {
                requested is LaunchConsentFollowUp.StopAddressBookSharing -> requested
                existing.followUp is LaunchConsentFollowUp.StopAddressBookSharing ->
                    existing.followUp
                requested is LaunchConsentFollowUp.AutomaticAddressBookDeletion -> requested
                existing.followUp is LaunchConsentFollowUp.AutomaticAddressBookDeletion ->
                    existing.followUp
                else -> existing.followUp
            }
            if (selected == existing.followUp) {
                false
            } else {
                existing.followUp = selected
                true
            }
        }
        if (changed) {
            _state.update { current ->
                current.copy(
                    launchConsentRecovery = current.launchConsentRecovery?.copy(
                        message = "Murph will finish stopping address-book sharing after consent is current.",
                    ),
                )
            }
        }
        return true
    }

    private suspend fun loadLaunchConsentStatus(
        pending: PendingLaunchConsentRecovery,
        healthLockHeld: Boolean,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Pausing,
                    message = "Pausing health sync and meal photo suggestions before loading consent.",
                    canDismiss = false,
                    canAccept = false,
                ) ?: LaunchConsentRecoveryUiState(
                    phase = LaunchConsentRecoveryPhase.Pausing,
                    message = "Pausing health sync and meal photo suggestions before loading consent.",
                    showSheet = true,
                ),
            )
        }
        val mealTeardownSucceeded = try {
            meals.pauseForConsentRecovery(pending.memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!mealTeardownSucceeded) {
            publishLaunchConsentLoadFailure(
                pending,
                "Murph couldn't safely pause meal photo suggestions. Try again, or sign out.",
            )
            return
        }
        val teardownSucceeded = try {
            if (healthLockHeld) {
                health.signOutSdk()
            } else {
                healthMutex.withLock { health.signOutSdk() }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!ownsLaunchConsentRecovery(pending)) return
        if (!teardownSucceeded) {
            publishLaunchConsentLoadFailure(
                pending,
                "Murph couldn't safely pause health sync. Try again, or sign out.",
            )
            return
        }
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Loading,
                    message = "Loading the latest consent documents.",
                    canDismiss = false,
                    canAccept = false,
                ),
            )
        }
        val status = try {
            api.fetchLaunchConsentStatus(pending.memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionApiException.Unauthorized) {
            publishLaunchConsentMemberBoundaryFailure(
                pending = pending,
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
            return
        } catch (_: CompanionApiException.NoAccount) {
            publishLaunchConsentMemberBoundaryFailure(
                pending = pending,
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
            return
        } catch (_: Exception) {
            publishLaunchConsentLoadFailure(
                pending,
                "Murph couldn't load the latest consent documents. Check your connection and try again.",
            )
            return
        }
        if (!ownsLaunchConsentRecovery(pending)) return
        publishLaunchConsentRequired(
            pending = pending,
            status = status,
            message = if (status.launchGranted) {
                "Consent is already complete. Continue to resume Murph."
            } else {
                null
            },
        )
    }

    private suspend fun acceptLaunchConsentLocked(
        pending: PendingLaunchConsentRecovery,
        initialStatus: LaunchConsentStatus,
    ): LaunchConsentFollowUp? {
        var latest = initialStatus
        val attemptedScopes = mutableSetOf<LaunchConsentScope>()
        if (!ownsLaunchConsentRecovery(pending)) return null
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Saving,
                    status = latest,
                    message = "Saving consent.",
                    showSheet = true,
                    canDismiss = false,
                    canAccept = false,
                ),
            )
        }
        while (!latest.launchGranted) {
            val nextScope = latest.missingLaunchScopes.firstOrNull()
                ?: break
            if (!attemptedScopes.add(nextScope.scope)) {
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't confirm consent progress. Reload the latest documents and try again.",
                )
                return null
            }
            val missingScopesBefore = latest.missingLaunchScopes.map { it.scope }.toSet()
            val request = LaunchConsentAcceptanceRequest(
                scope = nextScope.scope,
                acceptedDocumentVersions = nextScope.missingDocuments.associate {
                    it.id to it.version
                },
            )
            val updated = try {
                api.acceptLaunchConsent(pending.memberKey, request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: CompanionApiException.StaleConsentDocuments) {
                reloadLaunchConsentAfterStaleDocuments(pending, latest)
                return null
            } catch (_: CompanionApiException.Unauthorized) {
                publishLaunchConsentMemberBoundaryFailure(
                    pending = pending,
                    message = "Your session needs a refresh. Sign in again.",
                    canRetry = false,
                )
                return null
            } catch (_: CompanionApiException.NoAccount) {
                publishLaunchConsentMemberBoundaryFailure(
                    pending = pending,
                    message = "This sign-in isn't linked to an active Murph account.",
                    canRetry = false,
                )
                return null
            } catch (_: Exception) {
                publishLaunchConsentRequired(
                    pending = pending,
                    status = latest,
                    message = "Murph couldn't save consent. Check your connection and try again.",
                )
                return null
            }
            if (!ownsLaunchConsentRecovery(pending)) return null
            val missingScopesAfter = updated.missingLaunchScopes.map { it.scope }.toSet()
            if (
                nextScope.scope in missingScopesAfter ||
                !missingScopesBefore.containsAll(missingScopesAfter) ||
                missingScopesAfter.size >= missingScopesBefore.size
            ) {
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't confirm consent progress. Reload the latest documents and try again.",
                )
                return null
            }
            latest = updated
            _state.update { current ->
                current.copy(
                    launchConsentRecovery = current.launchConsentRecovery?.copy(
                        phase = LaunchConsentRecoveryPhase.Saving,
                        status = latest,
                        message = "Saving consent.",
                        showSheet = true,
                        canDismiss = false,
                        canAccept = false,
                    ),
                )
            }
        }
        if (!latest.launchGranted) {
            publishLaunchConsentRequired(
                pending = pending,
                status = latest,
                message = "Review the remaining consent documents before continuing.",
            )
            return null
        }
        val followUp = synchronized(pending) {
            if (!ownsLaunchConsentRecovery(pending)) return null
            pendingLaunchConsentRecovery = null
            pending.followUp
        }
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Finishing,
                    status = latest,
                    message = "Finishing setup.",
                    showSheet = true,
                    canDismiss = false,
                    canAccept = false,
                ),
            )
        }
        return followUp
    }

    private suspend fun reloadLaunchConsentAfterStaleDocuments(
        pending: PendingLaunchConsentRecovery,
        retainedStatus: LaunchConsentStatus,
    ) {
        val reloaded = try {
            api.fetchLaunchConsentStatus(pending.memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionApiException.Unauthorized) {
            publishLaunchConsentMemberBoundaryFailure(
                pending = pending,
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
            return
        } catch (_: CompanionApiException.NoAccount) {
            publishLaunchConsentMemberBoundaryFailure(
                pending = pending,
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
            return
        } catch (_: Exception) {
            publishLaunchConsentRequired(
                pending = pending,
                status = retainedStatus,
                message = "Consent documents changed, but Murph couldn't reload them. Try again.",
            )
            return
        }
        publishLaunchConsentRequired(
            pending = pending,
            status = reloaded,
            message = "Consent documents changed. Review the latest versions before continuing.",
        )
    }

    private fun publishLaunchConsentRequired(
        pending: PendingLaunchConsentRecovery,
        status: LaunchConsentStatus,
        message: String?,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = LaunchConsentRecoveryUiState(
                    phase = LaunchConsentRecoveryPhase.Required,
                    status = status,
                    message = message,
                    showSheet = current.launchConsentRecovery?.showSheet ?: true,
                ),
            )
        }
    }

    private fun publishLaunchConsentLoadFailure(
        pending: PendingLaunchConsentRecovery,
        message: String,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = LaunchConsentRecoveryUiState(
                    phase = LaunchConsentRecoveryPhase.LoadFailed,
                    status = current.launchConsentRecovery?.status,
                    message = message,
                    showSheet = current.launchConsentRecovery?.showSheet ?: true,
                ),
            )
        }
    }

    private fun publishLaunchConsentMemberBoundaryFailure(
        pending: PendingLaunchConsentRecovery,
        message: String,
        canRetry: Boolean,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        invalidateSessionEpoch()
        _state.update { current ->
            current.copy(
                launchConsentRecovery = null,
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                ),
            )
        }
    }

    private suspend fun resumeLaunchConsentFollowUp(
        followUp: LaunchConsentFollowUp,
        resumeMealCapture: Boolean,
    ) {
        try {
            when (followUp) {
                LaunchConsentFollowUp.Reconcile -> reconcile(force = true)
                LaunchConsentFollowUp.SyncHealth -> syncNow()
                LaunchConsentFollowUp.PrepareHealthPermission -> prepareHealthConnection()
                LaunchConsentFollowUp.PrepareAddressBookPermission ->
                    prepareAddressBookSharing()
                LaunchConsentFollowUp.ReconcileAddressBook ->
                    reconcileAddressBookForeground(showBusy = false)
                LaunchConsentFollowUp.StopAddressBookSharing -> stopAddressBookSharing()
                LaunchConsentFollowUp.EnableMealPhotoCapture,
                LaunchConsentFollowUp.RefreshMealPhotoCapture,
                -> Unit
                is LaunchConsentFollowUp.AutomaticAddressBookDeletion ->
                    resumeAutomaticAddressBookDeletion(followUp.mutation)
                is LaunchConsentFollowUp.CompleteHealthPermission ->
                    resumeHealthPermissionAfterConsent(followUp.requestedAt)
                is LaunchConsentFollowUp.AddressBookReplacement -> {
                    val memberKey = currentMemberKey ?: return
                    val restored = followUp.pending.copy(
                        epoch = sessionEpoch,
                        memberKey = memberKey,
                    )
                    pendingAddressBookPermissionFlow = restored
                    _state.update {
                        it.copy(isAddressBookBusy = true, addressBookMessage = null)
                    }
                    completeAddressBookPermissionFlow(permissionGranted = true)
                }
            }
            if (resumeMealCapture && _state.value.phase == AppPhase.Ready) {
                resumeMealPhotoCaptureAfterConsent()
            }
        } finally {
            if (!hasActiveLaunchConsentRecovery()) {
                _state.update { current ->
                    current.copy(launchConsentRecovery = null)
                }
            }
        }
    }

    private suspend fun resumeMealPhotoCaptureAfterConsent() {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        if (!ownsMealPhotoWork(memberKey, epoch)) return
        val localState = meals.currentState(memberKey)
        if (localState == MealPhotoCaptureState.Off || localState == MealPhotoCaptureState.Unavailable) {
            publishMealPhotoState(memberKey, epoch, localState, null)
            return
        }
        if (localState == MealPhotoCaptureState.On) {
            publishMealPhotoState(memberKey, epoch, localState, null)
            refreshMealPhotoReviews(memberKey, epoch)
            return
        }
        mealPhotoMutex.withLock {
            if (!ownsMealPhotoWork(memberKey, epoch)) return@withLock
            _state.update { it.copy(isMealPhotoBusy = true, mealPhotoMessage = null) }
            val captureState = try {
                meals.resumeAfterConsent(memberKey)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MealPhotoCaptureState.NeedsAttention
            }
            publishMealPhotoState(
                memberKey,
                epoch,
                captureState,
                mealPhotoMessage(captureState),
            )
            refreshMealPhotoReviews(memberKey, epoch)
            if (ownsMealPhotoWork(memberKey, epoch)) {
                _state.update { it.copy(isMealPhotoBusy = false) }
            }
        }
    }

    private suspend fun resumeHealthPermissionAfterConsent(requestedAt: Instant) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        pendingHealthConnection = PendingHealthConnection(
            epoch = epoch,
            memberKey = memberKey,
            requestedAt = requestedAt,
        )
        _state.update {
            it.copy(
                phase = AppPhase.Ready,
                isConnectingHealth = true,
                healthMessage = null,
            )
        }
        val permissionStillGranted = healthMutex.withLock {
            if (!ownsPendingHealthConnection(memberKey)) return@withLock false
            try {
                health.refreshPermissionState()
            } catch (error: CancellationException) {
                pendingHealthConnection = null
                if (epoch == sessionEpoch) {
                    _state.update { it.copy(isConnectingHealth = false) }
                }
                throw error
            } catch (_: Exception) {
                pendingHealthConnection = null
                if (epoch == sessionEpoch) {
                    _state.update {
                        it.copy(
                            isConnectingHealth = false,
                            healthMessage =
                                "Murph couldn't verify Health Connect permissions. Try again.",
                        )
                    }
                }
                return@withLock false
            }
            if (!ownsPendingHealthConnection(memberKey)) return@withLock false
            if (health.grantedResourceCount() == 0) {
                pendingHealthConnection = null
                _state.update {
                    it.copy(
                        isConnectingHealth = false,
                        healthSync = HealthSyncState.NotConnected,
                        grantedResourceCount = 0,
                        healthMessage = HEALTH_PERMISSION_RECOVERY_MESSAGE,
                    )
                }
                return@withLock false
            }
            true
        }
        if (permissionStillGranted) {
            completeHealthPermissionFlow(permissionRequestCompleted = true)
        }
    }

    private fun requestHealthPermissionLaunch() {
        val requestId = nextHealthPermissionRequestId++
        _state.update { current ->
            current.copy(pendingHealthPermissionRequestId = requestId)
        }
    }

    private fun requestAddressBookPermissionLaunch() {
        val requestId = nextAddressBookPermissionRequestId++
        _state.update { current ->
            current.copy(pendingAddressBookPermissionRequestId = requestId)
        }
    }

    private suspend fun reconcileAddressBookForeground(showBusy: Boolean) {
        if (!contacts.isSupported) return
        if (!addressBookMutex.tryLock()) {
            enqueueAddressBookReconcile(showBusy)
            return
        }
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        try {
            if (pendingAddressBookPermissionFlow != null) return
            val memberKey = currentMemberKey ?: return
            val epoch = sessionEpoch
            ownerMemberKey = memberKey
            ownerEpoch = epoch
            if (!ownsAddressBookWork(memberKey, epoch)) return
            if (showBusy) {
                _state.update { it.copy(isAddressBookBusy = true, addressBookMessage = null) }
            }

            val pendingDeletion = localState.pendingAddressBookDeletion
            val status = fetchAddressBookStatusLocked(
                memberKey = memberKey,
                epoch = epoch,
                consentFollowUp = pendingDeletion?.let {
                    LaunchConsentFollowUp.AutomaticAddressBookDeletion(it)
                } ?: LaunchConsentFollowUp.ReconcileAddressBook,
            ) ?: return
            if (!ownsAddressBookWork(memberKey, epoch) || !status.enabled) return

            pendingDeletion?.let {
                if (pendingDeletion.baseRevision != status.revision) {
                    localState.abandonAddressBookDeletion(pendingDeletion.mutationId)
                    publishAddressBookMessage(
                        memberKey,
                        epoch,
                        "The shared projection changed before Murph could finish deleting it. Use Stop to delete the latest revision.",
                    )
                    return
                }
                if (!contacts.hasPermission()) {
                    _state.update { it.copy(contactsPermissionDenied = true) }
                }
                performAutomaticAddressBookDeletionLocked(
                    memberKey = memberKey,
                    epoch = epoch,
                    mutation = it,
                )
                return
            }

            if (contacts.hasPermission()) return
            if (localState.addressBookRevision != status.revision) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "This projection was changed by another installation. Murph won't delete that newer revision automatically; use Stop to delete it.",
                )
                return
            }

            _state.update { it.copy(contactsPermissionDenied = true) }

            val deletion = try {
                createAddressBookMutation(status.revision)
            } catch (_: Exception) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Murph couldn't prepare automatic deletion. Use Stop to delete the shared names.",
                )
                return
            }
            if (!localState.beginAddressBookDeletion(deletion)) {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Contacts access is off, but Murph couldn't safely save the deletion retry marker. Use Stop to try again.",
                )
                return
            }
            performAutomaticAddressBookDeletionLocked(memberKey, epoch, deletion)
        } finally {
            addressBookMutex.unlock()
            if (
                showBusy &&
                ownerEpoch == sessionEpoch &&
                ownerMemberKey == currentMemberKey
            ) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            drainAddressBookReconcile(ownerMemberKey, ownerEpoch)
        }
    }

    private suspend fun performAutomaticAddressBookDeletionLocked(
        memberKey: String,
        epoch: Int,
        mutation: AddressBookMutation,
    ) {
        try {
            val deleted = api.deleteAddressBook(
                memberKey = memberKey,
                request = AddressBookDeletionRequest(mutation),
            )
            if (!ownsAddressBookWork(memberKey, epoch)) return
            if (!localState.completeAddressBookDeletion(mutation.mutationId, deleted.revision)) {
                publishAddressBookStatus(
                    memberKey,
                    epoch,
                    deleted,
                    "Murph deleted the shared names, but couldn't confirm that locally. Use Stop to verify.",
                )
                return
            }
            publishAddressBookStatus(
                memberKey,
                epoch,
                deleted,
                "Contacts access is off, so Murph deleted this installation's shared names.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionApiException.Conflict) {
            if (!ownsAddressBookWork(memberKey, epoch)) return
            localState.abandonAddressBookDeletion(mutation.mutationId)
            fetchAddressBookStatusLocked(
                memberKey = memberKey,
                epoch = epoch,
                consentFollowUp = LaunchConsentFollowUp.ReconcileAddressBook,
            )
            publishAddressBookMessage(
                memberKey,
                epoch,
                "The projection changed before automatic deletion. Murph left the newer revision alone; use Stop to delete it.",
            )
        } catch (_: CompanionApiException.ConsentRequired) {
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = LaunchConsentFollowUp.AutomaticAddressBookDeletion(mutation),
                healthLockHeld = false,
            )
        } catch (_: CompanionApiException.Unauthorized) {
            publishAddressBookMessage(
                memberKey,
                epoch,
                "Contacts access is off. Murph will retry exact deletion after your session is refreshed.",
            )
        } catch (_: Exception) {
            publishAddressBookMessage(
                memberKey,
                epoch,
                "Contacts access is off. Murph will retry deleting the exact shared revision on the next foreground check.",
            )
        }
    }

    private suspend fun resumeAutomaticAddressBookDeletion(mutation: AddressBookMutation) {
        if (!contacts.isSupported) return
        addressBookMutex.lock()
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        try {
            val memberKey = currentMemberKey ?: return
            val epoch = sessionEpoch
            ownerMemberKey = memberKey
            ownerEpoch = epoch
            if (
                !ownsAddressBookWork(memberKey, epoch) ||
                localState.pendingAddressBookDeletion != mutation
            ) {
                return
            }
            _state.update {
                it.copy(isAddressBookBusy = true, addressBookMessage = null)
            }
            performAutomaticAddressBookDeletionLocked(memberKey, epoch, mutation)
        } finally {
            addressBookMutex.unlock()
            if (ownerEpoch == sessionEpoch && ownerMemberKey == currentMemberKey) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            drainAddressBookReconcile(ownerMemberKey, ownerEpoch)
        }
    }

    /** Called only while [addressBookMutex] is held. */
    private suspend fun deleteOwnedAddressBookAfterPermissionLossLocked(
        pending: PendingAddressBookPermissionFlow,
        exactRevision: Int? = pending.ownedRevisionForPermissionLoss,
    ) {
        if (
            exactRevision == null ||
            !ownsAddressBookWork(pending.memberKey, pending.epoch)
        ) {
            return
        }
        val deletion = try {
            createAddressBookMutation(exactRevision)
        } catch (_: Exception) {
            publishAddressBookMessage(
                pending.memberKey,
                pending.epoch,
                "Contacts access is off, but Murph couldn't prepare exact deletion. Use Stop to delete the shared names.",
            )
            return
        }
        if (!localState.beginAddressBookDeletion(deletion)) {
            publishAddressBookMessage(
                pending.memberKey,
                pending.epoch,
                "Contacts access is off, but Murph couldn't safely save the deletion retry marker. Use Stop to delete the shared names.",
            )
            return
        }
        performAutomaticAddressBookDeletionLocked(
            memberKey = pending.memberKey,
            epoch = pending.epoch,
            mutation = deletion,
        )
    }

    /** Called only while [addressBookMutex] is held. */
    private suspend fun fetchAddressBookStatusLocked(
        memberKey: String,
        epoch: Int,
        consentFollowUp: LaunchConsentFollowUp,
    ): AddressBookServerStatus? {
        if (!ownsAddressBookWork(memberKey, epoch)) return null
        val status = try {
            api.fetchAddressBookStatus(memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionApiException.ConsentRequired) {
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = consentFollowUp,
                healthLockHeld = false,
            )
            return null
        } catch (_: CompanionApiException.Unauthorized) {
            publishAddressBookUnavailable(
                memberKey,
                epoch,
                "Your session needs a refresh before Murph can check address-book sharing.",
            )
            return null
        } catch (_: CompanionApiException.NoAccount) {
            publishAddressBookUnavailable(
                memberKey,
                epoch,
                "Address-book sharing isn't available for this Murph account.",
            )
            return null
        } catch (_: Exception) {
            publishAddressBookUnavailable(
                memberKey,
                epoch,
                "Murph couldn't refresh address-book sharing. Try again.",
            )
            return null
        }
        if (!ownsAddressBookWork(memberKey, epoch)) return null

        val minimumKnownRevision = listOfNotNull(
            localState.addressBookRevision,
            localState.pendingAddressBookReplacement?.baseRevision,
            localState.pendingAddressBookDeletion?.baseRevision,
        ).maxOrNull()
        if (minimumKnownRevision != null && status.revision < minimumKnownRevision) {
            publishAddressBookUnavailable(
                memberKey,
                epoch,
                "Murph received an invalid older address-book revision and left local ownership unchanged. Try again.",
            )
            return null
        }

        var message: String? = null
        if (!status.enabled) {
            val persisted = if (localState.pendingAddressBookReplacement != null) {
                localState.recordAddressBookRevision(status.revision)
            } else {
                localState.recordDisabledAddressBookRevision(status.revision)
            }
            if (!persisted) {
                message = "Murph couldn't safely remember the current sharing revision. Try again."
            }
        }
        publishAddressBookStatus(memberKey, epoch, status, message)
        return status
    }

    private fun publishAddressBookStatus(
        memberKey: String,
        epoch: Int,
        status: AddressBookServerStatus,
        message: String?,
    ) {
        if (!ownsAddressBookWork(memberKey, epoch)) return
        _state.update { current ->
            current.copy(
                addressBookSharing = AddressBookSharingState.Server(
                    enabled = status.enabled,
                    storedContactCount = status.storedContactCount,
                    canWrite = status.writeCapability == AddressBookWriteCapability.Enabled,
                    ownedByInstallation = localState.addressBookRevision == status.revision,
                ),
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                contactsPermissionDenied =
                    current.contactsPermissionDenied && !contacts.hasPermission(),
                addressBookMessage = message,
            )
        }
    }

    private fun publishAddressBookUnavailable(
        memberKey: String,
        epoch: Int,
        message: String,
    ) {
        if (!ownsAddressBookWork(memberKey, epoch)) return
        _state.update { current ->
            current.copy(
                addressBookSharing = AddressBookSharingState.Unavailable,
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                addressBookMessage = message,
            )
        }
    }

    private fun publishAddressBookPermissionDenied(memberKey: String, epoch: Int) {
        if (!ownsAddressBookWork(memberKey, epoch)) return
        _state.update { current ->
            current.copy(
                contactsPermissionDenied = true,
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                addressBookMessage =
                    "Contacts access is off. Open app settings to allow it, then try again. Other Murph features still work.",
            )
        }
    }

    private fun publishAddressBookMessage(
        memberKey: String,
        epoch: Int,
        message: String,
    ) {
        if (!ownsAddressBookWork(memberKey, epoch)) return
        _state.update { current ->
            current.copy(
                addressBookHasInterruptedReplacement =
                    localState.pendingAddressBookReplacement != null,
                addressBookMessage = message,
            )
        }
    }

    private fun enqueueAddressBookReconcile(showBusy: Boolean) {
        val requested = PendingAddressBookReconcile(
            memberKey = currentMemberKey,
            epoch = sessionEpoch,
            showBusy = showBusy,
        )
        synchronized(pendingAddressBookReconcileLock) {
            val existing = pendingAddressBookReconcile
            pendingAddressBookReconcile = if (
                existing != null &&
                existing.memberKey == requested.memberKey &&
                existing.epoch == requested.epoch
            ) {
                existing.copy(showBusy = existing.showBusy || requested.showBusy)
            } else {
                requested
            }
        }
    }

    private suspend fun drainAddressBookReconcile(
        completedMemberKey: String?,
        completedEpoch: Int?,
    ) {
        val requested = synchronized(pendingAddressBookReconcileLock) {
            pendingAddressBookReconcile.also { pendingAddressBookReconcile = null }
        } ?: return
        if (
            requested.memberKey == completedMemberKey &&
            requested.epoch == completedEpoch
        ) {
            return
        }
        if (
            requested.memberKey != currentMemberKey ||
            requested.epoch != sessionEpoch
        ) {
            return
        }
        reconcileAddressBookForeground(requested.showBusy)
    }

    private fun ownsAddressBookWork(memberKey: String, epoch: Int): Boolean =
        contacts.isSupported &&
            epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline &&
            pendingLaunchConsentRecovery == null &&
            !localState.signOutPending

    private fun ownsMealPhotoWork(memberKey: String, epoch: Int): Boolean =
        epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline &&
            pendingLaunchConsentRecovery == null &&
            !localState.signOutPending

    private fun publishMealPhotoState(
        memberKey: String,
        epoch: Int,
        captureState: MealPhotoCaptureState,
        message: String?,
    ) {
        if (!ownsMealPhotoWork(memberKey, epoch)) return
        _state.update {
            it.copy(
                mealPhotoCapture = captureState,
                mealPhotoMessage = message,
            )
        }
    }

    private suspend fun refreshMealPhotoReviews(memberKey: String, epoch: Int) {
        if (!ownsMealPhotoWork(memberKey, epoch)) return
        val reviews = try {
            meals.reviewItems()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        if (ownsMealPhotoWork(memberKey, epoch)) {
            _state.update { it.copy(mealPhotoReviewItems = reviews) }
        }
    }

    private suspend fun updateMealPhotoReview(captureId: String, approve: Boolean) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        if (!ownsMealPhotoWork(memberKey, epoch)) return
        mealPhotoMutex.withLock {
            if (!ownsMealPhotoWork(memberKey, epoch)) return@withLock
            _state.update {
                it.copy(
                    mealPhotoActionId = captureId,
                    mealPhotoMessage = null,
                )
            }
            val result = try {
                if (approve) {
                    meals.approveReviewItem(captureId)
                } else {
                    meals.dismissReviewItem(captureId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                MealPhotoActionResult.TryAgain
            }
            refreshMealPhotoReviews(memberKey, epoch)
            if (ownsMealPhotoWork(memberKey, epoch)) {
                _state.update {
                    it.copy(
                        mealPhotoActionId = null,
                        mealPhotoMessage = when (result) {
                            MealPhotoActionResult.Sent -> "Meal photo sent."
                            MealPhotoActionResult.Dismissed -> "Photo removed from review."
                            MealPhotoActionResult.PhotoUnavailable ->
                                "That photo is no longer available on this phone."
                            MealPhotoActionResult.NeedsAttention ->
                                "Meal photo suggestions need attention before this photo can be sent."
                            MealPhotoActionResult.TryAgain -> "That didn't finish. Try again."
                        },
                    )
                }
            }
        }
    }

    private fun mealPhotoMessage(captureState: MealPhotoCaptureState): String? = when (captureState) {
        MealPhotoCaptureState.Unavailable ->
            "Meal photo suggestions aren't available on this Android version."
        MealPhotoCaptureState.Off,
        MealPhotoCaptureState.On,
        MealPhotoCaptureState.Enabling,
        -> null
        MealPhotoCaptureState.NeedsPhotosAccess,
        MealPhotoCaptureState.NeedsFullAccess,
        -> "Meal photo suggestions need full Photos access. You can keep texting meal photos in your existing Murph conversation instead."
        MealPhotoCaptureState.NeedsAttention ->
            "Meal photo suggestions need attention. Open Murph while online and try again."
    }

    private fun createAddressBookMutation(baseRevision: Int): AddressBookMutation =
        AddressBookMutation(baseRevision, newMutationId())

    private fun replacementResultPredatesPreflight(
        preflight: AddressBookServerStatus,
        result: AddressBookServerStatus,
    ): Boolean = when {
        result.revision < preflight.revision -> true
        result.revision > preflight.revision -> false
        !preflight.enabled -> true
        result.storedContactCount != preflight.storedContactCount -> true
        else -> false
    }

    private fun deriveCachedHealthState(): HealthSyncState {
        return HealthSyncState.derive(
            requestedAt = healthRequestedAt(),
            status = cachedHealthStatus(),
            now = now(),
        )
    }

    private fun cachedHealthStatus(): CompanionSyncStatus? =
        localState.lastKnownDataReceivedAt?.epochMilliseconds
            ?.let(Instant::ofEpochMilli)
            ?.let { CompanionSyncStatus(it, emptyMap()) }

    private fun healthRequestedAt(): Instant? =
        localState.healthAccessRequestedAt?.epochMilliseconds?.let(Instant::ofEpochMilli)

    private fun healthWasRequested(): Boolean = healthRequestedAt() != null

    private fun ownsPendingHealthConnection(memberKey: String? = currentMemberKey): Boolean {
        val pending = pendingHealthConnection ?: return false
        return pending.epoch == sessionEpoch &&
            pending.memberKey == memberKey &&
            pending.memberKey == currentMemberKey &&
            pending.memberKey == localState.memberKey &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.isConnectingHealth &&
            _state.value.authVerifiedOnline &&
            !localState.signOutPending
    }

    private fun ownsHealthConnectionPreparation(memberKey: String, epoch: Int): Boolean =
        epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            _state.value.phase == AppPhase.Ready &&
            !_state.value.isConnectingHealth &&
            _state.value.authVerifiedOnline &&
            !localState.signOutPending

    private fun hasActiveLaunchConsentRecovery(): Boolean =
        pendingLaunchConsentRecovery?.let(::ownsLaunchConsentRecovery) == true

    private fun ownsLaunchConsentRecovery(pending: PendingLaunchConsentRecovery): Boolean =
        pendingLaunchConsentRecovery === pending &&
            pending.epoch == sessionEpoch &&
            pending.memberKey == currentMemberKey &&
            pending.memberKey == localState.memberKey &&
            !localState.signOutPending

    private fun invalidateSessionEpoch() {
        sessionEpoch += 1
        pendingHealthConnection = null
        pendingAddressBookPermissionFlow = null
        pendingMealPhotoPermissionFlow = null
        pendingLaunchConsentRecovery = null
        synchronized(pendingAddressBookReconcileLock) {
            pendingAddressBookReconcile = null
        }
        _state.update {
            it.copy(
                isConnectingHealth = false,
                isAddressBookBusy = false,
                isMealPhotoBusy = false,
                mealPhotoActionId = null,
                mealPhotoReviewItems = emptyList(),
                launchConsentRecovery = null,
                pendingHealthPermissionRequestId = null,
                pendingAddressBookPermissionRequestId = null,
                pendingMealPhotoPermissionRequestId = null,
            )
        }
    }

    private fun connectionErrorMessage(error: Exception): String = when (error) {
        CompanionApiException.Network -> "Murph couldn't reach the network. Check your connection and try again."
        CompanionApiException.Unauthorized -> "Your session needs a refresh. Sign in again."
        CompanionApiException.NoAccount -> "This sign-in isn't linked to an active Murph account."
        CompanionApiException.ConsentRequired -> CONSENT_REQUIRED_MESSAGE
        CompanionApiException.ReconnectRequired -> "Reconnect Health Connect to resume syncing."
        else -> "Murph couldn't finish connecting Health Connect. Try again in a moment."
    }

    private data class PendingHealthConnection(
        val epoch: Int,
        val memberKey: String,
        val requestedAt: Instant,
    )

    private data class PendingAddressBookPermissionFlow(
        val epoch: Int,
        val memberKey: String,
        val mutation: AddressBookMutation,
        val preflightStatus: AddressBookServerStatus,
        val ownedRevisionForPermissionLoss: Int?,
    )

    private data class PendingMealPhotoPermissionFlow(
        val epoch: Int,
        val memberKey: String,
        val previousState: MealPhotoCaptureState,
    )

    private data class PendingAddressBookReconcile(
        val memberKey: String?,
        val epoch: Int,
        val showBusy: Boolean,
    )

    private class PendingLaunchConsentRecovery(
        val epoch: Int,
        val memberKey: String,
        var followUp: LaunchConsentFollowUp,
        val resumeMealCapture: Boolean,
    )

    private sealed interface LaunchConsentFollowUp {
        data object Reconcile : LaunchConsentFollowUp
        data object SyncHealth : LaunchConsentFollowUp
        data object PrepareHealthPermission : LaunchConsentFollowUp
        data object PrepareAddressBookPermission : LaunchConsentFollowUp
        data object ReconcileAddressBook : LaunchConsentFollowUp
        data object StopAddressBookSharing : LaunchConsentFollowUp
        data object EnableMealPhotoCapture : LaunchConsentFollowUp
        data object RefreshMealPhotoCapture : LaunchConsentFollowUp
        data class AutomaticAddressBookDeletion(
            val mutation: AddressBookMutation,
        ) : LaunchConsentFollowUp
        data class CompleteHealthPermission(val requestedAt: Instant) : LaunchConsentFollowUp
        data class AddressBookReplacement(
            val pending: PendingAddressBookPermissionFlow,
        ) : LaunchConsentFollowUp
    }

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val CONSENT_REQUIRED_MESSAGE =
            "Murph needs your latest launch consent. Review it in the app, then try again."
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
    }
}
