package ai.withmurph.companion.meal

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import ai.withmurph.companion.core.MealPhotoActionResult
import ai.withmurph.companion.core.MealPhotoReviewItem
import ai.withmurph.companion.core.MealPhotoReviewStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

internal fun newMealPhotoMediaExecutor(
    threadName: String = "murph-meal-media",
): ThreadPoolExecutor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    SynchronousQueue(),
) { runnable ->
    Thread(runnable, threadName).apply { isDaemon = true }
}

internal fun executeMealPhotoMediaTask(
    executor: ThreadPoolExecutor,
    task: Runnable,
    canHandoff: () -> Boolean = { true },
    handoffGraceNanos: Long = MEDIA_HANDOFF_GRACE_NANOS,
) {
    val deadline = System.nanoTime() + handoffGraceNanos
    val rejection = try {
        executor.execute(task)
        return
    } catch (error: RejectedExecutionException) {
        if (executor.isShutdown) throw error
        error
    }
    while (canHandoff()) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) break
        val waitNanos = minOf(remaining, MEDIA_HANDOFF_POLL_NANOS)
        try {
            if (executor.queue.offer(task, waitNanos, TimeUnit.NANOSECONDS)) return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    }
    throw rejection
}

private const val MEDIA_HANDOFF_GRACE_NANOS = 50L * 1_000_000
private const val MEDIA_HANDOFF_POLL_NANOS = 500_000L

internal enum class MealPhotoMediaAccess {
    None,
    Partial,
    Full,
}

internal data class MealPhotoCandidate(
    val volumeName: String,
    val volumeVersion: String,
    val mediaId: Long,
    val generation: Long,
    val modifiedGeneration: Long,
    val contentUri: String,
    val capturedAtEpochMillis: Long,
    val mimeType: String,
    val isScreenshot: Boolean,
    val isCameraOrigin: Boolean,
)

internal fun MealPhotoCandidate.matches(record: MealPhotoReviewRecord): Boolean =
    volumeName == record.volumeName &&
        volumeVersion == record.volumeVersion &&
        mediaId == record.mediaId &&
        generation == record.generation &&
        modifiedGeneration == record.modifiedGeneration &&
        contentUri == record.contentUri &&
        capturedAtEpochMillis == record.capturedAtEpochMillis &&
        mimeType == record.mimeType &&
        !isScreenshot

internal interface MealPhotoMediaSource {
    val automaticCaptureSupported: Boolean
    fun access(): MealPhotoMediaAccess
    fun permissionRequest(): Array<String>
    suspend fun currentBoundaries(): List<MealPhotoVolumeCursor>
    suspend fun candidatesAfter(cursor: MealPhotoVolumeCursor, limit: Int): List<MealPhotoCandidate>
    suspend fun revalidatedCandidate(record: MealPhotoReviewRecord): MealPhotoCandidateValidation
    suspend fun sanitizedImage(
        contentUri: String,
        maximumDimension: Int = 1_280,
    ): MealPhotoImageAsset?
}

internal sealed interface MealPhotoCandidateValidation {
    data class Valid(val candidate: MealPhotoCandidate) : MealPhotoCandidateValidation
    data object Stale : MealPhotoCandidateValidation
    data object Unavailable : MealPhotoCandidateValidation
}

