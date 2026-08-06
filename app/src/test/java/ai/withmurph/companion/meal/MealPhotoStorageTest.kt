package ai.withmurph.companion.meal

import ai.withmurph.companion.api.MealPhotoCaptureApiContract
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.MealPhotoReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MealPhotoStorageTest {
    @Test
    fun stateCodecRoundTripsOnlyValidBoundedState() {
        val state = configuration().copy(
            retryCaptureId = CAPTURE_ONE,
            retryCount = 2,
            reviewRecords = listOf(
                MealPhotoReviewRecord(
                    captureId = CAPTURE_TWO,
                    contentUri = "content://media/external/images/media/2",
                    volumeName = "external",
                    volumeVersion = "v1",
                    mediaId = 2,
                    generation = 11,
                    modifiedGeneration = 12,
                    mimeType = "image/jpeg",
                    capturedAtEpochMillis = ENABLED_AT + 2,
                    status = MealPhotoReviewStatus.NeedsReview,
                ),
            ),
        )

        val encoded = MealPhotoStateCodec.encode(state)
        assertNotNull(encoded)
        val requiredEncoded = checkNotNull(encoded)

        assertEquals(state, MealPhotoStateCodec.decode(requiredEncoded))
        assertNull(MealPhotoStateCodec.decode(requiredEncoded + 0))
        assertNull(MealPhotoStateCodec.encode(state.copy(ownerDigest = "not-a-digest")))
    }

    @Test
    fun futureOnlyCursorNeverReconsidersTheEnablementGeneration() {
        val baseline = configuration()

        val sameGeneration = baseline.markTerminal("external", generation = 10, mediaId = 99)
        val futureGeneration = baseline.markTerminal("external", generation = 11, mediaId = 1)

        assertEquals(baseline, sameGeneration)
        assertEquals(11L, futureGeneration.cursor("external")?.generation)
        assertEquals(1L, futureGeneration.cursor("external")?.mediaId)
    }

    @Test
    fun assetFailureBecomesTerminalOnlyOnTheThirdAttempt() {
        val first = configuration().recordAssetFailure(CAPTURE_ONE)
        val second = first.first.recordAssetFailure(CAPTURE_ONE)
        val third = second.first.recordAssetFailure(CAPTURE_ONE)

        assertFalse(first.second)
        assertFalse(second.second)
        assertTrue(third.second)
        assertNull(third.first.retryCaptureId)
        assertEquals(0, third.first.retryCount)
    }

    @Test
    fun ownerAndCaptureDigestsAreFixedLowercaseHex() {
        val digest = MealPhotoCaptureConfiguration.ownerDigest(
            "00000000-0000-4000-8000-000000000001",
            "member-one",
        )

        assertTrue(Regex("^[0-9a-f]{64}$").matches(digest))
        assertEquals("00ff80", byteArrayOf(0, -1, -128).toLowerHex())
    }

    @Test
    fun authorityRevisionAllocationIsPositiveMonotonicAndInt32Bounded() {
        assertEquals(1L, nextMealPhotoAuthorityRevision(0))
        assertEquals(Int.MAX_VALUE.toLong(), nextMealPhotoAuthorityRevision(Int.MAX_VALUE - 1L))
        assertNull(nextMealPhotoAuthorityRevision(-1))
        assertNull(nextMealPhotoAuthorityRevision(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun credentialCodecRoundTripsAndRejectsTrailingData() {
        val credential = MealPhotoCredential(
            generationId = GENERATION_ID,
            uploadToken = "murph_meal_photo_${"A".repeat(43)}",
            idempotencySecret = "B".repeat(43),
            expiresAtEpochMillis = ENABLED_AT + 10_000,
        )

        val encoded = CredentialCodec.encode(credential)
        assertNotNull(encoded)
        val requiredEncoded = checkNotNull(encoded)

        assertEquals(credential, CredentialCodec.decode(requiredEncoded))
        assertNull(CredentialCodec.decode(requiredEncoded + 0))
    }

    @Test
    fun volumeReplacementAndEjectionResetCursorsAndInvalidateOldReviewIdentity() {
        val state = configuration().copy(
            cursors = listOf(
                MealPhotoVolumeCursor("external", "v1", generation = 14, mediaId = 4),
                MealPhotoVolumeCursor("sd-card", "sd-v1", generation = 20, mediaId = 2),
            ),
            retryCaptureId = CAPTURE_ONE,
            retryCount = 1,
            reviewRecords = listOf(
                reviewRecord(CAPTURE_ONE, "external", "v1", mediaId = 4),
                reviewRecord(CAPTURE_TWO, "sd-card", "sd-v1", mediaId = 2),
            ),
        )

        val replacementBoundary = MealPhotoVolumeCursor(
            volumeName = "external",
            version = "v2",
            generation = 3,
            mediaId = Long.MAX_VALUE,
        )
        val addedBoundary = MealPhotoVolumeCursor(
            volumeName = "usb",
            version = "usb-v1",
            generation = 7,
            mediaId = Long.MAX_VALUE,
        )

        val reconciled = state.reconciledWith(listOf(replacementBoundary, addedBoundary))

        assertEquals(listOf(replacementBoundary, addedBoundary), reconciled.cursors)
        assertTrue(reconciled.reviewRecords.isEmpty())
        assertNull(reconciled.retryCaptureId)
        assertEquals(0, reconciled.retryCount)
    }

    @Test
    fun reviewIdentityIncludesModifiedGenerationAndRejectsScreenshots() {
        val candidate = candidate()
        val record = candidate.reviewRecord(CAPTURE_ONE, MealPhotoReviewStatus.NeedsReview)

        assertTrue(candidate.matches(record))
        assertFalse(candidate.copy(modifiedGeneration = candidate.modifiedGeneration + 1).matches(record))
        assertFalse(candidate.copy(isScreenshot = true).matches(record))
        assertFalse(candidate.copy(volumeVersion = "v2").matches(record))
    }

    @Test
    fun captureIdChangesAcrossMediaStoreVersionsAndModifiedGenerations() {
        val candidate = candidate()
        val baseline = MealPhotoCaptureId.derive(candidate, IDEMPOTENCY_SECRET)

        assertTrue(MealPhotoCaptureConfiguration.CAPTURE_ID.matches(baseline))
        assertTrue(
            baseline != MealPhotoCaptureId.derive(
                candidate.copy(volumeVersion = "v2"),
                IDEMPOTENCY_SECRET,
            ),
        )
        assertTrue(
            baseline != MealPhotoCaptureId.derive(
                candidate.copy(modifiedGeneration = candidate.modifiedGeneration + 1),
                IDEMPOTENCY_SECRET,
            ),
        )
    }

    @Test
    fun uploadConflictRequiresAttentionWithoutDiscardingThePhoto() {
        assertEquals(
            MealPhotoUploadDisposition.NeedsAttention,
            MealPhotoUploadStatusPolicy.disposition(409),
        )
        assertEquals(
            MealPhotoUploadDisposition.CredentialRejected,
            MealPhotoUploadStatusPolicy.disposition(401),
        )
        assertEquals(MealPhotoUploadDisposition.Discard, MealPhotoUploadStatusPolicy.disposition(413))
        assertEquals(MealPhotoUploadDisposition.Retry, MealPhotoUploadStatusPolicy.disposition(500))
        assertEquals(MealPhotoUploadDisposition.Retry, MealPhotoUploadStatusPolicy.disposition(null))
    }

    @Test
    fun identityRevocationContractAcceptsOnlyAnExplicitBoolean() {
        assertTrue(MealPhotoCaptureApiContract.parseRevocation(true))
        assertFalse(MealPhotoCaptureApiContract.parseRevocation(false))

        listOf<Any?>(null, "true", 1, emptyMap<String, Any>()).forEach { invalid ->
            try {
                MealPhotoCaptureApiContract.parseRevocation(invalid)
                fail("Expected invalid revocation response")
            } catch (_: CompanionApiException.InvalidResponse) {
                // Expected.
            }
        }
    }

    private fun configuration() = MealPhotoCaptureConfiguration(
        generationId = GENERATION_ID,
        ownerDigest = "a".repeat(64),
        enabledAtEpochMillis = ENABLED_AT,
        cursors = listOf(
            MealPhotoVolumeCursor(
                volumeName = "external",
                version = "v1",
                generation = 10,
                mediaId = Long.MAX_VALUE,
            ),
        ),
    )

    private fun candidate() = MealPhotoCandidate(
        volumeName = "external",
        volumeVersion = "v1",
        mediaId = 12,
        generation = 21,
        modifiedGeneration = 22,
        contentUri = "content://media/external/images/media/12",
        capturedAtEpochMillis = ENABLED_AT + 2,
        mimeType = "image/jpeg",
        isScreenshot = false,
        isCameraOrigin = true,
    )

    private fun reviewRecord(
        captureId: String,
        volumeName: String,
        volumeVersion: String,
        mediaId: Long,
    ) = MealPhotoReviewRecord(
        captureId = captureId,
        contentUri = "content://media/$volumeName/images/media/$mediaId",
        volumeName = volumeName,
        volumeVersion = volumeVersion,
        mediaId = mediaId,
        generation = 11,
        modifiedGeneration = 11,
        mimeType = "image/jpeg",
        capturedAtEpochMillis = ENABLED_AT + mediaId,
        status = MealPhotoReviewStatus.NeedsReview,
    )

    private companion object {
        const val GENERATION_ID = "00000000-0000-4000-8000-000000000001"
        const val ENABLED_AT = 1_700_000_000_000L
        val CAPTURE_ONE = "1".repeat(64)
        val CAPTURE_TWO = "2".repeat(64)
        val IDEMPOTENCY_SECRET = "S".repeat(43)
    }
}
