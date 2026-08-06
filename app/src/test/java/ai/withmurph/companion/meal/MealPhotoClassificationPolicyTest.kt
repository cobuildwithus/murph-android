package ai.withmurph.companion.meal

import org.junit.Assert.assertEquals
import org.junit.Test

class MealPhotoClassificationPolicyTest {
    @Test
    fun acceptsOnlyWholeMealLabelsAboveTheSendThreshold() {
        assertEquals(
            MealPhotoClassificationDecision.Accepted,
            MealPhotoClassificationPolicy.decision(
                listOf(MealPhotoClassificationObservation("food", 0.50f)),
            ),
        )
        assertEquals(
            MealPhotoClassificationDecision.NeedsReview,
            MealPhotoClassificationPolicy.decision(
                listOf(MealPhotoClassificationObservation("fresh food", 0.99f)),
            ),
        )
        assertEquals(
            MealPhotoClassificationDecision.Rejected,
            MealPhotoClassificationPolicy.decision(
                listOf(MealPhotoClassificationObservation("seafoodie", 0.99f)),
            ),
        )
    }

    @Test
    fun uncertainMealLabelsRemainOnDeviceForReview() {
        assertEquals(
            MealPhotoClassificationDecision.NeedsReview,
            MealPhotoClassificationPolicy.decision(
                listOf(MealPhotoClassificationObservation("dish", 0.20f)),
            ),
        )
        assertEquals(
            MealPhotoClassificationDecision.Rejected,
            MealPhotoClassificationPolicy.decision(
                listOf(MealPhotoClassificationObservation("dish", 0.19f)),
            ),
        )
        listOf("person eating", "fruit", "vegetable garden").forEach { broadLabel ->
            assertEquals(
                MealPhotoClassificationDecision.Rejected,
                MealPhotoClassificationPolicy.decision(
                    listOf(MealPhotoClassificationObservation(broadLabel, 0.99f)),
                ),
            )
        }
    }
}
