package ai.withmurph.companion.visual

import ai.withmurph.companion.consumeHealthSyncReminderSettingsIntent
import ai.withmurph.companion.reminders.HealthSyncReminderController
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        onAllNodesWithText("Contact support").assertCountEquals(0)
        onAllNodesWithText("Delete account").assertCountEquals(0)
        onAllNodesWithText("Settings").assertCountEquals(0)
    }

    @Test
    fun onboardingErrorFixtureKeepsRecoveryOutOfTheForm() = withScenario("onboardingError") {
        onNodeWithText("Couldn't save. Your choices are still here.").assertIsDisplayed()
        onNodeWithText("Help").assertIsDisplayed().assertHasClickAction()
        onNodeWithText("Formal").assertIsDisplayed().assertHasClickAction()
        onNodeWithText("Continue").assertIsDisplayed().assertHasClickAction()
        onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
        onAllNodesWithText("Contact support").assertCountEquals(0)
        onAllNodesWithText("Delete account").assertCountEquals(0)
        onAllNodesWithText("Sign out and stop syncing").assertCountEquals(0)
        onAllNodesWithText("Settings").assertCountEquals(0)
    }

    @Test
    fun contactCardErrorKeepsOnlyContactStageRecovery() =
        withScenario("onboardingContactError") {
            onNodeWithText("We couldn't open the contact card. Check your connection and try again.")
                .assertIsDisplayed()
            onNodeWithText("Help").assertIsDisplayed().assertHasClickAction()
            onNodeWithText("Add Murph to Contacts").assertIsDisplayed().assertHasClickAction()
            onNodeWithText("Skip").assertIsDisplayed().assertHasClickAction()
            onAllNodesWithText("Contact support").assertCountEquals(0)
            onAllNodesWithText("Delete account").assertCountEquals(0)
            onAllNodesWithText("Sign out and stop syncing").assertCountEquals(0)
            onAllNodesWithText("Settings").assertCountEquals(0)
        }

    @Test
    fun onboardingConsentRecoveryDoesNotExposeSettings() =
        withScenario("onboardingConsentBanner") {
            onNodeWithText("Consent needed").assertIsDisplayed().assertHasClickAction()
            onAllNodesWithText("Settings").assertCountEquals(0)
        }

    @Test
    fun onboardingReconnectDoesNotExposeSettings() =
        withScenario("onboardingReconnectRequired") {
            onNodeWithText("Reconnect Health Connect").assertIsDisplayed().assertHasClickAction()
            onAllNodesWithText("Settings").assertCountEquals(0)
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

    @Test
    fun reminderSettingsIntentActionIsConsumedExactlyOnce() {
        val intent = Intent().setAction(HealthSyncReminderController.ACTION_OPEN_SETTINGS)

        assertTrue(consumeHealthSyncReminderSettingsIntent(intent))
        assertNull(intent.action)
        assertFalse(consumeHealthSyncReminderSettingsIntent(intent))
    }

    @Test
    fun staleOfflineStatusKeepsReminderOptInUnavailable() = withScenario("savedStatus") {
        onNodeWithText("Available after Murph checks sync status online.").assertIsDisplayed()
    }

    @Test
    fun compactTerminalFailureKeepsEveryHealthDisclosureReachable() =
        withScenario("accountFailure") {
            onNodeWithText("Health Data Notice").performScrollTo().assertIsDisplayed()
            onNodeWithText("AI Safety Disclosure").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun reminderSettingsNavigationIsAcknowledgedAndDoesNotReplayAfterRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "reminderOff")

        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithText("Sync reminder").assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(0, activity.openSettingsRequestId)
                assertEquals(1, activity.consumedOpenSettingsRequestCount)
            }

            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Check for new data").assertIsDisplayed()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Check for new data").assertIsDisplayed()

            scenario.onActivity { it.requestOpenSettings() }
            compose.waitForIdle()
            compose.onNodeWithText("Sync reminder").assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(0, activity.openSettingsRequestId)
            }
        }
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
