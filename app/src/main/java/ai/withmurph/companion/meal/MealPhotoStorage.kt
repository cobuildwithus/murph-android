package ai.withmurph.companion.meal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.withmurph.companion.core.MealPhotoCaptureEnrollment
import ai.withmurph.companion.core.MealPhotoReviewStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class MealPhotoVolumeCursor(
    val volumeName: String,
    val version: String,
    val generation: Long,
    val mediaId: Long = Long.MAX_VALUE,
)

internal data class MealPhotoReviewRecord(
    val captureId: String,
    val contentUri: String,
    val volumeName: String,
    val volumeVersion: String,
    val mediaId: Long,
    val generation: Long,
    val modifiedGeneration: Long,
    val mimeType: String,
    val capturedAtEpochMillis: Long,
    val status: MealPhotoReviewStatus,
)

internal data class MealPhotoCaptureConfiguration(
    val generationId: String,
    val ownerDigest: String,
    val enabledAtEpochMillis: Long,
    val cursors: List<MealPhotoVolumeCursor>,
    val retryCaptureId: String? = null,
    val retryCount: Int = 0,
    val reviewRecords: List<MealPhotoReviewRecord> = emptyList(),
) {
    val isValid: Boolean
        get() = runCatching {
            UUID.fromString(generationId)
            require(OWNER_DIGEST.matches(ownerDigest))
            require(enabledAtEpochMillis > 0)
            require(cursors.isNotEmpty() && cursors.size <= MAX_VOLUME_COUNT)
            require(cursors.map { it.volumeName }.toSet().size == cursors.size)
            require(cursors.all { cursor ->
                cursor.volumeName.isNotBlank() &&
                    cursor.volumeName.length <= MAX_VOLUME_NAME_LENGTH &&
                    cursor.version.isNotBlank() &&
                    cursor.version.length <= MAX_VERSION_LENGTH &&
                    cursor.generation >= 0 && cursor.mediaId >= 0
            })
            require(
                if (retryCaptureId == null) {
                    retryCount == 0
                } else {
                    CAPTURE_ID.matches(retryCaptureId) && retryCount in 1 until MAX_ASSET_RETRIES
                },
            )
            require(reviewRecords.size <= MAX_REVIEW_ITEM_COUNT)
            require(reviewRecords.map { it.captureId }.toSet().size == reviewRecords.size)
            require(reviewRecords.all { record ->
                CAPTURE_ID.matches(record.captureId) &&
                    record.contentUri.startsWith("content://") &&
                    record.contentUri.length <= MAX_CONTENT_URI_LENGTH &&
                    record.volumeName.isNotBlank() &&
                    record.volumeName.length <= MAX_VOLUME_NAME_LENGTH &&
                    record.volumeVersion.isNotBlank() &&
                    record.volumeVersion.length <= MAX_VERSION_LENGTH &&
                    record.mediaId >= 0 && record.generation >= 0 &&
                    record.modifiedGeneration >= record.generation &&
                    record.mimeType in SUPPORTED_REVIEW_MIME_TYPES &&
                    record.capturedAtEpochMillis >= enabledAtEpochMillis
            })
        }.isSuccess

    fun cursor(volumeName: String): MealPhotoVolumeCursor? =
        cursors.firstOrNull { it.volumeName == volumeName }

    fun replacingCursor(cursor: MealPhotoVolumeCursor): MealPhotoCaptureConfiguration {
        val remaining = cursors.filterNot { it.volumeName == cursor.volumeName }
        return copy(cursors = (remaining + cursor).sortedBy { it.volumeName })
    }

    fun markTerminal(
        volumeName: String,
        generation: Long,
        mediaId: Long,
    ): MealPhotoCaptureConfiguration {
        val cursor = cursor(volumeName) ?: return this
        if (
            generation < cursor.generation ||
            (generation == cursor.generation && mediaId <= cursor.mediaId)
        ) return this
        return replacingCursor(cursor.copy(generation = generation, mediaId = mediaId)).copy(
            retryCaptureId = null,
            retryCount = 0,
        )
    }

    fun recordAssetFailure(captureId: String): Pair<MealPhotoCaptureConfiguration, Boolean> {
        if (!CAPTURE_ID.matches(captureId)) return this to true
        val count = if (retryCaptureId == captureId) retryCount + 1 else 1
        return if (count >= MAX_ASSET_RETRIES) {
            copy(retryCaptureId = null, retryCount = 0) to true
        } else {
            copy(retryCaptureId = captureId, retryCount = count) to false
        }
    }

    fun recordingReview(
        record: MealPhotoReviewRecord,
        nowEpochMillis: Long,
    ): MealPhotoCaptureConfiguration {
        val cutoff = nowEpochMillis - REVIEW_RETENTION_MILLIS
        val retained = reviewRecords
            .filter { it.captureId != record.captureId && it.capturedAtEpochMillis >= cutoff }
            .plus(record)
            .sortedByDescending { it.capturedAtEpochMillis }
            .toMutableList()
        while (retained.size > MAX_REVIEW_ITEM_COUNT) {
            val oldestSent = retained.indexOfLast { it.status == MealPhotoReviewStatus.Sent }
            retained.removeAt(if (oldestSent >= 0) oldestSent else retained.lastIndex)
        }
        return copy(reviewRecords = retained)
    }

    fun markingSent(captureId: String): MealPhotoCaptureConfiguration = copy(
        reviewRecords = reviewRecords.map { record ->
            if (record.captureId == captureId) {
                record.copy(status = MealPhotoReviewStatus.Sent)
            } else {
                record
            }
        },
    )

    fun dismissing(captureId: String): MealPhotoCaptureConfiguration = copy(
        reviewRecords = reviewRecords.filterNot { it.captureId == captureId },
    )

    fun pruning(nowEpochMillis: Long): MealPhotoCaptureConfiguration {
        val cutoff = nowEpochMillis - REVIEW_RETENTION_MILLIS
        val latest = nowEpochMillis + MAX_FUTURE_REVIEW_SKEW_MILLIS
        return copy(
            reviewRecords = reviewRecords.filter {
                it.capturedAtEpochMillis in cutoff..latest
            },
        )
    }

    companion object {
        const val MAX_ASSET_RETRIES = 3
        const val MAX_REVIEW_ITEM_COUNT = 24
        const val REVIEW_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1_000
        const val MAX_FUTURE_REVIEW_SKEW_MILLIS = 5L * 60 * 1_000
        const val MAX_VOLUME_COUNT = 8
        const val MAX_VOLUME_NAME_LENGTH = 128
        const val MAX_VERSION_LENGTH = 256
        const val MAX_CONTENT_URI_LENGTH = 2_048
        val CAPTURE_ID = Regex("^[0-9a-f]{64}$")
        private val OWNER_DIGEST = Regex("^[0-9a-f]{64}$")
        private val SUPPORTED_REVIEW_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/heif",
            "image/webp",
        )

        fun ownerDigest(installationId: String, memberKey: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$installationId:$memberKey".toByteArray(Charsets.UTF_8))
                .toLowerHex()
    }
}

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal interface MealPhotoStateStoring {
    fun load(): MealPhotoCaptureConfiguration?
    fun save(configuration: MealPhotoCaptureConfiguration): Boolean
    fun clear(): Boolean
}

