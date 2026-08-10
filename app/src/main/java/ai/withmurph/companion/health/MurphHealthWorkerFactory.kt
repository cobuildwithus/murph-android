package ai.withmurph.companion.health

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import ai.withmurph.companion.storage.SharedPreferencesLocalState

internal val vitalHealthWorkerClasses = setOf(
    "io.tryvital.vitalhealthconnect.workers.ResourceSyncStarter",
    "io.tryvital.vitalhealthconnect.workers.ResourceSyncWorker",
)

internal fun vitalHealthWorkerIsAuthorized(
    hasMemberOwner: Boolean,
    hasCommittedHealthSetup: Boolean,
    signOutPending: Boolean,
): Boolean = hasMemberOwner && hasCommittedHealthSetup && !signOutPending

/**
 * WorkManager's default initializer is removed, so its first on-demand startup
 * installs this factory before any worker is constructed. Gate Vital's durable
 * workers from persisted Murph authority so a killed sign-out process cannot
 * resume the old identity.
 */
class MurphHealthWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName !in vitalHealthWorkerClasses) return null
        val localState = SharedPreferencesLocalState(appContext)
        return if (
            vitalHealthWorkerIsAuthorized(
                hasMemberOwner = localState.memberKey != null,
                hasCommittedHealthSetup = localState.healthAccessRequestedAt != null,
                signOutPending = localState.signOutPending,
            )
        ) {
            // WorkManager's default reflection factory creates the pinned Vital worker.
            null
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
