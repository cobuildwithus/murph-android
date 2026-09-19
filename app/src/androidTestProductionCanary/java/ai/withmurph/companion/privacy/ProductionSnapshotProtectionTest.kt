package ai.withmurph.companion.privacy

import ai.withmurph.companion.BuildConfig
import ai.withmurph.companion.MainActivity
import ai.withmurph.companion.MurphApplication
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppSession
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production window and renderers; only the in-memory projection is synthetic. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProductionSnapshotProtectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun sensitiveWindowsStayProtectedAcrossNavigationBackgroundingAndRecreation() {
        // This test must never use a live backend or an authenticated account.
        assertEquals("https://example.invalid", BuildConfig.MURPH_BACKEND_BASE_URL)
        for (surface in listOf("journal", "details", "selected", "sent")) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val session = (instrumentation.targetContext.applicationContext as MurphApplication).graph.session
                compose.waitUntil(10_000) {
                    if (surface == "journal") session.state.value.phase == AppPhase.NeedsLogin
                    else session.state.value.phase != AppPhase.Launching
                }
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
                showSurface(scenario, session, surface)
                assertProtectedWindows(scenario, surface == "details")
                assertForegroundCaptureIsBlank()
                device.pressRecentApps()
                device.waitForIdle()
                SystemClock.sleep(800)
                File(instrumentation.targetContext.cacheDir, "synthetic-$surface-recents.png").writeBytes(rawScreenshot())
                device.pressBack()
                scenario.recreate()
                compose.waitUntil(10_000) { session.state.value.phase != AppPhase.Launching }
                showSurface(scenario, session, surface)
                assertProtectedWindows(scenario, surface == "details")
                assertForegroundCaptureIsBlank()
                if (surface == "details") {
                    device.pressBack()
                    compose.waitForIdle()
                    compose.onNode(hasText("Meals") and hasClickAction()).performClick()
                    compose.onNodeWithContentDescription("Add meal photos").assertIsDisplayed()
                }
            }
        }
    }

    private fun showSurface(scenario: ActivityScenario<MainActivity>, session: AppSession, surface: String) {
        val date = LocalDate.now()
        val now = Instant.now()
        val event = JournalEvent("synthetic-event", date, "Synthetic sleep", "sleep", "Synthetic summary",
            emptyList(), now.toString(), "night", "UTC", emptyMap(),
            listOf(JournalRecord("synthetic-record", "Synthetic record", now.toString(), "Health Connect", "Synthetic detail", "UTC")))
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val photo = ManualMealPhoto(bytes, bytes, now, "20000000-0000-4000-8000-000000000001")
        val meals = when (surface) {
            "selected" -> ManualMealsState(selected = listOf(photo), partialFailure = true)
            "sent" -> ManualMealsState(sent = listOf(SentMealPhoto(photo.id, bytes, now)))
            else -> ManualMealsState()
        }
        val state = AppUiState(phase = AppPhase.Ready, initialSetupStep = InitialSetupStep.Complete,
            journal = JournalState.Ready(JournalResponse(Journal(listOf(JournalDay(date, listOf(event))), 120), "fresh")),
            meals = meals)
        // Drive MainActivity's actual composition, including its window policy, without credentials or API results.
        scenario.onActivity {
            val field = AppSession::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val projection = field.get(session) as MutableStateFlow<AppUiState>
            projection.value = state
        }
        compose.waitForIdle()
        when (surface) {
            "journal" -> compose.onNodeWithText("Synthetic sleep").assertIsDisplayed()
            "details" -> {
                compose.onNodeWithText("Synthetic sleep").performClick()
                compose.onNodeWithText("RECORDS").performScrollTo().assertIsDisplayed()
            }
            "selected" -> {
                compose.onNode(hasText("Meals") and hasClickAction()).performClick()
                compose.onNodeWithContentDescription("Remove meal photo").assertIsDisplayed()
            }
            "sent" -> {
                compose.onNode(hasText("Meals") and hasClickAction()).performClick()
                compose.onNodeWithText("SENT TO MURPH").assertIsDisplayed()
            }
        }
    }

    private fun assertProtectedWindows(scenario: ActivityScenario<MainActivity>, hasSheet: Boolean) {
        scenario.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val windows = WindowInspector.getGlobalWindowViews().filter { it.isShown }
                .mapNotNull { it.layoutParams as? WindowManager.LayoutParams }
            assertTrue(windows.size >= if (hasSheet) 2 else 1)
            assertTrue(windows.all { it.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
        }
    }

    private fun rawScreenshot(): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("screencap -p"),
    ).use { it.readBytes() }

    private fun assertForegroundCaptureIsBlank() {
        val screenshot = rawScreenshot()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size))
        try {
            for (x in listOf(0.2, 0.5, 0.8)) for (y in listOf(0.2, 0.5, 0.8)) {
                val pixel = bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt())
                assertTrue(Color.red(pixel) < 4 && Color.green(pixel) < 4 && Color.blue(pixel) < 4)
            }
        } finally { bitmap.recycle() }
    }
}