internal class AndroidMealPhotoMediaSource(
    private val context: Context,
) : MealPhotoMediaSource {
    private val resolver = context.contentResolver

    override val automaticCaptureSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun access(): MealPhotoMediaAccess {
        val fullPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, fullPermission) == PackageManager.PERMISSION_GRANTED) {
            return MealPhotoMediaAccess.Full
        }
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            MealPhotoMediaAccess.Partial
        } else {
            MealPhotoMediaAccess.None
        }
    }

    override fun permissionRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun currentBoundaries(): List<MealPhotoVolumeCursor> =
        withContext(Dispatchers.IO) {
            check(automaticCaptureSupported && access() == MealPhotoMediaAccess.Full)
            val volumeNames = externalVolumeNames()
                .asSequence()
                .filterNot { it == MediaStore.VOLUME_INTERNAL }
                .sorted()
                .take(MealPhotoCaptureConfiguration.MAX_VOLUME_COUNT)
                .toList()
            require(volumeNames.isNotEmpty())
            val boundaries = mutableListOf<MealPhotoVolumeCursor>()
            for (volumeName in volumeNames) {
                currentCoroutineContext().ensureActive()
                boundaries += volumeBoundary(volumeName)
            }
            boundaries
        }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun candidatesAfter(
        cursor: MealPhotoVolumeCursor,
        limit: Int,
    ): List<MealPhotoCandidate> = withContext(Dispatchers.IO) {
        check(automaticCaptureSupported && access() == MealPhotoMediaAccess.Full)
        require(limit in 1..MAX_QUERY_ITEMS)
        val collection = MediaStore.Images.Media.getContentUri(cursor.volumeName)
        val query = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.MediaColumns.GENERATION_ADDED} > ?) OR " +
                    "(${MediaStore.MediaColumns.GENERATION_ADDED} = ? AND " +
                    "${MediaStore.MediaColumns._ID} > ?)",
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    cursor.generation.toString(),
                    cursor.generation.toString(),
                    cursor.mediaId.toString(),
                ),
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns._ID),
            )
            putInt(
                android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            )
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val processingContext = currentCoroutineContext()
        cancellableQuery(collection, query) { rows ->
            val idColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val generationColumn = rows.getColumnIndexOrThrow(
                MediaStore.MediaColumns.GENERATION_ADDED,
            )
            val modifiedGenerationColumn = rows.getColumnIndexOrThrow(
                MediaStore.MediaColumns.GENERATION_MODIFIED,
            )
            val capturedAtColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedAtColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val relativePathColumn = rows.getColumnIndexOrThrow(
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            val displayNameColumn = rows.getColumnIndexOrThrow(
                MediaStore.MediaColumns.DISPLAY_NAME,
            )
            buildList {
                while (rows.moveToNext()) {
                    processingContext.ensureActive()
                    val mediaId = rows.getLong(idColumn)
                    val addedAt = rows.getLong(addedAtColumn).coerceAtLeast(0) * 1_000
                    val capturedAt = trustworthyCapturedAt(
                        dateTaken = rows.getLong(capturedAtColumn),
                        dateAdded = addedAt,
                    )
                    val relativePath = rows.getString(relativePathColumn).orEmpty()
                    val displayName = rows.getString(displayNameColumn).orEmpty()
                    add(
                        MealPhotoCandidate(
                            volumeName = cursor.volumeName,
                            volumeVersion = cursor.version,
                            mediaId = mediaId,
                            generation = rows.getLong(generationColumn),
                            modifiedGeneration = rows.getLong(modifiedGenerationColumn),
                            contentUri = ContentUris.withAppendedId(collection, mediaId).toString(),
                            capturedAtEpochMillis = capturedAt,
                            mimeType = rows.getString(mimeColumn).orEmpty().lowercase(),
                            isScreenshot = isScreenshot(relativePath, displayName),
                            isCameraOrigin = isCameraOrigin(relativePath),
                        ),
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun revalidatedCandidate(
        record: MealPhotoReviewRecord,
    ): MealPhotoCandidateValidation = withContext(Dispatchers.IO) {
        if (!automaticCaptureSupported || access() != MealPhotoMediaAccess.Full) {
            return@withContext MealPhotoCandidateValidation.Unavailable
        }
        val volumes = runCatching { externalVolumeNames() }.getOrNull()
            ?: return@withContext MealPhotoCandidateValidation.Unavailable
        if (record.volumeName !in volumes) {
            return@withContext MealPhotoCandidateValidation.Unavailable
        }
        val boundary = try {
            volumeBoundary(record.volumeName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext MealPhotoCandidateValidation.Unavailable
        }
        if (boundary.version != record.volumeVersion) {
            return@withContext MealPhotoCandidateValidation.Stale
        }
        val contentUri = runCatching { Uri.parse(record.contentUri) }.getOrNull()
            ?: return@withContext MealPhotoCandidateValidation.Stale
        val candidate = try {
            cancellableQuery(contentUri, Bundle.EMPTY) { rows ->
                if (!rows.moveToFirst() || rows.count != 1) return@cancellableQuery null
                val id = rows.getLong(rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val generation = rows.getLong(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED),
                )
                val modifiedGeneration = rows.getLong(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED),
                )
                val addedAt = rows.getLong(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED),
                ).coerceAtLeast(0) * 1_000
                val capturedAt = trustworthyCapturedAt(
                    dateTaken = rows.getLong(
                        rows.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN),
                    ),
                    dateAdded = addedAt,
                )
                val mimeType = rows.getString(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE),
                ).orEmpty().lowercase()
                val relativePath = rows.getString(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH),
                ).orEmpty()
                val displayName = rows.getString(
                    rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),
                ).orEmpty()
                MealPhotoCandidate(
                    volumeName = record.volumeName,
                    volumeVersion = record.volumeVersion,
                    mediaId = id,
                    generation = generation,
                    modifiedGeneration = modifiedGeneration,
                    contentUri = contentUri.toString(),
                    capturedAtEpochMillis = capturedAt,
                    mimeType = mimeType,
                    isScreenshot = isScreenshot(relativePath, displayName),
                    isCameraOrigin = isCameraOrigin(relativePath),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext MealPhotoCandidateValidation.Unavailable
        } ?: return@withContext MealPhotoCandidateValidation.Stale
        MealPhotoCandidateValidation.Valid(candidate)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun sanitizedImage(
        contentUri: String,
        maximumDimension: Int,
    ): MealPhotoImageAsset? = withContext(Dispatchers.IO) {
        require(maximumDimension in 320..1_280)
        val signal = CancellationSignal()
        try {
            cancellableBlocking<MealPhotoImageAsset?>(
                cancel = signal::cancel,
                dispose = { asset -> asset?.close() },
            ) {
                val imageSource = ImageDecoder.createSource {
                    // ImageDecoder owns the descriptor returned by this callback. Closing it
                    // concurrently from cancellation races the decoder's duplicated native fd.
                    // Cancellation instead abandons this one bounded daemon lane; late output is
                    // recycled and the no-backlog executor rejects all further media work.
                    resolver.openAssetFileDescriptor(
                        Uri.parse(contentUri),
                        "r",
                        signal,
                    )
                        ?: throw IOException("Photo asset is unavailable")
                }
                val source = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val width = info.size.width
                    val height = info.size.height
                    if (width > 0 && height > 0) {
                        val scale = minOf(1.0, maximumDimension.toDouble() / max(width, height))
                        decoder.setTargetSize(
                            max(1, (width * scale).roundToInt()),
                            max(1, (height * scale).roundToInt()),
                        )
                    }
                }
                try {
                    if (signal.isCanceled || Thread.currentThread().isInterrupted) {
                        throw OperationCanceledException()
                    }
                    MealPhotoImageSanitizer.sanitize(source, maximumDimension)
                } finally {
                    source.recycle()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext null
        }
    }

    @SuppressLint("NewApi") // getGeneration is present on the Android 11+ feature floor.
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun volumeBoundary(volumeName: String): MealPhotoVolumeCursor = try {
        withTimeout(MEDIA_METADATA_TIMEOUT_MILLIS) {
            cancellableBlocking(cancel = {}) {
                val version = MediaStore.getVersion(context, volumeName)
                    .takeIf(String::isNotBlank)
                    ?: throw IOException("MediaStore version is unavailable")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                MealPhotoVolumeCursor(
                    volumeName = volumeName,
                    version = version,
                    generation = MediaStore.getGeneration(context, volumeName),
                    mediaId = Long.MAX_VALUE,
                )
            }
        }
    } catch (error: TimeoutCancellationException) {
        throw IOException("MediaStore metadata timed out", error)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun externalVolumeNames(): Set<String> =
        cancellableBlocking(cancel = {}) {
            MediaStore.getExternalVolumeNames(context)
        }

    private suspend fun <T> cancellableQuery(
        uri: Uri,
        query: Bundle,
        read: (Cursor) -> T,
    ): T {
        val signal = CancellationSignal()
        val cancelled = AtomicBoolean(false)
        val cursorReference = AtomicReference<Cursor?>()
        return cancellableBlocking(
            cancel = {
                cancelled.set(true)
                signal.cancel()
                cursorReference.getAndSet(null)?.close()
            },
        ) {
            if (cancelled.get()) throw OperationCanceledException()
            val cursor = resolver.query(uri, PROJECTION, query, signal)
                ?: throw IOException("Media query is unavailable")
            if (!cursorReference.compareAndSet(null, cursor) || cancelled.get()) {
                cursor.close()
                throw OperationCanceledException()
            }
            try {
                read(cursor)
            } finally {
                cursorReference.compareAndSet(cursor, null)
                cursor.close()
            }
        }
    }

    private suspend fun <T> cancellableBlocking(
        cancel: () -> Unit,
        dispose: (T) -> Unit = {},
        block: () -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val futureReference = AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation {
            runCatching(cancel)
            futureReference.getAndSet(null)?.cancel(true)
        }
        val future = FutureTask<Unit> {
            try {
                val value = block()
                if (continuation.isActive) {
                    continuation.resume(value) { _, cancelledValue, _ ->
                        runCatching { dispose(cancelledValue) }
                    }
                } else {
                    runCatching { dispose(value) }
                }
            } catch (error: OperationCanceledException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        CancellationException("Media provider operation cancelled").apply {
                            initCause(error)
                        },
                    )
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        try {
            executeMealPhotoMediaTask(
                executor = MEDIA_EXECUTOR,
                task = future,
                canHandoff = { continuation.isActive },
            )
        } catch (error: RejectedExecutionException) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    IOException("Media provider lane is unavailable", error),
                )
            }
            return@suspendCancellableCoroutine
        }
        if (!futureReference.compareAndSet(null, future) || !continuation.isActive) {
            future.cancel(true)
        }
    }

    private companion object {
        const val MAX_QUERY_ITEMS = 64
        const val MEDIA_METADATA_TIMEOUT_MILLIS = 5_000L
        val MEDIA_EXECUTOR = newMealPhotoMediaExecutor()
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )

        fun isScreenshot(relativePath: String, displayName: String): Boolean =
            relativePath.split('/').any { it.equals("screenshots", ignoreCase = true) } ||
                displayName.contains("screenshot", ignoreCase = true)

        fun isCameraOrigin(relativePath: String): Boolean {
            val components = relativePath.split('/').filter(String::isNotBlank)
            return components.firstOrNull()?.equals("dcim", ignoreCase = true) == true &&
                components.any { it.equals("camera", ignoreCase = true) }
        }

        fun trustworthyCapturedAt(dateTaken: Long, dateAdded: Long): Long {
            val now = System.currentTimeMillis()
            val safeDateAdded = dateAdded.takeIf {
                it in 1..(now + FUTURE_TIMESTAMP_TOLERANCE_MILLIS)
            } ?: return 0
            val latestAllowed = minOf(
                now + FUTURE_TIMESTAMP_TOLERANCE_MILLIS,
                safeDateAdded + 86_400_000,
            )
            val earliestAllowed =
                (safeDateAdded - MAX_CAPTURE_TO_INSERT_DELAY_MILLIS).coerceAtLeast(0)
            return dateTaken.takeIf { it in earliestAllowed..latestAllowed } ?: safeDateAdded
        }

        const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 5 * 60 * 1_000L
        const val MAX_CAPTURE_TO_INSERT_DELAY_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}

internal interface MealPhotoImageAsset : AutoCloseable {
    val jpeg: ByteArray
    suspend fun classify(classifier: MealPhotoImageClassifier): List<MealPhotoClassificationObservation>
}

internal data class SanitizedMealImage(
    val bitmap: Bitmap,
    override val jpeg: ByteArray,
) : MealPhotoImageAsset {
    override suspend fun classify(
        classifier: MealPhotoImageClassifier,
    ): List<MealPhotoClassificationObservation> = classifier.classify(bitmap)

    override fun close() {
        bitmap.recycle()
        jpeg.fill(0)
    }
}

internal object MealPhotoImageSanitizer {
    const val MAXIMUM_BYTE_COUNT = 1 * 1_024 * 1_024
    private val DIMENSIONS = intArrayOf(1_280, 1_120, 960)
    private val QUALITIES = intArrayOf(72, 62, 52)

    fun sanitize(source: Bitmap, requestedMaximumDimension: Int = 1_280): SanitizedMealImage? {
        if (source.width <= 0 || source.height <= 0) return null
        val dimensions = DIMENSIONS
            .map { minOf(it, requestedMaximumDimension) }
            .distinct()
        for (dimension in dimensions) {
            val rendered = renderOpaque(source, dimension) ?: continue
            for (quality in QUALITIES) {
                val encoded = ByteArrayOutputStream().use { bytes ->
                    if (!rendered.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) null
                    else removeMetadataSegments(bytes.toByteArray())
                }
                if (encoded != null && encoded.size <= MAXIMUM_BYTE_COUNT) {
                    return SanitizedMealImage(rendered, encoded)
                }
            }
            rendered.recycle()
        }
        return null
    }

    private fun renderOpaque(source: Bitmap, maximumDimension: Int): Bitmap? = runCatching {
        val scale = minOf(
            1.0,
            maximumDimension.toDouble() / max(source.width, source.height),
        )
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rendered ->
            Canvas(rendered).apply {
                drawColor(Color.BLACK)
                drawBitmap(
                    source,
                    Rect(0, 0, source.width, source.height),
                    Rect(0, 0, width, height),
                    null,
                )
            }
        }
    }.getOrNull()

    internal fun removeMetadataSegments(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val output = ByteArrayOutputStream(jpeg.size)
        output.write(jpeg, 0, 2)
        var offset = 2
        while (offset < jpeg.size) {
            val markerStart = offset
            if (jpeg[offset] != 0xFF.toByte()) return null
            while (offset < jpeg.size && jpeg[offset] == 0xFF.toByte()) offset += 1
            if (offset >= jpeg.size) return null
            val marker = jpeg[offset].toInt() and 0xFF
            offset += 1
            if (marker == 0xDA) {
                output.write(jpeg, markerStart, jpeg.size - markerStart)
                return output.toByteArray()
            }
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                output.write(jpeg, markerStart, offset - markerStart)
                continue
            }
            if (offset + 1 >= jpeg.size) return null
            val segmentLength =
                ((jpeg[offset].toInt() and 0xFF) shl 8) or
                    (jpeg[offset + 1].toInt() and 0xFF)
            val segmentEnd = offset + segmentLength
            if (segmentLength < 2 || segmentEnd > jpeg.size) return null
            val isMetadata = marker in 0xE0..0xEF || marker == 0xFE
            if (!isMetadata) output.write(jpeg, markerStart, segmentEnd - markerStart)
            offset = segmentEnd
        }
        return null
    }
}

