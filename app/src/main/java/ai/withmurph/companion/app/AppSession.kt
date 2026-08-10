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
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.InitialOnboardingCompletionAction
import ai.withmurph.companion.core.InitialOnboardingCompletionRequest
import ai.withmurph.companion.core.InitialOnboardingContactCardRequest
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.UnsupportedAddressBookContactSource
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
import java.time.ZoneId
import java.util.UUID

class AppSession(
    private val auth: AuthProvider,
    private val api: CompanionApi,
    private val health: HealthSyncing,
    private val contacts: AddressBookContactSource = UnsupportedAddressBookContactSource,
    private val localState: LocalState,
    private val config: AppConfig,
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val startMutex = Mutex()
    private val healthMutex = Mutex()
    private val foregroundMutex = Mutex()
    private val addressBookMutex = Mutex()
    private val launchConsentMutex = Mutex()
    private val initialOnboardingMutex = Mutex()
    private var hasCompletedStartup = false
    private var needsForegroundRefresh = false
    private var foregroundGeneration = 0
    private var lastStartedHealthSyncSequence = 0L
    private var lastCompletedValidatedHealthSyncSequence = 0L
    private var sessionEpoch = 0
    private var currentMemberKey: String? = null
    private var pendingHealthConnection: PendingHealthConnection? = null
    private var pendingAddressBookPermissionFlow: PendingAddressBookPermissionFlow? = null
    private val pendingAddressBookReconcileLock = Any()
    private var pendingAddressBookReconcile: PendingAddressBookReconcile? = null
    private var pendingLaunchConsentRecovery: PendingLaunchConsentRecovery? = null
    private var nextHealthPermissionRequestId = 1
    private var nextAddressBookPermissionRequestId = 1
    private var nextInitialOnboardingContactCardHandoffId = 1
    private var pendingInitialOnboardingContactCardHandoff:
        PendingInitialOnboardingContactCardHandoffRequest? = null
    private var initialOnboardingGeneration = 0

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
        val pendingConsent = pendingLaunchConsentRecovery
        if (
            pendingConsent != null &&
            ownsAcceptedConsentContinuation(pendingConsent)
        ) {
            retryLaunchConsentRecovery()
            return
        }
        reconcile(force = true)
    }

    suspend fun refreshAddressBookSharing() {
        if (hasActiveLaunchConsentRecovery()) return
        var deferredBoundary: DeferredSessionBoundary? = null
        reconcileAddressBookForeground(
            showBusy = true,
            onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
        )
        deferredBoundary?.let { handleDeferredSessionBoundary(it) }
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
        if (_state.value.launchConsentRecovery == null) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(showSheet = false),
            )
        }
    }

    suspend fun retryLaunchConsentRecovery() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        var authoritativeLocalAuth: AuthSessionState? = null
        val continuation = launchConsentMutex.withLock {
            if (!ownsLaunchConsentRecovery(pending)) return@withLock null
            if (pending.accepted) {
                beginAcceptedConsentContinuation(pending)
            } else {
                loadLaunchConsentStatus(
                    pending,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                null
            }
        }
        authoritativeLocalAuth?.let {
            handleAuthoritativeLocalAuthObservation(it, healthAlreadySignedOut = true)
        }
        continuation?.let { resumeLaunchConsentFollowUp(it) }
    }

    suspend fun acceptLaunchConsent() {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        var authoritativeLocalAuth: AuthSessionState? = null
        val continuation = launchConsentMutex.withLock {
            if (!ownsLaunchConsentRecovery(pending)) return@withLock null
            val status = _state.value.launchConsentRecovery?.status
                ?: return@withLock null
            acceptLaunchConsentLocked(
                pending,
                status,
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
            )
        }
        authoritativeLocalAuth?.let {
            handleAuthoritativeLocalAuthObservation(it, healthAlreadySignedOut = true)
        }
        continuation ?: return
        resumeLaunchConsentFollowUp(continuation)
    }

    fun selectInitialOnboardingAvatar(avatarId: String) {
        updateInitialOnboardingDraft { onboarding, draft ->
            if (onboarding.contactCard?.avatars?.none { it.id == avatarId } != false) draft
            else draft.copy(avatarId = avatarId)
        }
    }

    fun selectInitialOnboardingMainPersona(personaId: String) {
        updateInitialOnboardingDraft { onboarding, draft ->
            val persona = onboarding.catalog?.personas?.firstOrNull { it.id == personaId }
                ?: return@updateInitialOnboardingDraft draft
            draft.copy(
                mainPersonaId = persona.id,
                supportingPersonaId = draft.supportingPersonaId?.takeIf { it != persona.id },
                voiceId = persona.defaultVoiceId,
                toneId = persona.defaultTone,
            )
        }
    }

    fun selectInitialOnboardingSupportingPersona(personaId: String?) {
        updateInitialOnboardingDraft { onboarding, draft ->
            val valid = personaId == null || (
                personaId != draft.mainPersonaId &&
                    onboarding.catalog?.personas?.any { it.id == personaId } == true
                )
            if (valid) draft.copy(supportingPersonaId = personaId) else draft
        }
    }

    fun selectInitialOnboardingVoice(voiceId: String) {
        updateInitialOnboardingDraft { onboarding, draft ->
            if (onboarding.catalog?.voices?.none { it.id == voiceId } != false) draft
            else draft.copy(voiceId = voiceId)
        }
    }

    fun selectInitialOnboardingTone(toneId: String) {
        updateInitialOnboardingDraft { onboarding, draft ->
            if (onboarding.catalog?.tones?.none { it.id == toneId } != false) draft
            else draft.copy(toneId = toneId)
        }
    }

    fun setInitialOnboardingStage(stage: InitialOnboardingStage) {
        val current = _state.value
        if (
            current.phase != AppPhase.Ready ||
            current.initialOnboarding?.status != InitialOnboardingStatus.Pending ||
            current.launchConsentRecovery != null ||
            current.isInitialOnboardingSaving
        ) return
        _state.update { state -> state.copy(initialOnboardingStage = stage) }
    }

    suspend fun skipInitialOnboarding() {
        completeInitialOnboardingRequest(
            InitialOnboardingCompletionRequest(
                action = InitialOnboardingCompletionAction.Skip,
                preferences = null,
            ),
        )
    }

    suspend fun saveInitialOnboarding() {
        val draft = _state.value.initialOnboardingDraft ?: return
        completeInitialOnboardingRequest(
            InitialOnboardingCompletionRequest(
                action = InitialOnboardingCompletionAction.Save,
                preferences = InitialOnboardingPreferences(
                    persona = draft.supportingPersonaId?.let { supporting ->
                        "${draft.mainPersonaId}-with-$supporting"
                    } ?: draft.mainPersonaId,
                    tone = draft.toneId,
                    voice = draft.voiceId,
                ),
            ),
        )
    }

    suspend fun prepareInitialOnboardingContactCard() {
        val avatarId = _state.value.initialOnboardingDraft?.avatarId ?: return
        prepareInitialOnboardingContactCard(avatarId)
    }

    suspend fun launchInitialOnboardingContactCardHandoff(
        id: Int,
        launch: (String) -> Boolean,
    ): Boolean {
        var authoritativeLocalAuth: AuthSessionState? = null
        val launched = initialOnboardingMutex.withLock {
            val pending = pendingInitialOnboardingContactCardHandoff
                ?.takeIf { it.id == id }
                ?: return@withLock false
            if (!ownsInitialOnboardingContactCardHandoff(pending)) {
                clearInitialOnboardingContactCardHandoff(pending)
                return@withLock false
            }
            val authBeforeMint = currentAuthStateForHandoff()
            if (!ownsInitialOnboardingContactCardAuth(pending, authBeforeMint)) {
                rejectInitialOnboardingContactCardAuth(
                    pending,
                    authBeforeMint,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                return@withLock false
            }
            val response = try {
                api.prepareInitialOnboardingContactCard(
                    pending.memberKey,
                    InitialOnboardingContactCardRequest(pending.avatarId),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionApiException.LocalAuthUnavailable) {
                rejectInitialOnboardingContactCardAuth(
                    pending,
                    error.observedState,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                return@withLock false
            } catch (_: CompanionApiException.ConsentRequired) {
                clearInitialOnboardingContactCardHandoff(pending)
                beginLaunchConsentRecovery(
                    expectedEpoch = pending.epoch,
                    memberKey = pending.memberKey,
                    followUp = LaunchConsentFollowUp.PrepareInitialOnboardingContactCard(
                        pending.avatarId,
                    ),
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                return@withLock false
            } catch (_: CompanionApiException.AccountConflict) {
                clearInitialOnboardingContactCardHandoff(pending)
                publishAccountConflictFailure()
                return@withLock false
            } catch (error: CompanionApiException) {
                clearInitialOnboardingContactCardHandoff(pending)
                if (isTerminalMemberBoundaryError(error)) {
                    publishTerminalMemberBoundaryFailure(error)
                } else {
                    publishInitialOnboardingFailure(
                        "We couldn't open the contact card. Check your connection and try again.",
                    )
                }
                return@withLock false
            } catch (_: Exception) {
                clearInitialOnboardingContactCardHandoff(pending)
                publishInitialOnboardingFailure(
                    "We couldn't open the contact card. Check your connection and try again.",
                )
                return@withLock false
            }
            if (!ownsInitialOnboardingContactCardHandoff(pending)) {
                return@withLock false
            }
            val authAfterMint = currentAuthStateForHandoff()
            if (!ownsInitialOnboardingContactCardAuth(pending, authAfterMint)) {
                rejectInitialOnboardingContactCardAuth(
                    pending,
                    authAfterMint,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                return@withLock false
            }
            if (!ownsInitialOnboardingContactCardHandoff(pending)) {
                return@withLock false
            }
            val didLaunch = try {
                launch(response.url)
            } catch (_: Exception) {
                false
            }
            if (!didLaunch) {
                clearInitialOnboardingContactCardHandoff(pending)
                return@withLock false
            }
            pendingInitialOnboardingContactCardHandoff = null
            _state.update { current ->
                if (current.initialOnboardingContactCardHandoff?.id != pending.id) {
                    current
                } else {
                    current.copy(
                        initialOnboardingStage = InitialOnboardingStage.MainPersona,
                        isInitialOnboardingSaving = false,
                        initialOnboardingContactCardHandoff = null,
                    )
                }
            }
            true
        }
        authoritativeLocalAuth?.let { handleAuthoritativeLocalAuthObservation(it) }
        return launched
    }

    fun dismissCompletedInitialOnboarding() {
        if (!_state.value.initialOnboardingCompletedNow) return
        clearInitialOnboardingState()
    }

    private fun updateInitialOnboardingDraft(
        update: (InitialOnboarding, InitialOnboardingDraft) -> InitialOnboardingDraft,
    ) {
        val current = _state.value
        val onboarding = current.initialOnboarding ?: return
        val draft = current.initialOnboardingDraft ?: return
        if (
            current.phase != AppPhase.Ready ||
            onboarding.status != InitialOnboardingStatus.Pending ||
            current.launchConsentRecovery != null ||
            current.isInitialOnboardingSaving
        ) return
        _state.update { state ->
            if (state.initialOnboarding !== onboarding || state.initialOnboardingDraft != draft) {
                state
            } else {
                state.copy(initialOnboardingDraft = update(onboarding, draft))
            }
        }
    }

    private suspend fun completeInitialOnboardingRequest(
        request: InitialOnboardingCompletionRequest,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        var authoritativeLocalAuth: AuthSessionState? = null
        initialOnboardingMutex.withLock {
            val current = _state.value
            if (
                current.phase != AppPhase.Ready ||
                current.initialOnboarding?.status != InitialOnboardingStatus.Pending ||
                !allowsLaunchConsentWork(acceptedConsentOwner) ||
                current.isInitialOnboardingSaving ||
                current.initialOnboardingCompletedNow ||
                memberKey != currentMemberKey ||
                epoch != sessionEpoch
            ) return@withLock
            initialOnboardingGeneration += 1
            val generation = initialOnboardingGeneration
            _state.update {
                it.copy(isInitialOnboardingSaving = true, initialOnboardingMessage = null)
            }
            try {
                val response = api.completeInitialOnboarding(memberKey, request)
                if (!ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    return@withLock
                }
                if (response.status != InitialOnboardingStatus.Completed) {
                    throw CompanionApiException.InvalidResponse
                }
                val showsWelcome =
                    request.action == InitialOnboardingCompletionAction.Save &&
                        response.completedNow == true
                if (showsWelcome) {
                    _state.update {
                        it.copy(
                            isInitialOnboardingSaving = false,
                            initialOnboardingCompletedNow = true,
                            initialOnboardingStage = InitialOnboardingStage.Welcome,
                            initialOnboardingMessage = null,
                        )
                    }
                } else {
                    clearInitialOnboardingState()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionApiException.LocalAuthUnavailable) {
                if (!ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    return@withLock
                }
                if (isAuthoritativeLocalAuthObservation(error.observedState)) {
                    authoritativeLocalAuth = error.observedState
                } else {
                    publishInitialOnboardingFailure(
                        "We couldn't save your setup yet. Your choices are still here. Try again.",
                    )
                }
            } catch (_: CompanionApiException.ConsentRequired) {
                val followUp = LaunchConsentFollowUp.CompleteInitialOnboarding(request)
                if (!ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    prioritizeStaleInitialOnboardingConsent(memberKey, followUp)
                    return@withLock
                }
                releaseInitialOnboardingBusy(generation)
                beginLaunchConsentRecovery(
                    expectedEpoch = epoch,
                    memberKey = memberKey,
                    followUp = followUp,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
            } catch (_: CompanionApiException.AccountConflict) {
                if (ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    publishAccountConflictFailure()
                }
            } catch (error: CompanionApiException) {
                if (!ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    return@withLock
                }
                if (isTerminalMemberBoundaryError(error)) {
                    publishTerminalMemberBoundaryFailure(error)
                } else {
                    publishInitialOnboardingFailure(
                        "We couldn't save your setup yet. Your choices are still here. Try again.",
                    )
                }
            } catch (_: Exception) {
                if (ownsInitialOnboardingRequest(memberKey, epoch, generation)) {
                    publishInitialOnboardingFailure(
                        "We couldn't save your setup yet. Your choices are still here. Try again.",
                    )
                }
            } finally {
                releaseInitialOnboardingBusy(generation)
            }
        }
        authoritativeLocalAuth?.let { handleAuthoritativeLocalAuthObservation(it) }
    }

    private suspend fun prepareInitialOnboardingContactCard(
        avatarId: String,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        initialOnboardingMutex.withLock {
            val current = _state.value
            if (
                current.phase != AppPhase.Ready ||
                current.initialOnboarding?.contactCard?.avatars?.none { it.id == avatarId } != false ||
                !allowsLaunchConsentWork(acceptedConsentOwner) ||
                current.isInitialOnboardingSaving ||
                current.initialOnboardingCompletedNow ||
                !ownsInitialOnboardingWork(memberKey, epoch)
            ) return@withLock
            initialOnboardingGeneration += 1
            val generation = initialOnboardingGeneration
            val handoffId = nextInitialOnboardingContactCardHandoffId++
            pendingInitialOnboardingContactCardHandoff =
                PendingInitialOnboardingContactCardHandoffRequest(
                    id = handoffId,
                    memberKey = memberKey,
                    epoch = epoch,
                    generation = generation,
                    avatarId = avatarId,
                )
            _state.update {
                it.copy(
                    isInitialOnboardingSaving = true,
                    initialOnboardingMessage = null,
                    initialOnboardingContactCardHandoff =
                        PendingInitialOnboardingContactCardHandoff(handoffId),
                )
            }
        }
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

    suspend fun deferAddressBookSharingInitialSetup(): Boolean = addressBookMutex.withLock {
        val current = _state.value
        if (
            current.phase != AppPhase.Ready ||
            current.initialSetupStep != InitialSetupStep.FriendlyNames ||
            current.isAddressBookBusy ||
            pendingAddressBookPermissionFlow != null ||
            hasActiveLaunchConsentRecovery()
        ) {
            return@withLock false
        }
        val memberKey = currentMemberKey ?: return@withLock false
        if (localState.pendingAddressBookReplacement != null) {
            _state.update {
                if (
                    it.phase == AppPhase.Ready &&
                    it.initialSetupStep == InitialSetupStep.FriendlyNames &&
                    currentMemberKey == memberKey &&
                    localState.memberKey == memberKey &&
                    !localState.signOutPending
                ) {
                    it.copy(
                        addressBookHasInterruptedReplacement = true,
                        addressBookMessage =
                            "Finish the saved contact update or stop and delete it before skipping Friendly Names.",
                    )
                } else {
                    it
                }
            }
            return@withLock false
        }
        val deferred = advanceInitialSetupStep(
            expected = InitialSetupStep.FriendlyNames,
            next = InitialSetupStep.Complete,
            abandonPendingAddressBookReplacement = false,
        )
        if (!deferred) {
            _state.update {
                if (
                    it.phase == AppPhase.Ready &&
                    it.initialSetupStep == InitialSetupStep.FriendlyNames &&
                    currentMemberKey == memberKey &&
                    localState.memberKey == memberKey &&
                    localState.initialSetupStep == InitialSetupStep.FriendlyNames &&
                    !localState.signOutPending
                ) {
                    it.copy(
                        addressBookMessage =
                            "Murph couldn't save that Friendly Names setup choice. Try again.",
                    )
                } else {
                    it
                }
            }
        } else {
            _state.update {
                it.copy(
                    addressBookHasInterruptedReplacement = false,
                    addressBookMessage = null,
                )
            }
        }
        deferred
    }

    suspend fun prepareInitialAddressBookSharing(): Boolean =
        prepareAddressBookSharing(acceptedConsentOwner = null, completesInitialSetup = true)

    suspend fun prepareAddressBookSharing(): Boolean =
        prepareAddressBookSharing(acceptedConsentOwner = null, completesInitialSetup = false)

    private suspend fun prepareAddressBookSharing(
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
        completesInitialSetup: Boolean,
    ): Boolean {
        if (!allowsLaunchConsentWork(acceptedConsentOwner)) return false
        if (!contacts.isSupported) return false
        addressBookMutex.lock()
        var prepared = false
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        var deferredBoundary: DeferredSessionBoundary? = null
        try {
            if (pendingAddressBookPermissionFlow != null) return false
            if (
                completesInitialSetup &&
                (
                    _state.value.initialSetupStep != InitialSetupStep.FriendlyNames ||
                        localState.initialSetupStep != InitialSetupStep.FriendlyNames
                )
            ) {
                return false
            }
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
                consentFollowUp = LaunchConsentFollowUp.PrepareAddressBookPermission(
                    completesInitialSetup,
                ),
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
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
                completesInitialSetup = completesInitialSetup,
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
            val boundaryBeforeDrain = deferredBoundary
            deferredBoundary = null
            boundaryBeforeDrain?.let { handleDeferredSessionBoundary(it) }
            drainAddressBookReconcile(
                ownerMemberKey,
                ownerEpoch,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
            deferredBoundary?.let { handleDeferredSessionBoundary(it) }
        }
    }

    suspend fun completeAddressBookPermissionFlow(permissionGranted: Boolean): Boolean =
        completeAddressBookPermissionFlow(permissionGranted, acceptedConsentDispatch = null)

    private suspend fun completeAddressBookPermissionFlow(
        permissionGranted: Boolean,
        acceptedConsentDispatch: AcceptedLaunchConsentDispatch?,
    ): Boolean {
        val pending = pendingAddressBookPermissionFlow ?: return false
        _state.update { it.copy(pendingAddressBookPermissionRequestId = null) }
        var deferredBoundary: DeferredSessionBoundary? = null
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
                        deleteOwnedAddressBookAfterPermissionLossLocked(
                            pending,
                            onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
                        )
                        return@withLock false
                    }
                    currentAuthOwnershipLoss(pending.memberKey)?.let { authState ->
                        deferredBoundary = deferredBoundary
                            ?: DeferredSessionBoundary.LocalAuth(authState)
                        return@withLock false
                    }
                    if (
                        acceptedConsentDispatch != null &&
                        !ownsAcceptedConsentDispatch(acceptedConsentDispatch)
                    ) {
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
                            deleteOwnedAddressBookAfterPermissionLossLocked(
                                pending,
                                onSessionBoundary = {
                                    deferredBoundary = deferredBoundary ?: it
                                },
                            )
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
                            deleteOwnedAddressBookAfterPermissionLossLocked(
                                pending,
                                onSessionBoundary = {
                                    deferredBoundary = deferredBoundary ?: it
                                },
                            )
                        }
                        return@withLock false
                    }
                    if (
                        acceptedConsentDispatch != null &&
                        !ownsAcceptedConsentDispatch(acceptedConsentDispatch)
                    ) {
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
                            deleteOwnedAddressBookAfterPermissionLossLocked(
                                pending,
                                onSessionBoundary = {
                                    deferredBoundary = deferredBoundary ?: it
                                },
                            )
                        }
                        return@withLock false
                    }
                    if (
                        acceptedConsentDispatch != null &&
                        !ownsAcceptedConsentDispatch(acceptedConsentDispatch)
                    ) {
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
                                onSessionBoundary = {
                                    deferredBoundary = deferredBoundary ?: it
                                },
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
                                onAuthoritativeLocalAuth = {
                                    deferredBoundary = deferredBoundary
                                        ?: DeferredSessionBoundary.LocalAuth(it)
                                },
                            )
                        }
                        return@withLock false
                    } catch (error: CompanionApiException.LocalAuthUnavailable) {
                        if (
                            ownsAddressBookWork(pending.memberKey, pending.epoch) &&
                            isAuthoritativeLocalAuthObservation(error.observedState)
                        ) {
                            deferredBoundary = deferredBoundary
                                ?: DeferredSessionBoundary.LocalAuth(error.observedState)
                        } else {
                            publishAddressBookMessage(
                                pending.memberKey,
                                pending.epoch,
                                "Murph couldn't finish the address-book update. Tap Retry to use the saved mutation safely.",
                            )
                        }
                        return@withLock false
                    } catch (_: CompanionApiException.AccountConflict) {
                        if (ownsAddressBookWork(pending.memberKey, pending.epoch)) {
                            deferredBoundary = deferredBoundary
                                ?: DeferredSessionBoundary.AccountConflict
                        }
                        return@withLock false
                    } catch (error: CompanionApiException) {
                        if (
                            ownsAddressBookWork(pending.memberKey, pending.epoch) &&
                            isTerminalMemberBoundaryError(error)
                        ) {
                            deferredBoundary = deferredBoundary
                                ?: DeferredSessionBoundary.BackendRejected(error)
                        } else {
                            publishAddressBookMessage(
                                pending.memberKey,
                                pending.epoch,
                                "Murph couldn't finish the address-book update. Tap Retry to use the saved mutation safely.",
                            )
                        }
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
                            onSessionBoundary = {
                                deferredBoundary = deferredBoundary ?: it
                            },
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
                            onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
                        )
                        return@withLock false
                    }
                    if (
                        !localState.completeAddressBookReplacement(
                            mutationId = mutation.mutationId,
                            revision = status.revision,
                            completesInitialSetup = pending.completesInitialSetup,
                        )
                    ) {
                        publishAddressBookStatus(
                            pending.memberKey,
                            pending.epoch,
                            status,
                            "The server saved this update, but Murph couldn't confirm it locally. Tap Retry.",
                        )
                        return@withLock false
                    }
                    if (pending.completesInitialSetup) {
                        projectInitialSetupStep(
                            expected = InitialSetupStep.FriendlyNames,
                            next = InitialSetupStep.Complete,
                        )
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
            val boundaryBeforeDrain = deferredBoundary
            deferredBoundary = null
            boundaryBeforeDrain?.let { handleDeferredSessionBoundary(it) }
            drainAddressBookReconcile(
                pending.memberKey,
                pending.epoch,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
        }
        deferredBoundary?.let { handleDeferredSessionBoundary(it) }
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

    suspend fun stopAddressBookSharing() = stopAddressBookSharing(null)

    private suspend fun stopAddressBookSharing(
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ) {
        if (!contacts.isSupported) return
        if (acceptedConsentOwner == null &&
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
        var deferredBoundary: DeferredSessionBoundary? = null
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
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
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
                        onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
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
                        onAuthoritativeLocalAuth = {
                            deferredBoundary = deferredBoundary
                                ?: DeferredSessionBoundary.LocalAuth(it)
                        },
                    )
                    return
                } catch (error: CompanionApiException.LocalAuthUnavailable) {
                    if (
                        ownsAddressBookWork(memberKey, epoch) &&
                        isAuthoritativeLocalAuthObservation(error.observedState)
                    ) {
                        deferredBoundary = deferredBoundary
                            ?: DeferredSessionBoundary.LocalAuth(error.observedState)
                    } else {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Murph couldn't delete the shared names yet. It will keep the exact deletion retry marker.",
                        )
                    }
                    return
                } catch (_: CompanionApiException.AccountConflict) {
                    if (ownsAddressBookWork(memberKey, epoch)) {
                        deferredBoundary = deferredBoundary
                            ?: DeferredSessionBoundary.AccountConflict
                    }
                    return
                } catch (error: CompanionApiException) {
                    if (
                        ownsAddressBookWork(memberKey, epoch) &&
                        isTerminalMemberBoundaryError(error)
                    ) {
                        deferredBoundary = deferredBoundary
                            ?: DeferredSessionBoundary.BackendRejected(error)
                    } else {
                        publishAddressBookMessage(
                            memberKey,
                            epoch,
                            "Murph couldn't delete the shared names yet. It will keep the exact deletion retry marker.",
                        )
                    }
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
            val boundaryBeforeDrain = deferredBoundary
            deferredBoundary = null
            boundaryBeforeDrain?.let { handleDeferredSessionBoundary(it) }
            drainAddressBookReconcile(
                ownerMemberKey,
                ownerEpoch,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
            deferredBoundary?.let { handleDeferredSessionBoundary(it) }
        }
    }

    private suspend fun reconcile(
        force: Boolean,
        foregroundClaim: ForegroundRefreshClaim? = null,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) = startMutex.withLock {
        if (!allowsLaunchConsentWork(acceptedConsentOwner)) return@withLock
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
            val authState = try {
                auth.currentState()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AuthSessionState.TemporarilyUnavailable
            }
            if (
                acceptedConsentOwner != null &&
                ownsAcceptedConsentContinuation(acceptedConsentOwner) &&
                authState is AuthSessionState.SignedIn &&
                !authState.verifiedOnline &&
                authState.memberKey != acceptedConsentOwner.memberKey
            ) {
                _state.update { current ->
                    current.copy(
                        phase = AppPhase.Failed(
                            message = "Murph couldn't verify the account change. Check your connection and try again.",
                            canRetry = true,
                            canSignOut = true,
                        ),
                        authVerifiedOnline = false,
                        healthStatusIsStale = healthWasRequested(),
                    )
                }
                publishLaunchConsentLoadFailure(
                    acceptedConsentOwner,
                    "Murph couldn't verify the account change. Check your connection and try again.",
                )
                hasCompletedStartup = true
                return@withLock
            }
            if (
                acceptedConsentOwner != null &&
                ownsAcceptedConsentContinuation(acceptedConsentOwner) &&
                isAuthoritativeLaunchConsentAuthObservation(
                    acceptedConsentOwner,
                    authState,
                )
            ) {
                abandonLaunchConsentForAuthBoundary(acceptedConsentOwner)
            }
            if (
                authState is AuthSessionState.SignedIn &&
                isUnverifiedDifferentMemberCandidate(authState)
            ) {
                reconcileSignedIn(
                    authState,
                    foregroundClaim,
                    acceptedConsentOwner = acceptedConsentOwner,
                )
                hasCompletedStartup = _state.value.phase != AppPhase.Launching
                return@withLock
            }
            if (!enforceHealthSetupAuthorization()) {
                hasCompletedStartup = true
                return@withLock
            }
            when (authState) {
                AuthSessionState.SignedOut -> enterSignedOut()
                AuthSessionState.TemporarilyUnavailable -> restoreOfflineIfPossible()
                is AuthSessionState.SignedIn -> reconcileSignedIn(
                    authState,
                    foregroundClaim,
                    acceptedConsentOwner = acceptedConsentOwner,
                )
            }
            hasCompletedStartup = _state.value.phase != AppPhase.Launching
        } catch (error: CancellationException) {
            hasCompletedStartup = false
            throw error
        }
    }

    suspend fun deferHealthConnectInitialSetup(): Boolean = healthMutex.withLock {
        val current = _state.value
        if (
            current.phase != AppPhase.Ready ||
            current.initialSetupStep != InitialSetupStep.HealthConnect ||
            current.isConnectingHealth ||
            pendingHealthConnection != null ||
            hasActiveLaunchConsentRecovery()
        ) {
            return@withLock false
        }
        val memberKey = currentMemberKey ?: return@withLock false
        val deferred = advanceInitialSetupStep(
            expected = InitialSetupStep.HealthConnect,
            next = InitialSetupStep.FriendlyNames,
        )
        if (!deferred) {
            _state.update {
                if (
                    it.phase == AppPhase.Ready &&
                    it.initialSetupStep == InitialSetupStep.HealthConnect &&
                    currentMemberKey == memberKey &&
                    localState.memberKey == memberKey &&
                    localState.initialSetupStep == InitialSetupStep.HealthConnect &&
                    !localState.signOutPending
                ) {
                    it.copy(
                        healthMessage =
                            "Murph couldn't save that Health Connect setup choice. Try again.",
                    )
                } else {
                    it
                }
            }
        }
        deferred
    }

    suspend fun prepareHealthConnection(): Boolean = prepareHealthConnection(null)

    private suspend fun prepareHealthConnection(
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): Boolean {
        if (!allowsLaunchConsentWork(acceptedConsentOwner)) return false
        if (
            _state.value.phase == AppPhase.Ready &&
            !_state.value.authVerifiedOnline
        ) {
            reconcile(force = true, acceptedConsentOwner = acceptedConsentOwner)
        }
        var authStateToReconcile: AuthSessionState? = null
        var deferredConsentRecovery: DeferredLaunchConsentRecovery? = null
        val prepared = healthMutex.withLock {
            if (
                _state.value.phase != AppPhase.Ready ||
                _state.value.isConnectingHealth ||
                !_state.value.authVerifiedOnline
            ) {
                return false
            }
            val memberKey = currentMemberKey ?: return false
            val availabilityEpoch = sessionEpoch
            val availability = health.availability()
            _state.update { current ->
                if (
                    availabilityEpoch == sessionEpoch &&
                    memberKey == currentMemberKey &&
                    memberKey == localState.memberKey &&
                    current.phase == AppPhase.Ready &&
                    !current.isConnectingHealth &&
                    current.authVerifiedOnline &&
                    !localState.signOutPending
                ) {
                    current.copy(healthAvailability = availability)
                } else {
                    current
                }
            }
            if (!ownsHealthConnectionPreparation(memberKey, availabilityEpoch)) return false
            when (availability) {
                HealthConnectAvailability.Available -> {
                    val validatedEpoch = sessionEpoch
                    val receiptBeforePreflight = localState.lastKnownDataReceivedAt
                    val observationBeforePreflight = localState.lastKnownStatusObservedAt
                    val preflightStatus =
                        fetchValidatedHealthStatus(
                            validatedEpoch,
                            LaunchConsentFollowUp.PrepareHealthPermission,
                            onAuthoritativeLocalAuth = { authStateToReconcile = it },
                            onConsentRequired = { deferredConsentRecovery = it },
                        ) ?: return@withLock false
                    if (validatedEpoch != sessionEpoch) return false
                    currentAuthOwnershipLoss(memberKey)?.let { authState ->
                        localState.lastKnownDataReceivedAt = receiptBeforePreflight
                        localState.lastKnownStatusObservedAt = observationBeforePreflight
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
                    try {
                        health.pauseAutomaticSync()
                    } catch (_: Exception) {
                        _state.update {
                            it.copy(
                                healthMessage =
                                    "Murph couldn't safely prepare Health Connect. Try again.",
                            )
                        }
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
                    val hadLiveHealthSession = health.isSignedIn()
                    invalidateSessionEpoch()
                    val preparationEpoch = sessionEpoch
                    if (hadCompletedSetup || hadLiveHealthSession) {
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
                        requestedAt = preflightStatus.observedAt,
                        receiptBaselineAt = preflightStatus.lastDataReceivedAt,
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
        deferredConsentRecovery?.let { deferred ->
            beginLaunchConsentRecovery(
                deferred,
                onAuthoritativeLocalAuth = { authStateToReconcile = it },
            )
        }
        authStateToReconcile?.let { handleAuthoritativeLocalAuthObservation(it) }
        if (prepared) requestHealthPermissionLaunch()
        return prepared
    }

    suspend fun completeHealthPermissionFlow(permissionRequestCompleted: Boolean): Boolean =
        completeHealthPermissionFlow(
            permissionRequestCompleted = permissionRequestCompleted,
            acceptedConsentOwner = null,
        )

    private suspend fun completeHealthPermissionFlow(
        permissionRequestCompleted: Boolean,
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): Boolean {
        _state.update { it.copy(pendingHealthPermissionRequestId = null) }
        var authStateToReconcile: AuthSessionState? = null
        var deferredConsentRecovery: DeferredLaunchConsentRecovery? = null
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
                val finalPreConnectStatus = fetchValidatedHealthStatus(
                    epoch,
                    LaunchConsentFollowUp.CompleteHealthPermission(
                        pending.requestedAt,
                        pending.receiptBaselineAt,
                    ),
                    onAuthoritativeLocalAuth = { authStateToReconcile = it },
                    onConsentRequired = { deferredConsentRecovery = it },
                )
                if (finalPreConnectStatus == null) {
                    pendingHealthConnection = null
                    _state.update { it.copy(isConnectingHealth = false) }
                    return@withLock false
                }
                val refreshedPending = pending.copy(
                    requestedAt = finalPreConnectStatus.observedAt,
                    receiptBaselineAt = finalPreConnectStatus.lastDataReceivedAt,
                )
                pendingHealthConnection = refreshedPending
                if (!ownsPendingHealthConnection(refreshedPending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(refreshedPending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                try {
                    when (
                        val result = identifyJunction(
                            memberKey = refreshedPending.memberKey,
                            intent = ConnectionIntent.Connect,
                            epoch = epoch,
                            ownsIdentity = {
                                ownsPendingHealthConnection(refreshedPending.memberKey)
                            },
                        )
                    ) {
                        JunctionIdentificationResult.Identified -> Unit
                        JunctionIdentificationResult.OwnershipLost ->
                            return@withLock abortPendingHealthConnection(epoch)
                        is JunctionIdentificationResult.AuthLost -> {
                            authStateToReconcile = result.state
                            return@withLock abortPendingHealthConnection(epoch)
                        }
                    }
                } catch (error: CompanionApiException.LocalAuthUnavailable) {
                    if (isAuthoritativeLocalAuthObservation(error.observedState)) {
                        authStateToReconcile = error.observedState
                        return@withLock abortPendingHealthConnection(epoch)
                    }
                    throw error
                } catch (error: CompanionApiException.ConsentRequired) {
                    if (epoch == sessionEpoch) {
                        deferredConsentRecovery = DeferredLaunchConsentRecovery(
                            expectedEpoch = epoch,
                            memberKey = refreshedPending.memberKey,
                            followUp = LaunchConsentFollowUp.CompleteHealthPermission(
                                refreshedPending.requestedAt,
                                refreshedPending.receiptBaselineAt,
                            ),
                        )
                    }
                    pendingHealthConnection = null
                    return@withLock false
                }
                if (!ownsPendingHealthConnection(refreshedPending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(refreshedPending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                health.configure()
                health.connectAfterPermissionRequest()
                if (!ownsPendingHealthConnection(refreshedPending.memberKey)) {
                    return abortPendingHealthConnection(epoch)
                }
                currentAuthOwnershipLoss(refreshedPending.memberKey)?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
                }
                val requestedAt = InstantValue(refreshedPending.requestedAt.toEpochMilli())
                val completesInitialSetup =
                    _state.value.initialSetupStep == InitialSetupStep.HealthConnect &&
                        localState.initialSetupStep == InitialSetupStep.HealthConnect
                val committed = localState.completeHealthSetupAuthorization(
                    requestedAt = requestedAt,
                    receiptBaselineAt = refreshedPending.receiptBaselineAt?.let {
                        InstantValue(it.toEpochMilli())
                    },
                    statusObservedAt = requestedAt,
                    completesInitialSetup = completesInitialSetup,
                )
                if (!committed) {
                    pendingHealthConnection = null
                    val rollbackSucceeded = rollbackIncompleteHealthSetup(epoch)
                    _state.update { current ->
                        current.copy(
                            isConnectingHealth = false,
                            healthMessage = if (rollbackSucceeded) {
                                "Murph couldn't save Health Connect setup. Try again."
                            } else {
                                "Murph couldn't safely reset health sync. Keep the app open and sign out."
                            },
                        )
                    }
                    return@withLock false
                }
                if (completesInitialSetup) {
                    projectInitialSetupStep(
                        expected = InitialSetupStep.HealthConnect,
                        next = InitialSetupStep.FriendlyNames,
                    )
                }
                pendingHealthConnection = null
                _state.update { current ->
                    current.copy(
                        isConnectingHealth = false,
                        healthSync = HealthSyncState.AwaitingFirstData,
                        healthStatusObservedAt = refreshedPending.requestedAt,
                        healthStatusIsStale = false,
                        healthReconnectRequired = false,
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
            } catch (_: CompanionApiException.AccountConflict) {
                pendingHealthConnection = null
                if (epoch == sessionEpoch) {
                    publishAccountConflictFailureWhileHealthLocked()
                }
                false
            } catch (error: Exception) {
                pendingHealthConnection = null
                if (
                    epoch == sessionEpoch &&
                    error is CompanionApiException &&
                    isTerminalMemberBoundaryError(error)
                ) {
                    failCurrentSessionWhileHealthLocked(
                        message = terminalMemberBoundaryMessage(error),
                        canRetry = false,
                        signOutLabel = terminalMemberBoundarySignOutLabel(error),
                        supplementalActions = FailureSupplementalActions.Support,
                        revokeAuthorization = true,
                    )
                } else {
                    val rollbackSucceeded = rollbackIncompleteHealthSetup(epoch)
                    if (epoch != sessionEpoch) return@withLock false
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
        deferredConsentRecovery?.let { deferred ->
            beginLaunchConsentRecovery(
                deferred,
                onAuthoritativeLocalAuth = { authStateToReconcile = it },
            )
        }
        authStateToReconcile?.let { handleAuthoritativeLocalAuthObservation(it) }
        if (completed && !needsForegroundRefresh) {
            if (acceptedConsentOwner == null) {
                syncNow()
            } else {
                advanceCompletedHealthPermissionContinuationToSync(acceptedConsentOwner)
            }
        }
        return completed
    }

    fun cancelHealthPermissionFlow() {
        val pending = pendingHealthConnection
        if (pending == null) {
            _state.update {
                it.copy(pendingHealthPermissionRequestId = null)
            }
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

    suspend fun syncNow() = syncNow(foregroundClaim = null, acceptedConsentOwner = null)

    private suspend fun syncNow(
        foregroundClaim: ForegroundRefreshClaim?,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
        permissionStateVerified: Boolean = false,
        onAuthoritativeLocalAuth: ((AuthSessionState) -> Unit)? = null,
    ) {
        if (!allowsLaunchConsentWork(acceptedConsentOwner)) return
        if (
            _state.value.phase != AppPhase.Ready ||
            !healthWasRequested()
        ) return
        if (!_state.value.authVerifiedOnline) {
            reconcile(force = true, foregroundClaim, acceptedConsentOwner)
            return
        }
        if (!health.isSignedIn()) {
            reconcile(force = true, foregroundClaim, acceptedConsentOwner)
            return
        }
        if (
            !permissionStateVerified &&
            _state.value.healthStatusIsStale &&
            !refreshHealthPermissionState()
        ) {
            publishHealthPermissionVerificationFailure()
            return
        }
        if (health.grantedResourceCount() == 0) {
            publishPermissionAwareHealthState(
                status = cachedHealthStatus(),
                message = _state.value.healthMessage,
                healthStatusIsStale = false,
            )
            return
        }
        var authoritativeLocalAuth: AuthSessionState? = null
        var deferredConsentRecovery: DeferredLaunchConsentRecovery? = null
        val needsHealthReconciliation = healthMutex.withLock {
            if (
                foregroundClaim != null &&
                !ownsForegroundRefresh(foregroundClaim)
            ) return@withLock false
            val epoch = sessionEpoch
            syncAndRefresh(
                epoch,
                foregroundClaim,
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                onConsentRequired = { deferredConsentRecovery = it },
            )
        }
        deferredConsentRecovery?.let { deferred ->
            beginLaunchConsentRecovery(
                deferred,
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
            )
        }
        authoritativeLocalAuth?.let {
            if (onAuthoritativeLocalAuth != null) {
                onAuthoritativeLocalAuth(it)
            } else {
                handleAuthoritativeLocalAuthObservation(it)
            }
            return
        }
        if (needsHealthReconciliation) {
            reconcile(force = true, foregroundClaim, acceptedConsentOwner)
        }
    }

    suspend fun didBecomeActive() {
        val foregroundClaim = ForegroundRefreshClaim(
            generation = foregroundGeneration,
            sessionEpoch = sessionEpoch,
            healthSyncSequenceAtEntry = lastStartedHealthSyncSequence,
        )
        runForegroundRefresh(foregroundClaim)
    }

    private suspend fun runForegroundRefresh(foregroundClaim: ForegroundRefreshClaim) {
        var deferredBoundary: DeferredSessionBoundary? = null
        foregroundMutex.withLock {
            if (!ownsForegroundRefresh(foregroundClaim)) return@withLock
            val consentRecovery = pendingLaunchConsentRecovery
            if (
                consentRecovery != null &&
                deferForegroundRefreshForAcceptedConsent(consentRecovery, foregroundClaim)
            ) {
                return@withLock
            }
            if (hasActiveLaunchConsentRecovery()) {
                if (!needsForegroundRefresh) return@withLock
                needsForegroundRefresh = false
                refreshActiveLaunchConsentAfterForeground {
                    deferredBoundary = deferredBoundary
                        ?: DeferredSessionBoundary.LocalAuth(it)
                }
                return@withLock
            }
            val consumesForegroundRefresh = needsForegroundRefresh
            if (!consumesForegroundRefresh) {
                if (
                    _state.value.phase != AppPhase.Ready ||
                    _state.value.authVerifiedOnline
                ) return@withLock
            }
            val healthWasRequestedAtClaim = healthWasRequested()
            if (consumesForegroundRefresh) needsForegroundRefresh = false
            val authAllowsSync = reconcileForegroundAuth(foregroundClaim)
            if (!ownsForegroundRefresh(foregroundClaim)) return@withLock
            if (
                authAllowsSync &&
                _state.value.phase == AppPhase.Ready &&
                _state.value.initialOnboarding != null
            ) {
                refreshInitialOnboardingAfterForeground()?.let {
                    deferredBoundary = deferredBoundary
                        ?: DeferredSessionBoundary.LocalAuth(it)
                }
                if (deferredBoundary != null) return@withLock
                if (!ownsForegroundRefresh(foregroundClaim)) return@withLock
            }
            if (
                ownsPendingHealthConnection() ||
                (!healthWasRequestedAtClaim && healthWasRequested())
            ) {
                return@withLock
            }
            if (
                authAllowsSync &&
                _state.value.phase == AppPhase.Ready &&
                _state.value.authVerifiedOnline
            ) {
                reconcileAddressBookForeground(
                    showBusy = false,
                    onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
                )
                if (deferredBoundary != null) return@withLock
                if (!ownsForegroundRefresh(foregroundClaim)) return@withLock
            }
            if (_state.value.phase != AppPhase.Ready) return@withLock
            val permissionStateVerified = refreshHealthPermissionState()
            if (!ownsForegroundRefresh(foregroundClaim)) return@withLock
            val availability = health.availability()
            if (!permissionStateVerified) {
                publishHealthPermissionVerificationFailure(availability)
                return@withLock
            }
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
                    healthStatusIsStale = if (needsPermissionRecovery) {
                        false
                    } else {
                        current.healthStatusIsStale
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
                ownsForegroundRefresh(foregroundClaim) &&
                authAllowsSync &&
                healthWasRequestedAtClaim &&
                _state.value.phase == AppPhase.Ready &&
                healthWasRequested() &&
                grantedResourceCount > 0
            ) {
                syncNow(
                    foregroundClaim,
                    permissionStateVerified = true,
                    onAuthoritativeLocalAuth = {
                        deferredBoundary = deferredBoundary
                            ?: DeferredSessionBoundary.LocalAuth(it)
                    },
                )
            }
        }
        deferredBoundary?.let { handleDeferredSessionBoundary(it) }
    }

    private suspend fun refreshInitialOnboardingAfterForeground(): AuthSessionState? {
        if (!initialOnboardingMutex.tryLock()) return null
        try {
            val memberKey = currentMemberKey ?: return null
            val epoch = sessionEpoch
            val generation = initialOnboardingGeneration
            val current = _state.value
            if (
                current.phase != AppPhase.Ready ||
                current.initialOnboarding == null ||
                current.isInitialOnboardingSaving ||
                current.initialOnboardingCompletedNow ||
                current.launchConsentRecovery != null
            ) return null
            val response = try {
                api.fetchInitialOnboarding(memberKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionApiException.LocalAuthUnavailable) {
                return error.observedState.takeIf {
                    ownsInitialOnboardingRefresh(memberKey, epoch, generation) &&
                        isAuthoritativeLocalAuthObservation(it)
                }
            } catch (_: CompanionApiException.ConsentRequired) {
                var authoritativeLocalAuth: AuthSessionState? = null
                if (ownsInitialOnboardingRefresh(memberKey, epoch, generation)) {
                    beginLaunchConsentRecovery(
                        expectedEpoch = epoch,
                        memberKey = memberKey,
                        followUp = LaunchConsentFollowUp.Reconcile,
                        onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                    )
                }
                return authoritativeLocalAuth
            } catch (_: CompanionApiException.AccountConflict) {
                if (ownsInitialOnboardingRefresh(memberKey, epoch, generation)) {
                    publishAccountConflictFailure()
                }
                return null
            } catch (error: CompanionApiException) {
                if (
                    ownsInitialOnboardingRefresh(memberKey, epoch, generation) &&
                    isTerminalMemberBoundaryError(error)
                ) {
                    publishTerminalMemberBoundaryFailure(error)
                }
                return null
            } catch (_: Exception) {
                return null
            }
            if (!ownsInitialOnboardingRefresh(memberKey, epoch, generation)) return null
            if (response.status == InitialOnboardingStatus.Completed) {
                clearInitialOnboardingState()
            }
            // Pending refreshes are removal-only: never replace the mounted
            // projection or its unsaved draft.
            return null
        } finally {
            initialOnboardingMutex.unlock()
        }
    }

    private fun ownsInitialOnboardingRefresh(
        memberKey: String,
        epoch: Int,
        generation: Int,
    ): Boolean = ownsInitialOnboardingWork(memberKey, epoch) &&
        generation == initialOnboardingGeneration &&
        !_state.value.isInitialOnboardingSaving &&
        !_state.value.initialOnboardingCompletedNow

    private suspend fun refreshActiveLaunchConsentAfterForeground(
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        val pending = pendingLaunchConsentRecovery ?: return
        if (!revalidateLaunchConsentMember(pending)) return
        launchConsentMutex.withLock {
            if (ownsLaunchConsentRecovery(pending)) {
                loadLaunchConsentStatus(
                    pending,
                    onAuthoritativeLocalAuth = onAuthoritativeLocalAuth,
                )
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
                abandonLaunchConsentForAuthBoundary(pending)
                startMutex.withLock { enterSignedOut() }
                false
            }
            AuthSessionState.TemporarilyUnavailable -> {
                _state.update {
                    it.copy(
                        authVerifiedOnline = false,
                        healthStatusIsStale = healthWasRequested(),
                    )
                }
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't verify your session. Check your connection and try again.",
                )
                false
            }
            is AuthSessionState.SignedIn -> {
                if (
                    authState.memberKey != pending.memberKey &&
                    !authState.verifiedOnline
                ) {
                    _state.update {
                        it.copy(
                            authVerifiedOnline = false,
                            healthStatusIsStale = healthWasRequested(),
                        )
                    }
                    publishLaunchConsentLoadFailure(
                        pending,
                        "Murph couldn't verify the account change. Check your connection and try again.",
                    )
                    false
                } else if (authState.memberKey != pending.memberKey) {
                    abandonLaunchConsentForAuthBoundary(pending)
                    reconcile(force = true)
                    false
                } else if (!authState.verifiedOnline) {
                    _state.update {
                        it.copy(
                            authVerifiedOnline = false,
                            healthStatusIsStale = healthWasRequested(),
                        )
                    }
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
        foregroundGeneration += 1
        needsForegroundRefresh = true
    }

    private fun ownsForegroundRefresh(generation: Int): Boolean =
        generation == foregroundGeneration

    private fun ownsForegroundRefresh(claim: ForegroundRefreshClaim): Boolean =
        ownsForegroundRefresh(claim.generation) &&
            claim.sessionEpoch == sessionEpoch

    suspend fun signOut() = withContext(NonCancellable) {
        val expectedMemberKey = localState.memberKey
        val mountedMemberKey = currentMemberKey?.takeIf { it == expectedMemberKey }
        val privySignOutMemberKey = mountedMemberKey ?: run {
            val authState = try {
                auth.currentState()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AuthSessionState.TemporarilyUnavailable
            }
            if (authState == AuthSessionState.TemporarilyUnavailable) {
                _state.update {
                    it.copy(
                        phase = AppPhase.Failed(
                            message = "We couldn't verify which account to sign out. Check your connection and try again.",
                            canRetry = false,
                            canSignOut = true,
                        ),
                    )
                }
                return@withContext
            }
            (authState as? AuthSessionState.SignedIn)?.memberKey
        }
        if (
            !localState.beginSignOut(
                expectedMemberKey = expectedMemberKey,
                privySignOutMemberKey = privySignOutMemberKey,
            )
        ) {
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
        return resetHealthSdkAtTrustBoundary()
    }

    private suspend fun reconcileSignedIn(
        authState: AuthSessionState.SignedIn,
        foregroundClaim: ForegroundRefreshClaim? = null,
        canRetryLostHealthSession: Boolean = true,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) {
        if (isUnverifiedDifferentMemberCandidate(authState)) {
            val retainedConsentOwner =
                retainAcceptedConsentForUnverifiedCandidate(authState)
            if (!resetHealthSdkAtTrustBoundary(retainedConsentOwner)) return
            finishUnverifiedMemberCandidate(retainedConsentOwner)
            return
        }
        val previousMemberKey = localState.memberKey
        val shouldResumeHealth =
            previousMemberKey == authState.memberKey && healthWasRequested()
        val mustDistrustPersistedHealthSession =
            (previousMemberKey == null && health.isSignedIn()) ||
                (previousMemberKey != null && previousMemberKey != authState.memberKey)
        if (mustDistrustPersistedHealthSession) {
            if (!resetHealthSdkAtTrustBoundary(revokeAuthorization = true)) return
            clearInitialOnboardingState()
        }
        currentMemberKey = authState.memberKey
        var epoch = sessionEpoch
        val isAdmissionCandidate = localState.memberKey == null
        if (authState.verifiedOnline) {
            currentAuthOwnershipLoss(
                memberKey = authState.memberKey,
                allowUnboundMember = isAdmissionCandidate,
            )?.let { observed ->
                reconcileObservedAuthState(authState.memberKey, observed)
                return
            }
            if (
                !admitBackendMember(
                    memberKey = authState.memberKey,
                    epoch = epoch,
                    allowUnboundMember = isAdmissionCandidate,
                )
            ) {
                if (
                    isAdmissionCandidate &&
                    currentMemberKey == authState.memberKey &&
                    localState.memberKey == null &&
                    !hasActiveLaunchConsentRecovery()
                ) {
                    currentMemberKey = null
                }
                return
            }
            if (
                epoch != sessionEpoch ||
                authState.memberKey != currentMemberKey ||
                (
                    localState.memberKey != null &&
                        localState.memberKey != authState.memberKey
                    )
            ) return
            if (isAdmissionCandidate) {
                localState.memberKey = authState.memberKey
            }
        }
        val initialSetupStep = resolveInitialSetupStep()
        val requested = shouldResumeHealth
        if (
            !requested &&
            authState.verifiedOnline &&
            !verifyFreshBackendMemberStatus(epoch)
        ) return
        if (requested && authState.verifiedOnline) {
            val resumePreparation = try {
                prepareJunctionResume(
                    memberKey = authState.memberKey,
                    epoch = epoch,
                    foregroundClaim = foregroundClaim,
                    acceptedConsentOwner = acceptedConsentOwner,
                )
            } catch (error: CompanionApiException.ReconnectRequired) {
                if (epoch != sessionEpoch) return
                if (!localState.requireHealthReconnect()) {
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
                if (!resetHealthSdkAtTrustBoundary(acceptedConsentOwner)) return
                epoch = sessionEpoch
                null
            } catch (error: CompanionApiException.LocalAuthUnavailable) {
                if (epoch != sessionEpoch) return
                reconcileObservedAuthState(authState.memberKey, error.observedState)
                return
            } catch (_: CompanionApiException.AccountConflict) {
                if (epoch != sessionEpoch) return
                publishAccountConflictFailure()
                return
            } catch (error: CompanionApiException.ConsentRequired) {
                if (epoch != sessionEpoch) return
                var authoritativeLocalAuth: AuthSessionState? = null
                beginLaunchConsentRecovery(
                    expectedEpoch = epoch,
                    memberKey = authState.memberKey,
                    followUp = LaunchConsentFollowUp.Reconcile,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
                authoritativeLocalAuth?.let {
                    reconcileObservedAuthState(authState.memberKey, it)
                }
                return
            } catch (error: CompanionApiException) {
                if (epoch != sessionEpoch) return
                if (isTerminalMemberBoundaryError(error)) {
                    publishTerminalMemberBoundaryFailure(error)
                } else {
                    _state.update {
                        it.copy(
                            phase = AppPhase.Failed(
                                message = connectionErrorMessage(error),
                                canRetry = true,
                                canSignOut = true,
                            ),
                        )
                    }
                }
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
            when (resumePreparation) {
                null,
                JunctionIdentificationResult.Identified -> Unit
                JunctionIdentificationResult.OwnershipLost -> {
                    if (
                        foregroundClaim != null &&
                        !ownsForegroundRefresh(foregroundClaim) &&
                        ownsHealthResumeBoundary(
                            memberKey = authState.memberKey,
                            epoch = epoch,
                            acceptedConsentOwner = acceptedConsentOwner,
                        )
                    ) {
                        _state.update { current ->
                            current.copy(
                                phase = AppPhase.Ready,
                                authVerifiedOnline = false,
                                healthStatusIsStale = true,
                            )
                        }
                    }
                    return
                }
                is JunctionIdentificationResult.AuthLost -> {
                    reconcileObservedAuthState(authState.memberKey, resumePreparation.state)
                    return
                }
            }
        }

        if (authState.verifiedOnline) {
            currentAuthOwnershipLoss(authState.memberKey)?.let { observed ->
                reconcileObservedAuthState(authState.memberKey, observed)
                return
            }
            if (!fetchInitialOnboardingProjection(authState.memberKey, epoch)) return
        }
        val permissionStateVerified = when {
            !requested -> true
            !authState.verifiedOnline -> false
            else -> refreshHealthPermissionState()
        }
        if (requested && authState.verifiedOnline) {
            if (
                epoch != sessionEpoch ||
                authState.memberKey != currentMemberKey ||
                authState.memberKey != localState.memberKey
            ) return
        }
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery =
            permissionStateVerified && healthWasRequested() && grantedResourceCount == 0
        val reconnectRequired = localState.healthReconnectRequired
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                initialSetupStep = initialSetupStep,
                authVerifiedOnline = authState.verifiedOnline,
                healthAvailability = health.availability(),
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    deriveCachedHealthState()
                },
                healthStatusIsStale =
                    healthWasRequested() &&
                        (!authState.verifiedOnline || !permissionStateVerified),
                healthReconnectRequired = reconnectRequired,
                grantedResourceCount = grantedResourceCount,
                healthMessage = when {
                    reconnectRequired -> HEALTH_RECONNECT_REQUIRED_MESSAGE
                    needsPermissionRecovery -> HEALTH_PERMISSION_RECOVERY_MESSAGE
                    !permissionStateVerified && authState.verifiedOnline ->
                        HEALTH_PERMISSION_VERIFICATION_MESSAGE
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
            )
        }
        if (
            authState.verifiedOnline &&
            ownsAddressBookWork(authState.memberKey, epoch)
        ) {
            var deferredBoundary: DeferredSessionBoundary? = null
            reconcileAddressBookForeground(
                showBusy = false,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
            deferredBoundary?.let {
                handleDeferredSessionBoundaryWhileStartLocked(authState.memberKey, it)
                return
            }
        }
        if (
            healthWasRequested() &&
            authState.verifiedOnline &&
            permissionStateVerified &&
            grantedResourceCount > 0
        ) {
            var authoritativeLocalAuth: AuthSessionState? = null
            var deferredConsentRecovery: DeferredLaunchConsentRecovery? = null
            val needsHealthReconciliation =
                healthMutex.withLock {
                    syncAndRefresh(
                        epoch,
                        foregroundClaim,
                        onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                        onConsentRequired = { deferredConsentRecovery = it },
                    )
                }
            deferredConsentRecovery?.let { deferred ->
                beginLaunchConsentRecovery(
                    deferred,
                    onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
                )
            }
            authoritativeLocalAuth?.let {
                reconcileObservedAuthState(authState.memberKey, it)
                return
            }
            if (needsHealthReconciliation) {
                if (
                    !ownsVerifiedHealthWork(epoch, foregroundClaim) ||
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey
                ) {
                    return
                }
                if (canRetryLostHealthSession) {
                    _state.update { current ->
                        current.copy(phase = AppPhase.Launching, healthMessage = null)
                    }
                    reconcileSignedIn(
                        authState,
                        foregroundClaim,
                        canRetryLostHealthSession = false,
                        acceptedConsentOwner = acceptedConsentOwner,
                    )
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
        if (health.isSignedIn() || localState.memberKey != null || healthWasRequested()) {
            if (!resetHealthSdkAtTrustBoundary(revokeAuthorization = true)) return
        } else {
            invalidateSessionEpoch()
            localState.clearMemberScopedState()
        }
        finishSignedOut()
    }

    private fun finishSignedOut() {
        currentMemberKey = null
        clearInitialOnboardingState()
        _state.value = AppUiState(
            phase = AppPhase.NeedsLogin,
            healthAvailability = health.availability(),
            totalResourceCount = health.totalResourceCount,
        )
    }

    private fun finishChangedMemberAuthTransition() {
        currentMemberKey = null
        clearInitialOnboardingState()
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = "Your signed-in account changed. Try again to continue.",
                    canRetry = true,
                    canSignOut = true,
                ),
            )
        }
    }

    private fun finishUnverifiedMemberCandidate(
        retainedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) {
        currentMemberKey = retainedConsentOwner?.memberKey
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = "Murph couldn't verify the account change. Check your connection and try again.",
                    canRetry = true,
                    canSignOut = true,
                ),
                authVerifiedOnline = false,
                healthAvailability = health.availability(),
                isConnectingHealth = false,
                isSyncingHealth = false,
                isAddressBookBusy = false,
            )
        }
    }

    private suspend fun handleAuthoritativeLocalAuthObservation(
        observed: AuthSessionState,
        healthAlreadySignedOut: Boolean = false,
    ): Boolean {
        if (!isAuthoritativeLocalAuthObservation(observed)) return false
        val retainedConsentOwner = (observed as? AuthSessionState.SignedIn)?.let {
            retainAcceptedConsentForUnverifiedCandidate(it)
        }
        val revokeAuthorization = observed == AuthSessionState.SignedOut ||
            (
                observed is AuthSessionState.SignedIn &&
                    !isUnverifiedDifferentMemberCandidate(observed)
                )
        val resetSucceeded = if (revokeAuthorization) {
            resetMemberAtTrustBoundary(
                expectedMemberKey = localState.memberKey,
                acceptedConsentOwner = retainedConsentOwner,
                healthAlreadySignedOut = healthAlreadySignedOut,
            )
        } else {
            closeProductAuthorityForBoundary()
            if (healthAlreadySignedOut) {
                invalidateSessionEpoch(retainedConsentOwner)
                true
            } else {
                resetHealthSdkAtTrustBoundary(retainedConsentOwner)
            }
        }
        if (!resetSucceeded) return true
        if (observed == AuthSessionState.SignedOut) {
            finishSignedOut()
        } else if (
            observed is AuthSessionState.SignedIn &&
            isUnverifiedDifferentMemberCandidate(observed)
        ) {
            finishUnverifiedMemberCandidate(retainedConsentOwner)
        } else {
            finishChangedMemberAuthTransition()
            reconcile(force = true)
        }
        return true
    }

    private suspend fun handleDeferredSessionBoundary(
        boundary: DeferredSessionBoundary,
    ) {
        when (boundary) {
            is DeferredSessionBoundary.LocalAuth ->
                handleAuthoritativeLocalAuthObservation(boundary.observedState)
            is DeferredSessionBoundary.BackendRejected ->
                publishTerminalMemberBoundaryFailure(boundary.error)
            DeferredSessionBoundary.AccountConflict -> publishAccountConflictFailure()
        }
    }

    /** Called only while [startMutex] is held. */
    private suspend fun handleDeferredSessionBoundaryWhileStartLocked(
        expectedMemberKey: String,
        boundary: DeferredSessionBoundary,
    ) {
        when (boundary) {
            is DeferredSessionBoundary.LocalAuth -> {
                if (isAuthoritativeLocalAuthObservation(boundary.observedState)) {
                    closeProductAuthorityForBoundary()
                }
                reconcileObservedAuthState(expectedMemberKey, boundary.observedState)
            }
            is DeferredSessionBoundary.BackendRejected ->
                publishTerminalMemberBoundaryFailure(boundary.error)
            DeferredSessionBoundary.AccountConflict -> publishAccountConflictFailure()
        }
    }

    private fun isAuthoritativeLocalAuthObservation(
        observed: AuthSessionState,
    ): Boolean = observed == AuthSessionState.SignedOut ||
        (
            observed is AuthSessionState.SignedIn &&
                (
                    observed.memberKey != currentMemberKey ||
                    observed.memberKey != localState.memberKey
                    )
            )

    private fun isAuthoritativeLaunchConsentAuthObservation(
        pending: PendingLaunchConsentRecovery,
        observed: AuthSessionState,
    ): Boolean = observed == AuthSessionState.SignedOut ||
        (
            observed is AuthSessionState.SignedIn &&
                observed.verifiedOnline &&
                observed.memberKey != pending.memberKey
            )

    private fun abandonLaunchConsentForAuthBoundary(
        pending: PendingLaunchConsentRecovery,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        val clearsAdmissionCandidate =
            pending.memberOwnership == LaunchConsentMemberOwnership.AdmissionCandidate &&
                localState.memberKey == null &&
                currentMemberKey == pending.memberKey
        invalidateSessionEpoch()
        if (clearsAdmissionCandidate) currentMemberKey = null
        _state.update { current ->
            current.copy(
                phase = AppPhase.Launching,
                authVerifiedOnline = false,
                healthMessage = null,
            )
        }
    }

    private fun isUnverifiedDifferentMemberCandidate(
        observed: AuthSessionState.SignedIn,
    ): Boolean = !observed.verifiedOnline && observed.memberKey != localState.memberKey

    private fun retainAcceptedConsentForUnverifiedCandidate(
        observed: AuthSessionState.SignedIn,
    ): PendingLaunchConsentRecovery? {
        if (!isUnverifiedDifferentMemberCandidate(observed)) return null
        val pending = pendingLaunchConsentRecovery ?: return null
        val retained = synchronized(pending) {
            if (!ownsAcceptedConsentContinuation(pending)) {
                false
            } else {
                pending.continuationInProgress = false
                if (
                    pending.continuationStage == LaunchConsentContinuationStage.Dispatch &&
                    pending.followUp.healthRestoreOrder() ==
                    LaunchConsentHealthRestoreOrder.Before &&
                    healthWasRequested()
                ) {
                    pending.continuationStage = LaunchConsentContinuationStage.RestoreBefore
                }
                true
            }
        }
        if (!retained) return null
        publishLaunchConsentLoadFailure(
            pending,
            "Murph couldn't verify the account change. Check your connection and try again.",
        )
        return pending
    }

    private fun restoreOfflineIfPossible() {
        val memberKey = localState.memberKey
        if (memberKey == null) {
            _state.update {
                it.copy(
                    phase = AppPhase.Failed(
                        message = "Murph couldn't check your saved sign-in. Check your connection and try again.",
                    ),
                    authVerifiedOnline = false,
                )
            }
            return
        }
        currentMemberKey = memberKey
        val grantedResourceCount = health.grantedResourceCount()
        val needsPermissionRecovery = healthWasRequested() && grantedResourceCount == 0
        val initialSetupStep = resolveInitialSetupStep()
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                initialSetupStep = initialSetupStep,
                authVerifiedOnline = false,
                healthSync = if (needsPermissionRecovery) {
                    HealthSyncState.NotConnected
                } else {
                    deriveCachedHealthState()
                },
                healthStatusIsStale = healthWasRequested(),
                healthReconnectRequired = localState.healthReconnectRequired,
                grantedResourceCount = grantedResourceCount,
                healthMessage = when {
                    localState.healthReconnectRequired ->
                        "$HEALTH_RECONNECT_REQUIRED_MESSAGE Connect when you're back online."
                    needsPermissionRecovery -> HEALTH_PERMISSION_RECOVERY_MESSAGE
                    else -> "You're offline. Saved sync status is shown until Murph reconnects."
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
            )
        }
    }

    private suspend fun reconcileForegroundAuth(
        foregroundClaim: ForegroundRefreshClaim,
    ): Boolean {
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        if (!ownsForegroundRefresh(foregroundClaim)) return false
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
                        healthStatusIsStale = healthWasRequested(),
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
                            healthStatusIsStale = healthWasRequested(),
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
                } else if (_state.value.phase == AppPhase.Launching) {
                    startMutex.withLock {
                        // Wait for the existing reconciliation owner without
                        // scheduling another backend bootstrap.
                    }
                    ownsForegroundRefresh(foregroundClaim) &&
                        _state.value.phase == AppPhase.Ready &&
                        _state.value.authVerifiedOnline &&
                        authState.memberKey == currentMemberKey &&
                        authState.memberKey == localState.memberKey
                } else if (
                    !_state.value.authVerifiedOnline
                ) {
                    reconcile(force = true, foregroundClaim)
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
        ownsIdentity: () -> Boolean,
    ): JunctionIdentificationResult {
        if (epoch != sessionEpoch) throw CancellationException()
        if (!ownsIdentity()) return JunctionIdentificationResult.OwnershipLost
        val response = api.createJunctionSignInToken(
            memberKey = memberKey,
            request = signInTokenRequest(intent),
        )
        if (epoch != sessionEpoch) throw CancellationException()
        if (!ownsIdentity()) return JunctionIdentificationResult.OwnershipLost
        if (response.environment != config.environment.wireValue) {
            throw CompanionApiException.InvalidResponse
        }
        _state.update { current ->
            current.copy(
                backendEnvironment = response.environment,
            )
        }
        currentAuthOwnershipLoss(memberKey)?.let {
            return JunctionIdentificationResult.AuthLost(it)
        }
        if (!ownsIdentity()) return JunctionIdentificationResult.OwnershipLost
        health.identify(memberKey = memberKey) {
            if (!ownsIdentity()) throw CancellationException()
            response.signInToken
        }
        return if (ownsIdentity()) {
            JunctionIdentificationResult.Identified
        } else {
            JunctionIdentificationResult.OwnershipLost
        }
    }

    private suspend fun prepareJunctionResume(
        memberKey: String,
        epoch: Int,
        foregroundClaim: ForegroundRefreshClaim?,
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): JunctionIdentificationResult = healthMutex.withLock {
        val ownsIdentity = {
            ownsHealthResumePreparation(
                memberKey = memberKey,
                epoch = epoch,
                foregroundClaim = foregroundClaim,
                acceptedConsentOwner = acceptedConsentOwner,
            )
        }
        if (!ownsIdentity()) return@withLock JunctionIdentificationResult.OwnershipLost
        currentAuthOwnershipLoss(memberKey)?.let {
            return@withLock JunctionIdentificationResult.AuthLost(it)
        }
        if (!ownsIdentity()) return@withLock JunctionIdentificationResult.OwnershipLost
        val identification = identifyJunction(
            memberKey = memberKey,
            intent = ConnectionIntent.Resume,
            epoch = epoch,
            ownsIdentity = ownsIdentity,
        )
        if (identification != JunctionIdentificationResult.Identified) {
            return@withLock identification
        }
        currentAuthOwnershipLoss(memberKey)?.let {
            return@withLock JunctionIdentificationResult.AuthLost(it)
        }
        if (!ownsIdentity()) return@withLock JunctionIdentificationResult.OwnershipLost
        health.configure()
        JunctionIdentificationResult.Identified
    }

    private suspend fun syncAndRefresh(
        epoch: Int,
        foregroundClaim: ForegroundRefreshClaim? = null,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
        onConsentRequired: (DeferredLaunchConsentRecovery) -> Unit,
    ): Boolean {
        if (!ownsVerifiedHealthWork(epoch, foregroundClaim)) return false
        if (
            foregroundClaim != null &&
            lastCompletedValidatedHealthSyncSequence >
            foregroundClaim.healthSyncSequenceAtEntry
        ) return false
        _state.update { it.copy(isSyncingHealth = true, healthMessage = null) }
        try {
            if (
                fetchValidatedHealthStatus(
                    epoch,
                    LaunchConsentFollowUp.SyncHealth,
                    onAuthoritativeLocalAuth,
                    onConsentRequired,
                ) == null
            ) return false
            if (!ownsVerifiedHealthWork(epoch, foregroundClaim)) return false
            if (health.grantedResourceCount() == 0) {
                publishPermissionAwareHealthState(
                    status = cachedHealthStatus(),
                    message = _state.value.healthMessage,
                )
                return false
            }
            if (!health.isSignedIn()) return true
            lastStartedHealthSyncSequence += 1
            val syncSequence = lastStartedHealthSyncSequence
            var syncSucceeded = false
            try {
                health.syncAllGrantedResources()
                syncSucceeded = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!ownsVerifiedHealthWork(epoch, foregroundClaim)) return false
                if (!health.isSignedIn()) return true
                // Status refresh below still reports the last backend-confirmed receipt.
            }
            if (!ownsVerifiedHealthWork(epoch, foregroundClaim)) return false
            if (!health.isSignedIn()) return true
            if (
                fetchValidatedHealthStatus(
                    epoch,
                    LaunchConsentFollowUp.SyncHealth,
                    onAuthoritativeLocalAuth,
                    onConsentRequired,
                ) == null
            ) return false
            if (!ownsVerifiedHealthWork(epoch, foregroundClaim)) return false
            if (!health.isSignedIn()) return true
            if (syncSucceeded) {
                lastCompletedValidatedHealthSyncSequence = syncSequence
            }
            return false
        } finally {
            _state.update { it.copy(isSyncingHealth = false) }
        }
    }

    private fun ownsVerifiedHealthWork(
        epoch: Int,
        foregroundClaim: ForegroundRefreshClaim? = null,
    ): Boolean =
        (foregroundClaim == null || ownsForegroundRefresh(foregroundClaim)) &&
            epoch == sessionEpoch &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline

    private fun ownsHealthResumePreparation(
        memberKey: String,
        epoch: Int,
        foregroundClaim: ForegroundRefreshClaim?,
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): Boolean =
        (foregroundClaim == null || ownsForegroundRefresh(foregroundClaim)) &&
            ownsHealthResumeBoundary(memberKey, epoch, acceptedConsentOwner)

    private fun ownsHealthResumeBoundary(
        memberKey: String,
        epoch: Int,
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): Boolean =
        epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            healthWasRequested() &&
            pendingHealthConnection == null &&
            _state.value.phase == AppPhase.Launching &&
            !_state.value.isConnectingHealth &&
            !_state.value.isSyncingHealth &&
            !localState.signOutPending &&
            allowsLaunchConsentWork(acceptedConsentOwner)

    /** Called only while [startMutex] is held. */
    private suspend fun finishPendingSignOut() {
        if (!localState.signOutPending) return
        val expectedMemberKey = localState.memberKey
        val privySignOutMemberKey =
            localState.pendingPrivySignOutMemberKey ?: expectedMemberKey
        invalidateSessionEpoch()
        _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
        try {
            signOutHealthSdkAfterDrainingWork()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPendingSignOutFailure(
                "We couldn't safely reset health sync. Keep Murph open and try again.",
            )
            return
        }
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        if (authState == AuthSessionState.TemporarilyUnavailable) {
            publishPendingSignOutFailure(
                "We couldn't verify which account is signed in. Check your connection and try again.",
            )
            return
        }
        if (
            authState is AuthSessionState.SignedIn &&
            authState.memberKey == privySignOutMemberKey
        ) {
            try {
                auth.signOut()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publishPendingSignOutFailure("We couldn't finish signing out. Try once more.")
                return
            }
        }
        if (!localState.completeSignOut(expectedMemberKey)) {
            publishPendingSignOutFailure(
                "We couldn't safely finish signing out. Keep Murph open and try again.",
            )
            return
        }
        currentMemberKey = null
        if (
            authState is AuthSessionState.SignedIn &&
            authState.memberKey != privySignOutMemberKey
        ) {
            clearInitialOnboardingState()
            reconcileSignedIn(authState)
            return
        }
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
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
        onConsentRequired: (DeferredLaunchConsentRecovery) -> Unit,
    ): CompanionSyncStatus? {
        if (epoch != sessionEpoch || _state.value.phase != AppPhase.Ready) return null
        val authState = try {
            auth.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AuthSessionState.TemporarilyUnavailable
        }
        if (!hasVerifiedHealthAuthWhileLocked(authState)) return null

        if (epoch != sessionEpoch || _state.value.phase != AppPhase.Ready) return null
        val memberKey = currentMemberKey ?: return null
        val status = try {
            api.fetchSyncStatus(memberKey, HEALTH_CONNECT_SOURCE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (hasVerifiedHealthAuthWhileLocked(error.observedState)) {
                publishReadOnlyHealthState(
                    message = "You're offline. Saved sync status is shown until Murph reconnects.",
                )
            }
            return null
        } catch (_: CompanionApiException.AccountConflict) {
            publishAccountConflictFailureWhileHealthLocked()
            return null
        } catch (error: CompanionApiException.ConsentRequired) {
            onConsentRequired(
                DeferredLaunchConsentRecovery(
                    expectedEpoch = epoch,
                    memberKey = memberKey,
                    followUp = consentFollowUp,
                ),
            )
            return null
        } catch (error: CompanionApiException) {
            if (isTerminalMemberBoundaryError(error)) {
                failCurrentSessionWhileHealthLocked(
                    message = terminalMemberBoundaryMessage(error),
                    canRetry = false,
                    signOutLabel = terminalMemberBoundarySignOutLabel(error),
                    supplementalActions = FailureSupplementalActions.Support,
                    revokeAuthorization = true,
                )
            } else {
                publishPermissionAwareHealthState(
                    status = cachedHealthStatus(),
                    message = "Murph couldn't verify your account. Saved status is still shown.",
                    healthStatusIsStale = true,
                    clearSyncing = true,
                )
            }
            return null
        } catch (_: Exception) {
            publishPermissionAwareHealthState(
                status = cachedHealthStatus(),
                message = "Murph couldn't verify your account. Saved status is still shown.",
                healthStatusIsStale = true,
                clearSyncing = true,
            )
            return null
        }
        if (epoch != sessionEpoch) return null
        val receiptFloorAt = healthReceiptFloorAt()
        val qualifyingReceipt = status.lastDataReceivedAt?.takeIf { receivedAt ->
            receiptFloorAt == null || receivedAt.isAfter(receiptFloorAt)
        }
        localState.lastKnownStatusObservedAt = InstantValue(status.observedAt.toEpochMilli())
        localState.lastKnownDataReceivedAt = qualifyingReceipt?.let {
            InstantValue(it.toEpochMilli())
        }
        publishPermissionAwareHealthState(
            status = status.copy(lastDataReceivedAt = qualifyingReceipt),
            message = null,
            healthStatusIsStale = false,
        )
        return status
    }

    /** Called only while [healthMutex] is held. */
    private suspend fun hasVerifiedHealthAuthWhileLocked(
        authState: AuthSessionState,
    ): Boolean = when (authState) {
        AuthSessionState.SignedOut -> {
            if (
                failCurrentSessionWhileHealthLocked(
                    message = "Your session needs a refresh. Sign in again.",
                    canRetry = false,
                    revokeAuthorization = true,
                )
            ) {
                finishSignedOut()
            }
            false
        }
        AuthSessionState.TemporarilyUnavailable -> {
            publishReadOnlyHealthState(
                message = "You're offline. Saved sync status is shown until Murph reconnects.",
            )
            false
        }
        is AuthSessionState.SignedIn -> {
            if (isUnverifiedDifferentMemberCandidate(authState)) {
                val retainedConsentOwner =
                    retainAcceptedConsentForUnverifiedCandidate(authState)
                if (
                    failCurrentSessionWhileHealthLocked(
                        message =
                            "Murph couldn't verify the account change. Check your connection and try again.",
                        canRetry = true,
                        retainedConsentOwner = retainedConsentOwner,
                    )
                ) {
                    finishUnverifiedMemberCandidate(retainedConsentOwner)
                }
                false
            } else if (
                authState.memberKey != currentMemberKey ||
                authState.memberKey != localState.memberKey
            ) {
                if (
                    failCurrentSessionWhileHealthLocked(
                        message = "Your signed-in account changed. Try again to continue.",
                        canRetry = true,
                        revokeAuthorization = true,
                    )
                ) {
                    finishChangedMemberAuthTransition()
                }
                false
            } else if (!authState.verifiedOnline) {
                publishReadOnlyHealthState(
                    message = "You're offline. Saved sync status is shown until Murph reconnects.",
                )
                false
            } else {
                true
            }
        }
    }

    private fun publishReadOnlyHealthState(message: String) {
        publishPermissionAwareHealthState(
            status = cachedHealthStatus(),
            message = message,
            authVerifiedOnline = false,
            healthStatusIsStale = true,
            clearSyncing = true,
        )
    }

    private suspend fun refreshHealthPermissionState(): Boolean = try {
        health.refreshPermissionState()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun publishHealthPermissionVerificationFailure(
        availability: HealthConnectAvailability = health.availability(),
    ) {
        val requested = healthWasRequested()
        _state.update { current ->
            current.copy(
                healthAvailability = availability,
                healthStatusIsStale = if (requested) true else current.healthStatusIsStale,
                healthMessage = if (requested) {
                    HEALTH_PERMISSION_VERIFICATION_MESSAGE
                } else {
                    current.healthMessage
                },
            )
        }
    }

    private fun publishPermissionAwareHealthState(
        status: CompanionSyncStatus?,
        message: String?,
        authVerifiedOnline: Boolean? = null,
        healthStatusIsStale: Boolean? = null,
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
                    )
                },
                healthStatusObservedAt = status?.observedAt,
                healthStatusIsStale = healthStatusIsStale ?: current.healthStatusIsStale,
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
        signOutLabel: String = "Sign out and start fresh",
        supplementalActions: FailureSupplementalActions =
            FailureSupplementalActions.AccountAndLegal,
        retainedConsentOwner: PendingLaunchConsentRecovery? = null,
        revokeAuthorization: Boolean = false,
    ): Boolean {
        val resetSucceeded = if (revokeAuthorization) {
            resetMemberAtTrustBoundary(
                expectedMemberKey = localState.memberKey,
                acceptedConsentOwner = retainedConsentOwner,
                healthMutexHeld = true,
            )
        } else {
            closeProductAuthorityForBoundary()
            invalidateSessionEpoch(retainedConsentOwner)
            try {
                health.signOutSdk()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
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
                    canRetry = if (resetSucceeded) canRetry else true,
                    canSignOut = true,
                    signOutLabel = signOutLabel,
                    supplementalActions = if (resetSucceeded) {
                        supplementalActions
                    } else {
                        FailureSupplementalActions.AccountAndLegal
                    },
                ),
            )
        }
        return resetSucceeded
    }

    private suspend fun verifyFreshBackendMemberStatus(epoch: Int): Boolean {
        val memberKey = currentMemberKey ?: return false
        return try {
            api.fetchSyncStatus(memberKey, HEALTH_CONNECT_SOURCE)
            epoch == sessionEpoch
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (epoch != sessionEpoch) return false
            reconcileObservedAuthState(memberKey, error.observedState)
            false
        } catch (error: CompanionApiException.AccountConflict) {
            if (epoch != sessionEpoch) return false
            publishAccountConflictFailure()
            false
        } catch (error: CompanionApiException.ConsentRequired) {
            if (epoch != sessionEpoch) return false
            val memberKey = currentMemberKey ?: return false
            var authoritativeLocalAuth: AuthSessionState? = null
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = LaunchConsentFollowUp.Reconcile,
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
            )
            authoritativeLocalAuth?.let { reconcileObservedAuthState(memberKey, it) }
            false
        } catch (error: CompanionApiException) {
            if (epoch != sessionEpoch) return false
            if (isTerminalMemberBoundaryError(error)) {
                publishTerminalMemberBoundaryFailure(error)
            } else {
                publishBackendBootstrapFailure(
                    message = "Murph couldn't verify your account. Check your connection and try again.",
                    canRetry = true,
                )
            }
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

    private suspend fun fetchInitialOnboardingProjection(
        memberKey: String,
        epoch: Int,
    ): Boolean {
        if (
            epoch != sessionEpoch ||
            memberKey != currentMemberKey ||
            memberKey != localState.memberKey
        ) return false
        val projection = try {
            api.fetchInitialOnboarding(memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (epoch != sessionEpoch) return false
            reconcileObservedAuthState(memberKey, error.observedState)
            return false
        } catch (_: CompanionApiException.ConsentRequired) {
            if (epoch != sessionEpoch) return false
            var authoritativeLocalAuth: AuthSessionState? = null
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = LaunchConsentFollowUp.Reconcile,
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
            )
            authoritativeLocalAuth?.let { reconcileObservedAuthState(memberKey, it) }
            return false
        } catch (_: CompanionApiException.AccountConflict) {
            if (epoch != sessionEpoch) return false
            publishAccountConflictFailure()
            return false
        } catch (error: CompanionApiException) {
            if (epoch != sessionEpoch) return false
            if (isTerminalMemberBoundaryError(error)) {
                publishTerminalMemberBoundaryFailure(error)
            } else {
                publishBackendBootstrapFailure(
                    message = "We couldn't load account setup. Check your connection and try again.",
                    canRetry = true,
                )
            }
            return false
        } catch (_: Exception) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "We couldn't load account setup. Check your connection and try again.",
                canRetry = true,
            )
            return false
        }
        if (
            epoch != sessionEpoch ||
            memberKey != currentMemberKey ||
            memberKey != localState.memberKey
        ) return false
        applyInitialOnboardingProjection(projection)
        return true
    }

    private fun applyInitialOnboardingProjection(projection: InitialOnboarding) {
        if (projection.status == InitialOnboardingStatus.Completed) {
            clearInitialOnboardingState()
            return
        }
        val catalog = projection.catalog ?: return
        val current = _state.value
        if (
            current.initialOnboarding?.status == InitialOnboardingStatus.Pending &&
            current.initialOnboardingDraft != null
        ) {
            // A retry after consent keeps the exact ephemeral draft. The
            // canonical server is still authoritative for completion only.
            _state.update { it.copy(initialOnboardingMessage = null) }
            return
        }
        val personaParts = resolveInitialOnboardingPersonaParts(
            projection.preferences.persona,
            catalog.personas.map { it.id },
        )
        val mainPersona = catalog.personas.firstOrNull { it.id == personaParts.first }
            ?: catalog.personas.first()
        val voiceId = projection.preferences.voice
            ?.takeIf { selected -> catalog.voices.any { it.id == selected } }
            ?: mainPersona.defaultVoiceId
        val toneId = projection.preferences.tone
            ?.takeIf { selected -> catalog.tones.any { it.id == selected } }
            ?: mainPersona.defaultTone
        initialOnboardingGeneration += 1
        pendingInitialOnboardingContactCardHandoff = null
        _state.update {
            it.copy(
                initialOnboarding = projection,
                initialOnboardingStage = if (projection.contactCard != null) {
                    InitialOnboardingStage.Contact
                } else {
                    InitialOnboardingStage.MainPersona
                },
                initialOnboardingDraft = InitialOnboardingDraft(
                    avatarId = projection.contactCard?.defaultAvatarId,
                    mainPersonaId = mainPersona.id,
                    supportingPersonaId = personaParts.second,
                    voiceId = voiceId,
                    toneId = toneId,
                ),
                isInitialOnboardingSaving = false,
                initialOnboardingCompletedNow = false,
                initialOnboardingMessage = null,
                initialOnboardingContactCardHandoff = null,
            )
        }
    }

    private fun resolveInitialOnboardingPersonaParts(
        value: String?,
        personaIds: List<String>,
    ): Pair<String?, String?> {
        if (value == null) return null to null
        if (value in personaIds) return value to null
        personaIds.forEach { main ->
            val prefix = "$main-with-"
            if (value.startsWith(prefix)) {
                val supporting = value.removePrefix(prefix)
                if (supporting != main && supporting in personaIds) return main to supporting
            }
        }
        return null to null
    }

    private fun clearInitialOnboardingState() {
        initialOnboardingGeneration += 1
        pendingInitialOnboardingContactCardHandoff = null
        _state.update {
            it.copy(
                initialOnboarding = null,
                initialOnboardingStage = null,
                initialOnboardingDraft = null,
                isInitialOnboardingSaving = false,
                initialOnboardingCompletedNow = false,
                initialOnboardingMessage = null,
                initialOnboardingContactCardHandoff = null,
            )
        }
    }

    private fun ownsInitialOnboardingWork(memberKey: String, epoch: Int): Boolean =
        epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            _state.value.initialOnboarding != null &&
            !localState.signOutPending

    private fun ownsInitialOnboardingRequest(
        memberKey: String,
        epoch: Int,
        generation: Int,
    ): Boolean = ownsInitialOnboardingWork(memberKey, epoch) &&
        generation == initialOnboardingGeneration

    private fun ownsInitialOnboardingContactCardHandoff(
        pending: PendingInitialOnboardingContactCardHandoffRequest,
    ): Boolean =
        pendingInitialOnboardingContactCardHandoff === pending &&
            _state.value.initialOnboardingContactCardHandoff?.id == pending.id &&
            ownsInitialOnboardingRequest(
                pending.memberKey,
                pending.epoch,
                pending.generation,
            )

    private fun ownsInitialOnboardingContactCardAuth(
        pending: PendingInitialOnboardingContactCardHandoffRequest,
        observed: AuthSessionState,
    ): Boolean =
        observed is AuthSessionState.SignedIn &&
            observed.verifiedOnline &&
            observed.memberKey == pending.memberKey &&
            ownsInitialOnboardingContactCardHandoff(pending)

    private suspend fun currentAuthStateForHandoff(): AuthSessionState = try {
        auth.currentState()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AuthSessionState.TemporarilyUnavailable
    }

    private fun rejectInitialOnboardingContactCardAuth(
        pending: PendingInitialOnboardingContactCardHandoffRequest,
        observed: AuthSessionState,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        clearInitialOnboardingContactCardHandoff(pending)
        if (isAuthoritativeLocalAuthObservation(observed)) {
            onAuthoritativeLocalAuth(observed)
        } else {
            publishInitialOnboardingFailure(
                "We couldn't verify your session. Check your connection and try again.",
            )
        }
    }

    private fun clearInitialOnboardingContactCardHandoff(
        pending: PendingInitialOnboardingContactCardHandoffRequest,
    ) {
        if (pendingInitialOnboardingContactCardHandoff !== pending) return
        pendingInitialOnboardingContactCardHandoff = null
        _state.update { current ->
            if (current.initialOnboardingContactCardHandoff?.id != pending.id) {
                current
            } else {
                current.copy(
                    isInitialOnboardingSaving = false,
                    initialOnboardingContactCardHandoff = null,
                )
            }
        }
    }

    private fun releaseInitialOnboardingBusy(generation: Int) {
        if (generation != initialOnboardingGeneration) return
        _state.update { current ->
            if (generation == initialOnboardingGeneration && current.isInitialOnboardingSaving) {
                current.copy(isInitialOnboardingSaving = false)
            } else {
                current
            }
        }
    }

    private fun prioritizeStaleInitialOnboardingConsent(
        memberKey: String,
        followUp: LaunchConsentFollowUp,
    ) {
        if (
            memberKey != currentMemberKey ||
            memberKey != localState.memberKey ||
            localState.signOutPending
        ) return
        prioritizeActiveLaunchConsentFollowUp(followUp)
    }

    private fun publishInitialOnboardingFailure(message: String) {
        _state.update {
            it.copy(isInitialOnboardingSaving = false, initialOnboardingMessage = message)
        }
    }

    private suspend fun admitBackendMember(
        memberKey: String,
        epoch: Int,
        allowUnboundMember: Boolean,
    ): Boolean {
        return try {
            api.admitCompanion(memberKey, ZoneId.systemDefault().id)
            if (epoch != sessionEpoch) return false
            currentAuthOwnershipLoss(
                memberKey = memberKey,
                allowUnboundMember = allowUnboundMember,
            )?.let { observed ->
                reconcileObservedAuthState(memberKey, observed)
                return false
            }
            true
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (epoch != sessionEpoch) return false
            reconcileObservedAuthState(memberKey, error.observedState)
            false
        } catch (_: CompanionApiException.ConsentRequired) {
            if (epoch != sessionEpoch) return false
            var authoritativeLocalAuth: AuthSessionState? = null
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = LaunchConsentFollowUp.Reconcile,
                memberOwnership = if (allowUnboundMember) {
                    LaunchConsentMemberOwnership.AdmissionCandidate
                } else {
                    LaunchConsentMemberOwnership.Bound
                },
                onAuthoritativeLocalAuth = { authoritativeLocalAuth = it },
            )
            authoritativeLocalAuth?.let { reconcileObservedAuthState(memberKey, it) }
            false
        } catch (_: CompanionApiException.AccountConflict) {
            if (epoch != sessionEpoch) return false
            publishAccountConflictFailure()
            false
        } catch (_: CompanionApiException.Unauthorized) {
            if (epoch != sessionEpoch) return false
            publishTerminalAdmissionFailure(
                message = "Your session needs a refresh. Sign in again.",
            )
            false
        } catch (_: CompanionApiException.NoAccount) {
            if (epoch != sessionEpoch) return false
            publishTerminalAdmissionFailure(
                message = "This sign-in isn't linked to an active Murph account.",
                signOutLabel = "Try a different sign-in",
            )
            false
        } catch (_: CompanionApiException.AccessRequired) {
            if (epoch != sessionEpoch) return false
            publishTerminalAdmissionFailure(
                message = terminalMemberBoundaryMessage(CompanionApiException.AccessRequired),
                signOutLabel = "Try a different sign-in",
            )
            false
        } catch (_: CompanionApiException.MemberSuspended) {
            if (epoch != sessionEpoch) return false
            publishTerminalAdmissionFailure(
                message =
                    "This Murph account is paused. Try a different sign-in or contact Murph support.",
                signOutLabel = "Try a different sign-in",
            )
            false
        } catch (_: CompanionApiException.AdmissionRetryable) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "Murph account setup is temporarily unavailable. Try again.",
                canRetry = true,
                supplementalActions = FailureSupplementalActions.Support,
            )
            false
        } catch (_: CompanionApiException.AdmissionSupportRequired) {
            if (epoch != sessionEpoch) return false
            publishTerminalAdmissionFailure(
                message =
                    "Murph support needs to finish setting up this account. Try a different sign-in or contact support.",
                signOutLabel = "Try a different sign-in",
            )
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (epoch != sessionEpoch) return false
            publishBackendBootstrapFailure(
                message = "Murph couldn't finish account setup. Check your connection and try again.",
                canRetry = true,
                supplementalActions = FailureSupplementalActions.Support,
            )
            false
        }
    }

    private suspend fun publishAccountConflictFailure() {
        closeProductAuthorityForBoundary()
        if (!resetHealthSdkAtTrustBoundary(revokeAuthorization = true)) return
        finishAccountConflictFailure()
    }

    /** Called only while [healthMutex] is held. */
    private suspend fun publishAccountConflictFailureWhileHealthLocked() {
        if (
            !resetMemberAtTrustBoundary(
                expectedMemberKey = localState.memberKey,
                healthMutexHeld = true,
            )
        ) return
        finishAccountConflictFailure()
    }

    private fun finishAccountConflictFailure() {
        currentMemberKey = null
        clearInitialOnboardingState()
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = "This sign-in conflicts with another Murph account. Try a different sign-in.",
                    canRetry = false,
                    canSignOut = true,
                    signOutLabel = "Try a different sign-in",
                    supplementalActions = FailureSupplementalActions.Support,
                ),
            )
        }
    }

    private fun publishBackendBootstrapFailure(
        message: String,
        canRetry: Boolean,
        signOutLabel: String = "Sign out and start fresh",
        supplementalActions: FailureSupplementalActions =
            FailureSupplementalActions.AccountAndLegal,
    ) {
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                    signOutLabel = signOutLabel,
                    supplementalActions = supplementalActions,
                ),
            )
        }
    }

    private suspend fun publishTerminalAdmissionFailure(
        message: String,
        signOutLabel: String = "Sign out and start fresh",
    ) {
        closeProductAuthorityForBoundary()
        if (!resetHealthSdkAtTrustBoundary(revokeAuthorization = true)) return
        publishBackendBootstrapFailure(
            message = message,
            canRetry = false,
            signOutLabel = signOutLabel,
            supplementalActions = FailureSupplementalActions.Support,
        )
    }

    private suspend fun publishAuthoritativeResumeFailure(
        message: String,
        canRetry: Boolean,
        signOutLabel: String = "Sign out and start fresh",
    ) {
        closeProductAuthorityForBoundary()
        if (!resetHealthSdkAtTrustBoundary(revokeAuthorization = true)) return
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                    signOutLabel = signOutLabel,
                    supplementalActions = FailureSupplementalActions.Support,
                ),
            )
        }
    }

    private suspend fun publishTerminalMemberBoundaryFailure(
        error: CompanionApiException,
    ) {
        publishAuthoritativeResumeFailure(
            message = terminalMemberBoundaryMessage(error),
            canRetry = false,
            signOutLabel = terminalMemberBoundarySignOutLabel(error),
        )
    }

    private fun closeProductAuthorityForBoundary() {
        _state.update { current ->
            current.copy(
                phase = AppPhase.Launching,
                authVerifiedOnline = false,
                isAddressBookBusy = false,
            )
        }
    }

    private suspend fun currentAuthOwnershipLoss(
        memberKey: String,
        allowUnboundMember: Boolean = false,
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
                (
                    memberKey == localState.memberKey ||
                        (allowUnboundMember && localState.memberKey == null)
                    ) &&
                !localState.signOutPending
        if (ownsMember) return null
        _state.update { current ->
            current.copy(
                authVerifiedOnline = false,
                healthStatusIsStale = healthWasRequested(),
            )
        }
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
                } else if (isUnverifiedDifferentMemberCandidate(observed)) {
                    reconcileSignedIn(observed)
                } else {
                    abandonUnboundMemberCandidateForAuthBoundary(expectedMemberKey)
                    reconcileSignedIn(observed)
                }
            }
        }
    }

    private fun abandonUnboundMemberCandidateForAuthBoundary(
        expectedMemberKey: String,
    ) {
        if (
            localState.memberKey != null ||
            currentMemberKey != expectedMemberKey
        ) return
        val pending = pendingLaunchConsentRecovery
        if (pending != null && ownsLaunchConsentRecovery(pending)) {
            abandonLaunchConsentForAuthBoundary(pending)
            return
        }
        invalidateSessionEpoch()
        currentMemberKey = null
        _state.update { current ->
            current.copy(
                phase = AppPhase.Launching,
                authVerifiedOnline = false,
                healthMessage = null,
            )
        }
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

    private suspend fun resetHealthSdkAtTrustBoundary(
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
        revokeAuthorization: Boolean = false,
    ): Boolean {
        if (revokeAuthorization) {
            return resetMemberAtTrustBoundary(
                expectedMemberKey = localState.memberKey,
                acceptedConsentOwner = acceptedConsentOwner,
            )
        }
        invalidateSessionEpoch(acceptedConsentOwner)
        return try {
            signOutHealthSdkAfterDrainingWork()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishHealthResetFailure()
            false
        }
    }

    private suspend fun resetMemberAtTrustBoundary(
        expectedMemberKey: String?,
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
        healthAlreadySignedOut: Boolean = false,
        healthMutexHeld: Boolean = false,
    ): Boolean {
        closeProductAuthorityForBoundary()
        invalidateSessionEpoch(acceptedConsentOwner)
        if (
            !localState.beginSignOut(
                expectedMemberKey = expectedMemberKey,
                preserveMemberState = true,
            )
        ) {
            publishHealthResetFailure()
            return false
        }
        val sdkResetSucceeded = if (healthAlreadySignedOut) {
            true
        } else {
            try {
                if (healthMutexHeld) {
                    health.signOutSdk()
                } else {
                    signOutHealthSdkAfterDrainingWork()
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
        if (
            !sdkResetSucceeded ||
            !localState.completeSignOut(expectedMemberKey)
        ) {
            publishHealthResetFailure()
            return false
        }
        currentMemberKey = null
        return true
    }

    /**
     * Called only after epoch and phase state have fenced new health work.
     * Vital 5.0.2 cancels its starter before a separately scheduled child is
     * guaranteed terminal, so drain the app-owned chain before changing SDK
     * identity instead of treating cancellation state as execution proof.
     */
    private suspend fun signOutHealthSdkAfterDrainingWork() {
        healthMutex.withLock { health.signOutSdk() }
    }

    private fun publishHealthResetFailure() {
        _state.update { current ->
            current.copy(
                phase = AppPhase.Failed(
                    message = "Murph couldn't safely reset health sync. Keep the app open and try again.",
                    canRetry = true,
                    canSignOut = true,
                ),
            )
        }
    }

    private suspend fun beginLaunchConsentRecovery(
        deferred: DeferredLaunchConsentRecovery,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        beginLaunchConsentRecovery(
            expectedEpoch = deferred.expectedEpoch,
            memberKey = deferred.memberKey,
            followUp = deferred.followUp,
            memberOwnership = deferred.memberOwnership,
            onAuthoritativeLocalAuth = onAuthoritativeLocalAuth,
        )
    }

    private suspend fun beginLaunchConsentRecovery(
        expectedEpoch: Int,
        memberKey: String,
        followUp: LaunchConsentFollowUp,
        memberOwnership: LaunchConsentMemberOwnership =
            LaunchConsentMemberOwnership.Bound,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        launchConsentMutex.withLock {
            beginLaunchConsentRecoveryLocked(
                expectedEpoch = expectedEpoch,
                memberKey = memberKey,
                followUp = followUp,
                memberOwnership = memberOwnership,
                onAuthoritativeLocalAuth = onAuthoritativeLocalAuth,
            )
        }
    }

    /** Called only while [launchConsentMutex] is held. */
    private suspend fun beginLaunchConsentRecoveryLocked(
        expectedEpoch: Int,
        memberKey: String,
        followUp: LaunchConsentFollowUp,
        memberOwnership: LaunchConsentMemberOwnership,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        val ownsExpectedMember =
            memberKey == currentMemberKey &&
                when (memberOwnership) {
                    LaunchConsentMemberOwnership.Bound ->
                        memberKey == localState.memberKey
                    LaunchConsentMemberOwnership.AdmissionCandidate ->
                        localState.memberKey == null || memberKey == localState.memberKey
                }
        if (
            expectedEpoch != sessionEpoch ||
            !ownsExpectedMember ||
            localState.signOutPending
        ) {
            return
        }
        val existing = pendingLaunchConsentRecovery?.takeIf(::ownsLaunchConsentRecovery)
        val pending = if (existing != null) {
            prioritizeActiveLaunchConsentFollowUp(followUp)
            val resumesAcceptedContinuation = synchronized(existing) { existing.accepted }
            if (!resumesAcceptedContinuation) return
            invalidateSessionEpoch(existing)
            synchronized(existing) {
                existing.accepted = false
                existing.continuationInProgress = false
                existing.continuationAttempt += 1
                existing.continuationStage = LaunchConsentContinuationStage.Dispatch
            }
            existing
        } else {
            invalidateSessionEpoch()
            currentMemberKey = memberKey
            PendingLaunchConsentRecovery(
                epoch = sessionEpoch,
                memberKey = memberKey,
                followUp = followUp,
                memberOwnership = memberOwnership,
            )
        }
        pendingLaunchConsentRecovery = pending
        val initialSetupStep = if (
            pending.memberOwnership == LaunchConsentMemberOwnership.AdmissionCandidate &&
            localState.memberKey == null
        ) {
            InitialSetupStep.HealthConnect
        } else {
            resolveInitialSetupStep()
        }
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                initialSetupStep = initialSetupStep,
                authVerifiedOnline = true,
                healthAvailability = health.availability(),
                healthSync = deriveCachedHealthState(),
                isConnectingHealth = false,
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
        if (!ownsLaunchConsentRecovery(pending)) return
        loadLaunchConsentStatus(
            pending,
            onAuthoritativeLocalAuth,
        )
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
                existing.followUp is LaunchConsentFollowUp.AutomaticAddressBookDeletion ->
                    existing.followUp
                requested is LaunchConsentFollowUp.AutomaticAddressBookDeletion &&
                    existing.followUp.isInitialOnboardingContinuation() ->
                    existing.followUp
                requested is LaunchConsentFollowUp.AutomaticAddressBookDeletion -> requested
                existing.followUp.isGenericReconciliation() -> requested
                else -> existing.followUp
            }
            if (selected == existing.followUp) {
                false
            } else {
                existing.followUp = selected
                existing.followUpVersion += 1
                if (
                    existing.accepted &&
                    (!existing.continuationInProgress ||
                        existing.continuationStage ==
                        LaunchConsentContinuationStage.RestoreAfter)
                ) {
                    existing.continuationStage = initialAcceptedConsentStage(selected)
                }
                true
            }
        }
        if (changed) {
            _state.update { current ->
                current.copy(
                    launchConsentRecovery = current.launchConsentRecovery?.copy(
                        message = "Murph will continue your requested action after consent is current.",
                    ),
                )
            }
        }
        return true
    }

    private fun advanceCompletedHealthPermissionContinuationToSync(
        pending: PendingLaunchConsentRecovery,
    ) {
        synchronized(pending) {
            if (
                !ownsAcceptedConsentContinuation(pending) ||
                !pending.continuationInProgress ||
                pending.followUp !is LaunchConsentFollowUp.CompleteHealthPermission
            ) {
                return
            }
            pending.followUp = LaunchConsentFollowUp.SyncHealth
            pending.followUpVersion += 1
        }
    }

    private suspend fun loadLaunchConsentStatus(
        pending: PendingLaunchConsentRecovery,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Pausing,
                    message = "Pausing health sync before loading consent.",
                    canAccept = false,
                ) ?: LaunchConsentRecoveryUiState(
                    phase = LaunchConsentRecoveryPhase.Pausing,
                    message = "Pausing health sync before loading consent.",
                    showSheet = true,
                ),
            )
        }
        val teardownSucceeded = try {
            signOutHealthSdkAfterDrainingWork()
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
                isSyncingHealth = false,
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Loading,
                    message = "Loading the latest consent documents.",
                    canAccept = false,
                ),
            )
        }
        val status = try {
            api.fetchLaunchConsentStatus(pending.memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (
                ownsLaunchConsentRecovery(pending) &&
                isAuthoritativeLaunchConsentAuthObservation(pending, error.observedState)
            ) {
                onAuthoritativeLocalAuth(error.observedState)
            } else {
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't load the latest consent documents. Check your connection and try again.",
                )
            }
            return
        } catch (_: CompanionApiException.AccountConflict) {
            if (ownsLaunchConsentRecovery(pending)) {
                publishAccountConflictFailure()
            }
            return
        } catch (error: CompanionApiException) {
            if (isTerminalMemberBoundaryError(error)) {
                publishLaunchConsentTerminalBoundaryFailure(pending, error)
            } else {
                publishLaunchConsentLoadFailure(
                    pending,
                    "Murph couldn't load the latest consent documents. Check your connection and try again.",
                )
            }
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
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ): AcceptedLaunchConsentContinuation? {
        if (synchronized(pending) { pending.accepted || pending.continuationInProgress }) {
            return null
        }
        var latest = initialStatus
        val attemptedScopes = mutableSetOf<LaunchConsentScope>()
        if (!ownsLaunchConsentRecovery(pending)) return null
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Saving,
                    status = latest,
                    message = "Saving consent.",
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
                acceptedDocumentVersions = nextScope.documents.associate {
                    it.id to it.version
                },
            )
            val updated = try {
                api.acceptLaunchConsent(pending.memberKey, request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionApiException.LocalAuthUnavailable) {
                if (
                    ownsLaunchConsentRecovery(pending) &&
                    isAuthoritativeLaunchConsentAuthObservation(pending, error.observedState)
                ) {
                    onAuthoritativeLocalAuth(error.observedState)
                } else {
                    publishLaunchConsentRequired(
                        pending = pending,
                        status = latest,
                        message = "Murph couldn't save consent. Check your connection and try again.",
                    )
                }
                return null
            } catch (_: CompanionApiException.StaleConsentDocuments) {
                reloadLaunchConsentAfterStaleDocuments(
                    pending,
                    latest,
                    onAuthoritativeLocalAuth,
                )
                return null
            } catch (_: CompanionApiException.AccountConflict) {
                if (ownsLaunchConsentRecovery(pending)) {
                    publishAccountConflictFailure()
                }
                return null
            } catch (error: CompanionApiException) {
                if (isTerminalMemberBoundaryError(error)) {
                    publishLaunchConsentTerminalBoundaryFailure(pending, error)
                } else {
                    publishLaunchConsentRequired(
                        pending = pending,
                        status = latest,
                        message = "Murph couldn't save consent. Check your connection and try again.",
                    )
                }
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
        val continuation = synchronized(pending) {
            if (!ownsLaunchConsentRecovery(pending)) return null
            pending.accepted = true
            pending.continuationInProgress = true
            pending.continuationAttempt += 1
            pending.continuationStage = initialAcceptedConsentStage(pending.followUp)
            AcceptedLaunchConsentContinuation(
                pending = pending,
                attempt = pending.continuationAttempt,
            )
        }
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Saving,
                    status = latest,
                    message = "Consent is current. Resuming your requested action.",
                    canAccept = false,
                ),
            )
        }
        return continuation
    }

    private fun beginAcceptedConsentContinuation(
        pending: PendingLaunchConsentRecovery,
    ): AcceptedLaunchConsentContinuation? = synchronized(pending) {
        if (
            !ownsAcceptedConsentContinuation(pending) ||
            pending.continuationInProgress
        ) {
            return@synchronized null
        }
        pending.continuationInProgress = true
        pending.continuationAttempt += 1
        AcceptedLaunchConsentContinuation(
            pending = pending,
            attempt = pending.continuationAttempt,
        )
    }

    private suspend fun reloadLaunchConsentAfterStaleDocuments(
        pending: PendingLaunchConsentRecovery,
        retainedStatus: LaunchConsentStatus,
        onAuthoritativeLocalAuth: (AuthSessionState) -> Unit,
    ) {
        val reloaded = try {
            api.fetchLaunchConsentStatus(pending.memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (
                ownsLaunchConsentRecovery(pending) &&
                isAuthoritativeLaunchConsentAuthObservation(pending, error.observedState)
            ) {
                onAuthoritativeLocalAuth(error.observedState)
            } else {
                publishLaunchConsentRequired(
                    pending = pending,
                    status = retainedStatus,
                    message =
                        "Consent documents changed, but Murph couldn't reload them. Try again.",
                )
            }
            return
        } catch (_: CompanionApiException.AccountConflict) {
            if (ownsLaunchConsentRecovery(pending)) {
                publishAccountConflictFailure()
            }
            return
        } catch (error: CompanionApiException) {
            if (isTerminalMemberBoundaryError(error)) {
                publishLaunchConsentTerminalBoundaryFailure(pending, error)
            } else {
                publishLaunchConsentRequired(
                    pending = pending,
                    status = retainedStatus,
                    message = "Consent documents changed, but Murph couldn't reload them. Try again.",
                )
            }
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

    private suspend fun publishLaunchConsentMemberBoundaryFailure(
        pending: PendingLaunchConsentRecovery,
        message: String,
        canRetry: Boolean,
        signOutLabel: String = "Sign out and start fresh",
    ) {
        if (!ownsLaunchConsentRecovery(pending)) return
        val clearsAdmissionCandidate =
            pending.memberOwnership == LaunchConsentMemberOwnership.AdmissionCandidate &&
                localState.memberKey == null
        if (!resetMemberAtTrustBoundary(localState.memberKey)) return
        if (clearsAdmissionCandidate) currentMemberKey = null
        _state.update { current ->
            current.copy(
                launchConsentRecovery = null,
                phase = AppPhase.Failed(
                    message = message,
                    canRetry = canRetry,
                    canSignOut = true,
                    signOutLabel = signOutLabel,
                    supplementalActions = FailureSupplementalActions.Support,
                ),
            )
        }
    }

    private suspend fun publishLaunchConsentTerminalBoundaryFailure(
        pending: PendingLaunchConsentRecovery,
        error: CompanionApiException,
    ) {
        publishLaunchConsentMemberBoundaryFailure(
            pending = pending,
            message = terminalMemberBoundaryMessage(error),
            canRetry = false,
            signOutLabel = terminalMemberBoundarySignOutLabel(error),
        )
    }

    private suspend fun resumeLaunchConsentFollowUp(
        continuation: AcceptedLaunchConsentContinuation,
    ) {
        val pending = continuation.pending
        try {
            while (ownsAcceptedConsentAttempt(continuation)) {
                val step = snapshotAcceptedConsentStep(continuation) ?: return
                publishAcceptedConsentProgress(pending, step.stage)
                when (step.stage) {
                    LaunchConsentContinuationStage.RestoreBefore -> {
                        reconcile(force = true, acceptedConsentOwner = pending)
                        if (!ownsAcceptedConsentAttempt(continuation)) return
                        if (!canCompleteAcceptedConsentHealthRestore(pending)) {
                            failAcceptedConsentContinuation(continuation)
                            return
                        }
                        val completesAddressReconciliation = synchronized(pending) {
                            if (
                                !ownsAcceptedConsentAttempt(continuation) ||
                                pending.continuationStage !=
                                LaunchConsentContinuationStage.RestoreBefore
                            ) {
                                false
                            } else if (
                                pending.followUp == LaunchConsentFollowUp.ReconcileAddressBook
                            ) {
                                true
                            } else {
                                pending.continuationStage =
                                    LaunchConsentContinuationStage.Dispatch
                                false
                            }
                        }
                        if (completesAddressReconciliation) {
                            if (finishAcceptedConsentContinuation(continuation, step)) return
                        }
                    }

                    LaunchConsentContinuationStage.Dispatch -> {
                        dispatchAcceptedConsentFollowUp(continuation, step)
                        if (!ownsAcceptedConsentAttempt(continuation)) return
                        val completesContinuation = synchronized(pending) {
                            if (
                                !ownsAcceptedConsentAttempt(continuation) ||
                                pending.continuationStage !=
                                LaunchConsentContinuationStage.Dispatch
                            ) {
                                false
                            } else if (pending.followUpVersion != step.followUpVersion) {
                                pending.continuationStage =
                                    initialAcceptedConsentStage(pending.followUp)
                                false
                            } else if (
                                step.followUp.healthRestoreOrder() ==
                                LaunchConsentHealthRestoreOrder.After &&
                                healthWasRequested() &&
                                !health.isSignedIn()
                            ) {
                                pending.continuationStage =
                                    LaunchConsentContinuationStage.RestoreAfter
                                false
                            } else {
                                true
                            }
                        }
                        if (completesContinuation) {
                            if (finishAcceptedConsentContinuation(continuation, step)) return
                        }
                    }

                    LaunchConsentContinuationStage.RestoreAfter -> {
                        reconcile(force = true, acceptedConsentOwner = pending)
                        if (!ownsAcceptedConsentAttempt(continuation)) return
                        if (!canCompleteAcceptedConsentHealthRestore(pending)) {
                            failAcceptedConsentContinuation(continuation)
                            return
                        }
                        if (finishAcceptedConsentContinuation(continuation, step)) return
                    }
                }
            }
        } finally {
            synchronized(pending) {
                if (
                    ownsAcceptedConsentContinuation(pending) &&
                    pending.continuationAttempt == continuation.attempt
                ) {
                    pending.continuationInProgress = false
                }
            }
        }
    }

    private fun snapshotAcceptedConsentStep(
        continuation: AcceptedLaunchConsentContinuation,
    ): AcceptedLaunchConsentStep? = synchronized(continuation.pending) {
        val pending = continuation.pending
        if (!ownsAcceptedConsentAttempt(continuation)) return@synchronized null
        AcceptedLaunchConsentStep(
            stage = pending.continuationStage,
            followUp = pending.followUp,
            followUpVersion = pending.followUpVersion,
        )
    }

    private suspend fun dispatchAcceptedConsentFollowUp(
        continuation: AcceptedLaunchConsentContinuation,
        step: AcceptedLaunchConsentStep,
    ) {
        val pending = continuation.pending
        val followUp = step.followUp
        if (
            followUp == LaunchConsentFollowUp.StopAddressBookSharing ||
            followUp is LaunchConsentFollowUp.AutomaticAddressBookDeletion
        ) {
            cancelAddressBookPermissionFlow()
        }
        when (followUp) {
            LaunchConsentFollowUp.Reconcile ->
                reconcile(force = true, acceptedConsentOwner = pending)
            LaunchConsentFollowUp.SyncHealth ->
                syncNow(foregroundClaim = null, acceptedConsentOwner = pending)
            LaunchConsentFollowUp.PrepareHealthPermission ->
                prepareHealthConnection(pending)
            is LaunchConsentFollowUp.PrepareAddressBookPermission ->
                prepareAddressBookSharing(
                    acceptedConsentOwner = pending,
                    completesInitialSetup = followUp.completesInitialSetup,
                )
            LaunchConsentFollowUp.ReconcileAddressBook -> {
                var deferredBoundary: DeferredSessionBoundary? = null
                reconcileAddressBookForeground(
                    showBusy = false,
                    onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
                )
                deferredBoundary?.let { handleDeferredSessionBoundary(it) }
            }
            LaunchConsentFollowUp.StopAddressBookSharing ->
                stopAddressBookSharing(pending)
            is LaunchConsentFollowUp.AutomaticAddressBookDeletion ->
                resumeAutomaticAddressBookDeletion(followUp.mutation)
            is LaunchConsentFollowUp.CompleteHealthPermission ->
                resumeHealthPermissionAfterConsent(
                    acceptedConsentOwner = pending,
                    requestedAt = followUp.requestedAt,
                    receiptBaselineAt = followUp.receiptBaselineAt,
                )
            is LaunchConsentFollowUp.AddressBookReplacement -> {
                if (
                    followUp.pending.completesInitialSetup &&
                    (
                        _state.value.initialSetupStep != InitialSetupStep.FriendlyNames ||
                            localState.initialSetupStep != InitialSetupStep.FriendlyNames
                    )
                ) {
                    localState.abandonAddressBookReplacement(
                        followUp.pending.mutation.mutationId,
                    )
                    return
                }
                val memberKey = currentMemberKey ?: return
                val restored = followUp.pending.copy(
                    epoch = sessionEpoch,
                    memberKey = memberKey,
                )
                pendingAddressBookPermissionFlow = restored
                _state.update {
                    it.copy(isAddressBookBusy = true, addressBookMessage = null)
                }
                completeAddressBookPermissionFlow(
                    permissionGranted = true,
                    acceptedConsentDispatch = AcceptedLaunchConsentDispatch(
                        continuation = continuation,
                        step = step,
                    ),
                )
            }
            is LaunchConsentFollowUp.CompleteInitialOnboarding ->
                completeInitialOnboardingRequest(followUp.request, pending)
            is LaunchConsentFollowUp.PrepareInitialOnboardingContactCard ->
                prepareInitialOnboardingContactCard(followUp.avatarId, pending)
        }
    }

    private suspend fun finishAcceptedConsentContinuation(
        continuation: AcceptedLaunchConsentContinuation,
        completedStep: AcceptedLaunchConsentStep,
    ): Boolean {
        val pending = continuation.pending
        if (
            completedStep.followUp == LaunchConsentFollowUp.Reconcile &&
            pending.memberOwnership == LaunchConsentMemberOwnership.AdmissionCandidate &&
            (
                _state.value.phase != AppPhase.Ready ||
                    !_state.value.authVerifiedOnline ||
                    currentMemberKey != pending.memberKey ||
                    localState.memberKey != pending.memberKey
                )
        ) {
            failAcceptedConsentContinuation(continuation)
            return true
        }
        val completion = synchronized(pending) {
            if (
                !ownsAcceptedConsentAttempt(continuation) ||
                pending.continuationStage != completedStep.stage ||
                pending.followUpVersion != completedStep.followUpVersion
            ) {
                return@synchronized null
            }
            pendingLaunchConsentRecovery = null
            pending.continuationInProgress = false
            AcceptedLaunchConsentCompletion(pending.deferredForegroundClaim)
        } ?: return false
        _state.update { current -> current.copy(launchConsentRecovery = null) }
        completion.deferredForegroundClaim?.takeIf(::ownsForegroundRefresh)?.let {
            runForegroundRefresh(it)
        }
        return true
    }

    private fun failAcceptedConsentContinuation(
        continuation: AcceptedLaunchConsentContinuation,
    ) {
        val pending = continuation.pending
        synchronized(pending) {
            if (!ownsAcceptedConsentAttempt(continuation)) return
            pending.continuationInProgress = false
        }
        publishLaunchConsentLoadFailure(
            pending,
            "Consent is current, but Murph couldn't resume your request. Check your connection and try again.",
        )
    }

    private fun publishAcceptedConsentProgress(
        pending: PendingLaunchConsentRecovery,
        stage: LaunchConsentContinuationStage,
    ) {
        if (!ownsAcceptedConsentContinuation(pending)) return
        val message = when (stage) {
            LaunchConsentContinuationStage.RestoreBefore ->
                "Consent is current. Restoring health sync before your requested action."
            LaunchConsentContinuationStage.Dispatch ->
                "Consent is current. Resuming your requested action."
            LaunchConsentContinuationStage.RestoreAfter ->
                "Your requested action is complete. Restoring health sync."
        }
        _state.update { current ->
            current.copy(
                launchConsentRecovery = current.launchConsentRecovery?.copy(
                    phase = LaunchConsentRecoveryPhase.Saving,
                    message = message,
                    canAccept = false,
                ),
            )
        }
    }

    private fun initialAcceptedConsentStage(
        followUp: LaunchConsentFollowUp,
    ): LaunchConsentContinuationStage = if (
        followUp.healthRestoreOrder() == LaunchConsentHealthRestoreOrder.Before &&
        healthWasRequested() &&
        !health.isSignedIn()
    ) {
        LaunchConsentContinuationStage.RestoreBefore
    } else {
        LaunchConsentContinuationStage.Dispatch
    }

    private fun LaunchConsentFollowUp.healthRestoreOrder():
        LaunchConsentHealthRestoreOrder = when (this) {
        LaunchConsentFollowUp.Reconcile,
        LaunchConsentFollowUp.SyncHealth,
        LaunchConsentFollowUp.PrepareHealthPermission,
        is LaunchConsentFollowUp.CompleteHealthPermission,
        -> LaunchConsentHealthRestoreOrder.None
        is LaunchConsentFollowUp.PrepareAddressBookPermission,
        LaunchConsentFollowUp.ReconcileAddressBook,
        is LaunchConsentFollowUp.CompleteInitialOnboarding,
        is LaunchConsentFollowUp.PrepareInitialOnboardingContactCard,
        -> LaunchConsentHealthRestoreOrder.Before
        LaunchConsentFollowUp.StopAddressBookSharing,
        is LaunchConsentFollowUp.AutomaticAddressBookDeletion,
        is LaunchConsentFollowUp.AddressBookReplacement,
        -> LaunchConsentHealthRestoreOrder.After
    }

    private fun LaunchConsentFollowUp.isGenericReconciliation(): Boolean = when (this) {
        LaunchConsentFollowUp.Reconcile,
        LaunchConsentFollowUp.SyncHealth,
        LaunchConsentFollowUp.ReconcileAddressBook,
        -> true
        else -> false
    }

    private fun LaunchConsentFollowUp.isInitialOnboardingContinuation(): Boolean = when (this) {
        is LaunchConsentFollowUp.CompleteInitialOnboarding,
        is LaunchConsentFollowUp.PrepareInitialOnboardingContactCard,
        -> true
        else -> false
    }

    private fun canCompleteAcceptedConsentHealthRestore(
        pending: PendingLaunchConsentRecovery,
    ): Boolean =
        ownsAcceptedConsentContinuation(pending) &&
            !ownsPendingHealthConnection() &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline &&
            (!healthWasRequested() || health.isSignedIn())

    private suspend fun resumeHealthPermissionAfterConsent(
        acceptedConsentOwner: PendingLaunchConsentRecovery,
        requestedAt: Instant,
        receiptBaselineAt: Instant?,
    ) {
        val memberKey = currentMemberKey ?: return
        val epoch = sessionEpoch
        pendingHealthConnection = PendingHealthConnection(
            epoch = epoch,
            memberKey = memberKey,
            requestedAt = requestedAt,
            receiptBaselineAt = receiptBaselineAt,
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
            completeHealthPermissionFlow(
                permissionRequestCompleted = true,
                acceptedConsentOwner = acceptedConsentOwner,
            )
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

    private suspend fun reconcileAddressBookForeground(
        showBusy: Boolean,
        onSessionBoundary: (DeferredSessionBoundary) -> Unit,
    ) {
        if (!contacts.isSupported || ownsPendingHealthConnection()) return
        if (!addressBookMutex.tryLock()) {
            enqueueAddressBookReconcile(showBusy)
            return
        }
        var ownerMemberKey: String? = null
        var ownerEpoch: Int? = null
        var deferredBoundary: DeferredSessionBoundary? = null
        val deferBoundary: (DeferredSessionBoundary) -> Unit = {
            deferredBoundary = deferredBoundary ?: it
        }
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
                onSessionBoundary = deferBoundary,
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
                    onSessionBoundary = deferBoundary,
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
            performAutomaticAddressBookDeletionLocked(
                memberKey,
                epoch,
                deletion,
                deferBoundary,
            )
        } finally {
            addressBookMutex.unlock()
            if (
                showBusy &&
                ownerEpoch == sessionEpoch &&
                ownerMemberKey == currentMemberKey
            ) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            val boundary = deferredBoundary
            if (boundary != null) {
                onSessionBoundary(boundary)
            } else {
                drainAddressBookReconcile(
                    ownerMemberKey,
                    ownerEpoch,
                    onSessionBoundary,
                )
            }
        }
    }

    private suspend fun performAutomaticAddressBookDeletionLocked(
        memberKey: String,
        epoch: Int,
        mutation: AddressBookMutation,
        onSessionBoundary: (DeferredSessionBoundary) -> Unit,
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
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (
                ownsAddressBookWork(memberKey, epoch) &&
                isAuthoritativeLocalAuthObservation(error.observedState)
            ) {
                onSessionBoundary(DeferredSessionBoundary.LocalAuth(error.observedState))
            } else {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Contacts access is off. Murph will retry deleting the exact shared revision on the next foreground check.",
                )
            }
        } catch (_: CompanionApiException.Conflict) {
            if (!ownsAddressBookWork(memberKey, epoch)) return
            localState.abandonAddressBookDeletion(mutation.mutationId)
            fetchAddressBookStatusLocked(
                memberKey = memberKey,
                epoch = epoch,
                consentFollowUp = LaunchConsentFollowUp.ReconcileAddressBook,
                onSessionBoundary = onSessionBoundary,
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
                onAuthoritativeLocalAuth = {
                    onSessionBoundary(DeferredSessionBoundary.LocalAuth(it))
                },
            )
        } catch (_: CompanionApiException.AccountConflict) {
            if (ownsAddressBookWork(memberKey, epoch)) {
                onSessionBoundary(DeferredSessionBoundary.AccountConflict)
            }
        } catch (error: CompanionApiException) {
            if (
                ownsAddressBookWork(memberKey, epoch) &&
                isTerminalMemberBoundaryError(error)
            ) {
                onSessionBoundary(DeferredSessionBoundary.BackendRejected(error))
            } else {
                publishAddressBookMessage(
                    memberKey,
                    epoch,
                    "Contacts access is off. Murph will retry deleting the exact shared revision on the next foreground check.",
                )
            }
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
        var deferredBoundary: DeferredSessionBoundary? = null
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
            performAutomaticAddressBookDeletionLocked(
                memberKey,
                epoch,
                mutation,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
        } finally {
            addressBookMutex.unlock()
            if (ownerEpoch == sessionEpoch && ownerMemberKey == currentMemberKey) {
                _state.update { it.copy(isAddressBookBusy = false) }
            }
            val boundaryBeforeDrain = deferredBoundary
            deferredBoundary = null
            boundaryBeforeDrain?.let { handleDeferredSessionBoundary(it) }
            drainAddressBookReconcile(
                ownerMemberKey,
                ownerEpoch,
                onSessionBoundary = { deferredBoundary = deferredBoundary ?: it },
            )
            deferredBoundary?.let { handleDeferredSessionBoundary(it) }
        }
    }

    /** Called only while [addressBookMutex] is held. */
    private suspend fun deleteOwnedAddressBookAfterPermissionLossLocked(
        pending: PendingAddressBookPermissionFlow,
        exactRevision: Int? = pending.ownedRevisionForPermissionLoss,
        onSessionBoundary: (DeferredSessionBoundary) -> Unit,
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
            onSessionBoundary = onSessionBoundary,
        )
    }

    /** Called only while [addressBookMutex] is held. */
    private suspend fun fetchAddressBookStatusLocked(
        memberKey: String,
        epoch: Int,
        consentFollowUp: LaunchConsentFollowUp,
        onSessionBoundary: (DeferredSessionBoundary) -> Unit,
    ): AddressBookServerStatus? {
        if (!ownsAddressBookWork(memberKey, epoch)) return null
        val status = try {
            api.fetchAddressBookStatus(memberKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionApiException.LocalAuthUnavailable) {
            if (
                ownsAddressBookWork(memberKey, epoch) &&
                isAuthoritativeLocalAuthObservation(error.observedState)
            ) {
                onSessionBoundary(DeferredSessionBoundary.LocalAuth(error.observedState))
            } else {
                publishAddressBookUnavailable(
                    memberKey,
                    epoch,
                    "Murph couldn't refresh address-book sharing. Try again.",
                )
            }
            return null
        } catch (_: CompanionApiException.ConsentRequired) {
            beginLaunchConsentRecovery(
                expectedEpoch = epoch,
                memberKey = memberKey,
                followUp = consentFollowUp,
                onAuthoritativeLocalAuth = {
                    onSessionBoundary(DeferredSessionBoundary.LocalAuth(it))
                },
            )
            return null
        } catch (_: CompanionApiException.AccountConflict) {
            if (ownsAddressBookWork(memberKey, epoch)) {
                onSessionBoundary(DeferredSessionBoundary.AccountConflict)
            }
            return null
        } catch (error: CompanionApiException) {
            if (
                ownsAddressBookWork(memberKey, epoch) &&
                isTerminalMemberBoundaryError(error)
            ) {
                onSessionBoundary(DeferredSessionBoundary.BackendRejected(error))
            } else {
                publishAddressBookUnavailable(
                    memberKey,
                    epoch,
                    "Murph couldn't refresh address-book sharing. Try again.",
                )
            }
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
        onSessionBoundary: (DeferredSessionBoundary) -> Unit,
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
        reconcileAddressBookForeground(
            requested.showBusy,
            onSessionBoundary,
        )
    }

    private fun ownsAddressBookWork(memberKey: String, epoch: Int): Boolean =
        contacts.isSupported &&
            epoch == sessionEpoch &&
            memberKey == currentMemberKey &&
            memberKey == localState.memberKey &&
            _state.value.phase == AppPhase.Ready &&
            _state.value.authVerifiedOnline &&
            !localState.signOutPending

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
        )
    }

    private fun cachedHealthStatus(): CompanionSyncStatus? {
        val observedAt = localState.lastKnownStatusObservedAt?.epochMilliseconds
            ?.let(Instant::ofEpochMilli)
            ?: return null
        val receiptFloorAt = healthReceiptFloorAt()
        val receivedAt = localState.lastKnownDataReceivedAt?.epochMilliseconds
            ?.let(Instant::ofEpochMilli)
            ?.takeIf { receiptFloorAt == null || it.isAfter(receiptFloorAt) }
        return CompanionSyncStatus(receivedAt, observedAt, emptyMap())
    }

    private fun healthRequestedAt(): Instant? =
        localState.healthAccessRequestedAt?.epochMilliseconds?.let(Instant::ofEpochMilli)

    private fun healthWasRequested(): Boolean = healthRequestedAt() != null

    private fun resolveInitialSetupStep(): InitialSetupStep {
        val stored = localState.initialSetupStep
        val resolved = when {
            stored == InitialSetupStep.HealthConnect && healthWasRequested() ->
                InitialSetupStep.FriendlyNames
            stored != null -> stored
            healthWasRequested() || localState.healthReconnectRequired ->
                InitialSetupStep.Complete
            else -> InitialSetupStep.HealthConnect
        }
        if (stored != resolved) localState.initialSetupStep = resolved
        return resolved
    }

    private fun advanceInitialSetupStep(
        expected: InitialSetupStep,
        next: InitialSetupStep,
        abandonPendingAddressBookReplacement: Boolean = false,
    ): Boolean {
        val memberKey = currentMemberKey ?: return false
        val current = _state.value
        if (
            current.phase != AppPhase.Ready ||
            current.initialSetupStep != expected ||
            memberKey != localState.memberKey ||
            localState.signOutPending ||
            localState.initialSetupStep != expected
        ) {
            return false
        }
        if (
            !localState.advanceInitialSetupStep(
                expected = expected,
                next = next,
                abandonPendingAddressBookReplacement =
                    abandonPendingAddressBookReplacement,
            )
        ) {
            return false
        }
        return projectInitialSetupStep(expected, next)
    }

    private fun projectInitialSetupStep(
        expected: InitialSetupStep,
        next: InitialSetupStep,
    ): Boolean {
        val memberKey = currentMemberKey ?: return false
        if (localState.initialSetupStep != next) return false
        _state.update { state ->
            if (
                state.phase == AppPhase.Ready &&
                state.initialSetupStep == expected &&
                memberKey == currentMemberKey &&
                memberKey == localState.memberKey &&
                !localState.signOutPending
            ) {
                state.copy(initialSetupStep = next)
            } else {
                state
            }
        }
        return _state.value.initialSetupStep == next
    }

    private fun healthReceiptBaselineAt(): Instant? =
        localState.healthReceiptBaselineAt?.epochMilliseconds?.let(Instant::ofEpochMilli)

    private fun healthReceiptFloorAt(): Instant? {
        val requestedAt = healthRequestedAt()
        val receiptBaselineAt = healthReceiptBaselineAt()
        return when {
            requestedAt == null -> receiptBaselineAt
            receiptBaselineAt == null -> requestedAt
            else -> maxOf(requestedAt, receiptBaselineAt)
        }
    }

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

    private fun allowsLaunchConsentWork(
        acceptedConsentOwner: PendingLaunchConsentRecovery?,
    ): Boolean = !hasActiveLaunchConsentRecovery() ||
        acceptedConsentOwner?.let(::ownsAcceptedConsentContinuation) == true

    private fun ownsAcceptedConsentContinuation(
        pending: PendingLaunchConsentRecovery,
    ): Boolean = ownsLaunchConsentRecovery(pending) && pending.accepted

    private fun ownsAcceptedConsentAttempt(
        continuation: AcceptedLaunchConsentContinuation,
    ): Boolean = continuation.pending.let { pending ->
        ownsAcceptedConsentContinuation(pending) &&
            pending.continuationInProgress &&
            pending.continuationAttempt == continuation.attempt
    }

    private fun ownsAcceptedConsentDispatch(
        dispatch: AcceptedLaunchConsentDispatch,
    ): Boolean = synchronized(dispatch.continuation.pending) {
        val pending = dispatch.continuation.pending
        ownsAcceptedConsentAttempt(dispatch.continuation) &&
            pending.continuationStage == dispatch.step.stage &&
            pending.followUpVersion == dispatch.step.followUpVersion &&
            pending.followUp == dispatch.step.followUp
    }

    private fun deferForegroundRefreshForAcceptedConsent(
        pending: PendingLaunchConsentRecovery,
        foregroundClaim: ForegroundRefreshClaim,
    ): Boolean = synchronized(pending) {
        if (!ownsAcceptedConsentContinuation(pending)) return@synchronized false
        val deferred = pending.deferredForegroundClaim
        pending.deferredForegroundClaim = if (
            deferred != null &&
            deferred.generation == foregroundClaim.generation &&
            deferred.sessionEpoch == foregroundClaim.sessionEpoch &&
            deferred.healthSyncSequenceAtEntry <= foregroundClaim.healthSyncSequenceAtEntry
        ) {
            deferred
        } else {
            foregroundClaim
        }
        true
    }

    private fun ownsLaunchConsentMember(pending: PendingLaunchConsentRecovery): Boolean =
        pending.memberKey == currentMemberKey &&
            when (pending.memberOwnership) {
                LaunchConsentMemberOwnership.Bound ->
                    pending.memberKey == localState.memberKey
                LaunchConsentMemberOwnership.AdmissionCandidate ->
                    localState.memberKey == null || pending.memberKey == localState.memberKey
            }

    private fun ownsLaunchConsentRecovery(pending: PendingLaunchConsentRecovery): Boolean =
        pendingLaunchConsentRecovery === pending &&
            pending.epoch == sessionEpoch &&
            ownsLaunchConsentMember(pending) &&
            !localState.signOutPending

    private fun invalidateSessionEpoch(
        acceptedConsentOwner: PendingLaunchConsentRecovery? = null,
    ) {
        val preservedConsentOwner = acceptedConsentOwner?.takeIf {
            ownsAcceptedConsentContinuation(it)
        }
        sessionEpoch += 1
        preservedConsentOwner?.let { pending ->
            synchronized(pending) {
                pending.epoch = sessionEpoch
                pending.deferredForegroundClaim = pending.deferredForegroundClaim
                    ?.takeIf { it.generation == foregroundGeneration }
                    ?.copy(sessionEpoch = sessionEpoch)
            }
        }
        pendingHealthConnection = null
        pendingAddressBookPermissionFlow = null
        pendingInitialOnboardingContactCardHandoff = null
        pendingLaunchConsentRecovery = preservedConsentOwner
        synchronized(pendingAddressBookReconcileLock) {
            pendingAddressBookReconcile = null
        }
        _state.update {
            it.copy(
                isConnectingHealth = false,
                isAddressBookBusy = false,
                isInitialOnboardingSaving = false,
                initialOnboardingContactCardHandoff = null,
                launchConsentRecovery = if (preservedConsentOwner == null) {
                    null
                } else {
                    it.launchConsentRecovery
                },
                pendingHealthPermissionRequestId = null,
                pendingAddressBookPermissionRequestId = null,
            )
        }
    }

    private fun connectionErrorMessage(error: Exception): String = when {
        error is CompanionApiException && isTerminalMemberBoundaryError(error) ->
            terminalMemberBoundaryMessage(error)
        else -> when (error) {
            CompanionApiException.Network ->
                "Murph couldn't reach the network. Check your connection and try again."
            CompanionApiException.AccountConflict ->
                "This sign-in conflicts with another Murph account. Try a different sign-in."
            CompanionApiException.ConsentRequired -> CONSENT_REQUIRED_MESSAGE
            CompanionApiException.ReconnectRequired ->
                "Reconnect Health Connect to resume syncing."
            else -> "Murph couldn't finish connecting Health Connect. Try again in a moment."
        }
    }

    private fun isTerminalMemberBoundaryError(error: CompanionApiException): Boolean =
        when (error) {
            CompanionApiException.Unauthorized,
            CompanionApiException.NoAccount,
            CompanionApiException.AccessRequired,
            CompanionApiException.MemberSuspended,
            CompanionApiException.AdmissionSupportRequired -> true
            else -> false
        }

    private fun terminalMemberBoundaryMessage(error: CompanionApiException): String = when (error) {
        CompanionApiException.Unauthorized -> "Your session needs a refresh. Sign in again."
        CompanionApiException.NoAccount -> "This sign-in isn't linked to an active Murph account."
        CompanionApiException.AccessRequired ->
            "This sign-in doesn't have access to the Murph companion app."
        CompanionApiException.MemberSuspended ->
            "This Murph account is paused. Try a different sign-in or contact Murph support."
        CompanionApiException.AdmissionSupportRequired ->
            "Murph support needs to finish setting up this account. Try a different sign-in or contact support."
        else -> kotlin.error("Non-terminal member boundary error")
    }

    private fun terminalMemberBoundarySignOutLabel(error: CompanionApiException): String = if (
        error == CompanionApiException.Unauthorized
    ) {
        "Sign in again"
    } else {
        "Try a different sign-in"
    }

    private data class ForegroundRefreshClaim(
        val generation: Int,
        val sessionEpoch: Int,
        val healthSyncSequenceAtEntry: Long,
    )

    private sealed interface JunctionIdentificationResult {
        data object Identified : JunctionIdentificationResult
        data object OwnershipLost : JunctionIdentificationResult
        data class AuthLost(val state: AuthSessionState) : JunctionIdentificationResult
    }

    private data class PendingHealthConnection(
        val epoch: Int,
        val memberKey: String,
        val requestedAt: Instant,
        val receiptBaselineAt: Instant?,
    )

    private data class PendingAddressBookPermissionFlow(
        val epoch: Int,
        val memberKey: String,
        val mutation: AddressBookMutation,
        val preflightStatus: AddressBookServerStatus,
        val completesInitialSetup: Boolean,
        val ownedRevisionForPermissionLoss: Int?,
    )

    private data class PendingAddressBookReconcile(
        val memberKey: String?,
        val epoch: Int,
        val showBusy: Boolean,
    )

    private data class PendingInitialOnboardingContactCardHandoffRequest(
        val id: Int,
        val memberKey: String,
        val epoch: Int,
        val generation: Int,
        val avatarId: String,
    )

    private data class DeferredLaunchConsentRecovery(
        val expectedEpoch: Int,
        val memberKey: String,
        val followUp: LaunchConsentFollowUp,
        val memberOwnership: LaunchConsentMemberOwnership =
            LaunchConsentMemberOwnership.Bound,
    )

    private sealed interface DeferredSessionBoundary {
        data class LocalAuth(
            val observedState: AuthSessionState,
        ) : DeferredSessionBoundary

        data class BackendRejected(
            val error: CompanionApiException,
        ) : DeferredSessionBoundary

        data object AccountConflict : DeferredSessionBoundary
    }

    private class PendingLaunchConsentRecovery(
        var epoch: Int,
        val memberKey: String,
        var followUp: LaunchConsentFollowUp,
        val memberOwnership: LaunchConsentMemberOwnership,
        var followUpVersion: Int = 0,
        var accepted: Boolean = false,
        var continuationInProgress: Boolean = false,
        var continuationAttempt: Int = 0,
        var continuationStage: LaunchConsentContinuationStage =
            LaunchConsentContinuationStage.Dispatch,
        var deferredForegroundClaim: ForegroundRefreshClaim? = null,
    )

    private enum class LaunchConsentMemberOwnership {
        Bound,
        AdmissionCandidate,
    }

    private data class AcceptedLaunchConsentContinuation(
        val pending: PendingLaunchConsentRecovery,
        val attempt: Int,
    )

    private data class AcceptedLaunchConsentStep(
        val stage: LaunchConsentContinuationStage,
        val followUp: LaunchConsentFollowUp,
        val followUpVersion: Int,
    )

    private data class AcceptedLaunchConsentDispatch(
        val continuation: AcceptedLaunchConsentContinuation,
        val step: AcceptedLaunchConsentStep,
    )

    private data class AcceptedLaunchConsentCompletion(
        val deferredForegroundClaim: ForegroundRefreshClaim?,
    )

    private enum class LaunchConsentContinuationStage {
        RestoreBefore,
        Dispatch,
        RestoreAfter,
    }

    private enum class LaunchConsentHealthRestoreOrder {
        None,
        Before,
        After,
    }

    private sealed interface LaunchConsentFollowUp {
        data object Reconcile : LaunchConsentFollowUp
        data object SyncHealth : LaunchConsentFollowUp
        data object PrepareHealthPermission : LaunchConsentFollowUp
        data class PrepareAddressBookPermission(
            val completesInitialSetup: Boolean,
        ) : LaunchConsentFollowUp
        data object ReconcileAddressBook : LaunchConsentFollowUp
        data object StopAddressBookSharing : LaunchConsentFollowUp
        data class AutomaticAddressBookDeletion(
            val mutation: AddressBookMutation,
        ) : LaunchConsentFollowUp
        data class CompleteHealthPermission(
            val requestedAt: Instant,
            val receiptBaselineAt: Instant?,
        ) : LaunchConsentFollowUp
        data class AddressBookReplacement(
            val pending: PendingAddressBookPermissionFlow,
        ) : LaunchConsentFollowUp
        data class CompleteInitialOnboarding(
            val request: InitialOnboardingCompletionRequest,
        ) : LaunchConsentFollowUp
        data class PrepareInitialOnboardingContactCard(
            val avatarId: String,
        ) : LaunchConsentFollowUp
    }

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val CONSENT_REQUIRED_MESSAGE =
            "Murph needs your latest launch consent. Review it in the app, then try again."
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
        const val HEALTH_PERMISSION_VERIFICATION_MESSAGE =
            "Murph couldn't verify current Health Connect permissions. Saved status is still shown."
        const val HEALTH_RECONNECT_REQUIRED_MESSAGE =
            "Health Connect needs to reconnect before syncing can resume."
    }

    private fun signInTokenRequest(intent: ConnectionIntent?): SignInTokenRequest =
        SignInTokenRequest(
            appInstallationId = localState.installationId,
            appVersion = config.appVersion,
            connectionIntent = intent,
            sdkVersions = mapOf(
                "vital" to config.junctionSdkVersion,
                "privy" to config.privySdkVersion,
            ),
            timeZone = ZoneId.systemDefault().id,
        )
}
