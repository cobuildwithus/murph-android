package ai.withmurph.companion.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialOnboardingImageTest {
    @Test
    fun avatarSamplingBoundsA4096PixelImageForTheLargestRenderedAvatar() {
        assertEquals(8, avatarSampleSize(width = 4096, height = 4096, targetPixels = 384))
    }

    @Test
    fun avatarSamplingPreservesImagesAlreadyWithinTheRenderedTarget() {
        assertEquals(1, avatarSampleSize(width = 288, height = 144, targetPixels = 288))
    }

    @Test
    fun avatarSamplingUsesTheLargestDimensionForExtremeAspectRatios() {
        assertEquals(8, avatarSampleSize(width = 4096, height = 1, targetPixels = 384))
    }

    @Test
    fun avatarSamplingDoesNotUpscaleTheCurrent320PixelAssets() {
        assertEquals(1, avatarSampleSize(width = 320, height = 320, targetPixels = 288))
    }

    @Test
    fun avatarSamplingCanReduceFurtherWhenTheRenderedTargetIsSmall() {
        assertEquals(32, avatarSampleSize(width = 4096, height = 4096, targetPixels = 96))
        assertEquals(4, avatarSampleSize(width = 1024, height = 512, targetPixels = 128))
    }

    @Test
    fun avatarSamplingPrefersTheHardDecodeCeilingWhenTheTargetCannotFit() {
        assertEquals(2, avatarSampleSize(width = 513, height = 513, targetPixels = 384))
    }
}
