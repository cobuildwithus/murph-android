package ai.withmurph.companion.e2e

import ai.withmurph.companion.auth.CountryDialCode
import android.os.Bundle
import java.io.PrintStream
import java.net.URI

internal const val NATIVE_ANDROID_STAGE_SUMMARY_PREFIX =
    "MURPH_NATIVE_ANDROID_E2E_STAGE_SUMMARY_JSON:"

internal enum class NativeHostedE2EMode(val rawValue: String) {
    Pr("pr"),
    ProductionCanary("production_canary"),
    ;

    val expectedEnvironment: String
        get() = when (this) {
            Pr -> "sandbox"
            ProductionCanary -> "production"
        }

    val expectedApplicationId: String
        get() = when (this) {
            Pr -> "ai.withmurph.app.dev"
            ProductionCanary -> "ai.withmurph.app"
        }

    val expectedIdentityLifecycle: String
        get() = when (this) {
            Pr -> "orchestrator_owned_reset"
            ProductionCanary -> "non_destructive_existing_identity"
        }

    companion object {
        fun fromRaw(value: String?): NativeHostedE2EMode? = entries.firstOrNull {
            it.rawValue == value
        }
    }
}

internal enum class NativeHostedE2EOtpRequestDecision {
    TapSendCode,
    VerificationCodeVisible,
    Wait,
}

internal fun nativeHostedE2EOtpRequestDecision(
    verificationCodeVisible: Boolean,
    enabledSendCodeCount: Int,
): NativeHostedE2EOtpRequestDecision = when {
    verificationCodeVisible -> NativeHostedE2EOtpRequestDecision.VerificationCodeVisible
    enabledSendCodeCount == 1 -> NativeHostedE2EOtpRequestDecision.TapSendCode
    else -> NativeHostedE2EOtpRequestDecision.Wait
}

internal enum class NativeHostedE2ECodeSubmissionDecision {
    Complete,
    TapSignIn,
    Wait,
}

internal fun nativeHostedE2ECodeSubmissionDecision(
    postAuthenticationSurfaceVisible: Boolean,
    enabledSignInCount: Int,
): NativeHostedE2ECodeSubmissionDecision = when {
    postAuthenticationSurfaceVisible -> NativeHostedE2ECodeSubmissionDecision.Complete
    enabledSignInCount == 1 -> NativeHostedE2ECodeSubmissionDecision.TapSignIn
    else -> NativeHostedE2ECodeSubmissionDecision.Wait
}

private val NativeHostedE2ELaunchConsentSurfaceMarkers = listOf(
    "Pausing health sync…",
    "Loading consent…",
    "Saving consent…",
    "Use your health data",
    "Review Murph’s terms",
)

internal fun nativeHostedE2EHasLaunchConsentSurface(
    hasVisibleText: (String) -> Boolean,
): Boolean = NativeHostedE2ELaunchConsentSurfaceMarkers.any(hasVisibleText)

internal class NativeHostedE2EOnboardingProgress {
    private var lastObservedIndex: Int? = null

    val isComplete: Boolean
        get() = lastObservedIndex == OrderedTitles.lastIndex

    fun observe(title: String) {
        val index = OrderedTitles.indexOf(title)
        require(index >= 0)
        lastObservedIndex?.let { previous ->
            require(index == previous + 1)
        }
        lastObservedIndex = index
    }

    companion object {
        private val OrderedTitles = listOf(
            "Add Murph to your contacts",
            "Choose Murph’s main personality",
            "Add a supporting personality",
            "Choose a voice",
            "Pick Murph’s tone",
            "Welcome to Murph",
        )
    }
}

