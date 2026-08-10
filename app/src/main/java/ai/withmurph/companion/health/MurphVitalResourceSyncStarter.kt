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
private const val failedResourcesOutputKey = "failedResources"

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

internal fun vitalFailedResourcesOutputData(resources: Set<VitalResource>): Data =
    Data.Builder()
        .putStringArray(
            failedResourcesOutputKey,
            resources.map(VitalResource::toString).toTypedArray(),
        )
        .build()

internal fun vitalFailedResources(outputData: Data): Set<VitalResource>? =
    outputData.getStringArray(failedResourcesOutputKey)
        ?.let { values ->
            runCatching {
                values.mapTo(linkedSetOf(), VitalResource::valueOf)
            }.getOrNull()
        }
        ?.takeIf { it.isNotEmpty() }

internal fun newVitalStarterFailedResources(
    workInfos: List<WorkInfo>,
    existingIds: Set<java.util.UUID>,
): Set<VitalResource>? = workInfos
    .filter { it.id !in existingIds && it.state == WorkInfo.State.FAILED }
    .mapNotNull { vitalFailedResources(it.outputData) }
    .singleOrNull()

internal fun vitalDataSyncForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }

internal enum class VitalResourceTerminalDecision {
    Continue,
    ContinueAfterFailure,
    Stop,
}

internal fun vitalResourceTerminalDecision(
    state: WorkInfo.State?,
): VitalResourceTerminalDecision = when (state) {
    WorkInfo.State.SUCCEEDED -> VitalResourceTerminalDecision.Continue
    WorkInfo.State.FAILED -> VitalResourceTerminalDecision.ContinueAfterFailure
    else -> VitalResourceTerminalDecision.Stop
}

internal fun vitalResourcesFailedByHardStop(
    orderedResources: List<VitalResource>,
    stoppedAtIndex: Int,
): Set<VitalResource> {
    require(stoppedAtIndex in orderedResources.indices)
    return orderedResources.drop(stoppedAtIndex).toCollection(linkedSetOf())
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
        val resources = runCatching { vitalStarterResources(inputData) }.getOrNull()
            ?: return Result.failure()
        if (resources.isEmpty()) return Result.success()
        if (!inputData.getBoolean(startForegroundInputKey, false)) {
            return Result.failure(vitalFailedResourcesOutputData(resources))
        }
        if (!VitalHealthWorkerLease.isOpenFor(expectedMemberKey)) {
            return Result.failure(vitalFailedResourcesOutputData(resources))
        }
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
            return Result.failure(vitalFailedResourcesOutputData(resources))
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
            return Result.failure(vitalFailedResourcesOutputData(resources))
        }
        if (!VitalHealthWorkerLease.markPromotedFor(expectedMemberKey)) {
            return Result.failure(vitalFailedResourcesOutputData(resources))
        }

        val tags = inputData.getIntArray(tagsInputKey) ?: intArrayOf()
        val workerClass = runCatching {
            Class.forName(vitalResourceSyncWorkerClass)
                .asSubclass(ListenableWorker::class.java)
        }.getOrElse {
            return Result.failure(vitalFailedResourcesOutputData(resources))
        }
        val workManager = WorkManager.getInstance(applicationContext)

        val orderedResources = resources.sortedBy(VitalResource::priority)
        val failedResources = linkedSetOf<VitalResource>()
        for ((resourceIndex, resource) in orderedResources.withIndex()) {
            if (!VitalHealthWorkerLease.isOpenFor(expectedMemberKey)) {
                failedResources += vitalResourcesFailedByHardStop(
                    orderedResources,
                    resourceIndex,
                )
                return Result.failure(vitalFailedResourcesOutputData(failedResources))
            }
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
                when (
                    vitalResourceTerminalDecision(
                        workManager.awaitTerminalWorkInfo(request.id)?.state,
                    )
                ) {
                    VitalResourceTerminalDecision.Continue -> Unit
                    VitalResourceTerminalDecision.ContinueAfterFailure -> {
                        failedResources += resource
                    }
                    VitalResourceTerminalDecision.Stop -> {
                        failedResources += vitalResourcesFailedByHardStop(
                            orderedResources,
                            resourceIndex,
                        )
                        return Result.failure(vitalFailedResourcesOutputData(failedResources))
                    }
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    workManager.cancelUniqueWork(workName).result.await()
                }
                throw error
            } catch (_: Exception) {
                failedResources += vitalResourcesFailedByHardStop(
                    orderedResources,
                    resourceIndex,
                )
                return Result.failure(vitalFailedResourcesOutputData(failedResources))
            }
        }
        return if (failedResources.isEmpty()) {
            Result.success()
        } else {
            Result.failure(vitalFailedResourcesOutputData(failedResources))
        }
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
