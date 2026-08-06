package ai.withmurph.companion.meal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealPhotoImageSanitizerTest {
    @Test
    fun metadataSegmentsAreRemovedWithoutChangingImageAndScanSegments() {
        val quantization = segment(0xDB, byteArrayOf(6, 7))
        val scan = byteArrayOf(
            0xFF.toByte(),
            0xDA.toByte(),
            0x00,
            0x02,
            0x11,
            0x22,
            0xFF.toByte(),
            0xD9.toByte(),
        )
        val encoded = JPEG_START +
            segment(0xE1, byteArrayOf(1, 2, 3)) +
            segment(0xFE, byteArrayOf(4, 5)) +
            quantization +
            scan

        assertArrayEquals(
            JPEG_START + quantization + scan,
            MealPhotoImageSanitizer.removeMetadataSegments(encoded),
        )
    }

    @Test
    fun malformedOrNonJpegInputIsRejected() {
        assertNull(MealPhotoImageSanitizer.removeMetadataSegments(byteArrayOf(1, 2, 3)))
        assertNull(
            MealPhotoImageSanitizer.removeMetadataSegments(
                JPEG_START + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x7F),
            ),
        )
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + payload
    }

    private companion object {
        val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    }
}
