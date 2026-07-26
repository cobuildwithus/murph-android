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
    private var hasStarted = false
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
        if (hasStarted && !force) return@withLock
        hasStarted = true
        _state.update { current ->
            current.copy(
                phase = AppPhase.Launching,
                healthAvailability = health.availability(),
                healthMessage = null,
            )
        }
        when (val authState = auth.currentState()) {
            AuthSessionState.SignedOut -> enterSignedOut()
            AuthSessionState.TemporarilyUnavailable -> restoreOfflineIfPossible()
            is AuthSessionState.SignedIn -> reconcileSignedIn(authState)
        }
    }

    suspend fun prepareHealthConnection(): Boolean = healthMutex.withLock {
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
                val epoch = sessionEpoch
                pendingHealthConnection = PendingHealthConnection(epoch, memberKey)
                _state.update { it.copy(isConnectingHealth = true, healthMessage = null) }
                true
            }
            HealthConnectAvailability.InstallOrUpdateRequired -> {
                _state.update { it.copy(healthMessage = "Install or update Health Connect, then try again.") }
                false
            }
            HealthConnectAvailability.OnboardingRequired -> {
                _state.update { it.copy(healthMessage = "Finish setting up Health Connect, then try again.") }
                false
            }
            HealthConnectAvailability.AppNotAllowed -> {
                _state.update {
                    it.copy(healthMessage = "This build isn't authorized for Health Connect. Contact Murph support.")
                }
                false
            }
            HealthConnectAvailability.Unsupported -> {
                _state.update { it.copy(healthMessage = "Health Connect isn't supported on this device.") }
                false
            }
            HealthConnectAvailability.TemporarilyUnavailable -> {
                _state.update { it.copy(healthMessage = "Health Connect isn't ready yet. Try again in a moment.") }
                false
            }
        }
    }

    suspend fun completeHealthPermissionFlow(permissionRequestCompleted: Boolean): Boolean =
        healthMutex.withLock {
            val pending = pendingHealthConnection
            if (
                !_state.value.isConnectingHealth ||
                pending == null ||
                pending.epoch != sessionEpoch
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
                identifyJunction(pending.memberKey, ConnectionIntent.Connect, epoch)
                if (epoch != sessionEpoch) return false
                health.configure()
                health.connectAfterPermissionRequest()
                if (epoch != sessionEpoch) return false
                localState.healthAccessRequestedAt = InstantValue(now().toEpochMilli())
                pendingHealthConnection = null
                _state.update { current ->
                    current.copy(
                        isConnectingHealth = false,
                        healthSync = HealthSyncState.AwaitingFirstData,
                        grantedResourceCount = health.grantedResourceCount(),
                        backgroundSyncEnabled = health.isBackgroundSyncEnabled(),
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

    fun cancelHealthPermissionFlow() {
        val pending = pendingHealthConnection ?: return
        if (pending.epoch != sessionEpoch) return
        pendingHealthConnection = null
        _state.update { it.copy(isConnectingHealth = false) }
    }

    suspend fun syncNow() {
        if (
            _state.value.phase != AppPhase.Ready ||
            !healthWasRequested() ||
            health.grantedResourceCount() == 0
        ) return
        healthMutex.withLock {
            val epoch = sessionEpoch
            syncAndRefresh(epoch)
        }
    }

    suspend fun didBecomeActive() {
        if (!needsForegroundRefresh) return
        needsForegroundRefresh = false
        if (!reconcileForegroundAuth()) return
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
                backgroundSyncEnabled = health.isBackgroundSyncEnabled(),
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

    suspend fun prepareBackgroundSync(): Boolean = healthMutex.withLock {
        if (
            _state.value.phase != AppPhase.Ready ||
            !healthWasRequested() ||
            health.grantedResourceCount() == 0
        ) {
            return false
        }
        fetchValidatedHealthStatus(sessionEpoch) != null
    }

    fun onBackgroundSyncResult(enabled: Boolean) {
        _state.update { current ->
            current.copy(
                backgroundSyncEnabled = enabled || health.isBackgroundSyncEnabled(),
                healthMessage = if (enabled) null else "Background sync stayed off. Foreground sync still works.",
            )
        }
    }

    suspend fun disableBackgroundSync() {
        try {
            health.disableBackgroundSync()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Best effort: the SDK remains the source of truth for the rendered state.
        }
        _state.update { current ->
            current.copy(backgroundSyncEnabled = health.isBackgroundSyncEnabled())
        }
    }

    suspend fun signOut() {
        if (_state.value.phase == AppPhase.Launching) return
        _state.update { it.copy(phase = AppPhase.Launching, healthMessage = null) }
        invalidateSessionEpoch()
        try {
            healthMutex.withLock { health.signOutSdk() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    phase = AppPhase.Failed(
                        message = "We couldn't safely reset health sync. Keep Murph open and try again.",
                        canRetry = false,
                        canSignOut = true,
                    ),
                )
            }
            return
        }
        try {
            auth.signOut()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    phase = AppPhase.Failed(
                        message = "We couldn't finish signing out. Try once more.",
                        canRetry = false,
                        canSignOut = true,
                    ),
                )
            }
            return
        }
        currentMemberKey = null
        localState.clearMemberScopedState()
        _state.value = AppUiState(
            phase = AppPhase.NeedsLogin,
            healthAvailability = health.availability(),
            totalResourceCount = health.totalResourceCount,
        )
    }

    private suspend fun reconcileSignedIn(authState: AuthSessionState.SignedIn) {
        val previousMemberKey = localState.memberKey
        val mustDistrustPersistedHealthSession =
            (health.isSignedIn() && !healthWasRequested()) ||
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
        if (!requested && authState.verifiedOnline && !verifyBackendMember(epoch)) {
            return
        }
        if (requested && authState.verifiedOnline) {
            try {
                identifyJunction(authState.memberKey, ConnectionIntent.Resume, epoch)
                if (epoch != sessionEpoch) return
                health.configure()
            } catch (error: CompanionApiException.ReconnectRequired) {
                if (!resetHealthSdkAtTrustBoundary()) return
                localState.healthAccessRequestedAt = null
                localState.lastKnownDataReceivedAt = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
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
                backgroundSyncEnabled = health.isBackgroundSyncEnabled(),
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
            healthMutex.withLock { syncAndRefresh(epoch) }
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
        _state.update { current ->
            current.copy(
                phase = AppPhase.Ready,
                authVerifiedOnline = false,
                healthSync = deriveCachedHealthState(),
                healthMessage = "You're offline. Saved sync status is shown until Murph reconnects.",
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
                enterSignedOut()
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
                if (
                    authState.memberKey != currentMemberKey ||
                    authState.memberKey != localState.memberKey ||
                    (health.isSignedIn() && !healthWasRequested())
                ) {
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
    ) {
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
        health.identify(memberKey = memberKey) {
            response.signInToken
        }
    }

    private suspend fun syncAndRefresh(epoch: Int) {
        if (epoch != sessionEpoch || _state.value.phase != AppPhase.Ready) return
        _state.update { it.copy(isSyncingHealth = true, healthMessage = null) }
        if (fetchValidatedHealthStatus(epoch) == null) {
            _state.update { it.copy(isSyncingHealth = false) }
            return
        }
        try {
            health.syncAllGrantedResources()
        } catch (error: CancellationException) {
            _state.update { it.copy(isSyncingHealth = false) }
            throw error
        } catch (_: Exception) {
            // Status refresh below still reports the last backend-confirmed receipt.
        }
        if (epoch != sessionEpoch) return
        try {
            fetchValidatedHealthStatus(epoch)
            if (epoch == sessionEpoch) {
                _state.update { it.copy(isSyncingHealth = false) }
            }
        } catch (error: CancellationException) {
            _state.update { it.copy(isSyncingHealth = false) }
            throw error
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
            _state.update { current ->
                current.copy(
                    isSyncingHealth = false,
                    healthSync = deriveCachedHealthState(),
                    healthMessage = "Murph couldn't verify your account. Saved status is still shown.",
                )
            }
            return null
        }
        if (epoch != sessionEpoch) return null
        localState.lastKnownDataReceivedAt = status.lastDataReceivedAt?.let {
            InstantValue(it.toEpochMilli())
        }
        _state.update { current ->
            current.copy(
                authVerifiedOnline = true,
                healthSync = HealthSyncState.derive(
                    requestedAt = healthRequestedAt(),
                    status = status,
                    now = now(),
                ),
                grantedResourceCount = health.grantedResourceCount(),
                backgroundSyncEnabled = health.isBackgroundSyncEnabled(),
            )
        }
        return status
    }

    private fun publishReadOnlyHealthState(message: String) {
        _state.update { current ->
            current.copy(
                authVerifiedOnline = false,
                isSyncingHealth = false,
                healthSync = deriveCachedHealthState(),
                healthMessage = message,
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
            publishBackendBootstrapFailure(
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
            false
        } catch (error: CompanionApiException.ConsentRequired) {
            publishBackendBootstrapFailure(
                message = CONSENT_REQUIRED_MESSAGE,
                canRetry = true,
            )
            false
        } catch (error: CompanionApiException.Unauthorized) {
            publishBackendBootstrapFailure(
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
            false
        } catch (_: Exception) {
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
        val cached = localState.lastKnownDataReceivedAt?.epochMilliseconds?.let(Instant::ofEpochMilli)
        return HealthSyncState.derive(
            requestedAt = healthRequestedAt(),
            status = cached?.let { CompanionSyncStatus(it, emptyMap()) },
            now = now(),
        )
    }

    private fun healthRequestedAt(): Instant? =
        localState.healthAccessRequestedAt?.epochMilliseconds?.let(Instant::ofEpochMilli)

    private fun healthWasRequested(): Boolean = healthRequestedAt() != null

    private fun invalidateSessionEpoch() {
        sessionEpoch += 1
        pendingHealthConnection = null
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
    )

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val CONSENT_REQUIRED_MESSAGE =
            "Murph needs your latest health consent. Complete it at withmurph.ai, then try again."
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
    }
}