/**
 * A separate durable fence prevents a stale processor state save from reopening upload authority.
 * The generation binding also prevents an old worker from authorizing or clearing a replacement.
 */
internal enum class MealPhotoAuthorizationDisposition {
    Authorized,
    ConsentSuspended,
    CredentialSuspended,
    Suspended,
    Disabled,
}

internal data class MealPhotoAuthorizationSnapshot(
    val epoch: Long,
    val generationId: String?,
    val disposition: MealPhotoAuthorizationDisposition,
)

internal interface MealPhotoAuthorizationStoring {
    fun snapshot(): MealPhotoAuthorizationSnapshot
    fun isAuthorized(generationId: String): Boolean
    fun allocateAuthorityRevision(): Long? = null
    fun suspendForConsent(): Boolean
    fun suspendForCredentialRepair(): Boolean
    fun suspendAll(): Boolean
    fun disableAll(): Boolean
    fun authorize(
        generationId: String,
        expectedEpoch: Long,
        allowedPrevious: Set<MealPhotoAuthorizationDisposition>,
    ): Boolean
    fun clearGeneration(generationId: String): Boolean
}

internal class SharedPreferencesMealPhotoAuthorizationStore(
    context: Context,
) : MealPhotoAuthorizationStoring {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun snapshot(): MealPhotoAuthorizationSnapshot {
        val disposition = preferences.getString(DISPOSITION_KEY, null)
            ?.let { runCatching { MealPhotoAuthorizationDisposition.valueOf(it) }.getOrNull() }
            ?: MealPhotoAuthorizationDisposition.Disabled
        return MealPhotoAuthorizationSnapshot(
            epoch = preferences.getLong(EPOCH_KEY, 0L).coerceAtLeast(0L),
            generationId = preferences.getString(GENERATION_KEY, null),
            disposition = disposition,
        )
    }

    override fun isAuthorized(generationId: String): Boolean = snapshot().let { current ->
        current.generationId == generationId &&
            current.disposition == MealPhotoAuthorizationDisposition.Authorized
    }

    override fun allocateAuthorityRevision(): Long? = synchronized(AUTHORITY_REVISION_LOCK) {
        val current = runCatching { preferences.getLong(AUTHORITY_REVISION_KEY, 0L) }
            .getOrNull()
            ?: return@synchronized null
        val next = nextMealPhotoAuthorityRevision(current) ?: return@synchronized null
        next.takeIf {
            preferences.edit().putLong(AUTHORITY_REVISION_KEY, next).commit()
        }
    }

    @Synchronized
    override fun suspendForConsent(): Boolean {
        val current = snapshot()
        if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
        val disposition = if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
            MealPhotoAuthorizationDisposition.Disabled
        } else {
            MealPhotoAuthorizationDisposition.ConsentSuspended
        }
        return advanceTo(disposition)
    }

    @Synchronized
    override fun suspendForCredentialRepair(): Boolean {
        val current = snapshot()
        if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
        val disposition = if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
            MealPhotoAuthorizationDisposition.Disabled
        } else {
            MealPhotoAuthorizationDisposition.CredentialSuspended
        }
        return advanceTo(disposition)
    }

    @Synchronized
    override fun suspendAll(): Boolean {
        val disposition = if (snapshot().disposition == MealPhotoAuthorizationDisposition.Disabled) {
            MealPhotoAuthorizationDisposition.Disabled
        } else {
            MealPhotoAuthorizationDisposition.Suspended
        }
        return advanceTo(disposition)
    }

    @Synchronized
    override fun disableAll(): Boolean = advanceTo(MealPhotoAuthorizationDisposition.Disabled)

    @Synchronized
    override fun authorize(
        generationId: String,
        expectedEpoch: Long,
        allowedPrevious: Set<MealPhotoAuthorizationDisposition>,
    ): Boolean {
        val current = snapshot()
        if (current.epoch != expectedEpoch || current.disposition !in allowedPrevious) return false
        return preferences.edit()
            .putString(GENERATION_KEY, generationId)
            .putString(DISPOSITION_KEY, MealPhotoAuthorizationDisposition.Authorized.name)
            .commit()
    }

    @Synchronized
    override fun clearGeneration(generationId: String): Boolean {
        val current = snapshot()
        if (current.generationId == null) return true
        if (current.generationId != generationId) return false
        return preferences.edit().remove(GENERATION_KEY).commit()
    }

    private fun advanceTo(disposition: MealPhotoAuthorizationDisposition): Boolean {
        val current = snapshot()
        if (current.epoch == Long.MAX_VALUE) return false
        return preferences.edit()
            .putLong(EPOCH_KEY, current.epoch + 1)
            .putString(DISPOSITION_KEY, disposition.name)
            .commit()
    }

    private companion object {
        val AUTHORITY_REVISION_LOCK = Any()
        const val PREFERENCES = "murph_meal_photo_authorization"
        const val AUTHORITY_REVISION_KEY = "authority_revision_v2"
        const val GENERATION_KEY = "generation_v1"
        const val EPOCH_KEY = "epoch_v1"
        const val DISPOSITION_KEY = "disposition_v1"
    }
}

