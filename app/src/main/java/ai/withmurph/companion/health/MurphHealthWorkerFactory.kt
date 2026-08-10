package ai.withmurph.companion.health

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import java.util.concurrent.atomic.AtomicReference

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
    private val memberKey = AtomicReference<String?>(null)

    fun openFor(expectedMemberKey: String) {
        require(expectedMemberKey.isNotBlank())
        check(memberKey.compareAndSet(null, expectedMemberKey)) {
            "A Vital health worker lease is already open"
        }
    }

    fun isOpenFor(expectedMemberKey: String?): Boolean =
        expectedMemberKey != null && memberKey.get() == expectedMemberKey

    fun closeFor(expectedMemberKey: String) {
        memberKey.compareAndSet(expectedMemberKey, null)
    }

    fun close() {
        memberKey.set(null)
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
                // Preserve Vital's pinned per-resource reader and uploader.
                null
            }
        } else {
            RejectedVitalHealthWorker(appContext, workerParameters)
        }
    }
}

private class RejectedVitalHealthWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result = Result.failure()
}
