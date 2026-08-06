package ai.withmurph.companion.meal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import ai.withmurph.companion.app.AppConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal interface MealPhotoWorkScheduling {
    suspend fun schedule(): Boolean
    suspend fun cancel(): Boolean
}

internal class WorkManagerMealPhotoScheduler(context: Context) : MealPhotoWorkScheduling {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun schedule(): Boolean {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MealPhotoCaptureWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        return awaitMealPhotoWorkOperation {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    override suspend fun cancel(): Boolean = awaitMealPhotoWorkCancellation {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private companion object {
        const val REPEAT_HOURS = 6L
        const val UNIQUE_WORK_NAME = "murph-meal-photo-capture"
        const val WORK_TAG = "meal-photo-capture"
    }
}

internal suspend fun awaitMealPhotoWorkCancellation(cancel: () -> Operation): Boolean =
    awaitMealPhotoWorkOperation(cancel)

internal suspend fun awaitMealPhotoWorkOperation(operation: () -> Operation): Boolean =
    runCatching {
        operation().await()
        true
    }.getOrDefault(false)

internal object MealPhotoCaptureRuntime {
    val processorMutex = Mutex()

    fun processor(context: Context): MealPhotoProcessor {
        val applicationContext = context.applicationContext
        return MealPhotoProcessor(
            media = AndroidMealPhotoMediaSource(applicationContext),
            classifier = MlKitMealPhotoImageClassifier(),
            uploader = HttpMealPhotoUploader(AppConfig.current.backendBaseUrl),
            stateStore = SharedPreferencesMealPhotoStateStore(applicationContext),
            credentialStore = KeystoreMealPhotoCredentialStore(applicationContext),
            authorizationStore = SharedPreferencesMealPhotoAuthorizationStore(applicationContext),
        )
    }
}

internal class MealPhotoCaptureWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = MealPhotoCaptureRuntime.processorMutex.withLock {
        when (MealPhotoCaptureRuntime.processor(applicationContext).process()) {
            MealPhotoProcessingResult.Pending -> Result.retry()
            MealPhotoProcessingResult.Inactive,
            MealPhotoProcessingResult.Completed,
            MealPhotoProcessingResult.NeedsAttention,
            -> Result.success()
        }
    }
}