internal data class MealPhotoClassificationObservation(
    val identifier: String,
    val confidence: Float,
)

internal enum class MealPhotoClassificationDecision {
    Accepted,
    NeedsReview,
    Rejected,
}

internal object MealPhotoClassificationPolicy {
    const val MINIMUM_CONFIDENCE = 0.50f
    const val MINIMUM_REVIEW_CONFIDENCE = 0.20f
    private val MEAL_IDENTIFIERS = setOf(
        "breakfast", "burrito", "cake", "cereal", "dessert", "dish",
        "food", "fries", "hamburger", "meal", "noodle", "oatmeal",
        "pasta", "pizza", "salad", "sandwich", "seafood", "soup", "steak",
        "sushi", "taco",
    )

    fun decision(observations: List<MealPhotoClassificationObservation>): MealPhotoClassificationDecision {
        val exactConfidence = observations
            .asSequence()
            .filter { observation -> normalized(observation.identifier) in MEAL_IDENTIFIERS }
            .maxOfOrNull { it.confidence } ?: 0f
        val compoundConfidence = observations
            .asSequence()
            .filter { observation -> isCompoundMealIdentifier(observation.identifier) }
            .maxOfOrNull { it.confidence } ?: 0f
        return when {
            exactConfidence >= MINIMUM_CONFIDENCE -> MealPhotoClassificationDecision.Accepted
            maxOf(exactConfidence, compoundConfidence) >= MINIMUM_REVIEW_CONFIDENCE ->
                MealPhotoClassificationDecision.NeedsReview
            else -> MealPhotoClassificationDecision.Rejected
        }
    }

