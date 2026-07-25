package ai.withmurph.companion.app

import ai.withmurph.companion.BuildConfig
import ai.withmurph.companion.core.AppEnvironment
import java.net.URI

data class AppConfig(
    val backendBaseUrl: String,
    val environment: AppEnvironment,
    val privyAppId: String,
    val privyAppClientId: String,
    val appVersion: String,
    val junctionSdkVersion: String,
    val privySdkVersion: String,
) {
    fun requireConfigured() {
        require(privyAppId.isNotBlank()) {
            "MURPH_PRIVY_APP_ID must be supplied through Gradle properties"
        }
        require(privyAppClientId.isNotBlank()) {
            "MURPH_PRIVY_APP_CLIENT_ID must be supplied through Gradle properties"
        }
        val backend = URI(backendBaseUrl)
        require(backend.scheme == "https" && backend.host != null) {
            "MURPH_BACKEND_BASE_URL must be an absolute HTTPS URL"
        }
    }

    companion object {
        val current: AppConfig
            get() = AppConfig(
                backendBaseUrl = BuildConfig.MURPH_BACKEND_BASE_URL,
                environment = when (BuildConfig.MURPH_ENVIRONMENT) {
                    "production" -> AppEnvironment.Production
                    else -> AppEnvironment.Sandbox
                },
                privyAppId = BuildConfig.PRIVY_APP_ID,
                privyAppClientId = BuildConfig.PRIVY_APP_CLIENT_ID,
                appVersion = BuildConfig.VERSION_NAME,
                junctionSdkVersion = BuildConfig.JUNCTION_SDK_VERSION,
                privySdkVersion = BuildConfig.PRIVY_SDK_VERSION,
            )
    }
}

object AppLinks {
    const val Privacy = "https://www.withmurph.ai/legal/privacy"
    const val Terms = "https://www.withmurph.ai/legal/terms"
    const val HealthNotice = "https://www.withmurph.ai/consumer-health-data-privacy-policy"
    const val AiSafety = "https://www.withmurph.ai/legal/health-ai-safety-disclosure"
    const val AccountDeletion = "https://www.withmurph.ai/settings/data-privacy"
    const val Support = "mailto:support@withmurph.ai"
}
