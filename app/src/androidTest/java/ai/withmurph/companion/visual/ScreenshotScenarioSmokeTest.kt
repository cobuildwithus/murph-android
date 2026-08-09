package ai.withmurph.companion.visual

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotScenarioSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun loginFixtureRendersProductionLoginSurface() = withScenario("login") {
        onNodeWithText("Health challenges with friends.").assertIsDisplayed()
        onNodeWithText("Send code").assertIsDisplayed()
    }

    @Test
    fun onboardingFixtureRendersProductionChoiceSurface() = withScenario("onboardingPersona") {
        onNodeWithText("Choose Murph’s main personality").assertIsDisplayed()
        onNodeWithText("Classic").assertIsDisplayed()
    }

    @Test
    fun syncedFixtureRendersBackendConfirmedStatusSurface() = withScenario("synced") {
        onNodeWithText("Synced").assertIsDisplayed()
        onNodeWithText("Check for new data").assertIsDisplayed()
    }

    @Test
    fun consentFixtureRendersRecoverySurface() = withScenario("consentRequired") {
        onNodeWithText("Use your health data").assertIsDisplayed()
        onNodeWithText("Terms").assertIsDisplayed()
    }

    private fun withScenario(
        scenario: String,
        assertions: ComposeTestRule.() -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, scenario)

        ActivityScenario.launch<ScreenshotActivity>(intent).use {
            compose.waitForIdle()
            assertions(compose)
        }
    }
}
