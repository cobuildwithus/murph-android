package ai.withmurph.companion.visual

import ai.withmurph.companion.healthSyncReminderSettingsDeliveryToConsume
import ai.withmurph.companion.reminders.HealthSyncReminderController
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotScenarioSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun launchingMessagingAdmissionKeepsProgressAndSignOutReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (fontScale in listOf(1.0f, 2.0f)) for (state in listOf("connected", "phone")) {
            val intent = Intent(context, ScreenshotActivity::class.java)
                .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "messagingSetupConfirming")
                .putExtra("messagingState", state)
                .putExtra("messagingFontScale", fontScale)
            ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
                compose.onNodeWithText(if (state == "phone") "Choose how to message Murph" else "Account connected").assertIsDisplayed()
                // Progress renders inline in the content flow; a re-check from the
                // phone step replaces its controls instead of covering them.
                compose.onNodeWithText("Confirming your account…").performScrollTo().assertIsDisplayed()
                if (state == "phone") compose.onAllNodesWithText("Send code").assertCountEquals(0)
                // Native linking retains its existing sign-out escape during admission.
                compose.onNodeWithText("Sign out").performScrollTo().performClick()
                scenario.onActivity { assertEquals(1, it.signOutRequests) }
            }
        }
    }

    @Test
    fun recheckFromCodeStepNeverFocusesHiddenCodeEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (scenarioName in listOf("messagingSetupConfirming", "messagingSetup")) {
            val intent = Intent(context, ScreenshotActivity::class.java)
                .putExtra(ScreenshotActivity.SCENARIO_EXTRA, scenarioName)
                .putExtra("messagingState", "code")
                .putExtra("messagingAutofocus", true)
            // A recreated Activity during a code-step re-check must not request
            // focus for the OTP field that progress has replaced.
            ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
                compose.waitForIdle()
                if (scenarioName == "messagingSetupConfirming") {
                    compose.onNodeWithText("Confirming your account…").assertIsDisplayed()
                    compose.onAllNodesWithContentDescription("6-digit verification code").assertCountEquals(0)
                    scenario.recreate()
                    compose.waitForIdle()
                    compose.onNodeWithText("Confirming your account…").assertIsDisplayed()
                } else {
                    compose.onNodeWithContentDescription("6-digit verification code").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun messagingControlsRemainReachableAtMaximumFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (state in listOf("phone", "contact-in-use", "telegram-conflict", "code", "waiting")) {
            val intent = Intent(context, ScreenshotActivity::class.java)
                .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "messagingSetup")
                .putExtra("messagingState", state)
                .putExtra("messagingFontScale", 2.0f)
            ActivityScenario.launch<ScreenshotActivity>(intent).use {
                compose.waitForIdle()
                compose.onNodeWithText("Sign out").assertFullyContained().assertHasClickAction()
                val buttons = when (state) {
                    "code" -> {
                        compose.onNodeWithText("Resend").performScrollTo().assertFullyContained().assertHasClickAction()
                        compose.onNodeWithContentDescription("6-digit verification code").performScrollTo().assertFullyContained()
                        listOf("Use a different number")
                    }
                    "waiting" -> emptyList()
                    else -> {
                        compose.onNodeWithContentDescription("Country or region", substring = true)
                            .performScrollTo().assertFullyContained().assertHasClickAction()
                        compose.onNodeWithContentDescription("Phone number").performScrollTo().assertFullyContained()
                        buildList {
                            if (state == "contact-in-use") add("Manage in account settings")
                            add("Send code")
                            add("Connect Telegram")
                            if (state == "telegram-conflict") add("Manage in account settings")
                        }
                    }
                }
                for (label in buttons) {
                    compose.onNodeWithText(label).performScrollTo().assertFullyContained().assertHasClickAction()
                }
                if (state == "waiting") {
                    compose.onNodeWithContentDescription("Cancel Telegram login").assertFullyContained().assertHasClickAction()
                }
                // Returning to the top must also leave the exit action reachable.
                compose.onNodeWithText("Sign out").performScrollTo().assertFullyContained().assertHasClickAction()
            }
        }
    }

    private fun SemanticsNodeInteraction.assertFullyContained(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // boundsInRoot clips to ancestors: comparing to the measured size catches
        // a partly visible button that assertIsDisplayed still accepts.
        assertEquals("Control is vertically clipped", node.size.height.toFloat(), bounds.height, 1f)
        assertEquals("Control is horizontally clipped", node.size.width.toFloat(), bounds.width, 1f)
        assertTrue(
            "Control extends outside viewport",
            bounds.left >= root.left && bounds.top >= root.top &&
                bounds.right <= root.right && bounds.bottom <= root.bottom,
        )
        return this
    }

    @Test
    fun optionalReminderRequiresExplicitChoiceAndCanBeSkipped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "notifications")
        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.onNodeWithText("One sync reminder").assertIsDisplayed()
            scenario.onActivity { assertTrue(it.healthSyncReminderPreferenceRequests.isEmpty()) }
            compose.onNodeWithText("Keep notifications off").performScrollTo().performClick()
            scenario.onActivity {
                assertTrue(it.reminderSetupDismissed)
                assertTrue(it.healthSyncReminderPreferenceRequests.isEmpty())
            }
            compose.onAllNodesWithText("One sync reminder").assertCountEquals(0)
        }
    }

    @Test
    fun optionalReminderAllowInvokesExistingPreferenceAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "notifications")
        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.onNodeWithText("Allow reminder").performScrollTo().performClick()
            scenario.onActivity { assertEquals(listOf(true), it.healthSyncReminderPreferenceRequests) }
            compose.onNodeWithText("Setting up…").assertIsDisplayed()
        }
    }

    @Test
    fun offlineJournalAndMealsShowRecoveryAndDisablePhotoAcquisition() = withScenario("mealsOffline") {
        onNodeWithText("Journal couldn't load").assertIsDisplayed()
        onNodeWithText("Try again").assertHasClickAction()
        onNodeWithText("Meals").performClick()
        onNodeWithText("Reconnect to add or send meal photos.").assertIsDisplayed()
        onNodeWithContentDescription("Add meal photos").assertIsNotEnabled()
        onNodeWithText("Try again").assertHasClickAction()
    }

    @Test
    fun unavailableJournalOffersRetryAndKeepsNavigation() = withScenario("journalUnavailable") {
        onNodeWithText("Your journal isn't ready yet").assertIsDisplayed()
        onNodeWithText("Try again").assertHasClickAction()
        onNodeWithText("Meals").performClick()
        onNodeWithText("No meal photos yet").assertIsDisplayed()
    }

    @Test
    fun journalEmptyOpensMealsAndKeepsThreeTabNavigation() = withScenario("journalEmpty") {
        onNodeWithText("Your journal\nstarts here.").assertIsDisplayed()
        onNodeWithText("Open Meals").performScrollTo().performClick()
        onNodeWithText("No meal photos yet").assertIsDisplayed()
        onNodeWithContentDescription("Add meal photos").performClick()
        onNodeWithText("Camera").assertIsDisplayed()
        onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun journalEntryOpensItsCanonicalRecordDetails() = withScenario("journalFilled") {
        onAllNodesWithText("Sleep")[0].performClick()
        onNodeWithText("RECORDS").performScrollTo().assertIsDisplayed()
        onNodeWithText("Health Connect").assertIsDisplayed()
    }

    @Test
    fun welcomeOpensTheSameSecureLoginForm() = withScenario("welcome") {
        onNodeWithText("Join for free").performScrollTo().performClick()
        onNodeWithText("Use your phone number or email to continue.").assertIsDisplayed()
        onNodeWithContentDescription("Back to welcome").performClick()
        onNodeWithText("Join for free").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun loginFixtureRendersProductionLoginSurface() = withScenario("login") {
        onNodeWithText("Use your phone number or email to continue.").assertIsDisplayed()
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
        onNodeWithText("Couldn't save. Try again.").assertIsDisplayed()
        onNodeWithText("Sign out").assertIsDisplayed().assertHasClickAction()
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
            onNodeWithText("Sign out").assertIsDisplayed().assertHasClickAction()
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
    fun onboardingConsentLoadFailureOffersRetryOrSignOutWithoutSettings() =
        withScenario("onboardingConsentLoadFailure") {
            onNodeWithText("Try again").assertIsDisplayed().assertHasClickAction()
            onNodeWithText("Sign out").assertIsDisplayed().assertHasClickAction()
            onAllNodesWithText("Not now").assertCountEquals(0)
            onAllNodesWithText("Settings").assertCountEquals(0)
        }

    @Test
    fun onboardingRequiredConsentOffersAndInvokesSignOutWithoutSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "onboardingConsentRequired")

        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithText(
                "Murph couldn't save consent. Check your connection and try again.",
            ).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Sign out")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText("Not now").assertCountEquals(0)
            compose.onAllNodesWithText("Settings").assertCountEquals(0)
            scenario.onActivity { activity ->
                assertEquals(1, activity.signOutRequests)
            }
        }
    }

    @Test
    fun onboardingReconnectDoesNotExposeSettings() =
        withScenario("onboardingReconnectRequired") {
            onNodeWithText("Reconnect Health Connect").assertIsDisplayed().assertHasClickAction()
            onAllNodesWithText("Settings").assertCountEquals(0)
        }

    @Test
    fun syncedFixtureRendersBackendConfirmedStatusSurface() = withScenario("synced") {
        onNodeWithText("Settings").performClick()
        onNodeWithText("Synced ·", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Check for new data").assertIsDisplayed()
    }

    @Test
    fun consentFixtureRendersRecoverySurface() = withScenario("consentRequired") {
        onNodeWithText("Use your health data").assertIsDisplayed()
        onNodeWithText("Terms").assertIsDisplayed()
    }

    @Test
    fun reminderSettingsDeliveryIsConsumedExactlyOnce() {
        val intent = reminderSettingsIntent("delivery-a")

        assertEquals(
            "delivery-a",
            healthSyncReminderSettingsDeliveryToConsume(intent, lastHandledDeliveryId = null),
        )
        assertNull(
            healthSyncReminderSettingsDeliveryToConsume(
                intent,
                lastHandledDeliveryId = "delivery-a",
            ),
        )
    }

    @Test
    fun restoredTaskConsumesANewDeliveryAndDoesNotReplayTheSavedDelivery() {
        assertEquals(
            "delivery-b",
            healthSyncReminderSettingsDeliveryToConsume(
                intent = reminderSettingsIntent("delivery-b"),
                lastHandledDeliveryId = "delivery-a",
                isRestoringLegacyIntent = true,
            ),
        )
        assertNull(
            healthSyncReminderSettingsDeliveryToConsume(
                intent = reminderSettingsIntent("delivery-a"),
                lastHandledDeliveryId = "delivery-a",
                isRestoringLegacyIntent = true,
            ),
        )
        assertEquals(
            "delivery-c",
            healthSyncReminderSettingsDeliveryToConsume(
                intent = reminderSettingsIntent("delivery-c"),
                lastHandledDeliveryId = "delivery-b",
            ),
        )
    }

    @Test
    fun restoredLegacyReminderIntentRemainsSuppressed() {
        val legacyIntent = Intent()
            .setAction(HealthSyncReminderController.ACTION_OPEN_SETTINGS)

        assertNull(
            healthSyncReminderSettingsDeliveryToConsume(
                intent = legacyIntent,
                lastHandledDeliveryId = null,
                isRestoringLegacyIntent = true,
            ),
        )
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
            .putExtra(ScreenshotActivity.START_LAUNCHING_EXTRA, true)

        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(1, activity.openSettingsRequestId)
                assertEquals(0, activity.consumedOpenSettingsRequestCount)
            }

            scenario.recreate()
            compose.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(1, activity.openSettingsRequestId)
                assertEquals(0, activity.consumedOpenSettingsRequestCount)
                activity.publishReadyApp()
            }
            compose.waitForIdle()
            compose.onNodeWithText("Sync reminder").assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(0, activity.openSettingsRequestId)
                assertEquals(1, activity.consumedOpenSettingsRequestCount)
            }

            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Today").assertIsDisplayed()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Today").assertIsDisplayed()

            scenario.onActivity { it.requestOpenSettings() }
            compose.waitForIdle()
            compose.onNodeWithText("Sync reminder").assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(0, activity.openSettingsRequestId)
                assertEquals(2, activity.consumedOpenSettingsRequestCount)
            }
        }
    }

    @Test
    fun reminderTogglePassesTheRequestedTargetValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ScreenshotActivity::class.java)
            .putExtra(ScreenshotActivity.SCENARIO_EXTRA, "reminderOff")
        ActivityScenario.launch<ScreenshotActivity>(intent).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithText("TURN ON").performClick()
            compose.onNodeWithText("Turning on… You can turn this off while Murph checks current sync status.")
                .assertIsDisplayed()
            compose.onNodeWithText("TURN OFF").performClick()
            scenario.onActivity { activity ->
                assertEquals(
                    listOf(true, false),
                    activity.healthSyncReminderPreferenceRequests,
                )
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

    private fun reminderSettingsIntent(deliveryId: String): Intent = Intent()
        .setAction(HealthSyncReminderController.ACTION_OPEN_SETTINGS)
        .putExtra(HealthSyncReminderController.EXTRA_SETTINGS_DELIVERY_ID, deliveryId)
}