internal fun nextMealPhotoAuthorityRevision(current: Long): Long? =
    (current + 1).takeIf { current in 0 until MAX_MEAL_PHOTO_AUTHORITY_REVISION }

private const val MAX_MEAL_PHOTO_AUTHORITY_REVISION = Int.MAX_VALUE.toLong()

internal class SharedPreferencesMealPhotoStateStore(context: Context) : MealPhotoStateStoring {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): MealPhotoCaptureConfiguration? {
        val encoded = preferences.getString(STATE_KEY, null) ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return MealPhotoStateCodec.decode(bytes)
    }

    override fun save(configuration: MealPhotoCaptureConfiguration): Boolean {
        val bytes = MealPhotoStateCodec.encode(configuration) ?: return false
        return preferences.edit()
            .putString(STATE_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .commit()
    }

    override fun clear(): Boolean = preferences.edit().remove(STATE_KEY).commit()

    private companion object {
        const val PREFERENCES = "murph_meal_photo_capture"
        const val STATE_KEY = "capture_state_v1"
    }
}

internal object MealPhotoStateCodec {
    private const val MAGIC = 0x4D504331
    private const val SCHEMA_VERSION = 1
    private const val MAX_STATE_BYTES = 256 * 1_024

    fun encode(configuration: MealPhotoCaptureConfiguration): ByteArray? {
        if (!configuration.isValid) return null
        return runCatching {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(SCHEMA_VERSION)
                    output.writeUTF(configuration.generationId)
                    output.writeUTF(configuration.ownerDigest)
                    output.writeLong(configuration.enabledAtEpochMillis)
                    output.writeInt(configuration.cursors.size)
                    configuration.cursors.forEach { cursor ->
                        output.writeUTF(cursor.volumeName)
                        output.writeUTF(cursor.version)
                        output.writeLong(cursor.generation)
                        output.writeLong(cursor.mediaId)
                    }
                    output.writeBoolean(configuration.retryCaptureId != null)
                    configuration.retryCaptureId?.let(output::writeUTF)
                    output.writeInt(configuration.retryCount)
                    output.writeInt(configuration.reviewRecords.size)
                    configuration.reviewRecords.forEach { record ->
                        output.writeUTF(record.captureId)
                        output.writeUTF(record.contentUri)
                        output.writeUTF(record.volumeName)
                        output.writeUTF(record.volumeVersion)
                        output.writeLong(record.mediaId)
                        output.writeLong(record.generation)
                        output.writeLong(record.modifiedGeneration)
                        output.writeUTF(record.mimeType)
                        output.writeLong(record.capturedAtEpochMillis)
                        output.writeUTF(record.status.name)
                    }
                }
                bytes.toByteArray().takeIf { it.size <= MAX_STATE_BYTES }
                    ?: error("Meal-photo state is too large")
            }
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): MealPhotoCaptureConfiguration? {
        if (bytes.isEmpty() || bytes.size > MAX_STATE_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == SCHEMA_VERSION)
                val generationId = input.readUTF()
                val ownerDigest = input.readUTF()
                val enabledAt = input.readLong()
                val cursorCount = input.readInt()
                require(cursorCount in 1..MealPhotoCaptureConfiguration.MAX_VOLUME_COUNT)
                val cursors = List(cursorCount) {
                    MealPhotoVolumeCursor(
                        volumeName = input.readUTF(),
                        version = input.readUTF(),
                        generation = input.readLong(),
                        mediaId = input.readLong(),
                    )
                }
                val retryCaptureId = if (input.readBoolean()) input.readUTF() else null
                val retryCount = input.readInt()
                val reviewCount = input.readInt()
                require(reviewCount in 0..MealPhotoCaptureConfiguration.MAX_REVIEW_ITEM_COUNT)
                val records = List(reviewCount) {
                    MealPhotoReviewRecord(
                        captureId = input.readUTF(),
                        contentUri = input.readUTF(),
                        volumeName = input.readUTF(),
                        volumeVersion = input.readUTF(),
                        mediaId = input.readLong(),
                        generation = input.readLong(),
                        modifiedGeneration = input.readLong(),
                        mimeType = input.readUTF(),
                        capturedAtEpochMillis = input.readLong(),
                        status = MealPhotoReviewStatus.valueOf(input.readUTF()),
                    )
                }
                require(input.read() == -1)
                MealPhotoCaptureConfiguration(
                    generationId = generationId,
                    ownerDigest = ownerDigest,
                    enabledAtEpochMillis = enabledAt,
                    cursors = cursors,
                    retryCaptureId = retryCaptureId,
                    retryCount = retryCount,
                    reviewRecords = records,
                ).takeIf { it.isValid } ?: error("Invalid meal-photo state")
            }
        }.getOrNull()
    }
}