    private fun normalized(identifier: String): String = identifier.trim().lowercase()

    private fun isCompoundMealIdentifier(identifier: String): Boolean {
        val normalized = normalized(identifier)
        if (normalized in MEAL_IDENTIFIERS) return false
        return normalized
            .split(Regex("[^a-z]+"))
            .any { it in MEAL_IDENTIFIERS }
    }
}

internal interface MealPhotoImageClassifier {
    suspend fun classify(bitmap: Bitmap): List<MealPhotoClassificationObservation>
}

internal class MlKitMealPhotoImageClassifier : MealPhotoImageClassifier {
    override suspend fun classify(bitmap: Bitmap): List<MealPhotoClassificationObservation> {
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0f).build(),
        )
        return try {
            labeler.process(InputImage.fromBitmap(bitmap, 0)).await(labeler).map { label ->
                MealPhotoClassificationObservation(label.text, label.confidence)
            }
        } finally {
            labeler.close()
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(
        labeler: ImageLabeler,
    ): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
        continuation.invokeOnCancellation { labeler.close() }
    }
}

internal enum class MealPhotoUploadDisposition {
    Uploaded,
    CredentialRejected,
    NeedsAttention,
    Retry,
    Discard,
}

internal enum class MealPhotoRevocationDisposition {
    Revoked,
    AlreadyInvalid,
    Retry,
}

