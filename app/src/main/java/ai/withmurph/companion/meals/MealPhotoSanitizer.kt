package ai.withmurph.companion.meals

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import ai.withmurph.companion.core.ManualMealPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

object MealPhotoSanitizer {
    suspend fun prepare(resolver: ContentResolver, uri: Uri): ManualMealPhoto = withContext(Dispatchers.IO) {
        val encoded = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 30 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Photo unavailable")
        coroutineContext.ensureActive()
        // ImageDecoder applies encoded orientation before re-encoding. JPEG output
        // is rendered onto an opaque fresh bitmap; source metadata is not copied.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1280.0 / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
        try {
            coroutineContext.ensureActive()
            ManualMealPhoto(
                jpeg = encode(bitmap, 1280, 1024 * 1024),
                thumbnail = encode(bitmap, 420, 256 * 1024),
                capturedAt = captureTime(resolver, uri),
            )
        } finally { bitmap.recycle() }
    }

    private fun captureTime(resolver: ContentResolver, uri: Uri): Instant {
        val now = Instant.now()
        return try {
            resolver.query(uri, arrayOf(android.provider.MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) now
                else Instant.ofEpochMilli(cursor.getLong(0)).takeIf { it > Instant.EPOCH && it <= now } ?: now
            } ?: now
        } catch (_: Exception) { now }
    }

    private fun encode(source: Bitmap, dimension: Int, limit: Int): ByteArray {
        val scale = minOf(1.0, dimension.toDouble() / max(source.width, source.height))
        val target = Bitmap.createBitmap(max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        return try {
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(0, 0, target.width, target.height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            for (quality in listOf(72, 62, 52)) {
                val output = ByteArrayOutputStream()
                check(target.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= limit) return output.toByteArray()
            }
            error("Photo could not be prepared")
        } finally { target.recycle() }
    }
}
