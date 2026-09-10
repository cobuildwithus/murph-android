package ai.withmurph.companion.app

import ai.withmurph.companion.BuildConfig
import ai.withmurph.companion.core.AppEnvironment
import java.net.URI

data class AppConfig(
    val backendBaseUrl: String,
    val environment: AppEnvironment,
    val appVersion: String,
    val junctionSdkVersion: String,
) {
    fun requireConfigured() {
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
                appVersion = BuildConfig.VERSION_NAME,
                junctionSdkVersion = BuildConfig.JUNCTION_SDK_VERSION,
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