internal enum class MealPhotoActivationDisposition {
    Activated,
    CredentialRejected,
    Retry,
}

internal object MealPhotoUploadStatusPolicy {
    fun disposition(status: Int?): MealPhotoUploadDisposition = when {
        status == null -> MealPhotoUploadDisposition.Retry
        status in 200..299 -> MealPhotoUploadDisposition.Uploaded
        status == 401 || status == 403 -> MealPhotoUploadDisposition.CredentialRejected
        status == 409 -> MealPhotoUploadDisposition.NeedsAttention
        status == 413 || status == 415 || status == 422 -> MealPhotoUploadDisposition.Discard
        else -> MealPhotoUploadDisposition.Retry
    }
}

internal object MealPhotoActivationStatusPolicy {
    fun disposition(status: Int?, activated: Any?): MealPhotoActivationDisposition = when {
        status == 401 -> MealPhotoActivationDisposition.CredentialRejected
        status == 200 && activated == true -> MealPhotoActivationDisposition.Activated
        else -> MealPhotoActivationDisposition.Retry
    }
}

internal interface MealPhotoUploading {
    suspend fun upload(
        jpeg: ByteArray,
        credential: MealPhotoCredential,
        captureId: String,
        capturedAt: Instant,
    ): MealPhotoUploadDisposition

    suspend fun activateScoped(uploadToken: String): MealPhotoActivationDisposition

    suspend fun revokeScoped(uploadToken: String): MealPhotoRevocationDisposition
}

internal class HttpMealPhotoUploader(
    baseUrl: String,
    private val openConnection: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
) : MealPhotoUploading {
    private val baseUri = URI(baseUrl.trimEnd('/')).also { uri ->
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        require(uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
    }
    private val uploadUri = baseUri.resolve(UPLOAD_PATH)
    private val enrollmentUri = baseUri.resolve(ENROLLMENT_PATH)

    override suspend fun upload(
        jpeg: ByteArray,
        credential: MealPhotoCredential,
        captureId: String,
        capturedAt: Instant,
    ): MealPhotoUploadDisposition {
        if (
            jpeg.isEmpty() || jpeg.size > MealPhotoImageSanitizer.MAXIMUM_BYTE_COUNT ||
            !credential.isValid || !MealPhotoCaptureConfiguration.CAPTURE_ID.matches(captureId)
        ) return MealPhotoUploadDisposition.Discard
        val status = request(
            uri = uploadUri,
            method = "POST",
            authorization = credential.uploadToken,
            body = jpeg,
            contentType = "image/jpeg",
            headers = mapOf(
                "X-Murph-Meal-Capture-Schema" to "1",
                "Idempotency-Key" to captureId,
                "X-Murph-Captured-At" to capturedAt.toString(),
            ),
        )?.status
        return MealPhotoUploadStatusPolicy.disposition(status)
    }

    override suspend fun activateScoped(uploadToken: String): MealPhotoActivationDisposition {
        if (!uploadToken.startsWith("murph_meal_photo_")) {
            return MealPhotoActivationDisposition.Retry
        }
        val response = request(
            uri = enrollmentUri,
            method = "PUT",
            authorization = uploadToken,
            body = null,
            contentType = null,
            readResponseBody = true,
        )
        val activated = if (response?.status == 200) {
            response.body?.let(::parseActivationFlag)
        } else {
            null
        }
        return MealPhotoActivationStatusPolicy.disposition(response?.status, activated)
    }

    override suspend fun revokeScoped(uploadToken: String): MealPhotoRevocationDisposition {
        if (!uploadToken.startsWith("murph_meal_photo_")) {
            return MealPhotoRevocationDisposition.Retry
        }
        val status = request(
            uri = enrollmentUri,
            method = "DELETE",
            authorization = uploadToken,
            body = null,
            contentType = null,
        )?.status
        return when {
            status in 200..299 -> MealPhotoRevocationDisposition.Revoked
            status == 401 || status == 403 -> MealPhotoRevocationDisposition.AlreadyInvalid
            else -> MealPhotoRevocationDisposition.Retry
        }
    }

    private suspend fun request(
        uri: URI,
        method: String,
        authorization: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
        readResponseBody: Boolean = false,
    ): MealPhotoHttpResponse? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connection = try {
            openConnection(uri)
        } catch (_: IOException) {
            return@withContext null
        }
        val response = suspendCancellableCoroutine<MealPhotoHttpResponse?> { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 45_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $authorization")
                headers.forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.outputStream.use { it.write(body) }
                }
                val responseCode = connection.responseCode
                val responseBody = if (readResponseBody && responseCode == 200) {
                    readBoundedResponseBody(connection)
                } else {
                    null
                }
                if (continuation.isActive) {
                    continuation.resume(MealPhotoHttpResponse(responseCode, responseBody))
                }
            } catch (_: IOException) {
                if (continuation.isActive) continuation.resume(null)
            } finally {
                connection.disconnect()
            }
        }
        currentCoroutineContext().ensureActive()
        response
    }

    private fun readBoundedResponseBody(connection: HttpURLConnection): String? =
        connection.inputStream.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ACTIVATION_RESPONSE_BYTES) return null
                bytes.write(buffer, 0, count)
            }
            String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }

    private fun parseActivationFlag(body: String): Any? =
        true.takeIf { ACTIVATED_RESPONSE.matches(body) }

    private companion object {
        const val UPLOAD_PATH = "/api/device-sync/companion/meal-photo-capture/photos"
        const val ENROLLMENT_PATH = "/api/device-sync/companion/meal-photo-capture/enrollment"
        const val MAX_ACTIVATION_RESPONSE_BYTES = 4_096
        val ACTIVATED_RESPONSE = Regex("""\A\s*\{\s*"activated"\s*:\s*true\s*}\s*\z""")
    }
}

