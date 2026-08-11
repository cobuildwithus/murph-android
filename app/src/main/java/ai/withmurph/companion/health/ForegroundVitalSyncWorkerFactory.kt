package ai.withmurph.companion.health

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Process-local admission for Vital's WorkManager chain.
 *
 * A fresh process starts closed. AppSession can open one lease only while its
 * foreground, member, consent, and setup claims still own the sync entrypoint.
 * Backgrounding or any identity boundary revokes the lease before cancellation,
 * so persisted vendor work cannot restart by merely recreating the process.
 */
internal object ForegroundVitalSyncAdmission {
    private val lock = Any()
    private var nextLeaseId = 0L
    private var activeLeaseId: Long? = null

    fun open(): Lease = synchronized(lock) {
        check(activeLeaseId == null) { "Vital sync admission is already owned" }
        nextLeaseId += 1
        Lease(nextLeaseId).also { activeLeaseId = it.id }
    }

    fun close(lease: Lease) {
        synchronized(lock) {
            if (activeLeaseId == lease.id) activeLeaseId = null
        }
    }

    fun revoke() {
        synchronized(lock) { activeLeaseId = null }
    }

    fun allowsWorker(): Boolean = synchronized(lock) { activeLeaseId != null }

    @JvmInline
    value class Lease internal constructor(internal val id: Long)
}

/**
 * WorkManager asks this factory before reflectively constructing any worker.
 * Only Vital's exact pinned worker classes are intercepted; every other worker
 * continues through WorkManager's normal default factory.
 */
internal class ForegroundVitalSyncWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (!isVitalSyncWorkerClassName(workerClassName)) return null
        return if (ForegroundVitalSyncAdmission.allowsWorker()) {
            null
        } else {
            ClosedVitalSyncWorker(appContext, workerParameters)
        }
    }

    companion object {
        internal const val STARTER_CLASS_NAME =
            "io.tryvital.vitalhealthconnect.workers.ResourceSyncStarter"
        internal const val RESOURCE_CLASS_NAME =
            "io.tryvital.vitalhealthconnect.workers.ResourceSyncWorker"

        internal fun isVitalSyncWorkerClassName(workerClassName: String): Boolean =
            workerClassName == STARTER_CLASS_NAME || workerClassName == RESOURCE_CLASS_NAME
    }
}

private class ClosedVitalSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result = Result.failure()
}