internal data class MealPhotoCredential(
    val generationId: String,
    val uploadToken: String,
    val idempotencySecret: String,
    val expiresAtEpochMillis: Long,
) {
    val isValid: Boolean
        get() = runCatching {
            UUID.fromString(generationId)
            MealPhotoCaptureEnrollment(
                uploadToken = uploadToken,
                idempotencySecret = idempotencySecret,
                expiresAt = Instant.ofEpochMilli(expiresAtEpochMillis),
            )
        }.isSuccess
}

internal interface MealPhotoCredentialStoring {
    fun currentGenerationId(): String?
    fun bindOwner(generationId: String, ownerDigest: String): Boolean
    fun ownerDigest(generationId: String): String?
    fun load(generationId: String): MealPhotoCredential?
    fun loadPrepared(generationId: String): MealPhotoCredential?
    fun retainedIdempotencySecret(generationId: String): String?
    fun pendingRevocationToken(generationId: String): String?
    fun markEnrollmentPending(generationId: String): Boolean
    fun hasPendingEnrollment(generationId: String): Boolean
    fun clearPendingEnrollment(generationId: String): Boolean
    fun savePrepared(credential: MealPhotoCredential): Boolean
    fun activatePrepared(generationId: String): Boolean
    fun suspend(generationId: String): Boolean
    fun confirmRevoked(generationId: String): Boolean
    fun hasGenerationKey(generationId: String): Boolean
    fun clear(generationId: String, preserveGenerationKey: Boolean): Boolean
}

