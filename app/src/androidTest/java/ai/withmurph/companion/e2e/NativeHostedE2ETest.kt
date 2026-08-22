package ai.withmurph.companion.e2e

import ai.withmurph.companion.BuildConfig
import ai.withmurph.companion.MainActivity
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHostedE2ETest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Test
    fun protectedHostedJourney() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "The live driver is inactive outside the protected hosted workflow.",
            nativeHostedE2EEnabled(arguments),
        )

        val dispatch = runCatching {
            NativeHostedE2EDispatchConfiguration.require(arguments)
        }.getOrElse {
            fail(NativeHostedE2EFailureCode.InvalidDispatchContract.rawValue)
            return
        }
        val reporter = NativeHostedE2EStageReporter(
            mode = dispatch.mode,
            output = null,
            statusPublisher = ::publishStageSummary,
        )

        perform(
            stage = NativeHostedE2EStage.ContractValidation,
            fallback = NativeHostedE2EFailureCode.InvalidDispatchContract,
            reporter = reporter,
        ) {
            require(BuildConfig.MURPH_BACKEND_BASE_URL == dispatch.webBaseUrl)
            require(BuildConfig.MURPH_ENVIRONMENT == dispatch.mode.expectedEnvironment)
            require(BuildConfig.APPLICATION_ID == dispatch.mode.expectedApplicationId)
            require(targetContext.packageName == dispatch.mode.expectedApplicationId)
            require(BuildConfig.PRIVY_APP_ID.isNotBlank())
            require(BuildConfig.PRIVY_APP_CLIENT_ID.isNotBlank())
        }

        val identity = runCatching {
            NativeHostedE2EProtectedIdentity.require(arguments)
        }.getOrElse {
            reporter.infrastructureFailed(
                NativeHostedE2EFailureCode.MissingProtectedConfiguration,
            )
            fail(NativeHostedE2EFailureCode.MissingProtectedConfiguration.rawValue)
            return
        }

        try {
            perform(
                NativeHostedE2EStage.LaunchLiveApp,
                NativeHostedE2EFailureCode.LaunchLiveAppFailed,
                reporter,
            ) {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                waitForText("Send code", 60_000)
            }

            perform(
                NativeHostedE2EStage.InitialPrivyOtp,
                NativeHostedE2EFailureCode.InitialPrivyOtpFailed,
                reporter,
            ) {
                signIn(
                    identity = identity,
                    requestRejected =
                        NativeHostedE2EFailureCode.InitialPrivyOtpRequestRejected,
                    codeRejected = NativeHostedE2EFailureCode.InitialPrivyOtpCodeRejected,
                )
            }

            perform(
                NativeHostedE2EStage.CanonicalAdmission,
                NativeHostedE2EFailureCode.CanonicalAdmissionFailed,
                reporter,
            ) {
                requireCanonicalPostAuthenticationSurface()
            }

            when (dispatch.mode) {
                NativeHostedE2EMode.Pr -> {
                    perform(
                        NativeHostedE2EStage.LaunchConsentRecovery,
                        NativeHostedE2EFailureCode.LaunchConsentRecoveryFailed,
                        reporter,
                    ) {
                        completeLaunchConsentRecovery(requireAcceptance = true)
                    }
                    perform(
                        NativeHostedE2EStage.ServerOwnedOnboarding,
                        NativeHostedE2EFailureCode.ServerOwnedOnboardingFailed,
                        reporter,
                    ) {
                        completeServerOwnedOnboarding()
                    }
                }
                NativeHostedE2EMode.ProductionCanary -> {
                    perform(
                        NativeHostedE2EStage.ExistingMemberState,
                        NativeHostedE2EFailureCode.ExistingMemberStateFailed,
                        reporter,
                    ) {
                        requireExistingMemberState()
                    }
                }
            }

            perform(
                NativeHostedE2EStage.HealthConnectHandoff,
                NativeHostedE2EFailureCode.HealthConnectHandoffFailed,
                reporter,
            ) {
                beginHealthConnectHandoff()
            }

            perform(
                NativeHostedE2EStage.HealthConnectPermissionState,
                NativeHostedE2EFailureCode.HealthConnectPermissionStateFailed,
                reporter,
            ) {
                completeHealthConnectPermissionState()
            }

            perform(
                NativeHostedE2EStage.ConnectedState,
                NativeHostedE2EFailureCode.ConnectedStateFailed,
                reporter,
            ) {
                finishOptionalSetupAndRequireConnectedState()
            }

            perform(
                NativeHostedE2EStage.SignOut,
                NativeHostedE2EFailureCode.SignOutFailed,
                reporter,
            ) {
                signOut()
            }

            perform(
                NativeHostedE2EStage.ReturningPrivyOtp,
                NativeHostedE2EFailureCode.ReturningPrivyOtpFailed,
                reporter,
            ) {
                signIn(
                    identity = identity,
                    requestRejected =
                        NativeHostedE2EFailureCode.ReturningPrivyOtpRequestRejected,
                    codeRejected = NativeHostedE2EFailureCode.ReturningPrivyOtpCodeRejected,
                )
            }

            perform(
                NativeHostedE2EStage.ReturningMemberState,
                NativeHostedE2EFailureCode.ReturningMemberStateFailed,
                reporter,
            ) {
                requireReturningMemberState()
            }

            reporter.finishPassed()
        } finally {
            runCatching { scenario?.close() }
            scenario = null
        }
    }

    private fun perform(
        stage: NativeHostedE2EStage,
        fallback: NativeHostedE2EFailureCode,
        reporter: NativeHostedE2EStageReporter,
        operation: () -> Unit,
    ) {
        try {
            operation()
            reporter.passed(stage)
        } catch (reported: JourneyFailure) {
            val code = reported.code.takeIf { it.stage == stage } ?: fallback
            reporter.failed(stage, code)
            fail(code.rawValue)
        } catch (_: Throwable) {
            reporter.failed(stage, fallback)
            fail(fallback.rawValue)
        }
    }

    private fun publishStageSummary(summary: String) {
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("stream", "$summary\n") },
        )
    }

    private fun signIn(
        identity: NativeHostedE2EProtectedIdentity,
        requestRejected: NativeHostedE2EFailureCode,
        codeRejected: NativeHostedE2EFailureCode,
    ) {
        waitForText("Send code", 45_000)
        if (hasClickableText("Use phone number instead")) {
            clickText("Use phone number instead", 20_000)
        }
        selectPhoneCountry(identity)
        replaceOnlyEditable(identity.nationalNumber, 20_000)

        var requestDecision = NativeHostedE2EOtpRequestDecision.Wait
        waitUntil(30_000) {
            requestDecision = nativeHostedE2EOtpRequestDecision(
                verificationCodeVisible = hasVerificationCodeField(),
                enabledSendCodeCount = nodeCount(clickableText("Send code")),
            )
            requestDecision != NativeHostedE2EOtpRequestDecision.Wait
        }
        if (requestDecision == NativeHostedE2EOtpRequestDecision.TapSendCode) {
            clickText("Send code", 20_000)
        }

        waitUntil(90_000) {
            hasVerificationCodeField() || hasVisibleText(OTP_REQUEST_ERROR)
        }
        if (!hasVerificationCodeField()) {
            throw JourneyFailure(requestRejected)
        }
        replaceOnlyEditable(identity.fixedOtp, 20_000)

        var submissionDecision = NativeHostedE2ECodeSubmissionDecision.Wait
        waitUntil(30_000) {
            submissionDecision = nativeHostedE2ECodeSubmissionDecision(
                postAuthenticationSurfaceVisible = isPostAuthenticationSurface(),
                enabledSignInCount = nodeCount(clickableText("Sign in")),
            )
            submissionDecision != NativeHostedE2ECodeSubmissionDecision.Wait
        }
        if (submissionDecision == NativeHostedE2ECodeSubmissionDecision.TapSignIn) {
            clickText("Sign in", 20_000)
        }
        waitUntil(180_000) {
            isPostAuthenticationSurface() || hasVisibleText(OTP_CODE_ERROR)
        }
        if (!isPostAuthenticationSurface()) {
            throw JourneyFailure(codeRejected)
        }
    }

    private fun selectPhoneCountry(identity: NativeHostedE2EProtectedIdentity) {
        val targetDescription =
            "Country or region, ${identity.country.localizedName}, ${identity.country.dialCode}"
        if (nodeCount(hasContentDescription(targetDescription)) == 1) return

        val picker = hasContentDescription(
            "Country or region",
            substring = true,
        ) and hasClickAction() and isEnabled()
        waitUntil(20_000) { nodeCount(picker) == 1 }
        compose.onAllNodes(picker)[0].performClick()
        compose.waitForIdle()

        val search = hasSetTextAction() and hasTextMatcher("Search countries")
        waitUntil(20_000) { nodeCount(search) == 1 }
        compose.onAllNodes(search)[0].performTextReplacement(identity.country.localizedName)
        compose.waitForIdle()
        clickText(identity.country.localizedName, 20_000, scroll = true)
        waitUntil(20_000) { nodeCount(hasContentDescription(targetDescription)) == 1 }
    }

    private fun hasVerificationCodeField(): Boolean =
        nodeCount(hasContentDescription("6-digit verification code")) == 1

    private fun isPostAuthenticationSurface(): Boolean =
        isReadyShell() ||
            isOnboardingSurface() ||
            hasAnyText(
                "Consent needed",
                "Use your health data",
                "Review Murph’s terms",
                "Bring your health into Murph",
                "Reconnect Health Connect",
            )

    private fun requireCanonicalPostAuthenticationSurface() {
        waitUntil(180_000, ::isPostAuthenticationSurface)
    }

    private fun completeLaunchConsentRecovery(requireAcceptance: Boolean) {
        val deadline = System.currentTimeMillis() + 180_000
        var accepted = false
        var retried = false

        while (System.currentTimeMillis() < deadline) {
            if (isLaunchConsentSheet()) {
                when {
                    hasClickableText("I Consent") -> {
                        clickText("I Consent", 30_000)
                        accepted = true
                        continue
                    }
                    hasClickableText("Continue") -> {
                        clickText("Continue", 30_000)
                        accepted = true
                        continue
                    }
                    hasClickableText("Try again") && !retried -> {
                        retried = true
                        clickText("Try again", 20_000)
                        continue
                    }
                    else -> {
                        sleepBriefly()
                        continue
                    }
                }
            }

            if (hasVisibleText("Consent needed")) {
                clickText("Consent needed", 20_000)
                continue
            }

            if (isOnboardingSurface() || isReadyShell()) {
                if (requireAcceptance && !accepted) {
                    throw JourneyFailure(
                        NativeHostedE2EFailureCode.LaunchConsentRecoveryFailed,
                    )
                }
                return
            }
            sleepBriefly()
        }
        throw JourneyFailure(NativeHostedE2EFailureCode.LaunchConsentRecoveryFailed)
    }

    private fun completeServerOwnedOnboarding() {
        val deadline = System.currentTimeMillis() + 300_000
        val progress = NativeHostedE2EOnboardingProgress()

        while (System.currentTimeMillis() < deadline) {
            when {
                hasVisibleText("Add Murph to your contacts") -> {
                    progress.observe("Add Murph to your contacts")
                    advanceOnboarding("Add Murph to your contacts", "Skip")
                }
                hasVisibleText("Choose Murph’s main personality") -> {
                    progress.observe("Choose Murph’s main personality")
                    advanceOnboarding("Choose Murph’s main personality", "Continue")
                }
                hasVisibleText("Add a supporting personality") -> {
                    progress.observe("Add a supporting personality")
                    advanceOnboarding("Add a supporting personality", "Continue")
                }
                hasVisibleText("Choose a voice") -> {
                    progress.observe("Choose a voice")
                    advanceOnboarding("Choose a voice", "Continue")
                }
                hasVisibleText("Pick Murph’s tone") -> {
                    progress.observe("Pick Murph’s tone")
                    advanceOnboarding("Pick Murph’s tone", "Continue", 60_000)
                }
                hasVisibleText("Welcome to Murph") -> {
                    progress.observe("Welcome to Murph")
                    advanceOnboarding("Welcome to Murph", "Start exploring", 30_000)
                }
                isReadyShell() -> {
                    require(progress.isComplete)
                    return
                }
                else -> sleepBriefly()
            }
        }
        throw JourneyFailure(NativeHostedE2EFailureCode.ServerOwnedOnboardingFailed)
    }

    private fun advanceOnboarding(
        title: String,
        action: String,
        timeout: Long = 20_000,
    ) {
        clickText(action, timeout)
        waitUntil(timeout) { !hasVisibleText(title) }
    }

    private fun requireExistingMemberState() {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (hasVisibleText("Consent needed") || isLaunchConsentSheet()) {
                completeLaunchConsentRecovery(requireAcceptance = false)
                continue
            }
            require(!isOnboardingSurface())
            if (
                isReadyShell() ||
                hasAnyText(
                    "Reconnect Health Connect",
                    "Finish setting up Health Connect",
                )
            ) {
                return
            }
            sleepBriefly()
        }
        throw JourneyFailure(NativeHostedE2EFailureCode.ExistingMemberStateFailed)
    }

    private fun beginHealthConnectHandoff() {
        if (
            tryBeginHealthConnectHandoff() !=
            NativeHostedE2EHealthPermissionHandoffResult.SystemSurfaceOpened
        ) {
            throw JourneyFailure(NativeHostedE2EFailureCode.HealthConnectSurfaceMissing)
        }
    }

    private fun tryBeginHealthConnectHandoff(): NativeHostedE2EHealthPermissionHandoffResult {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            when {
                waitForExternalHealthSurface(1_000) ->
                    return NativeHostedE2EHealthPermissionHandoffResult.SystemSurfaceOpened
                hasClickableText("Reconnect Health Connect") -> {
                    clickText("Reconnect Health Connect", 20_000)
                    acceptHealthDetailsIfPresented()
                }
                hasClickableText("Connect Health Connect") -> {
                    clickText("Connect Health Connect", 20_000)
                    acceptHealthDetails()
                }
                hasClickableText("Finish setting up Health Connect") -> {
                    clickText("Finish setting up Health Connect", 20_000)
                }
                isConnectedHealthState() ->
                    return NativeHostedE2EHealthPermissionHandoffResult.ConnectedWithoutPrompt
                else -> sleepBriefly()
            }
        }
        return NativeHostedE2EHealthPermissionHandoffResult.TimedOut
    }

    private fun acceptHealthDetails() {
        waitForText("How Health Connect works with Murph", 45_000)
        clickText("Continue to Health Connect", 30_000)
    }

    private fun acceptHealthDetailsIfPresented() {
        if (waitForTextOrTimeout("How Health Connect works with Murph", 10_000)) {
            clickText("Continue to Health Connect", 30_000)
        }
    }

    private fun completeHealthConnectPermissionState() {
        val deadline = System.currentTimeMillis() + 240_000
        var authorizationSelected = false
        var didActivateAllowAll = false
        var sawPermissionSurface = false
        var returnedToApp = false
        var handoffAttempts = 1
        var systemIdleIterations = 0
        fun currentFailure() = nativeHostedE2EHealthPermissionTimeoutFailure(
            sawPermissionSurface = sawPermissionSurface,
            didActivateAllowAll = didActivateAllowAll,
            authorizationSelected = authorizationSelected,
            returnedToApp = returnedToApp,
            hasVisibleText = { marker ->
                runCatching { hasVisibleText(marker) }.getOrDefault(false)
            },
        )

        while (System.currentTimeMillis() < deadline) {
            val currentPackage = device.currentPackageName
            if (currentPackage != null && currentPackage != targetContext.packageName) {
                if (currentPackage !in HealthConnectPackages) {
                    // Android can briefly report the launcher or a transition
                    // owner between app and Health Connect surfaces. Only the
                    // allowlisted system owners may satisfy this stage.
                    sleepBriefly()
                    continue
                }
                sawPermissionSurface = true
                val acted = when {
                    !didActivateAllowAll &&
                        clickSystemControl("Allow all", "Allow all permissions") -> {
                        didActivateAllowAll = true
                        true
                    }
                    !authorizationSelected && clickSystemControl("Allow") -> {
                        authorizationSelected = true
                        true
                    }
                    clickSystemControl(
                        "Get started",
                        "Set up",
                        "Continue",
                        "Next",
                        "Done",
                    ) -> true
                    scrollSystemSurface() -> true
                    else -> false
                }
                if (acted) {
                    systemIdleIterations = 0
                } else {
                    systemIdleIterations += 1
                    if (systemIdleIterations >= 8) {
                        // Health Connect onboarding may finish on its own home
                        // surface rather than returning to Murph. Return through
                        // Android, then take the ordinary app-owned permission
                        // handoff instead of injecting provider state.
                        device.pressBack()
                        systemIdleIterations = 0
                        sleepBriefly(700)
                    } else {
                        sleepBriefly()
                    }
                }
                continue
            }

            if (sawPermissionSurface && didActivateAllowAll) {
                // Some Health Connect builds use Allow all as the terminal
                // action; others expose a separate final Allow button.
                authorizationSelected = true
                if (currentPackage == targetContext.packageName) {
                    returnedToApp = true
                }
            }

            if (sawPermissionSurface && authorizationSelected && (
                    isConnectedHealthState() ||
                        hasAnyText("Connecting…", "Sync is on its way", "Check for new data")
                    )
            ) {
                return
            }

            if (
                handoffAttempts < 3 &&
                hasAnyClickableText(
                    "Connect Health Connect",
                    "Reconnect Health Connect",
                    "Finish setting up Health Connect",
                )
            ) {
                val failureBeforeRetry = currentFailure()
                handoffAttempts += 1
                val handoffResult = tryBeginHealthConnectHandoff()
                nativeHostedE2EHealthPermissionRetryFailure(
                    result = handoffResult,
                    failureBeforeRetry = failureBeforeRetry,
                )?.let { throw JourneyFailure(it) }
                if (
                    handoffResult ==
                    NativeHostedE2EHealthPermissionHandoffResult.ConnectedWithoutPrompt
                ) {
                    return
                }
                returnedToApp = false
                continue
            }
            sleepBriefly()
        }
        throw JourneyFailure(currentFailure())
    }

    private fun finishOptionalSetupAndRequireConnectedState() {
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            if (isConnectedHealthState()) {
                if (hasClickableText("Not now")) {
                    clickText("Not now", 20_000)
                    continue
                }
                require(isReadyShell())
                return
            }
            require(!hasClickableText("Reconnect Health Connect"))
            sleepBriefly()
        }
        throw JourneyFailure(NativeHostedE2EFailureCode.ConnectedStateFailed)
    }

    private fun signOut() {
        clickText("Settings", 45_000)
        clickText("Sign Out", 30_000, scroll = true)
        waitForClickableText("Send code", 120_000)
    }

    private fun requireReturningMemberState() {
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            if (hasVisibleText("Consent needed") || isLaunchConsentSheet()) {
                completeLaunchConsentRecovery(requireAcceptance = false)
                continue
            }
            require(!isOnboardingSurface())
            require(!hasClickableText("Reconnect Health Connect"))
            if (isReadyShell()) {
                if (
                    isConnectedHealthState() ||
                    hasClickableText("Connect Health Connect")
                ) {
                    return
                }
            }
            sleepBriefly()
        }
        throw JourneyFailure(NativeHostedE2EFailureCode.ReturningMemberStateFailed)
    }

    private fun isLaunchConsentSheet(): Boolean =
        nativeHostedE2EHasLaunchConsentSurface(::hasVisibleText)

    private fun isOnboardingSurface(): Boolean = hasAnyText(
        "Add Murph to your contacts",
        "Choose Murph’s main personality",
        "Add a supporting personality",
        "Choose a voice",
        "Pick Murph’s tone",
        "Welcome to Murph",
    )

    private fun isReadyShell(): Boolean =
        hasClickableText("Home") && hasClickableText("Settings")

    private fun isConnectedHealthState(): Boolean = hasAnyText(
        "You're connected",
        "Synced",
        "Sync is on its way",
        "Worth a quick check",
    )

    private fun waitForExternalHealthSurface(timeout: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val currentPackage = device.currentPackageName
            if (currentPackage != null && currentPackage in HealthConnectPackages) {
                return true
            }
            // A launcher or transition package is not proof of Health Connect,
            // but it is also not terminal while the bounded handoff is active.
            sleepBriefly()
        }
        return false
    }

    private fun clickSystemControl(vararg labels: String): Boolean {
        labels.forEach { label ->
            val selector = By.text(
                Pattern.compile("^${Pattern.quote(label)}$", Pattern.CASE_INSENSITIVE),
            )
            val control = device.findObject(selector)
            if (control != null && control.isEnabled) {
                control.click()
                sleepBriefly(700)
                return true
            }
        }
        return false
    }

    private fun scrollSystemSurface(): Boolean {
        val scrollable: UiObject2 = device.findObject(By.scrollable(true)) ?: return false
        return scrollable.scroll(Direction.DOWN, 0.75f).also { sleepBriefly(500) }
    }

    private fun replaceOnlyEditable(value: String, timeout: Long) {
        waitForEditableCount(1, timeout)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun waitForEditableCount(expected: Int, timeout: Long) {
        waitUntil(timeout) { editableCount() == expected }
    }

    private fun editableCount(): Int = runCatching {
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun waitForText(text: String, timeout: Long) {
        waitUntil(timeout) { hasVisibleText(text) }
    }

    private fun waitForTextOrTimeout(text: String, timeout: Long): Boolean =
        runCatching {
            waitForText(text, timeout)
            true
        }.getOrDefault(false)

    private fun waitForClickableText(text: String, timeout: Long) {
        waitUntil(timeout) { hasClickableText(text) }
    }

    private fun clickText(text: String, timeout: Long, scroll: Boolean = false) {
        waitForClickableText(text, timeout)
        val interaction = compose.onAllNodes(clickableText(text))[0]
        if (scroll) {
            runCatching { interaction.performScrollTo() }
        }
        interaction.assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun hasVisibleText(text: String): Boolean = nodeCount(hasTextMatcher(text)) > 0

    private fun hasClickableText(text: String): Boolean = nodeCount(clickableText(text)) > 0

    private fun hasAnyText(vararg texts: String): Boolean = texts.any(::hasVisibleText)

    private fun hasAnyClickableText(vararg texts: String): Boolean =
        texts.any(::hasClickableText)

    private fun clickableText(text: String): SemanticsMatcher =
        hasTextMatcher(text) and hasClickAction() and isEnabled()

    private fun nodeCount(matcher: SemanticsMatcher): Int = runCatching {
        compose.onAllNodes(matcher).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun waitForConditionOrTimeout(
        timeout: Long,
        condition: () -> Boolean,
    ): Boolean {
        val completed = runCatching {
            compose.waitUntil(timeoutMillis = timeout, condition = condition)
            true
        }.getOrDefault(false)
        return completed && condition()
    }

    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        if (!waitForConditionOrTimeout(timeout, condition)) {
            throw IllegalStateException()
        }
    }

    private fun sleepBriefly(milliseconds: Long = 250) {
        Thread.sleep(milliseconds)
    }

    private class JourneyFailure(
        val code: NativeHostedE2EFailureCode,
    ) : RuntimeException()

    companion object {
        private const val OTP_REQUEST_ERROR =
            "We couldn't send a code to that number. Check it and try again."
        private const val OTP_CODE_ERROR =
            "That code didn't work. Try again or send a new one."
        private val HealthConnectPackages = setOf(
            "com.android.healthconnect.controller",
            "com.google.android.healthconnect.controller",
            "com.google.android.apps.healthdata",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
        )
    }
}
