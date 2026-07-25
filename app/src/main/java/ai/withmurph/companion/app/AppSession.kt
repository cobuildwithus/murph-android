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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private var sessionEpoch = 0
    private var currentMemberKey: String? = null

    private val _state = MutableStateFlow(
        AppUiState(totalResourceCount = health.totalResourceCount),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    suspend fun start() = startMutex.withLock {
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

    suspend fun didLogin() {
        start()
    }

    suspend fun retry() {
        start()
    }

    suspend fun prepareHealthConnection(): Boolean = healthMutex.withLock {
        if (_state.value.phase != AppPhase.Ready || _state.value.isConnectingHealth) return false
        val memberKey = currentMemberKey ?: return false
        return when (health.availability()) {
            HealthConnectAvailability.Available -> {
                _state.update { it.copy(isConnectingHealth = true, healthMessage = null) }
                val epoch = sessionEpoch
                try {
                    identifyJunction(memberKey, ConnectionIntent.Connect, epoch)
                    if (epoch != sessionEpoch) return false
                    health.configure()
                    true
                } catch (error: CancellationException) {
                    if (epoch == sessionEpoch) {
                        _state.update { it.copy(isConnectingHealth = false) }
                    }
                    throw error
                } catch (error: Exception) {
                    if (epoch == sessionEpoch) {
                        _state.update {
                            it.copy(
                                isConnectingHealth = false,
                                healthMessage = connectionErrorMessage(error),
                            )
                        }
                    }
                    false
                }
            }
            HealthConnectAvailability.InstallOrUpdateRequired -> {
                _state.update { it.copy(healthMessage = "Install or update Health Connect, then try again.") }
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
            if (!_state.value.isConnectingHealth) return false
            val epoch = sessionEpoch
            try {
                if (!permissionRequestCompleted) {
                    _state.update { current ->
                        current.copy(
                            isConnectingHealth = false,
                            grantedResourceCount = health.grantedResourceCount(),
                            healthMessage = "Choose at least one Health Connect category to connect Murph.",
                        )
                    }
                    return false
                }
                health.connectAfterPermissionRequest()
                if (epoch != sessionEpoch) return false
                localState.healthAccessRequestedAt = InstantValue(now().toEpochMilli())
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
                if (epoch == sessionEpoch) {
                    _state.update { it.copy(isConnectingHealth = false) }
                }
                throw error
            } catch (error: Exception) {
                if (epoch == sessionEpoch) {
                    _state.update { current ->
                        current.copy(
                            isConnectingHealth = false,
                            healthMessage = connectionErrorMessage(error),
                        )
                    }
                }
                false
            }
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
        sessionEpoch += 1
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
            sessionEpoch += 1
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
            val status = api.fetchSyncStatus(HEALTH_CONNECT_SOURCE)
            if (epoch != sessionEpoch) return
            localState.lastKnownDataReceivedAt = status.lastDataReceivedAt?.let {
                InstantValue(it.toEpochMilli())
            }
            _state.update { current ->
                current.copy(
                    isSyncingHealth = false,
                    healthSync = HealthSyncState.derive(
                        requested = healthWasRequested(),
                        status = status,
                        now = now(),
                    ),
                    grantedResourceCount = health.grantedResourceCount(),
                    backgroundSyncEnabled = health.isBackgroundSyncEnabled(),
                )
            }
        } catch (error: CancellationException) {
            _state.update { it.copy(isSyncingHealth = false) }
            throw error
        } catch (error: CompanionApiException.Unauthorized) {
            failCurrentSessionWhileHealthLocked(
                message = "Your session needs a refresh. Sign in again.",
                canRetry = false,
            )
        } catch (error: CompanionApiException.NoAccount) {
            failCurrentSessionWhileHealthLocked(
                message = "This sign-in isn't linked to an active Murph account.",
                canRetry = false,
            )
        } catch (error: CompanionApiException.ConsentRequired) {
            failCurrentSessionWhileHealthLocked(
                message = CONSENT_REQUIRED_MESSAGE,
                canRetry = true,
            )
        } catch (_: Exception) {
            if (epoch == sessionEpoch) {
                _state.update { current ->
                    current.copy(
                        isSyncingHealth = false,
                        healthSync = deriveCachedHealthState(),
                        healthMessage = "Murph couldn't refresh sync status. Saved status is still shown.",
                    )
                }
            }
        }
    }

    /** Called only from [syncAndRefresh], whose callers hold [healthMutex]. */
    private suspend fun failCurrentSessionWhileHealthLocked(
        message: String,
        canRetry: Boolean,
    ) {
        sessionEpoch += 1
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

    private suspend fun resetHealthSdkAtTrustBoundary(): Boolean {
        sessionEpoch += 1
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
            requested = healthWasRequested(),
            status = cached?.let { CompanionSyncStatus(it, emptyMap()) },
            now = now(),
        )
    }

    private fun healthWasRequested(): Boolean = localState.healthAccessRequestedAt != null

    private fun connectionErrorMessage(error: Exception): String = when (error) {
        CompanionApiException.Network -> "Murph couldn't reach the network. Check your connection and try again."
        CompanionApiException.Unauthorized -> "Your session needs a refresh. Sign in again."
        CompanionApiException.NoAccount -> "This sign-in isn't linked to an active Murph account."
        CompanionApiException.ConsentRequired -> CONSENT_REQUIRED_MESSAGE
        CompanionApiException.ReconnectRequired -> "Reconnect Health Connect to resume syncing."
        else -> "Murph couldn't finish connecting Health Connect. Try again in a moment."
    }

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val CONSENT_REQUIRED_MESSAGE =
            "Murph needs your latest health consent. Complete it at withmurph.ai, then try again."
        const val HEALTH_PERMISSION_RECOVERY_MESSAGE =
            "Health Connect access is off. Reconnect and choose at least one category."
    }
}
