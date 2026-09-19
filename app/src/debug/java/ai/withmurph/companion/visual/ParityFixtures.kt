package ai.withmurph.companion.visual

import ai.withmurph.companion.core.*
import java.time.Instant
import java.time.ZoneId

/** Synthetic display data only. No member, provider or network input. */
internal object ParityFixtures {
    private val photo by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(420, 420, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(178, 147, 107))
        paint.color = android.graphics.Color.rgb(249, 241, 223)
        canvas.drawCircle(210f, 210f, 172f, paint)
        paint.color = android.graphics.Color.rgb(100, 131, 68)
        listOf(125f to 140f, 175f to 130f, 120f to 190f, 175f to 190f).forEach { (x, y) -> canvas.drawCircle(x, y, 32f, paint) }
        paint.color = android.graphics.Color.rgb(218, 134, 99)
        canvas.drawRoundRect(210f, 150f, 315f, 290f, 18f, 18f, paint)
        paint.color = android.graphics.Color.rgb(219, 205, 167)
        canvas.drawOval(115f, 235f, 205f, 310f, paint)
        val jpeg = java.io.ByteArrayOutputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, output); output.toByteArray() }
        bitmap.recycle()
        ManualMealPhoto(jpeg, jpeg, Instant.now(), "10000000-0000-4000-8000-000000000001")
    }
    fun meals(scenario: ScreenshotScenario): ManualMealsState = when (scenario) {
        ScreenshotScenario.MealsReview -> ManualMealsState(selected = listOf(photo))
        ScreenshotScenario.MealsSending -> ManualMealsState(selected = listOf(photo), sending = true, current = 1, total = 1)
        ScreenshotScenario.MealsHealthReset -> ManualMealsState(selected = listOf(photo), partialFailure = true, message = "Your photos are ready to retry.")
        ScreenshotScenario.MealsPartialFailure -> ManualMealsState(selected = listOf(photo), partialFailure = true, message = "Some photos couldn't be sent. Try again.")
        ScreenshotScenario.MealsSent -> ManualMealsState(sent = listOf(SentMealPhoto(photo.id, photo.thumbnail, photo.capturedAt)), message = "Photo sent to Murph.")
        else -> ManualMealsState()
    }

    fun journal(now: Instant): JournalState.Ready {
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val days = (0L..6L).map { offset ->
            val date = today.minusDays(offset)
            JournalDay(date, listOf(
                JournalEvent("sleep-$offset", date, "Sleep", "sleep", "7h 42m of sleep", emptyList(),
                    now.toString(), "night", "UTC", mapOf("sleepMinutes" to 462.0, "activityMinutes" to 0.0,
                        "hrvMs" to 48.0, "sleepEfficiencyPercent" to 91.0),
                    listOf(JournalRecord("record-$offset", "Sleep duration", now.toString(), "Health Connect", "462 min", "UTC"))),
                JournalEvent("walk-$offset", date, "Morning walk", "activity", "35 minutes outdoors", emptyList(),
                    date.atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toString(), "timed", ZoneId.systemDefault().id,
                    mapOf("activityMinutes" to 35.0), emptyList()),
                JournalEvent("meal-$offset", date, "Lunch", "meal", "Roasted vegetables, rice, and salmon", emptyList(),
                    date.atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toString(), "timed", ZoneId.systemDefault().id,
                    mapOf("activityMinutes" to 0.0), emptyList()),
            ))
        }
        return JournalState.Ready(JournalResponse(Journal(days, 120), "fresh"))
    }
}
