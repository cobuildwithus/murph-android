package ai.withmurph.companion.core

import java.time.Instant
import java.util.UUID

/** Sanitized bytes are never logged or saved as part of instance state. */
class ManualMealPhoto(
    val jpeg: ByteArray,
    val thumbnail: ByteArray,
    val capturedAt: Instant,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(jpeg.isNotEmpty() && jpeg.size <= 1024 * 1024)
        require(thumbnail.isNotEmpty() && thumbnail.size <= 256 * 1024)
        require(UUID.fromString(id).toString() == id)
    }
}
class SentMealPhoto(val id: String, val thumbnail: ByteArray, val capturedAt: Instant, val sentAt: Instant = Instant.now())
data class ManualMealsState(
    val selectionGeneration: String = UUID.randomUUID().toString(),
    val selected: List<ManualMealPhoto> = emptyList(),
    val sent: List<SentMealPhoto> = emptyList(),
    val sending: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val partialFailure: Boolean = false,
    val message: String? = null,
)
