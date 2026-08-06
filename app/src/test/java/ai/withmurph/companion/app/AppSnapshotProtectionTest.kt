package ai.withmurph.companion.app

import ai.withmurph.companion.core.MealPhotoReviewItem
import ai.withmurph.companion.core.MealPhotoReviewStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppSnapshotProtectionTest {
    @Test
    fun protectsLoginAndAnyInMemoryMealThumbnail() {
        assertTrue(shouldProtectAppSnapshot(AppUiState(phase = AppPhase.NeedsLogin)))
        assertFalse(shouldProtectAppSnapshot(AppUiState(phase = AppPhase.Ready)))
        assertFalse(
            shouldProtectAppSnapshot(
                AppUiState(
                    phase = AppPhase.Ready,
                    mealPhotoReviewItems = listOf(review(thumbnail = null)),
                ),
            ),
        )
        assertTrue(
            shouldProtectAppSnapshot(
                AppUiState(
                    phase = AppPhase.Ready,
                    mealPhotoReviewItems = listOf(review(thumbnail = byteArrayOf(1, 2, 3))),
                ),
            ),
        )
    }

    private fun review(thumbnail: ByteArray?) = MealPhotoReviewItem(
        id = "a".repeat(64),
        capturedAt = Instant.parse("2026-08-05T12:00:00Z"),
        status = MealPhotoReviewStatus.NeedsReview,
        thumbnailJpeg = thumbnail,
    )
}
