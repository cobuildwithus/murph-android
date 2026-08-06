package ai.withmurph.companion.meal

import ai.withmurph.companion.core.MealPhotoActionResult
import ai.withmurph.companion.core.MealPhotoReviewStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MealPhotoProcessorAuthorizationTest {
    @Test
    fun fenceClosedAfterClassificationPreventsThePendingUpload() = runTest {
        val classificationEntered = CompletableDeferred<Unit>()
        val releaseClassification = CompletableDeferred<Unit>()
        val fixture = fixture(
            candidates = listOf(candidate(isCameraOrigin = true)),
            asset = PausedAsset(classificationEntered, releaseClassification),
        )

        val processing = async { fixture.processor.process() }
        classificationEntered.await()
        assertEquals(1, fixture.media.pixelReads)

        assertTrue(fixture.authorization.suspendAll())
        releaseClassification.complete(Unit)

        assertEquals(MealPhotoProcessingResult.Inactive, processing.await())
        assertEquals(0, fixture.uploader.uploads)
        assertFalse(fixture.authorization.isAuthorized(GENERATION_ID))
    }

    @Test
    fun acceptedNonCameraImageEntersReviewAndIsNeverAutoUploaded() = runTest {
        val fixture = fixture(candidates = listOf(candidate(isCameraOrigin = false)))

        assertEquals(MealPhotoProcessingResult.Completed, fixture.processor.process())

        assertEquals(0, fixture.uploader.uploads)
        assertEquals(1, fixture.state.load()?.reviewRecords?.size)
        assertEquals(
            MealPhotoReviewStatus.NeedsReview,
            fixture.state.load()?.reviewRecords?.single()?.status,
        )
    }

    @Test
    fun acceptedCameraImageAlsoStaysLocalUntilExplicitReview() = runTest {
        val fixture = fixture(candidates = listOf(candidate(isCameraOrigin = true)))

        assertEquals(MealPhotoProcessingResult.Completed, fixture.processor.process())

        assertEquals(0, fixture.uploader.uploads)
        assertEquals(
            MealPhotoReviewStatus.NeedsReview,
            fixture.state.load()?.reviewRecords?.single()?.status,
        )
    }

    @Test
    fun modifiedOrPathDerivedIdentityChangeAfterDecodeBlocksAutomaticUpload() = runTest {
        val original = candidate(isCameraOrigin = true)
        val changedCandidates = listOf(
            original.copy(modifiedGeneration = original.modifiedGeneration + 1),
            original.copy(isScreenshot = true),
            original.copy(isCameraOrigin = false),
        )

        changedCandidates.forEach { changed ->
            val fixture = fixture(
                candidates = listOf(original),
                validation = { _, _ -> MealPhotoCandidateValidation.Valid(changed) },
            )

            assertEquals(MealPhotoProcessingResult.Pending, fixture.processor.process())
            assertEquals(0, fixture.uploader.uploads)
            assertTrue(fixture.state.load()?.reviewRecords?.isEmpty() == true)
        }
    }

    @Test
    fun normalizedInvalidCaptureTimestampIsTerminalWithoutReadingPixels() = runTest {
        val fixture = fixture(
            candidates = listOf(
                candidate(
                    capturedAtEpochMillis = 0,
                    generation = 11,
                    mediaId = 7,
                ),
            ),
        )

        assertEquals(MealPhotoProcessingResult.Completed, fixture.processor.process())

        assertEquals(0, fixture.media.pixelReads)
        assertEquals(11L, fixture.state.load()?.cursor("external")?.generation)
        assertEquals(7L, fixture.state.load()?.cursor("external")?.mediaId)
    }

    @Test
    fun unavailableProviderDoesNotDeleteReviewButStaleIdentityDoes() = runTest {
        val record = reviewRecord(CAPTURE_ONE, MealPhotoReviewStatus.NeedsReview)
        val unavailable = fixture(
            configuration = configuration(reviewRecords = listOf(record)),
            validation = { _, _ -> MealPhotoCandidateValidation.Unavailable },
        )

        assertTrue(unavailable.processor.reviewItems().isEmpty())
        assertEquals(listOf(record), unavailable.state.load()?.reviewRecords)

        val stale = fixture(
            configuration = configuration(reviewRecords = listOf(record)),
            validation = { _, _ -> MealPhotoCandidateValidation.Stale },
        )

        assertTrue(stale.processor.reviewItems().isEmpty())
        assertTrue(stale.state.load()?.reviewRecords?.isEmpty() == true)
    }

    @Test
    fun permissionLossNeitherReadsPixelsNorDeletesOrApprovesReview() = runTest {
        val record = reviewRecord(CAPTURE_ONE, MealPhotoReviewStatus.NeedsReview)
        val fixture = fixture(
            configuration = configuration(reviewRecords = listOf(record)),
            access = MealPhotoMediaAccess.None,
        )

        assertTrue(fixture.processor.reviewItems().isEmpty())
        assertEquals(MealPhotoActionResult.NeedsAttention, fixture.processor.approve(record.captureId))
        assertEquals(listOf(record), fixture.state.load()?.reviewRecords)
        assertEquals(0, fixture.media.validationCalls.size)
        assertEquals(0, fixture.media.pixelReads)
        assertEquals(0, fixture.uploader.uploads)
    }

    @Test
    fun needsReviewItemsRenderBeforeSentItemsRegardlessOfCaptureTime() = runTest {
        val olderNeedsReview = reviewRecord(
            CAPTURE_ONE,
            MealPhotoReviewStatus.NeedsReview,
            capturedAt = ENABLED_AT + 1,
        )
        val newerSent = reviewRecord(
            CAPTURE_TWO,
            MealPhotoReviewStatus.Sent,
            capturedAt = ENABLED_AT + 100,
        )
        val fixture = fixture(
            configuration = configuration(reviewRecords = listOf(newerSent, olderNeedsReview)),
        )

        assertEquals(
            listOf(CAPTURE_ONE, CAPTURE_TWO),
            fixture.processor.reviewItems().map { it.id },
        )
    }

    @Test
    fun reviewIdentityChangeAfterThumbnailDecodePreventsRenderingOrApproval() = runTest {
        val record = reviewRecord(CAPTURE_ONE, MealPhotoReviewStatus.NeedsReview)
        val exact = candidate(record)
        val changed = exact.copy(modifiedGeneration = exact.modifiedGeneration + 1)
        val fixture = fixture(
            configuration = configuration(reviewRecords = listOf(record)),
            validation = { _, call ->
                MealPhotoCandidateValidation.Valid(if (call % 2 == 1) exact else changed)
            },
        )

        assertTrue(fixture.processor.reviewItems().isEmpty())
        assertEquals(listOf(record), fixture.state.load()?.reviewRecords)

        fixture.media.resetValidationCount()
        assertEquals(MealPhotoActionResult.TryAgain, fixture.processor.approve(record.captureId))
        assertEquals(0, fixture.uploader.uploads)
        assertEquals(listOf(record), fixture.state.load()?.reviewRecords)
    }

    @Test
    fun uploadConflictLeavesReviewActionableAndReturnsNeedsAttention() = runTest {
        val record = reviewRecord(CAPTURE_ONE, MealPhotoReviewStatus.NeedsReview)
        val fixture = fixture(
            configuration = configuration(reviewRecords = listOf(record)),
            uploadDisposition = MealPhotoUploadDisposition.NeedsAttention,
        )

        assertEquals(MealPhotoActionResult.NeedsAttention, fixture.processor.approve(record.captureId))

        assertEquals(1, fixture.uploader.uploads)
        assertEquals(MealPhotoReviewStatus.NeedsReview, fixture.state.load()?.reviewRecords?.single()?.status)
    }

    private fun fixture(
        configuration: MealPhotoCaptureConfiguration = configuration(),
        candidates: List<MealPhotoCandidate> = emptyList(),
        access: MealPhotoMediaAccess = MealPhotoMediaAccess.Full,
        asset: MealPhotoImageAsset = FixedAsset(),
        validation: ((MealPhotoReviewRecord, Int) -> MealPhotoCandidateValidation)? = null,
        uploadDisposition: MealPhotoUploadDisposition = MealPhotoUploadDisposition.Uploaded,
    ): Fixture {
        val state = FakeStateStore(configuration)
        val authorization = FakeAuthorizationStore(configuration.generationId)
        val media = FakeMediaSource(
            candidates = candidates,
            access = access,
            asset = asset,
            validation = validation,
        )
        val uploader = RecordingUploader(uploadDisposition)
        val processor = MealPhotoProcessor(
            media = media,
            classifier = object : MealPhotoImageClassifier {
                override suspend fun classify(
                    bitmap: android.graphics.Bitmap,
                ): List<MealPhotoClassificationObservation> =
                    error("Fake assets own classification")
            },
            uploader = uploader,
            stateStore = state,
            credentialStore = FakeCredentialStore(credential(configuration.generationId)),
            authorizationStore = authorization,
            now = { NOW },
        )
        return Fixture(processor, media, state, authorization, uploader)
    }

    private data class Fixture(
        val processor: MealPhotoProcessor,
        val media: FakeMediaSource,
        val state: FakeStateStore,
        val authorization: FakeAuthorizationStore,
        val uploader: RecordingUploader,
    )

    private class FixedAsset(
        private val observations: List<MealPhotoClassificationObservation> = listOf(
            MealPhotoClassificationObservation("food", 0.99f),
        ),
    ) : MealPhotoImageAsset {
        override val jpeg: ByteArray = byteArrayOf(0x01, 0x02)

        override suspend fun classify(
            classifier: MealPhotoImageClassifier,
        ) = observations

        override fun close() = Unit
    }

    private class PausedAsset(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : MealPhotoImageAsset {
        override val jpeg: ByteArray = byteArrayOf(0x01, 0x02)

        override suspend fun classify(
            classifier: MealPhotoImageClassifier,
        ): List<MealPhotoClassificationObservation> {
            entered.complete(Unit)
            release.await()
            return listOf(MealPhotoClassificationObservation("food", 0.99f))
        }

        override fun close() = Unit
    }

    private class FakeMediaSource(
        private val candidates: List<MealPhotoCandidate>,
        private val access: MealPhotoMediaAccess,
        private val asset: MealPhotoImageAsset,
        private val validation: ((MealPhotoReviewRecord, Int) -> MealPhotoCandidateValidation)?,
    ) : MealPhotoMediaSource {
        var pixelReads = 0
            private set
        val validationCalls = mutableListOf<String>()
        private var validationCount = 0

        override val automaticCaptureSupported = true
        override fun access() = access
        override fun permissionRequest(): Array<String> = emptyArray()
        override suspend fun currentBoundaries() = listOf(BASELINE)
        override suspend fun candidatesAfter(
            cursor: MealPhotoVolumeCursor,
            limit: Int,
        ) = candidates

        override suspend fun revalidatedCandidate(
            record: MealPhotoReviewRecord,
        ): MealPhotoCandidateValidation {
            validationCalls += record.captureId
            validationCount += 1
            return validation?.invoke(record, validationCount)
                ?: MealPhotoCandidateValidation.Valid(
                    candidates.firstOrNull { candidate ->
                        candidate.volumeName == record.volumeName &&
                            candidate.volumeVersion == record.volumeVersion &&
                            candidate.mediaId == record.mediaId &&
                            candidate.generation == record.generation &&
                            candidate.modifiedGeneration == record.modifiedGeneration &&
                            candidate.contentUri == record.contentUri
                    } ?: MealPhotoCandidate(
                            volumeName = record.volumeName,
                            volumeVersion = record.volumeVersion,
                            mediaId = record.mediaId,
                            generation = record.generation,
                            modifiedGeneration = record.modifiedGeneration,
                            contentUri = record.contentUri,
                            capturedAtEpochMillis = record.capturedAtEpochMillis,
                            mimeType = record.mimeType,
                            isScreenshot = false,
                            isCameraOrigin = true,
                        ),
                )
        }

        override suspend fun sanitizedImage(
            contentUri: String,
            maximumDimension: Int,
        ): MealPhotoImageAsset {
            pixelReads += 1
            return asset
        }

        fun resetValidationCount() {
            validationCount = 0
            validationCalls.clear()
        }
    }

    private class RecordingUploader(
        private val disposition: MealPhotoUploadDisposition,
    ) : MealPhotoUploading {
        var uploads = 0
            private set

        override suspend fun upload(
            jpeg: ByteArray,
            credential: MealPhotoCredential,
            captureId: String,
            capturedAt: Instant,
        ): MealPhotoUploadDisposition {
            uploads += 1
            return disposition
        }

        override suspend fun activateScoped(
            uploadToken: String,
        ): MealPhotoActivationDisposition = MealPhotoActivationDisposition.Retry

        override suspend fun revokeScoped(
            uploadToken: String,
        ): MealPhotoRevocationDisposition = MealPhotoRevocationDisposition.Revoked
    }

    private class FakeStateStore(
        private var state: MealPhotoCaptureConfiguration?,
    ) : MealPhotoStateStoring {
        override fun load() = state
        override fun save(configuration: MealPhotoCaptureConfiguration): Boolean {
            state = configuration
            return true
        }

        override fun clear(): Boolean {
            state = null
            return true
        }
    }

    private class FakeCredentialStore(
        private var credential: MealPhotoCredential?,
    ) : MealPhotoCredentialStoring {
        private var generationId = credential?.generationId
        private var boundOwnerDigest: String? = null

        override fun currentGenerationId() = generationId

        override fun bindOwner(generationId: String, ownerDigest: String): Boolean {
            if (this.generationId != null && this.generationId != generationId) return false
            if (boundOwnerDigest != null && boundOwnerDigest != ownerDigest) return false
            this.generationId = generationId
            boundOwnerDigest = ownerDigest
            return true
        }

        override fun ownerDigest(generationId: String) =
            boundOwnerDigest?.takeIf { this.generationId == generationId }

        override fun load(generationId: String) = credential?.takeIf {
            it.generationId == generationId
        }

        override fun loadPrepared(generationId: String): MealPhotoCredential? = null

        override fun retainedIdempotencySecret(generationId: String) =
            load(generationId)?.idempotencySecret

        override fun pendingRevocationToken(generationId: String): String? = null
        override fun markEnrollmentPending(generationId: String): Boolean = false
        override fun hasPendingEnrollment(generationId: String): Boolean = false
        override fun clearPendingEnrollment(generationId: String): Boolean = true

        override fun savePrepared(credential: MealPhotoCredential): Boolean = false

        override fun activatePrepared(generationId: String): Boolean = false

        override fun suspend(generationId: String): Boolean {
            if (this.generationId != generationId) return false
            credential = null
            return true
        }

        override fun confirmRevoked(generationId: String): Boolean = true
        override fun hasGenerationKey(generationId: String) =
            credential?.generationId == generationId

        override fun clear(generationId: String, preserveGenerationKey: Boolean): Boolean {
            if (this.generationId != null && this.generationId != generationId) return false
            credential = null
            this.generationId = null
            boundOwnerDigest = null
            return true
        }
    }

    private class FakeAuthorizationStore(generationId: String) : MealPhotoAuthorizationStoring {
        private var current = MealPhotoAuthorizationSnapshot(
            epoch = 0,
            generationId = generationId,
            disposition = MealPhotoAuthorizationDisposition.Authorized,
        )

        override fun snapshot() = current
        override fun isAuthorized(generationId: String) =
            current.generationId == generationId &&
                current.disposition == MealPhotoAuthorizationDisposition.Authorized

        override fun suspendForConsent(): Boolean {
            if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
            return advance(
                if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
                    MealPhotoAuthorizationDisposition.Disabled
                } else {
                    MealPhotoAuthorizationDisposition.ConsentSuspended
                },
            )
        }


        override fun suspendForCredentialRepair(): Boolean {
            if (current.disposition == MealPhotoAuthorizationDisposition.Suspended) return false
            return advance(
                if (current.disposition == MealPhotoAuthorizationDisposition.Disabled) {
                    MealPhotoAuthorizationDisposition.Disabled
                } else {
                    MealPhotoAuthorizationDisposition.CredentialSuspended
                },
            )
        }

        override fun suspendAll(): Boolean = advance(MealPhotoAuthorizationDisposition.Suspended)
        override fun disableAll(): Boolean = advance(MealPhotoAuthorizationDisposition.Disabled)

        override fun authorize(
            generationId: String,
            expectedEpoch: Long,
            allowedPrevious: Set<MealPhotoAuthorizationDisposition>,
        ): Boolean {
            if (current.epoch != expectedEpoch || current.disposition !in allowedPrevious) return false
            current = current.copy(
                generationId = generationId,
                disposition = MealPhotoAuthorizationDisposition.Authorized,
            )
            return true
        }

        override fun clearGeneration(generationId: String): Boolean {
            if (current.generationId != generationId) return false
            current = current.copy(generationId = null)
            return true
        }

        private fun advance(disposition: MealPhotoAuthorizationDisposition): Boolean {
            current = current.copy(epoch = current.epoch + 1, disposition = disposition)
            return true
        }
    }

    private fun configuration(
        reviewRecords: List<MealPhotoReviewRecord> = emptyList(),
    ) = MealPhotoCaptureConfiguration(
        generationId = GENERATION_ID,
        ownerDigest = "a".repeat(64),
        enabledAtEpochMillis = ENABLED_AT,
        cursors = listOf(BASELINE),
        reviewRecords = reviewRecords,
    )

    private fun candidate(
        volumeName: String = "external",
        volumeVersion: String = "v1",
        mediaId: Long = 1,
        generation: Long = 11,
        modifiedGeneration: Long = generation,
        contentUri: String = "content://media/external/images/media/$mediaId",
        capturedAtEpochMillis: Long = ENABLED_AT + 1,
        mimeType: String = "image/jpeg",
        isScreenshot: Boolean = false,
        isCameraOrigin: Boolean = true,
    ) = MealPhotoCandidate(
        volumeName = volumeName,
        volumeVersion = volumeVersion,
        mediaId = mediaId,
        generation = generation,
        modifiedGeneration = modifiedGeneration,
        contentUri = contentUri,
        capturedAtEpochMillis = capturedAtEpochMillis,
        mimeType = mimeType,
        isScreenshot = isScreenshot,
        isCameraOrigin = isCameraOrigin,
    )

    private fun candidate(record: MealPhotoReviewRecord) = MealPhotoCandidate(
        volumeName = record.volumeName,
        volumeVersion = record.volumeVersion,
        mediaId = record.mediaId,
        generation = record.generation,
        modifiedGeneration = record.modifiedGeneration,
        contentUri = record.contentUri,
        capturedAtEpochMillis = record.capturedAtEpochMillis,
        mimeType = record.mimeType,
        isScreenshot = false,
        isCameraOrigin = true,
    )

    private fun reviewRecord(
        captureId: String,
        status: MealPhotoReviewStatus,
        capturedAt: Long = ENABLED_AT + 1,
    ) = MealPhotoReviewRecord(
        captureId = captureId,
        contentUri = "content://media/external/images/media/${captureId.first()}",
        volumeName = "external",
        volumeVersion = "v1",
        mediaId = captureId.first().digitToInt().toLong(),
        generation = 11,
        modifiedGeneration = 11,
        mimeType = "image/jpeg",
        capturedAtEpochMillis = capturedAt,
        status = status,
    )

    private fun credential(generationId: String) = MealPhotoCredential(
        generationId = generationId,
        uploadToken = "murph_meal_photo_${"A".repeat(43)}",
        idempotencySecret = "B".repeat(43),
        expiresAtEpochMillis = NOW.plusSeconds(86_400).toEpochMilli(),
    )

    private companion object {
        const val GENERATION_ID = "00000000-0000-4000-8000-000000000001"
        val NOW: Instant = Instant.parse("2026-08-05T12:00:00Z")
        val ENABLED_AT = NOW.minusSeconds(60).toEpochMilli()
        val CAPTURE_ONE = "1".repeat(64)
        val CAPTURE_TWO = "2".repeat(64)
        val BASELINE = MealPhotoVolumeCursor(
            volumeName = "external",
            version = "v1",
            generation = 10,
            mediaId = Long.MAX_VALUE,
        )
    }
}