private data class MealPhotoHttpResponse(
    val status: Int,
    val body: String?,
)

internal enum class MealPhotoProcessingResult {
    Inactive,
    Completed,
    Pending,
    NeedsAttention,
}

internal interface MealPhotoProcessing {
    suspend fun process(): MealPhotoProcessingResult
    suspend fun reviewItems(maximum: Int = 8): List<MealPhotoReviewItem>
    suspend fun approve(captureId: String): MealPhotoActionResult
    suspend fun dismiss(captureId: String): MealPhotoActionResult
}

internal class MealPhotoProcessor(
    private val media: MealPhotoMediaSource,
    private val classifier: MealPhotoImageClassifier,
    private val uploader: MealPhotoUploading,
    private val stateStore: MealPhotoStateStoring,
    private val credentialStore: MealPhotoCredentialStoring,
    private val authorizationStore: MealPhotoAuthorizationStoring,
    private val now: () -> Instant = Instant::now,
) : MealPhotoProcessing {
    override suspend fun process(): MealPhotoProcessingResult = withContext(Dispatchers.IO) {
        processOnIo()
    }

    private suspend fun processOnIo(): MealPhotoProcessingResult {
        currentCoroutineContext().ensureActive()
        var configuration = stateStore.load() ?: return MealPhotoProcessingResult.Inactive
        if (
            !configuration.isValid ||
            !isAuthorized(configuration) ||
            media.access() != MealPhotoMediaAccess.Full
        ) {
            return MealPhotoProcessingResult.Inactive
        }
        val pruned = configuration.pruning(now().toEpochMilli())
        if (pruned != configuration) {
            if (!stateStore.save(pruned)) return MealPhotoProcessingResult.NeedsAttention
            configuration = pruned
        }
        val credential = credentialStore.load(configuration.generationId)
            ?: return MealPhotoProcessingResult.NeedsAttention
        if (credential.expiresAtEpochMillis <= now().toEpochMilli()) {
            return MealPhotoProcessingResult.NeedsAttention
        }

        val reconciled = reconcileBoundaries(configuration)
            ?: return MealPhotoProcessingResult.NeedsAttention
        configuration = reconciled
        var expensiveBudget = MAX_EXPENSIVE_ASSETS
        for (cursor in configuration.cursors.sortedBy { it.volumeName }) {
            if (!isAuthorized(configuration)) return MealPhotoProcessingResult.Inactive
            val candidates = try {
                media.candidatesAfter(cursor, MAX_CANDIDATES_PER_VOLUME)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MealPhotoProcessingResult.NeedsAttention
            }
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                val captureId = captureId(candidate, credential.idempotencySecret)
                if (
                    candidate.isScreenshot ||
                    candidate.capturedAtEpochMillis < configuration.enabledAtEpochMillis ||
                    candidate.mimeType !in SUPPORTED_MIME_TYPES
                ) {
                    configuration = configuration.markTerminal(
                        candidate.volumeName,
                        candidate.generation,
                        candidate.mediaId,
                    )
                    if (!stateStore.save(configuration)) return MealPhotoProcessingResult.NeedsAttention
                    continue
                }
                if (expensiveBudget == 0) return MealPhotoProcessingResult.Pending
                expensiveBudget -= 1

                if (!isAuthorized(configuration)) return MealPhotoProcessingResult.Inactive
                val sanitized = try {
                    media.sanitizedImage(candidate.contentUri)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (sanitized == null) {
                    val (retried, terminal) = configuration.recordAssetFailure(captureId)
                    configuration = if (terminal) {
                        retried.markTerminal(
                            candidate.volumeName,
                            candidate.generation,
                            candidate.mediaId,
                        )
                    } else {
                        retried
                    }
                    if (!stateStore.save(configuration)) return MealPhotoProcessingResult.NeedsAttention
                    if (!terminal) return MealPhotoProcessingResult.Pending
                    continue
                }

                val classifiedDecision = try {
                    MealPhotoClassificationPolicy.decision(
                        sanitized.classify(classifier),
                    )
                } catch (error: CancellationException) {
                    sanitized.close()
                    throw error
                } catch (_: Exception) {
                    sanitized.close()
                    val (retried, terminal) = configuration.recordAssetFailure(captureId)
                    configuration = if (terminal) {
                        retried.markTerminal(
                            candidate.volumeName,
                            candidate.generation,
                            candidate.mediaId,
                        )
                    } else {
                        retried
                    }
                    if (!stateStore.save(configuration)) {
                        return MealPhotoProcessingResult.NeedsAttention
                    }
                    if (!terminal) return MealPhotoProcessingResult.Pending
                    continue
                }
                try {
                    if (!isAuthorized(configuration)) {
                        return MealPhotoProcessingResult.Inactive
                    }
                    val revalidated = revalidateOrUnavailable(
                        candidate.reviewRecord(
                            captureId,
                            MealPhotoReviewStatus.NeedsReview,
                        ),
                    )
                    if (
                        revalidated !is MealPhotoCandidateValidation.Valid ||
                        !revalidated.candidate.matches(
                            candidate.reviewRecord(
                                captureId,
                                MealPhotoReviewStatus.NeedsReview,
                            ),
                        ) ||
                        revalidated.candidate.isCameraOrigin != candidate.isCameraOrigin
                    ) {
                        return MealPhotoProcessingResult.Pending
                    }
                    // Android exposes no immutable screenshot/camera-origin media subtype. Path,
                    // filename, and owner-package hints are mutable, so even a high-confidence
                    // label stays local until the member sees the thumbnail and approves it.
                    when (classifiedDecision) {
                        MealPhotoClassificationDecision.Rejected -> {
                            configuration = configuration.markTerminal(
                                candidate.volumeName,
                                candidate.generation,
                                candidate.mediaId,
                            )
                        }
                        // Android exposes no immutable screenshot/camera-origin subtype. Path,
                        // filename, and owner-package hints are mutable, so every detected meal
                        // stays local until the member sees the thumbnail and approves it.
                        MealPhotoClassificationDecision.Accepted,
                        MealPhotoClassificationDecision.NeedsReview,
                        -> {
                            configuration = configuration.recordingReview(
                                candidate.reviewRecord(
                                    captureId,
                                    MealPhotoReviewStatus.NeedsReview,
                                ),
                                now().toEpochMilli(),
                            ).markTerminal(
                                candidate.volumeName,
                                candidate.generation,
                                candidate.mediaId,
                            )
                        }
                    }
                    if (!stateStore.save(configuration)) {
                        return MealPhotoProcessingResult.NeedsAttention
                    }
                } finally {
                    sanitized.close()
                }
            }
        }
        return MealPhotoProcessingResult.Completed
    }

    override suspend fun reviewItems(maximum: Int): List<MealPhotoReviewItem> =
        withContext(Dispatchers.IO) { reviewItemsOnIo(maximum) }

    private suspend fun reviewItemsOnIo(maximum: Int): List<MealPhotoReviewItem> {
        var configuration = stateStore.load() ?: return emptyList()
        if (!isAuthorized(configuration)) return emptyList()
        if (media.access() != MealPhotoMediaAccess.Full) return emptyList()
        val pruned = configuration.pruning(now().toEpochMilli())
        if (pruned != configuration && stateStore.save(pruned)) configuration = pruned
        val items = mutableListOf<MealPhotoReviewItem>()
        var removedInvalidRecord = false
        val orderedRecords = configuration.reviewRecords.sortedWith(
            compareByDescending<MealPhotoReviewRecord> {
                it.status == MealPhotoReviewStatus.NeedsReview
            }.thenByDescending { it.capturedAtEpochMillis },
        )
        for (record in orderedRecords) {
            if (items.size >= maximum.coerceIn(1, 8)) break
            if (!isAuthorized(configuration)) return emptyList()
            val validation = revalidateOrUnavailable(record)
            val candidate = when (validation) {
                is MealPhotoCandidateValidation.Valid -> validation.candidate
                MealPhotoCandidateValidation.Stale -> {
                    configuration = configuration.dismissing(record.captureId)
                    removedInvalidRecord = true
                    continue
                }
                MealPhotoCandidateValidation.Unavailable -> continue
            }
            if (!candidate.matches(record)) {
                configuration = configuration.dismissing(record.captureId)
                removedInvalidRecord = true
                continue
            }
            val thumbnailAsset = try {
                media.sanitizedImage(candidate.contentUri, maximumDimension = 420)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val thumbnail = thumbnailAsset?.use { it.jpeg.copyOf() } ?: continue
            val postDecode = revalidateOrUnavailable(record)
            if (
                postDecode !is MealPhotoCandidateValidation.Valid ||
                !postDecode.candidate.matches(record)
            ) continue
            items += MealPhotoReviewItem(
                id = record.captureId,
                capturedAt = Instant.ofEpochMilli(record.capturedAtEpochMillis),
                status = record.status,
                thumbnailJpeg = thumbnail,
            )
        }
        if (removedInvalidRecord && !stateStore.save(configuration)) return emptyList()
        return items
    }

    override suspend fun approve(captureId: String): MealPhotoActionResult =
        withContext(Dispatchers.IO) { approveOnIo(captureId) }

    private suspend fun approveOnIo(captureId: String): MealPhotoActionResult {
        val configuration = stateStore.load() ?: return MealPhotoActionResult.NeedsAttention
        if (!isAuthorized(configuration)) return MealPhotoActionResult.NeedsAttention
        val record = configuration.reviewRecords.firstOrNull { it.captureId == captureId }
            ?: return MealPhotoActionResult.PhotoUnavailable
        if (record.status == MealPhotoReviewStatus.Sent) return MealPhotoActionResult.Sent
        if (media.access() != MealPhotoMediaAccess.Full) return MealPhotoActionResult.NeedsAttention
        val credential = credentialStore.load(configuration.generationId)
            ?.takeIf { it.expiresAtEpochMillis > now().toEpochMilli() }
            ?: return MealPhotoActionResult.NeedsAttention
        if (!isAuthorized(configuration)) return MealPhotoActionResult.NeedsAttention
        val validation = revalidateOrUnavailable(record)
        val candidate = when (validation) {
            is MealPhotoCandidateValidation.Valid -> validation.candidate
            MealPhotoCandidateValidation.Stale -> {
                stateStore.save(configuration.dismissing(captureId))
                return MealPhotoActionResult.PhotoUnavailable
            }
            MealPhotoCandidateValidation.Unavailable -> return MealPhotoActionResult.TryAgain
        }
        if (!candidate.matches(record)) {
            stateStore.save(configuration.dismissing(captureId))
            return MealPhotoActionResult.PhotoUnavailable
        }
        val image = media.sanitizedImage(candidate.contentUri)
            ?: return MealPhotoActionResult.TryAgain
        return image.use {
            if (!isAuthorized(configuration)) return@use MealPhotoActionResult.NeedsAttention
            val postDecode = revalidateOrUnavailable(record)
            if (
                postDecode !is MealPhotoCandidateValidation.Valid ||
                !postDecode.candidate.matches(record)
            ) return@use MealPhotoActionResult.TryAgain
            when (
                uploader.upload(
                    jpeg = it.jpeg,
                    credential = credential,
                    captureId = record.captureId,
                    capturedAt = Instant.ofEpochMilli(record.capturedAtEpochMillis),
                )
            ) {
                MealPhotoUploadDisposition.Uploaded -> {
                    if (stateStore.save(configuration.markingSent(captureId))) {
                        MealPhotoActionResult.Sent
                    } else {
                        MealPhotoActionResult.TryAgain
                    }
                }
                MealPhotoUploadDisposition.CredentialRejected -> {
                    credentialStore.suspend(configuration.generationId)
                    MealPhotoActionResult.NeedsAttention
                }
                MealPhotoUploadDisposition.NeedsAttention -> MealPhotoActionResult.NeedsAttention
                MealPhotoUploadDisposition.Discard -> MealPhotoActionResult.PhotoUnavailable
                MealPhotoUploadDisposition.Retry -> MealPhotoActionResult.TryAgain
            }
        }
    }

    override suspend fun dismiss(captureId: String): MealPhotoActionResult =
        withContext(Dispatchers.IO) {
            val configuration = stateStore.load()
                ?: return@withContext MealPhotoActionResult.PhotoUnavailable
            if (stateStore.save(configuration.dismissing(captureId))) {
                MealPhotoActionResult.Dismissed
            } else {
                MealPhotoActionResult.TryAgain
            }
        }

    private suspend fun reconcileBoundaries(
        configuration: MealPhotoCaptureConfiguration,
    ): MealPhotoCaptureConfiguration? {
        if (!isAuthorized(configuration)) return null
        val current = try {
            media.currentBoundaries()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val reconciled = configuration.reconciledWith(current)
        return if (reconciled == configuration || stateStore.save(reconciled)) reconciled else null
    }

    private suspend fun revalidateOrUnavailable(
        record: MealPhotoReviewRecord,
    ): MealPhotoCandidateValidation = try {
        media.revalidatedCandidate(record)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        MealPhotoCandidateValidation.Unavailable
    }

    private fun isAuthorized(configuration: MealPhotoCaptureConfiguration): Boolean {
        val current = stateStore.load() ?: return false
        return current.generationId == configuration.generationId &&
            current.ownerDigest == configuration.ownerDigest &&
            authorizationStore.isAuthorized(configuration.generationId)
    }

    private fun captureId(candidate: MealPhotoCandidate, secret: String): String =
        MealPhotoCaptureId.derive(candidate, secret)

    private companion object {
        const val MAX_CANDIDATES_PER_VOLUME = 64
        const val MAX_EXPENSIVE_ASSETS = 4
        val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/heif",
            "image/webp",
        )
    }
}

internal fun MealPhotoCandidate.reviewRecord(
    captureId: String,
    status: MealPhotoReviewStatus,
): MealPhotoReviewRecord = MealPhotoReviewRecord(
    captureId = captureId,
    contentUri = contentUri,
    volumeName = volumeName,
    volumeVersion = volumeVersion,
    mediaId = mediaId,
    generation = generation,
    modifiedGeneration = modifiedGeneration,
    mimeType = mimeType,
    capturedAtEpochMillis = capturedAtEpochMillis,
    status = status,
)

internal fun MealPhotoCaptureConfiguration.reconciledWith(
    boundaries: List<MealPhotoVolumeCursor>,
): MealPhotoCaptureConfiguration {
    require(boundaries.isNotEmpty())
    val savedByVolume = cursors.associateBy { it.volumeName }
    val reconciledCursors = boundaries.map { boundary ->
        savedByVolume[boundary.volumeName]
            ?.takeIf { it.version == boundary.version }
            ?: boundary
    }.sortedBy { it.volumeName }
    val currentVersions = reconciledCursors.associate { it.volumeName to it.version }
    val topologyChanged = reconciledCursors != cursors.sortedBy { it.volumeName }
    return copy(
        cursors = reconciledCursors,
        retryCaptureId = if (topologyChanged) null else retryCaptureId,
        retryCount = if (topologyChanged) 0 else retryCount,
        reviewRecords = reviewRecords.filter { record ->
            currentVersions[record.volumeName] == record.volumeVersion
        },
    )
}

internal object MealPhotoCaptureId {
    fun derive(candidate: MealPhotoCandidate, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(
            (
                "${candidate.volumeName}:${candidate.volumeVersion}:" +
                    "${candidate.mediaId}:${candidate.generation}:" +
                    "${candidate.modifiedGeneration}"
                ).toByteArray(StandardCharsets.UTF_8),
        ).toLowerHex()
    }
}
