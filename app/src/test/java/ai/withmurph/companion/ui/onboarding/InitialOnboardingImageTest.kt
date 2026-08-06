package ai.withmurph.companion.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialOnboardingImageTest {
    @Test
    fun avatarSamplingBoundsA4096PixelImageForTheLargestRenderedAvatar() {
        assertEquals(16, avatarSampleSize(width = 4096, height = 4096, targetPixels = 384))
    }

    @Test
    fun avatarSamplingPreservesImagesAlreadyWithinTheRenderedTarget() {
        assertEquals(1, avatarSampleSize(width = 288, height = 144, targetPixels = 288))
    }

    @Test
    fun avatarSamplingUsesTheLargestDimensionForExtremeAspectRatios() {
        assertEquals(16, avatarSampleSize(width = 4096, height = 1, targetPixels = 384))
    }
}
