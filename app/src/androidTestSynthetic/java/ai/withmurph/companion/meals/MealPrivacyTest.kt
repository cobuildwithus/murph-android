package ai.withmurph.companion.meals

import ai.withmurph.companion.core.SentMealPhoto
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MealPrivacyTest {
    @Test fun pickerPreparationRemovesSourceMetadataAndBoundsBothImages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(2000, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160, 130, 90)) }
        val original = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output); output.toByteArray() }
        bitmap.recycle()
        val marker = "synthetic-camera-metadata".toByteArray()
        val length = marker.size + 2
        val jpeg = original.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), (length shr 8).toByte(), length.toByte()) + marker + original.copyOfRange(2, original.size)
        val directory = File(context.cacheDir, "meal-camera").apply { mkdirs() }
        val file = File.createTempFile("capture-test-", ".jpg", directory)
        try {
            file.writeBytes(jpeg)
            val uri = android.net.Uri.fromFile(file)
            val photo = MealPhotoSanitizer.prepare(context.contentResolver, uri)
            assertTrue(photo.jpeg.size <= 1024 * 1024)
            assertTrue(photo.thumbnail.size <= 256 * 1024)
            assertFalse(photo.jpeg.toString(Charsets.ISO_8859_1).contains("synthetic-camera-metadata"))
            for ((bytes, limit) in listOf(photo.jpeg to 1280, photo.thumbnail to 420)) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                assertTrue(options.outWidth in 1..limit)
                assertTrue(options.outHeight in 1..limit)
                assertEquals("image/jpeg", options.outMimeType)
            }
        } finally { file.delete() }
    }

    @Test fun encryptedHistoryIsBoundToTheMemberAndRetiresUploadKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = EncryptedSentMealHistory(context)
        history.clear()
        try {
            val uploadId = UUID.randomUUID().toString()
            history.append("synthetic-member-a", SentMealPhoto(uploadId, byteArrayOf(1, 2, 3), Instant.now())) { true }
            val restored = EncryptedSentMealHistory(context).load("synthetic-member-a")
            assertEquals(1, restored.size)
            assertNotEquals(uploadId, restored.single().id)
            assertTrue(history.load("synthetic-member-b").isEmpty())
            assertFalse(File(context.noBackupFilesDir, "sent-meal-previews-v1").readBytes().toString(Charsets.ISO_8859_1).contains(uploadId))
            history.clear()
            assertTrue(history.load("synthetic-member-a").isEmpty())
        } finally { history.clear() }
    }

    @Test fun expiredThumbnailsAreRemovedAndStaleWritersCannotRecreateHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = EncryptedSentMealHistory(context)
        history.clear()
        try {
            history.append("synthetic-member-a", SentMealPhoto(UUID.randomUUID().toString(), byteArrayOf(1),
                Instant.now(), Instant.now().minusSeconds(15L * 24 * 60 * 60))) { true }
            assertTrue(history.load("synthetic-member-a").isEmpty())
            assertFalse(File(context.noBackupFilesDir, "sent-meal-previews-v1").exists())
            history.append("synthetic-member-a", SentMealPhoto(UUID.randomUUID().toString(), byteArrayOf(1), Instant.now())) { false }
            assertTrue(history.load("synthetic-member-a").isEmpty())
        } finally { history.clear() }
    }
}
