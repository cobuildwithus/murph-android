package ai.withmurph.companion.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal const val vitalResourceSyncStarterClass =
    "io.tryvital.vitalhealthconnect.workers.ResourceSyncStarter"
internal const val vitalResourceSyncWorkerClass =
    "io.tryvital.vitalhealthconnect.workers.ResourceSyncWorker"
internal val vitalHealthWorkerClasses = setOf(
    vitalResourceSyncStarterClass,
    vitalResourceSyncWorkerClass,
)

/**
 * Process-local proof that the current authenticated member explicitly started a
 * foreground sync. A restarted process begins closed even when WorkManager still
 * has durable Vital requests, so headless reconstruction cannot infer authority
 * from stale preferences alone.
 */
internal object VitalHealthWorkerLease {
    private enum class Stage {
        LaunchAuthorized,
        Promoted,
        LaunchRejected,
    }

    private data class Lease(
        val memberKey: String,
        val stage: Stage,
    )

    private val lock = Any()
    private var lease: Lease? = null
    private var activeExecutionMemberKey: String? = null
    private val activeExecutionCount = MutableStateFlow(0)

    fun openFor(expectedMemberKey: String) {
        require(expectedMemberKey.isNotBlank())
        synchronized(lock) {
            check(lease == null) { "A Vital health worker lease is already open" }
            check(activeExecutionCount.value == 0) {
                "A Vital health worker execution is still active"
            }
            lease = Lease(expectedMemberKey, Stage.LaunchAuthorized)
        }
    }

    fun isOpenFor(expectedMemberKey: String?): Boolean = synchronized(lock) {
        expectedMemberKey != null &&
            lease?.memberKey == expectedMemberKey &&
            lease?.stage != Stage.LaunchRejected
    }

    fun isLaunchAuthorizedFor(expectedMemberKey: String): Boolean = synchronized(lock) {
        lease == Lease(expectedMemberKey, Stage.LaunchAuthorized)
    }

    fun markPromotedFor(expectedMemberKey: String): Boolean = synchronized(lock) {
        val current = lease
        if (
            current?.memberKey != expectedMemberKey ||
            current.stage != Stage.LaunchAuthorized
        ) {
            false
        } else {
            lease = current.copy(stage = Stage.Promoted)
            true
        }
    }

    fun rejectUnpromotedFor(expectedMemberKey: String) = synchronized(lock) {
        val current = lease
        if (
            current?.memberKey == expectedMemberKey &&
            current.stage == Stage.LaunchAuthorized
        ) {
            lease = current.copy(stage = Stage.LaunchRejected)
        }
    }

    fun rejectUnpromoted() = synchronized(lock) {
        val current = lease
        if (current?.stage == Stage.LaunchAuthorized) {
            lease = current.copy(stage = Stage.LaunchRejected)
        }
    }

    fun wasLaunchRejectedFor(expectedMemberKey: String): Boolean = synchronized(lock) {
        lease == Lease(expectedMemberKey, Stage.LaunchRejected)
    }

    fun closeFor(expectedMemberKey: String) = synchronized(lock) {
        if (lease?.memberKey == expectedMemberKey) {
            lease = null
        }
    }

    fun close() = synchronized(lock) {
        lease = null
    }

    /**
     * Called by the app-owned wrapper immediately before the pinned Vital child
     * worker body begins. Sharing [lock] with close makes a constructed-but-not-
     * started worker either join the drain or fail before reading health data.
     */
    fun beginExecutionFor(expectedMemberKey: String): Boolean = synchronized(lock) {
        val current = lease
        if (
            current?.memberKey != expectedMemberKey ||
            current.stage == Stage.LaunchRejected
        ) {
            false
        } else {
            check(
                activeExecutionMemberKey == null ||
                    activeExecutionMemberKey == expectedMemberKey,
            ) {
                "Vital health workers cannot span member leases"
            }
            activeExecutionMemberKey = expectedMemberKey
            activeExecutionCount.value += 1
            true
        }
    }

    fun finishExecutionFor(expectedMemberKey: String) = synchronized(lock) {
        check(
            activeExecutionCount.value > 0 &&
                activeExecutionMemberKey == expectedMemberKey,
        ) {
            "No Vital health worker execution is active"
        }
        activeExecutionCount.value -= 1
        if (activeExecutionCount.value == 0) activeExecutionMemberKey = null
    }

    suspend fun awaitNoActiveExecutions() {
        activeExecutionCount.first { it == 0 }
    }
}

internal fun vitalHealthWorkerIsAuthorized(
    hasMemberOwner: Boolean,
    hasCommittedHealthSetup: Boolean,
    signOutPending: Boolean,
    hasForegroundSyncLease: Boolean,
): Boolean =
    hasMemberOwner &&
        hasCommittedHealthSetup &&
        !signOutPending &&
        hasForegroundSyncLease

/**
 * WorkManager's default initializer is removed, so its first on-demand startup
 * installs this factory before any worker is constructed. Gate Vital's durable
 * workers from both persisted Murph authority and a process-local member lease.
 * A headless restarted process therefore rejects old work until the authenticated
 * app session performs its normal backend preflight and opens a new lease.
 */
class MurphHealthWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName !in vitalHealthWorkerClasses) return null
        val localState = SharedPreferencesLocalState(appContext)
        val memberKey = localState.memberKey
        return if (
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = memberKey != null,
                hasCommittedHealthSetup = localState.healthAccessRequestedAt != null,
                signOutPending = localState.signOutPending,
                hasForegroundSyncLease = VitalHealthWorkerLease.isOpenFor(memberKey),
            )
        ) {
            if (workerClassName == vitalResourceSyncStarterClass) {
                MurphVitalResourceSyncStarter(
                    appContext = appContext,
                    workerParameters = workerParameters,
                    expectedMemberKey = requireNotNull(memberKey),
                )
            } else {
                val delegate = createPinnedVitalResourceSyncWorker(
                    appContext,
                    workerParameters,
                ) ?: return RejectedVitalHealthWorker(appContext, workerParameters)
                QuiescenceTrackingVitalResourceSyncWorker(
                    appContext = appContext,
                    workerParameters = workerParameters,
                    expectedMemberKey = requireNotNull(memberKey),
                    delegate = delegate,
                )
            }
        } else {
            RejectedVitalHealthWorker(appContext, workerParameters)
        }
    }
}

/**
 * Runs the pinned Vital reader/uploader in this WorkManager coroutine while
 * tracking the actual delegated body. WorkInfo may become CANCELLED before that
 * body unwinds, so identity teardown waits on this finally rather than database
 * state alone.
 */
private class QuiescenceTrackingVitalResourceSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val expectedMemberKey: String,
    private val delegate: CoroutineWorker,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (!VitalHealthWorkerLease.beginExecutionFor(expectedMemberKey)) {
            return Result.failure()
        }
        return try {
            delegate.doWork()
        } finally {
            VitalHealthWorkerLease.finishExecutionFor(expectedMemberKey)
        }
    }
}

private fun createPinnedVitalResourceSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
): CoroutineWorker? = runCatching {
    Class.forName(vitalResourceSyncWorkerClass)
        .asSubclass(CoroutineWorker::class.java)
        .getConstructor(Context::class.java, WorkerParameters::class.java)
        .newInstance(appContext, workerParameters)
}.getOrNull()

private class RejectedVitalHealthWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result = Result.failure()
}