internal enum class NativeHostedE2EStage(val rawValue: String) {
    ContractValidation("contract_validation"),
    Infrastructure("infrastructure"),
    LaunchLiveApp("launch_live_app"),
    InitialPrivyOtp("initial_privy_otp"),
    CanonicalAdmission("canonical_admission"),
    LaunchConsentRecovery("launch_consent_recovery"),
    ServerOwnedOnboarding("server_owned_onboarding"),
    ExistingMemberState("existing_member_state"),
    HealthConnectHandoff("health_connect_handoff"),
    HealthConnectPermissionState("health_connect_permission_state"),
    ConnectedState("connected_state"),
    SignOut("sign_out"),
    ReturningPrivyOtp("returning_privy_otp"),
    ReturningMemberState("returning_member_state"),
}

internal enum class NativeHostedE2EFailureCode(
    val rawValue: String,
    val stage: NativeHostedE2EStage,
) {
    InvalidDispatchContract(
        "invalid_dispatch_contract",
        NativeHostedE2EStage.ContractValidation,
    ),
    MissingProtectedConfiguration(
        "missing_protected_configuration",
        NativeHostedE2EStage.Infrastructure,
    ),
    LaunchLiveAppFailed("launch_live_app_failed", NativeHostedE2EStage.LaunchLiveApp),
    InitialPrivyOtpFailed("initial_privy_otp_failed", NativeHostedE2EStage.InitialPrivyOtp),
    InitialPrivyOtpRequestRejected(
        "initial_privy_otp_request_rejected",
        NativeHostedE2EStage.InitialPrivyOtp,
    ),
    InitialPrivyOtpCodeRejected(
        "initial_privy_otp_code_rejected",
        NativeHostedE2EStage.InitialPrivyOtp,
    ),
    CanonicalAdmissionFailed(
        "canonical_admission_failed",
        NativeHostedE2EStage.CanonicalAdmission,
    ),
    LaunchConsentRecoveryFailed(
        "launch_consent_recovery_failed",
        NativeHostedE2EStage.LaunchConsentRecovery,
    ),
    ServerOwnedOnboardingFailed(
        "server_owned_onboarding_failed",
        NativeHostedE2EStage.ServerOwnedOnboarding,
    ),
    ExistingMemberStateFailed(
        "existing_member_state_failed",
        NativeHostedE2EStage.ExistingMemberState,
    ),
    HealthConnectHandoffFailed(
        "health_connect_handoff_failed",
        NativeHostedE2EStage.HealthConnectHandoff,
    ),
    HealthConnectSurfaceMissing(
        "health_connect_surface_missing",
        NativeHostedE2EStage.HealthConnectHandoff,
    ),
    HealthConnectPermissionStateFailed(
        "health_connect_permission_state_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionSurfaceMissing(
        "health_connect_permission_surface_missing",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionSelectionMissing(
        "health_connect_permission_selection_missing",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionApprovalMissing(
        "health_connect_permission_approval_missing",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionCompletionFailed(
        "health_connect_permission_completion_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionCompletionPending(
        "health_connect_permission_completion_pending",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionUiProjectionFailed(
        "health_connect_permission_ui_projection_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionAppStateFailed(
        "health_connect_permission_app_state_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionAppReturnMissing(
        "health_connect_permission_app_return_missing",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionGrantClassificationFailed(
        "health_connect_permission_grant_classification_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPermissionVerificationFailed(
        "health_connect_permission_verification_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPostPermissionResetFailed(
        "health_connect_post_permission_reset_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPostPermissionNetworkFailed(
        "health_connect_post_permission_network_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPostPermissionConnectionFailed(
        "health_connect_post_permission_connection_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    HealthConnectPostPermissionSetupSaveFailed(
        "health_connect_post_permission_setup_save_failed",
        NativeHostedE2EStage.HealthConnectPermissionState,
    ),
    ConnectedStateFailed("connected_state_failed", NativeHostedE2EStage.ConnectedState),
    SignOutFailed("sign_out_failed", NativeHostedE2EStage.SignOut),
    ReturningPrivyOtpFailed(
        "returning_privy_otp_failed",
        NativeHostedE2EStage.ReturningPrivyOtp,
    ),
    ReturningPrivyOtpRequestRejected(
        "returning_privy_otp_request_rejected",
        NativeHostedE2EStage.ReturningPrivyOtp,
    ),
    ReturningPrivyOtpCodeRejected(
        "returning_privy_otp_code_rejected",
        NativeHostedE2EStage.ReturningPrivyOtp,
    ),
    ReturningMemberStateFailed(
        "returning_member_state_failed",
        NativeHostedE2EStage.ReturningMemberState,
    ),
}

internal fun nativeHostedE2EHealthPermissionTimeoutFailure(
    sawPermissionSurface: Boolean,
    didActivateAllowAll: Boolean,
    authorizationSelected: Boolean,
    returnedToApp: Boolean = true,
    hasVisibleText: (String) -> Boolean = { false },
    appReady: Boolean = true,
    appIsConnecting: Boolean = false,
    appSetupAdvanced: Boolean = false,
    appHealthConnected: Boolean = false,
    hasAppStateText: (String) -> Boolean = { false },
): NativeHostedE2EFailureCode = when {
    !sawPermissionSurface -> NativeHostedE2EFailureCode.HealthConnectPermissionSurfaceMissing
    !didActivateAllowAll -> NativeHostedE2EFailureCode.HealthConnectPermissionSelectionMissing
    !authorizationSelected -> NativeHostedE2EFailureCode.HealthConnectPermissionApprovalMissing
    !returnedToApp -> NativeHostedE2EFailureCode.HealthConnectPermissionAppReturnMissing
    HealthPermissionGrantFailureMarkers.any { hasVisibleText(it) || hasAppStateText(it) } ->
        NativeHostedE2EFailureCode.HealthConnectPermissionGrantClassificationFailed
    HealthPermissionVerificationFailureMarkers.any {
        hasVisibleText(it) || hasAppStateText(it)
    } ->
        NativeHostedE2EFailureCode.HealthConnectPermissionVerificationFailed
    HealthPostPermissionResetFailureMarkers.any {
        hasVisibleText(it) || hasAppStateText(it)
    } ->
        NativeHostedE2EFailureCode.HealthConnectPostPermissionResetFailed
    HealthPostPermissionNetworkFailureMarkers.any {
        hasVisibleText(it) || hasAppStateText(it)
    } ->
        NativeHostedE2EFailureCode.HealthConnectPostPermissionNetworkFailed
    HealthPostPermissionConnectionFailureMarkers.any {
        hasVisibleText(it) || hasAppStateText(it)
    } ->
        NativeHostedE2EFailureCode.HealthConnectPostPermissionConnectionFailed
    HealthPostPermissionSetupSaveFailureMarkers.any {
        hasVisibleText(it) || hasAppStateText(it)
    } ->
        NativeHostedE2EFailureCode.HealthConnectPostPermissionSetupSaveFailed
    !appReady -> NativeHostedE2EFailureCode.HealthConnectPermissionAppStateFailed
    appIsConnecting -> NativeHostedE2EFailureCode.HealthConnectPermissionCompletionPending
    appSetupAdvanced || appHealthConnected ->
        NativeHostedE2EFailureCode.HealthConnectPermissionUiProjectionFailed
    else -> NativeHostedE2EFailureCode.HealthConnectPermissionCompletionFailed
}

internal enum class NativeHostedE2EHealthPermissionHandoffResult {
    SystemSurfaceOpened,
    ConnectedWithoutPrompt,
    TimedOut,
}

internal fun nativeHostedE2EHealthPermissionRetryFailure(
    result: NativeHostedE2EHealthPermissionHandoffResult,
    failureBeforeRetry: NativeHostedE2EFailureCode,
): NativeHostedE2EFailureCode? = failureBeforeRetry.takeIf {
    result == NativeHostedE2EHealthPermissionHandoffResult.TimedOut
}

internal fun nativeHostedE2EHasCompletedHealthSetup(
    hasVisibleText: (String) -> Boolean,
): Boolean = listOf(
    "You're connected",
    "Synced",
    "Sync is on its way",
    "Worth a quick check",
    // Initial setup advances to this committed next step before the health
    // status card is guaranteed to remain visible in the reduced viewport.
    "Friendly Names are optional",
).any(hasVisibleText)

internal fun nativeHostedE2EHasAppOwnedText(
    text: String,
    composeHasVisibleText: (String) -> Boolean,
    hierarchyHasVisibleText: (String) -> Boolean,
): Boolean = composeHasVisibleText(text) || hierarchyHasVisibleText(text)

private val HealthPermissionGrantFailureMarkers = listOf(
    "Choose at least one Health Connect category to connect Murph.",
    "Power, speed, and elevation require Workouts. Allow Workouts in Health Connect, then try again.",
    "Reproductive details require Menstruation. Allow Menstruation in Health Connect, then try again.",
    "Power, speed, and elevation require Workouts; reproductive details require Menstruation. Update Health Connect permissions, then try again.",
)

private val HealthPermissionVerificationFailureMarkers = listOf(
    "Murph couldn't verify Health Connect permissions. Try again.",
    "Murph couldn't verify current Health Connect permissions. Saved status is still shown.",
)

private val HealthPostPermissionResetFailureMarkers = listOf(
    "Murph couldn't safely reset health sync. Keep the app open and sign out.",
)

private val HealthPostPermissionNetworkFailureMarkers = listOf(
    "Murph couldn't reach the network. Check your connection and try again.",
)

private val HealthPostPermissionConnectionFailureMarkers = listOf(
    "Murph couldn't finish connecting Health Connect. Try again in a moment.",
    "Reconnect Health Connect to resume syncing.",
)

private val HealthPostPermissionSetupSaveFailureMarkers = listOf(
    "Murph couldn't save Health Connect setup. Try again.",
)

internal data class NativeHostedE2EDispatchConfiguration(
    val mode: NativeHostedE2EMode,
    val webBaseUrl: String,
    val webSha: String,
    val androidSha: String,
    val androidTag: String,
    val correlationId: String,
    val dispatchExpiresAtEpochSeconds: Long,
    val identityLifecycle: String,
) {
    companion object {
        private const val ContractVersion = "1"
        private const val MaximumDispatchLeaseSeconds = 35 * 60L

        fun require(
            arguments: Bundle,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
        ): NativeHostedE2EDispatchConfiguration {
            val mode = NativeHostedE2EMode.fromRaw(arguments.exact("murphHostedE2eMode"))
                ?: invalidDispatch()
            val webBaseUrl = arguments.exact("murphHostedE2eWebBaseUrl")
            val webSha = arguments.exact("murphHostedE2eWebSha")
            val androidSha = arguments.exact("murphHostedE2eAndroidSha")
            val androidTag = arguments.exact("murphHostedE2eAndroidTag")
            val correlationId = arguments.exact("murphHostedE2eCorrelationId")
            val dispatchExpiresAtRaw = arguments.exact("murphHostedE2eDispatchExpiresAt")
            val identityLifecycle = arguments.exact("murphHostedE2eIdentityLifecycle")

            requireDispatch(arguments.exact("murphHostedE2eContractVersion") == ContractVersion)
            requireDispatch(webSha.matches(Regex("[0-9a-f]{40}")))
            requireDispatch(androidSha.matches(Regex("[0-9a-f]{40}")))
            requireDispatch(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,120}")))
            requireDispatch(dispatchExpiresAtRaw.matches(Regex("[1-9][0-9]{9}")))
            requireDispatch(nowEpochSeconds >= 1_000_000_000)
            val dispatchExpiresAt = dispatchExpiresAtRaw.toLongOrNull() ?: invalidDispatch()
            requireDispatch(
                dispatchExpiresAt > nowEpochSeconds &&
                    dispatchExpiresAt <= nowEpochSeconds + MaximumDispatchLeaseSeconds,
            )
            requireDispatch(isSafeTag(androidTag))
            requireDispatch(identityLifecycle == mode.expectedIdentityLifecycle)

            val origin = exactHttpsOrigin(webBaseUrl)
            when (mode) {
                NativeHostedE2EMode.Pr -> {
                    val host = URI(origin).host.orEmpty()
                    requireDispatch(host != "vercel.app" && host.endsWith(".vercel.app"))
                }
                NativeHostedE2EMode.ProductionCanary -> {
                    requireDispatch(origin == "https://www.withmurph.ai")
                }
            }

            return NativeHostedE2EDispatchConfiguration(
                mode = mode,
                webBaseUrl = origin,
                webSha = webSha,
                androidSha = androidSha,
                androidTag = androidTag,
                correlationId = correlationId,
                dispatchExpiresAtEpochSeconds = dispatchExpiresAt,
                identityLifecycle = identityLifecycle,
            )
        }

        private fun exactHttpsOrigin(value: String): String {
            val parsed = runCatching { URI(value) }.getOrNull() ?: invalidDispatch()
            requireDispatch(
                parsed.scheme == "https" &&
                    parsed.rawAuthority != null &&
                    parsed.host != null &&
                    parsed.userInfo == null &&
                    parsed.port == -1 &&
                    (parsed.rawPath.isNullOrEmpty()) &&
                    parsed.rawQuery == null &&
                    parsed.rawFragment == null &&
                    parsed.toString() == value,
            )
            return value
        }

        private fun isSafeTag(value: String): Boolean =
            value.length in 1..180 &&
                value.matches(Regex("[A-Za-z0-9._/-]+")) &&
                !value.startsWith("refs/") &&
                !value.startsWith("/") &&
                !value.endsWith("/") &&
                !value.endsWith(".lock") &&
                ".." !in value &&
                "//" !in value &&
                "@{" !in value
    }
}

internal data class NativeHostedE2EProtectedIdentity(
    val phoneNumber: String,
    val fixedOtp: String,
    val country: CountryDialCode,
    val nationalNumber: String,
) {
    companion object {
        fun require(arguments: Bundle): NativeHostedE2EProtectedIdentity {
            val phoneNumber = arguments.exactProtected("murphHostedE2eLoginIdentifier")
            val fixedOtp = arguments.exactProtected("murphHostedE2eFixedOtp")
            requireProtected(CountryDialCode.isPlausibleE164(phoneNumber))
            requireProtected(fixedOtp.matches(Regex("[0-9]{6}")))

            val country = CountryDialCode.All
                .asSequence()
                .filter { phoneNumber.startsWith(it.dialCode) }
                .maxByOrNull { it.dialCode.length }
                ?: missingProtectedConfiguration()
            val nationalNumber = phoneNumber.removePrefix(country.dialCode)
            requireProtected(nationalNumber.length >= 4 && nationalNumber.all(Char::isDigit))

            return NativeHostedE2EProtectedIdentity(
                phoneNumber = phoneNumber,
                fixedOtp = fixedOtp,
                country = country,
                nationalNumber = nationalNumber,
            )
        }
    }
}

internal data class NativeHostedE2EStageEntry(
    val name: String,
    val status: String,
    val code: String? = null,
) {
    fun json(): String = buildString {
        append('{')
        if (code != null) {
            append("\"code\":\"")
            append(code)
            append("\",")
        }
        append("\"name\":\"")
        append(name)
        append("\",\"status\":\"")
        append(status)
        append("\"}")
    }
}

internal class NativeHostedE2EStageReporter(
    private val mode: NativeHostedE2EMode,
    private val output: PrintStream? = System.out,
    private val statusPublisher: ((String) -> Unit)? = null,
) {
    private val expectedStages = when (mode) {
        NativeHostedE2EMode.Pr -> listOf(
            NativeHostedE2EStage.ContractValidation,
            NativeHostedE2EStage.LaunchLiveApp,
            NativeHostedE2EStage.InitialPrivyOtp,
            NativeHostedE2EStage.CanonicalAdmission,
            NativeHostedE2EStage.LaunchConsentRecovery,
            NativeHostedE2EStage.ServerOwnedOnboarding,
            NativeHostedE2EStage.HealthConnectHandoff,
            NativeHostedE2EStage.HealthConnectPermissionState,
            NativeHostedE2EStage.ConnectedState,
            NativeHostedE2EStage.SignOut,
            NativeHostedE2EStage.ReturningPrivyOtp,
            NativeHostedE2EStage.ReturningMemberState,
        )
        NativeHostedE2EMode.ProductionCanary -> listOf(
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
    }
    private val entries = mutableListOf<NativeHostedE2EStageEntry>()
    private var emitted = false

    fun passed(stage: NativeHostedE2EStage) {
        require(!emitted)
        require(stage == expectedStages.getOrNull(entries.size))
        entries += NativeHostedE2EStageEntry(stage.rawValue, "passed")
    }

    fun failed(stage: NativeHostedE2EStage, code: NativeHostedE2EFailureCode) {
        require(!emitted)
        require(stage == expectedStages.getOrNull(entries.size))
        require(code.stage == stage)
        entries += NativeHostedE2EStageEntry(stage.rawValue, "failed", code.rawValue)
        emit("failed")
    }

    fun infrastructureFailed(code: NativeHostedE2EFailureCode) {
        require(!emitted)
        require(entries == listOf(
            NativeHostedE2EStageEntry(
                NativeHostedE2EStage.ContractValidation.rawValue,
                "passed",
            ),
        ))
        require(code.stage == NativeHostedE2EStage.Infrastructure)
        entries += NativeHostedE2EStageEntry(
            NativeHostedE2EStage.Infrastructure.rawValue,
            "failed",
            code.rawValue,
        )
        emit("failed")
    }

    fun finishPassed() {
        require(!emitted)
        require(entries.size == expectedStages.size)
        require(entries.all { it.status == "passed" })
        emit("passed")
    }

    private fun emit(result: String) {
        if (emitted) return
        val stages = entries.joinToString(separator = ",") { it.json() }
        val summary =
            "${NATIVE_ANDROID_STAGE_SUMMARY_PREFIX}{" +
                "\"contractVersion\":1," +
                "\"mode\":\"${mode.rawValue}\"," +
                "\"result\":\"$result\"," +
                "\"stages\":[$stages]}"
        output?.println(summary)
        output?.flush()
        statusPublisher?.invoke(summary)
        emitted = true
    }
}

internal fun nativeHostedE2EEnabled(arguments: Bundle): Boolean =
    arguments.getString("murphHostedE2eEnabled") == "true"

private fun Bundle.exact(name: String): String {
    val value = getString(name) ?: invalidDispatch()
    requireDispatch(value.isNotEmpty() && value.trim() == value)
    return value
}

private fun Bundle.exactProtected(name: String): String {
    val value = getString(name) ?: missingProtectedConfiguration()
    requireProtected(value.isNotEmpty() && value.trim() == value)
    return value
}

private fun requireDispatch(condition: Boolean) {
    if (!condition) invalidDispatch()
}

private fun requireProtected(condition: Boolean) {
    if (!condition) missingProtectedConfiguration()
}

private fun invalidDispatch(): Nothing =
    throw IllegalArgumentException(NativeHostedE2EFailureCode.InvalidDispatchContract.rawValue)

private fun missingProtectedConfiguration(): Nothing =
    throw IllegalArgumentException(
        NativeHostedE2EFailureCode.MissingProtectedConfiguration.rawValue,
    )
