package ai.withmurph.companion.health

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.tryvital.vitalhealthconnect.VitalHealthConnectManager
import io.tryvital.vitalhealthcore.model.VitalResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

private const val syncNotificationId = 123
private const val resourcesInputKey = "resources"
private const val startForegroundInputKey = "startForeground"
private const val resourceInputKey = "resource"
private const val tagsInputKey = "tags"

internal fun vitalStarterResources(inputData: Data): Set<VitalResource>? =
    inputData.getStringArray(resourcesInputKey)
        ?.mapTo(linkedSetOf(), VitalResource::valueOf)
        ?.let(::configuredHealthConnectReadResources)

internal fun vitalResourceWorkerInputData(
    resource: VitalResource,
    tags: IntArray,
): Data = Data.Builder()
    .putString(resourceInputKey, resource.toString())
    .putIntArray(tagsInputKey, tags)
    .build()

internal fun vitalDataSyncForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }

/**
 * Vital 5.0.2's umbrella worker declares shortService, which Android limits to
 * roughly three minutes. This replacement preserves the SDK's input contract,
 * notification, unique child names, and real per-resource reader/uploader while
 * keeping the explicit member-requested transfer under the dataSync service type.
 */
internal class MurphVitalResourceSyncStarter(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val expectedMemberKey: String,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (!inputData.getBoolean(startForegroundInputKey, false)) return Result.failure()
        if (!VitalHealthWorkerLease.isOpenFor(expectedMemberKey)) return Result.failure()
        val resources = runCatching { vitalStarterResources(inputData) }.getOrNull()
            ?: return Result.failure()
        if (resources.isEmpty()) return Result.success()
        setProgress(
            Data.Builder()
                .putStringArray(
                    resourcesInputKey,
                    resources.map(VitalResource::toString).toTypedArray(),
                )
                .build(),
        )
        val notification = VitalHealthConnectManager
            .syncNotificationBuilder(applicationContext)
            .build(applicationContext, resources)
        if (
            !ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED) ||
            !VitalHealthWorkerLease.isLaunchAuthorizedFor(expectedMemberKey)
        ) {
            VitalHealthWorkerLease.rejectUnpromotedFor(expectedMemberKey)
            return Result.failure()
        }
        try {
            setForeground(
                ForegroundInfo(
                    syncNotificationId,
                    notification,
                    vitalDataSyncForegroundServiceType(),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            VitalHealthWorkerLease.rejectUnpromotedFor(expectedMemberKey)
            return Result.failure()
        }
        if (!VitalHealthWorkerLease.markPromotedFor(expectedMemberKey)) {
            return Result.failure()
        }

        val tags = inputData.getIntArray(tagsInputKey) ?: intArrayOf()
        val workerClass = runCatching {
            Class.forName(vitalResourceSyncWorkerClass)
                .asSubclass(ListenableWorker::class.java)
        }.getOrElse { return Result.failure() }
        val workManager = WorkManager.getInstance(applicationContext)

        for (resource in resources.sortedBy(VitalResource::priority)) {
            if (!VitalHealthWorkerLease.isOpenFor(expectedMemberKey)) return Result.failure()
            val workName = vitalResourceSyncWorkerName(resource)
            val request = OneTimeWorkRequest.Builder(workerClass)
                .setInputData(vitalResourceWorkerInputData(resource, tags))
                .addTag(resource.name)
                .build()
            try {
                workManager.beginUniqueWork(
                    workName,
                    ExistingWorkPolicy.REPLACE,
                    request,
                ).enqueue().result.await()
                when (workManager.awaitTerminalWorkInfo(request.id)?.state) {
                    WorkInfo.State.SUCCEEDED -> Unit
                    else -> return Result.failure()
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    workManager.cancelUniqueWork(workName).result.await()
                }
                throw error
            }
        }
        return Result.success()
    }
}

private suspend fun WorkManager.awaitTerminalWorkInfo(
    id: java.util.UUID,
): WorkInfo? {
    while (true) {
        val workInfo = getWorkInfoById(id).await() ?: return null
        if (workInfo.state.isFinished) return workInfo
        delay(100)
    }
}