internal class KeystoreMealPhotoCredentialStore(context: Context) : MealPhotoCredentialStoring {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun currentGenerationId(): String? =
        preferences.getString(GENERATION_KEY, null)

    override fun bindOwner(generationId: String, ownerDigest: String): Boolean {
        if (
            runCatching { UUID.fromString(generationId) }.isFailure ||
            !OWNER_DIGEST.matches(ownerDigest)
        ) return false
        val currentGeneration = preferences.getString(GENERATION_KEY, null)
        if (currentGeneration != null && currentGeneration != generationId) return false
        val currentOwner = preferences.getString(OWNER_DIGEST_KEY, null)
        if (currentOwner != null && currentOwner != ownerDigest) return false
        return preferences.edit()
            .putString(GENERATION_KEY, generationId)
            .putString(OWNER_DIGEST_KEY, ownerDigest)
            .commit()
    }

    override fun ownerDigest(generationId: String): String? =
        preferences.getString(GENERATION_KEY, null)
            ?.takeIf { it == generationId }
            ?.let { preferences.getString(OWNER_DIGEST_KEY, null) }
            ?.takeIf(OWNER_DIGEST::matches)

    override fun load(generationId: String): MealPhotoCredential? {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return null
        val encoded = preferences.getString(CREDENTIAL_KEY, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val key = loadKey(generationId) ?: return null
        val clear = decrypt(encrypted, credentialAad(generationId), key) ?: return null
        return CredentialCodec.decode(clear)?.takeIf { it.generationId == generationId }
    }

    @Synchronized
    override fun loadPrepared(generationId: String): MealPhotoCredential? {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return null
        val encoded = preferences.getString(PREPARED_CREDENTIAL_KEY, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val key = loadKey(generationId) ?: return null
        val clear = decrypt(encrypted, preparedCredentialAad(generationId), key) ?: return null
        return CredentialCodec.decode(clear)?.takeIf { it.generationId == generationId }
    }

    override fun retainedIdempotencySecret(generationId: String): String? {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return null
        val encoded = preferences.getString(IDEMPOTENCY_SECRET_KEY, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val key = loadKey(generationId) ?: return null
        val clear = decrypt(encrypted, idempotencyAad(generationId), key) ?: return null
        return clear.toString(Charsets.UTF_8).takeIf(IDEMPOTENCY_SECRET::matches)
    }

    override fun pendingRevocationToken(generationId: String): String? {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return null
        val encoded = preferences.getString(PENDING_REVOCATION_TOKEN_KEY, null) ?: return null
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val key = loadKey(generationId) ?: return null
        val clear = decrypt(encrypted, revocationAad(generationId), key) ?: return null
        return clear.toString(Charsets.UTF_8).takeIf(UPLOAD_TOKEN::matches)
    }

    override fun markEnrollmentPending(generationId: String): Boolean {
        if (runCatching { UUID.fromString(generationId) }.isFailure) return false
        val current = preferences.getString(GENERATION_KEY, null)
        if (current != null && current != generationId) return false
        return preferences.edit()
            .putString(GENERATION_KEY, generationId)
            .putBoolean(ENROLLMENT_PENDING_KEY, true)
            .commit()
    }

    override fun hasPendingEnrollment(generationId: String): Boolean =
        preferences.getString(GENERATION_KEY, null) == generationId &&
            preferences.getBoolean(ENROLLMENT_PENDING_KEY, false)

    override fun clearPendingEnrollment(generationId: String): Boolean {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return false
        return preferences.edit().remove(ENROLLMENT_PENDING_KEY).commit()
    }

    @Synchronized
    override fun savePrepared(credential: MealPhotoCredential): Boolean {
        if (!credential.isValid) return false
        return runCatching {
            val clear = CredentialCodec.encode(credential) ?: return@runCatching false
            val key = getOrCreateKey(credential.generationId)
            val encrypted = encrypt(
                clear,
                preparedCredentialAad(credential.generationId),
                key,
            ) ?: return@runCatching false
            val encryptedIdempotencySecret = encrypt(
                credential.idempotencySecret.toByteArray(Charsets.UTF_8),
                idempotencyAad(credential.generationId),
                key,
            ) ?: return@runCatching false
            preferences.edit()
                .putString(GENERATION_KEY, credential.generationId)
                .putString(
                    PREPARED_CREDENTIAL_KEY,
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
                )
                .putString(
                    IDEMPOTENCY_SECRET_KEY,
                    Base64.encodeToString(encryptedIdempotencySecret, Base64.NO_WRAP),
                )
                .putBoolean(ENROLLMENT_PENDING_KEY, true)
                .remove(CREDENTIAL_KEY)
                .commit()
        }.getOrDefault(false)
    }

    @Synchronized
    override fun activatePrepared(generationId: String): Boolean {
        val credential = loadPrepared(generationId) ?: return false
        return runCatching {
            val clear = CredentialCodec.encode(credential) ?: return@runCatching false
            val key = loadKey(generationId) ?: return@runCatching false
            val encrypted = encrypt(clear, credentialAad(generationId), key)
                ?: return@runCatching false
            preferences.edit()
                .putString(CREDENTIAL_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove(PREPARED_CREDENTIAL_KEY)
                .remove(PENDING_REVOCATION_TOKEN_KEY)
                .remove(ENROLLMENT_PENDING_KEY)
                .commit()
        }.getOrDefault(false)
    }

    @Synchronized
    override fun suspend(generationId: String): Boolean {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return false
        val credential = loadPrepared(generationId) ?: load(generationId)
        if (retainedIdempotencySecret(generationId) == null) {
            val idempotencySecret = credential?.idempotencySecret
            if (idempotencySecret == null) {
                if (!hasPendingEnrollment(generationId)) return false
            } else {
                val key = loadKey(generationId) ?: return false
                val encryptedIdempotencySecret = encrypt(
                    idempotencySecret.toByteArray(Charsets.UTF_8),
                    idempotencyAad(generationId),
                    key,
                ) ?: return false
                if (
                    !preferences.edit().putString(
                        IDEMPOTENCY_SECRET_KEY,
                        Base64.encodeToString(encryptedIdempotencySecret, Base64.NO_WRAP),
                    ).commit()
                ) return false
            }
        }
        val edit = preferences.edit()
            .remove(CREDENTIAL_KEY)
            .remove(PREPARED_CREDENTIAL_KEY)
        if (credential != null) {
            val key = loadKey(generationId) ?: return false
            val encryptedToken = encrypt(
                credential.uploadToken.toByteArray(Charsets.UTF_8),
                revocationAad(generationId),
                key,
            ) ?: return false
            edit.putString(
                PENDING_REVOCATION_TOKEN_KEY,
                Base64.encodeToString(encryptedToken, Base64.NO_WRAP),
            )
        }
        return edit.commit()
    }

    override fun confirmRevoked(generationId: String): Boolean {
        if (preferences.getString(GENERATION_KEY, null) != generationId) return false
        return preferences.edit()
            .remove(PREPARED_CREDENTIAL_KEY)
            .remove(PENDING_REVOCATION_TOKEN_KEY)
            .remove(ENROLLMENT_PENDING_KEY)
            .commit()
    }

    override fun hasGenerationKey(generationId: String): Boolean =
        runCatching { keyStore().containsAlias(alias(generationId)) }.getOrDefault(false)

    @Synchronized
    override fun clear(generationId: String, preserveGenerationKey: Boolean): Boolean {
        val valuesCleared = preferences.edit()
            .remove(CREDENTIAL_KEY)
            .remove(PREPARED_CREDENTIAL_KEY)
            .remove(IDEMPOTENCY_SECRET_KEY)
            .remove(PENDING_REVOCATION_TOKEN_KEY)
            .remove(ENROLLMENT_PENDING_KEY)
            .remove(OWNER_DIGEST_KEY)
            .remove(GENERATION_KEY)
            .commit()
        val keyCleared = if (preserveGenerationKey) {
            true
        } else {
            runCatching {
                val store = keyStore()
                if (store.containsAlias(alias(generationId))) store.deleteEntry(alias(generationId))
                !store.containsAlias(alias(generationId))
            }.getOrDefault(false)
        }
        return valuesCleared && keyCleared
    }

    private fun getOrCreateKey(generationId: String): SecretKey {
        loadKey(generationId)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias(generationId),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKey(generationId: String): SecretKey? = runCatching {
        keyStore().getKey(alias(generationId), null) as? SecretKey
    }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun encrypt(clear: ByteArray, aad: String, key: SecretKey): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(clear)
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(cipher.iv.size)
                    output.write(cipher.iv)
                    output.write(encrypted)
                }
                bytes.toByteArray()
            }
        }.getOrNull()

    private fun decrypt(encrypted: ByteArray, aad: String, key: SecretKey): ByteArray? =
        runCatching {
            DataInputStream(ByteArrayInputStream(encrypted)).use { input ->
                val ivLength = input.readInt()
                require(ivLength in 12..16)
                val iv = ByteArray(ivLength)
                input.readFully(iv)
                val payload = input.readBytes()
                require(payload.isNotEmpty())
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
                cipher.doFinal(payload)
            }
        }.getOrNull()

    private fun credentialAad(generationId: String): String = "$generationId:credential"

    private fun preparedCredentialAad(generationId: String): String =
        "$generationId:prepared-credential"

    private fun idempotencyAad(generationId: String): String = "$generationId:idempotency"

    private fun revocationAad(generationId: String): String = "$generationId:revocation"

    private fun alias(generationId: String): String =
        "$ALIAS_PREFIX${generationId.replace("-", "")}".take(120)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "ai.withmurph.meal-photo."
        const val PREFERENCES = "murph_meal_photo_credentials"
        const val CREDENTIAL_KEY = "credential_v1"
        const val PREPARED_CREDENTIAL_KEY = "prepared_credential_v1"
        const val IDEMPOTENCY_SECRET_KEY = "idempotency_secret_v1"
        const val PENDING_REVOCATION_TOKEN_KEY = "pending_revocation_token_v1"
        const val ENROLLMENT_PENDING_KEY = "enrollment_pending_v1"
        const val OWNER_DIGEST_KEY = "owner_digest_v1"
        val OWNER_DIGEST = Regex("^[0-9a-f]{64}$")
        const val GENERATION_KEY = "generation_v1"
        val IDEMPOTENCY_SECRET = Regex("^[A-Za-z0-9_-]{43}$")
        val UPLOAD_TOKEN = Regex("^murph_meal_photo_[A-Za-z0-9_-]{43}$")
    }
}

internal object CredentialCodec {
    private const val MAGIC = 0x4D504343
    private const val VERSION = 1
    private const val MAX_BYTES = 2_048

    fun encode(credential: MealPhotoCredential): ByteArray? {
        if (!credential.isValid) return null
        return runCatching {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(credential.generationId)
                    output.writeUTF(credential.uploadToken)
                    output.writeUTF(credential.idempotencySecret)
                    output.writeLong(credential.expiresAtEpochMillis)
                }
                bytes.toByteArray().takeIf { it.size <= MAX_BYTES }
                    ?: error("Meal-photo credential is too large")
            }
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): MealPhotoCredential? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                MealPhotoCredential(
                    generationId = input.readUTF(),
                    uploadToken = input.readUTF(),
                    idempotencySecret = input.readUTF(),
                    expiresAtEpochMillis = input.readLong(),
                ).also {
                    require(it.isValid)
                    require(input.read() == -1)
                }
            }
        }.getOrNull()
    }
}
