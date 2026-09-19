package ai.withmurph.companion.meals

import ai.withmurph.companion.core.ManualMealsState
import ai.withmurph.companion.ui.meals.MealsScreen
import ai.withmurph.companion.ui.theme.MurphTheme
import ai.withmurph.companion.visual.ScreenshotActivity
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MealPreparationRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun consumedPickerResultSurvivesRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retainedState = mutableStateOf(ManualMealsState())
        val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        val source = File.createTempFile("synthetic-picked-meal-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        var preparations = 0
        var sends = 0
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches += 1
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        fun attach(activity: ScreenshotActivity) {
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MurphTheme {
                        MealsScreen(
                            state = retainedState.value,
                            onPreparePhotos = { generation, uris, file ->
                                preparations += 1
                                assertNull(file)
                                retainedState.value = retainedState.value.copy(preparing = true)
                                retainedScope.launch {
                                    try {
                                        gate.await()
                                        val photo = MealPhotoSanitizer.prepare(context.contentResolver, uris.single())
                                        assertEquals(generation, retainedState.value.selectionGeneration)
                                        retainedState.value = retainedState.value.copy(selected = listOf(photo), preparing = false)
                                    } finally { source.delete() }
                                }
                            },
                            onRemove = {}, onSend = { sends += 1 },
                        )
                    }
                }
            }
        }
        try {
            ActivityScenario.launch<ScreenshotActivity>(Intent(context, ScreenshotActivity::class.java).putExtra(ScreenshotActivity.SCENARIO_EXTRA, "mealsEmpty")).use { scenario ->
                scenario.onActivity(::attach)
                compose.onNodeWithContentDescription("Add meal photos").performClick()
                compose.onNodeWithText("Photos").performClick()
                scenario.onActivity { assertEquals("Picker result dispatched", 1, launches); assertEquals("Preparation started", 1, preparations) }
                compose.onNodeWithText("Preparing photos…").assertIsDisplayed()
                scenario.onActivity { assertTrue(source.isFile) }
                scenario.recreate()
                scenario.onActivity(::attach)
                compose.onNodeWithText("Preparing photos…").assertIsDisplayed()
                scenario.onActivity { assertTrue(source.isFile); gate.complete(Unit) }
                compose.waitUntil(5_000) { retainedState.value.selected.size == 1 }
                compose.onNodeWithText("1 photo ready to send").assertIsDisplayed()
                scenario.onActivity {
                    assertEquals(1, preparations)
                    assertEquals(0, sends)
                    assertFalse(source.exists())
                }
            }
        } finally { retainedScope.cancel(); source.delete() }
    }
}
