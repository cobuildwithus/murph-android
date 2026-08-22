package ai.withmurph.companion.e2e

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHostedE2EContractTest {
    private val nowEpochSeconds = 2_000_000_000L

    @Test
    fun dispatchConfigurationRequiresExactSourceOriginModeAndLifecycle() {
        val configuration = NativeHostedE2EDispatchConfiguration.require(validArguments(), nowEpochSeconds)

        assertEquals(NativeHostedE2EMode.Pr, configuration.mode)
        assertEquals("https://candidate-123.vercel.app", configuration.webBaseUrl)
        assertEquals("a".repeat(40), configuration.webSha)
        assertEquals("b".repeat(40), configuration.androidSha)
        assertEquals("native-hosted-e2e/android-v1", configuration.androidTag)
        assertEquals(nowEpochSeconds + 900, configuration.dispatchExpiresAtEpochSeconds)

        listOf(
            "murphHostedE2eContractVersion" to "2",
            "murphHostedE2eWebBaseUrl" to "https://candidate-123.vercel.app/path",
            "murphHostedE2eWebSha" to "A".repeat(40),
            "murphHostedE2eAndroidSha" to "b".repeat(39),
            "murphHostedE2eAndroidTag" to "refs/tags/native-e2e",
            "murphHostedE2eCorrelationId" to "unsafe value",
            "murphHostedE2eDispatchExpiresAt" to nowEpochSeconds.toString(),
            "murphHostedE2eDispatchExpiresAt" to (nowEpochSeconds + 2_101).toString(),
            "murphHostedE2eIdentityLifecycle" to "native_owned_reset",
        ).forEach { (key, rejected) ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeHostedE2EDispatchConfiguration.require(
                    Bundle(validArguments()).apply { putString(key, rejected) },
                    nowEpochSeconds,
                )
            }
        }
    }

    @Test
    fun productionCanaryRequiresExactProductionOriginAndNonDestructiveIdentity() {
        val arguments = validArguments().apply {
            putString("murphHostedE2eMode", "production_canary")
            putString("murphHostedE2eWebBaseUrl", "https://www.withmurph.ai")
            putString(
                "murphHostedE2eIdentityLifecycle",
                "non_destructive_existing_identity",
            )
        }

        assertEquals(
            NativeHostedE2EMode.ProductionCanary,
            NativeHostedE2EDispatchConfiguration.require(arguments, nowEpochSeconds).mode,
        )
        assertThrows(IllegalArgumentException::class.java) {
            NativeHostedE2EDispatchConfiguration.require(
                Bundle(arguments).apply {
                    putString("murphHostedE2eWebBaseUrl", "https://withmurph.ai")
                },
                nowEpochSeconds,
            )
        }
    }

    @Test
    fun protectedIdentityRequiresE164PhoneAndSixDigitOtp() {
        val identity = NativeHostedE2EProtectedIdentity.require(validArguments())
        assertEquals("+1", identity.country.dialCode)
        assertEquals("2025550142", identity.nationalNumber)

        listOf(
            "review@example.invalid",
            "2025550142",
            "+02025550142",
            "+1202555",
            "+12025550142x",
            " +12025550142 ",
        ).forEach { rejected ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeHostedE2EProtectedIdentity.require(
                    Bundle(validArguments()).apply {
                        putString("murphHostedE2eLoginIdentifier", rejected)
                    },
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeHostedE2EProtectedIdentity.require(
                Bundle(validArguments()).apply {
                    putString("murphHostedE2eFixedOtp", "12345x")
                },
            )
        }
    }

    @Test
    fun otpRequestPrefersVisibleCodeThenExactlyOneSendAction() {
        assertEquals(
            NativeHostedE2EOtpRequestDecision.VerificationCodeVisible,
            nativeHostedE2EOtpRequestDecision(
                verificationCodeVisible = true,
                enabledSendCodeCount = 1,
            ),
        )
        assertEquals(
            NativeHostedE2EOtpRequestDecision.TapSendCode,
            nativeHostedE2EOtpRequestDecision(
                verificationCodeVisible = false,
                enabledSendCodeCount = 1,
            ),
        )
        listOf(0, 2).forEach { count ->
            assertEquals(
                NativeHostedE2EOtpRequestDecision.Wait,
                nativeHostedE2EOtpRequestDecision(
                    verificationCodeVisible = false,
                    enabledSendCodeCount = count,
                ),
            )
        }
    }

    @Test
    fun codeSubmissionPrefersCompletedAuthenticationThenExactlyOneSignInAction() {
        assertEquals(
            NativeHostedE2ECodeSubmissionDecision.Complete,
            nativeHostedE2ECodeSubmissionDecision(
                postAuthenticationSurfaceVisible = true,
                enabledSignInCount = 1,
            ),
        )
        assertEquals(
            NativeHostedE2ECodeSubmissionDecision.TapSignIn,
            nativeHostedE2ECodeSubmissionDecision(
                postAuthenticationSurfaceVisible = false,
                enabledSignInCount = 1,
            ),
        )
        listOf(0, 2).forEach { count ->
            assertEquals(
                NativeHostedE2ECodeSubmissionDecision.Wait,
                nativeHostedE2ECodeSubmissionDecision(
                    postAuthenticationSurfaceVisible = false,
                    enabledSignInCount = count,
                ),
            )
        }
    }

    @Test
    fun launchConsentSurfaceIncludesProgressBeforeInteractiveContent() {
        listOf(
            "Pausing health sync…",
            "Loading consent…",
            "Saving consent…",
            "Use your health data",
            "Review Murph’s terms",
        ).forEach { visible ->
            assertTrue(nativeHostedE2EHasLaunchConsentSurface { it == visible })
        }
        assertFalse(nativeHostedE2EHasLaunchConsentSurface { it == "Welcome to Murph" })
    }

    @Test
    fun healthPermissionTimeoutReportsTheFirstUnfinishedSafeMilestone() {
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionSurfaceMissing,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = false,
                didActivateAllowAll = false,
                authorizationSelected = false,
            ),
        )
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionSelectionMissing,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = true,
                didActivateAllowAll = false,
                authorizationSelected = false,
            ),
        )
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionApprovalMissing,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = true,
                didActivateAllowAll = true,
                authorizationSelected = false,
            ),
        )
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionCompletionFailed,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = true,
                didActivateAllowAll = true,
                authorizationSelected = true,
            ),
        )
    }

    @Test
    fun healthPermissionTimeoutClassifiesFixedPostPermissionAppStates() {
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionAppReturnMissing,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = true,
                didActivateAllowAll = true,
                authorizationSelected = true,
                returnedToApp = false,
            ),
        )

        val expectedByMarker = mapOf(
            "Choose at least one Health Connect category to connect Murph." to
                NativeHostedE2EFailureCode.HealthConnectPermissionGrantClassificationFailed,
            "Murph couldn't verify Health Connect permissions. Try again." to
                NativeHostedE2EFailureCode.HealthConnectPermissionVerificationFailed,
            "Murph couldn't safely reset health sync. Keep the app open and sign out." to
                NativeHostedE2EFailureCode.HealthConnectPostPermissionResetFailed,
            "Murph couldn't reach the network. Check your connection and try again." to
                NativeHostedE2EFailureCode.HealthConnectPostPermissionNetworkFailed,
            "Murph couldn't finish connecting Health Connect. Try again in a moment." to
                NativeHostedE2EFailureCode.HealthConnectPostPermissionConnectionFailed,
            "Murph couldn't save Health Connect setup. Try again." to
                NativeHostedE2EFailureCode.HealthConnectPostPermissionSetupSaveFailed,
        )
        expectedByMarker.forEach { (visibleMarker, expected) ->
            assertEquals(
                expected,
                nativeHostedE2EHealthPermissionTimeoutFailure(
                    sawPermissionSurface = true,
                    didActivateAllowAll = true,
                    authorizationSelected = true,
                    returnedToApp = true,
                    hasVisibleText = { it == visibleMarker },
                ),
            )
        }
    }

    @Test
    fun healthPermissionRetryPreservesTheFailureAndStartsFreshReturnEvidence() {
        val networkFailure = nativeHostedE2EHealthPermissionTimeoutFailure(
            sawPermissionSurface = true,
            didActivateAllowAll = true,
            authorizationSelected = true,
            returnedToApp = true,
            hasVisibleText = {
                it == "Murph couldn't reach the network. Check your connection and try again."
            },
        )
        assertEquals(
            networkFailure,
            nativeHostedE2EHealthPermissionRetryFailure(
                result = NativeHostedE2EHealthPermissionHandoffResult.TimedOut,
                failureBeforeRetry = networkFailure,
            ),
        )
        assertEquals(
            null,
            nativeHostedE2EHealthPermissionRetryFailure(
                result = NativeHostedE2EHealthPermissionHandoffResult.ConnectedWithoutPrompt,
                failureBeforeRetry = networkFailure,
            ),
        )
        assertEquals(
            null,
            nativeHostedE2EHealthPermissionRetryFailure(
                result = NativeHostedE2EHealthPermissionHandoffResult.SystemSurfaceOpened,
                failureBeforeRetry = networkFailure,
            ),
        )
        assertEquals(
            NativeHostedE2EFailureCode.HealthConnectPermissionAppReturnMissing,
            nativeHostedE2EHealthPermissionTimeoutFailure(
                sawPermissionSurface = true,
                didActivateAllowAll = true,
                authorizationSelected = true,
                returnedToApp = false,
            ),
        )
    }

    @Test
    fun friendlyNamesBannerProvesInitialHealthSetupCompleted() {
        assertTrue(
            nativeHostedE2EHasCompletedHealthSetup { visibleText ->
                visibleText == "Friendly Names are optional"
            },
        )
    }

    @Test
    fun onboardingProgressSupportsServerContinuationAndRejectsSkippedStages() {
        NativeHostedE2EOnboardingProgress().apply {
            observe("Choose a voice")
            observe("Pick Murph’s tone")
            observe("Welcome to Murph")
            assertTrue(isComplete)
        }

        val reordered = NativeHostedE2EOnboardingProgress()
        reordered.observe("Choose Murph’s main personality")
        assertThrows(IllegalArgumentException::class.java) {
            reordered.observe("Choose a voice")
        }
    }

    @Test
    fun stageReporterEmitsExactlyOneOrderedPrivacySafeSummary() {
        val bytes = ByteArrayOutputStream()
        var publishedStatus: String? = null
        val reporter = NativeHostedE2EStageReporter(
            NativeHostedE2EMode.ProductionCanary,
            PrintStream(bytes, true, Charsets.UTF_8.name()),
        )
        val statusReporter = NativeHostedE2EStageReporter(
            mode = NativeHostedE2EMode.ProductionCanary,
            output = null,
            statusPublisher = { publishedStatus = it },
        )
        val stages = listOf(
            NativeHostedE2EStage.ContractValidation,
            NativeHostedE2EStage.LaunchLiveApp,
            NativeHostedE2EStage.InitialPrivyOtp,
            NativeHostedE2EStage.CanonicalAdmission,
            NativeHostedE2EStage.ExistingMemberState,
            NativeHostedE2EStage.HealthConnectHandoff,
            NativeHostedE2EStage.HealthConnectPermissionState,
            NativeHostedE2EStage.ConnectedState,
            NativeHostedE2EStage.SignOut,
            NativeHostedE2EStage.ReturningPrivyOtp,
            NativeHostedE2EStage.ReturningMemberState,
        )
        stages.forEach {
            reporter.passed(it)
            statusReporter.passed(it)
        }
        reporter.finishPassed()
        statusReporter.finishPassed()

        val output = bytes.toString(Charsets.UTF_8.name()).trim()
        assertTrue(output.startsWith(NATIVE_ANDROID_STAGE_SUMMARY_PREFIX))
        assertTrue(output.contains("\"result\":\"passed\""))
        assertEquals(output, publishedStatus)
        assertFalse(output.contains("+12025550142"))
        assertFalse(output.contains("123456"))
        assertThrows(IllegalArgumentException::class.java) {
            reporter.finishPassed()
        }
    }

    @Test
    fun stageReporterRejectsUnexpectedOrderAndBindsFailureCodeToStage() {
        val reporter = NativeHostedE2EStageReporter(
            NativeHostedE2EMode.Pr,
            PrintStream(ByteArrayOutputStream()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            reporter.passed(NativeHostedE2EStage.LaunchLiveApp)
        }
        reporter.passed(NativeHostedE2EStage.ContractValidation)
        assertThrows(IllegalArgumentException::class.java) {
            reporter.failed(
                NativeHostedE2EStage.LaunchLiveApp,
                NativeHostedE2EFailureCode.SignOutFailed,
            )
        }
    }

    @Test
    fun liveDriverIsInactiveWithoutExplicitProtectedFlag() {
        assertFalse(nativeHostedE2EEnabled(Bundle()))
        assertTrue(nativeHostedE2EEnabled(Bundle().apply {
            putString("murphHostedE2eEnabled", "true")
        }))
        assertFalse(nativeHostedE2EEnabled(Bundle().apply {
            putString("murphHostedE2eEnabled", "TRUE")
        }))
    }

    private fun validArguments(): Bundle = Bundle().apply {
        putString("murphHostedE2eEnabled", "true")
        putString("murphHostedE2eContractVersion", "1")
        putString("murphHostedE2eCorrelationId", "murph-pr-safe")
        putString("murphHostedE2eDispatchExpiresAt", (nowEpochSeconds + 900).toString())
        putString("murphHostedE2eMode", "pr")
        putString("murphHostedE2eWebBaseUrl", "https://candidate-123.vercel.app")
        putString("murphHostedE2eWebSha", "a".repeat(40))
        putString("murphHostedE2eAndroidSha", "b".repeat(40))
        putString("murphHostedE2eAndroidTag", "native-hosted-e2e/android-v1")
        putString("murphHostedE2eIdentityLifecycle", "orchestrator_owned_reset")
        putString("murphHostedE2eLoginIdentifier", "+12025550142")
        putString("murphHostedE2eFixedOtp", "123456")
    }
}
