package ai.withmurph.companion.meals

import ai.withmurph.companion.core.SentMealHistory
import ai.withmurph.companion.core.SentMealPhoto
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** At most 24 accepted 420px previews for 14 days. No originals or upload keys. */
class EncryptedSentMealHistory(context: Context) : SentMealHistory {
    private val file = AtomicFile(File(context.noBackupFilesDir, "sent-meal-previews-v1"))
    private val mutex = Mutex()

    override suspend fun load(memberKey: String): List<SentMealPhoto> = withContext(Dispatchers.IO) {
        mutex.withLock { read(memberKey) }
    }

    override suspend fun append(memberKey: String, photo: SentMealPhoto, isCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isCurrent()) return@withLock
            // A cache ID must never persist the idempotency key used for upload.
            val preview = SentMealPhoto(UUID.randomUUID().toString(), photo.thumbnail, photo.capturedAt, photo.sentAt)
            val history = (listOf(preview) + read(memberKey)).take(24)
            if (!isCurrent()) return@withLock
            write(memberKey, history, isCurrent)

        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Retiring the key also makes an undeletable old file unreadable.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
            file.delete()
        }
    }

    private fun write(memberKey: String, history: List<SentMealPhoto>, isCurrent: () -> Boolean = { true }) {
        if (history.isEmpty()) { file.delete(); return }
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(history.size)
                history.forEach { item ->
                    require(item.thumbnail.size in 1..256 * 1024)
                    output.writeUTF(item.id)
                    output.writeLong(item.capturedAt.toEpochMilli())
                    output.writeLong(item.sentAt.toEpochMilli())
                    output.writeInt(item.thumbnail.size)
                    output.write(item.thumbnail)
                }
            }
            buffer.toByteArray()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(binding(memberKey))
        check(cipher.iv.size == 12)
        val encoded = byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
        if (!isCurrent()) return
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        if (!isCurrent()) file.delete()
    }

    private fun read(memberKey: String): List<SentMealPhoto> = try {
        val encoded = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(encoded.size >= 30 && encoded[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(binding(memberKey))
        val oldest = Instant.now().minusSeconds(14L * 24 * 60 * 60)
        DataInputStream(ByteArrayInputStream(cipher.doFinal(encoded.copyOfRange(13, encoded.size)))).use { input ->
            val count = input.readInt().also { require(it in 0..24) }
            val items = (0 until count).map {
                val id = input.readUTF().also { require(UUID.fromString(it).toString() == it) }
                val captured = Instant.ofEpochMilli(input.readLong())
                val sent = Instant.ofEpochMilli(input.readLong())
                val length = input.readInt().also { require(it in 1..256 * 1024) }
                val thumbnail = ByteArray(length).also(input::readFully)
                SentMealPhoto(id, thumbnail, captured, sent)
            }
            require(input.read() == -1)
            val retained = items.filter { it.sentAt >= oldest && it.sentAt <= Instant.now().plusSeconds(60) }
            if (retained.size != items.size) write(memberKey, retained)
            retained
        }
    } catch (_: Exception) { emptyList() }

    private fun binding(memberKey: String): ByteArray =
        "murph.sent-meals.v1".toByteArray() + MessageDigest.getInstance("SHA-256").digest(memberKey.toByteArray())

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private companion object {
        const val KEY_ALIAS = "murph.sent-meal-previews.v1"
        const val MAX_BYTES = 24 * (256 * 1024 + 128) + 128
    }
}
