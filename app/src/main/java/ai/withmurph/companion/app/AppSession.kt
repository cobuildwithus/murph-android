package ai.withmurph.companion.app

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
import ai.withmurph.companion.core.SignInTokenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant

class AppSession(
    private val auth: AuthProvider,
    private val api: CompanionApi,
    private val health: HealthSyncing,
    private val localState: LocalState,
    private val config: AppConfig,
    private val now: () -> Instant = Instant::now,
) {
    private val startMutex = Mutex()
    private val healthMutex = Mutex()
    private var hasCompletedStartup = false
    private var needsForegroundRefresh = false
    private var sessionEpoch = 0
    private var currentMemberKey: String? = null
    private var pendingHealthConnection: PendingHealthConnection? = null

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

    private suspend fun reconcile(force: Boolean) = startMutex.withLock {
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
            return when (health.availability()) {
                HealthConnectAvailability.Available -> {
                    val validatedEpoch = sessionEpoch
                    val receiptBeforePreflight = localState.lastKnownDataReceivedAt
                    if (fetchValidatedHealthStatus(validatedEpoch) == null) {
                        return false
                    }
                    currentAuthOwnershipLoss(memberKey)?.let { authState ->
                        localState.lastKnownDataReceivedAt = receiptBeforePreflight
                        publishPermissionAwareHealthState(
                            status = cachedHealthStatus(),
                            message = _state.value.healthMessage,
                        )
                        authStateToReconcile = authState
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
                        currentAuthOwnershipLoss(memberKey)?.let { authState ->
                            authStateToReconcile = authState
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
        authStateToReconcile?.let { reconcileAfterHealthLockAuthLoss(it) }
        return prepared
    }

    suspend fun completeHealthPermissionFlow(permissionRequestCompleted: Boolean): Boolean {
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
                if (fetchValidatedHealthStatus(epoch) == null) {
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
                identifyJunction(
                    memberKey = pending.memberKey,
                    intent = ConnectionIntent.Connect,
                    epoch = epoch,
                )?.let { authState ->
                    authStateToReconcile = authState
                    return@withLock abortPendingHealthConnection(epoch)
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
        authStateToReconcile?.let { reconcileAfterHealthLockAuthLoss(it) }
        return completed
    }

    fun cancelHealthPermissionFlow() {
        val pending = pendingHealthConnection ?: return
        if (pending.epoch != sessionEpoch) return
        pendingHealthConnection = null
        _state.update { it.copy(isConnectingHealth = false) }
    }

    suspend fun syncNow() {
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
        if (!needsForegroundRefresh) {
            if (
                _state.value.phase == AppPhase.Ready &&
                !_state.value.authVerifiedOnline
            ) {
                reconcile(force = true)
            }
            return
        }
        needsForegroundRefresh = false
        val authAllowsSync = reconcileForegroundAuth()
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

    fun didEnterBackground() {
        needsForegroundRefresh = true
    }

    suspend fun signOut() = withContext(NonCancellable) {
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

    private suspend fun enforceHealthSetupAuthorization(): Boolean {
        if (!health.isSignedIn() || healthWasRequested()) return true
        if (!resetHealthSdkAtTrustBoundary()) return false
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
            if (!resetHealthSdkAtTrustBoundary()) return
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
                publishAuthoritativeResumeFailure(
                    message = connectionErrorMessage(error),
                    canRetry = true,
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
            )
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
            }
        }
    }

    private suspend fun enterSignedOut() {
        if (health.isSignedIn() || localState.memberKey != null) {
            if (!resetHealthSdkAtTrustBoundary()) return
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

    private fun restoreOfflineIfPossible() {
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
                    reconcile(force = true)
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
            if (fetchValidatedHealthStatus(epoch) == null) return false
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
            fetchValidatedHealthStatus(epoch)
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
    private suspend fun fetchValidatedHealthStatus(epoch: Int): CompanionSyncStatus? {
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
            failCurrentSessionWhileHealthLocked(
                message = CONSENT_REQUIRED_MESSAGE,
                canRetry = true,
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
            publishBackendBootstrapFailure(
                message = CONSENT_REQUIRED_MESSAGE,
                canRetry = true,
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
        if (!resetHealthSdkAtTrustBoundary()) return
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

    private suspend fun reconcileAfterHealthLockAuthLoss(observed: AuthSessionState) {
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

    private fun invalidateSessionEpoch() {
        sessionEpoch += 1
        pendingHealthConnection = null
        _state.update { it.copy(isConnectingHealth = false) }
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

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val CONSENT_REQUIRED_MESSAGE =
            "Murph needs your latest health consent. Complete it at withmurph.ai, then try again."
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
    }
}
