plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val privyAppId = providers.gradleProperty("MURPH_PRIVY_APP_ID").orElse("")
val privyAppClientId = providers.gradleProperty("MURPH_PRIVY_APP_CLIENT_ID").orElse("")
val developmentBackend = providers.gradleProperty("MURPH_BACKEND_BASE_URL_DEV")
    .orElse("https://linq-webhook-dev.ourrevolution.wtf")
val productionBackend = providers.gradleProperty("MURPH_BACKEND_BASE_URL_PROD")
    .orElse("https://www.withmurph.ai")

android {
    namespace = "ai.withmurph.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.withmurph.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "PRIVY_APP_ID", privyAppId.get().asBuildConfigString())
        buildConfigField("String", "PRIVY_APP_CLIENT_ID", privyAppClientId.get().asBuildConfigString())
        buildConfigField("String", "JUNCTION_SDK_VERSION", "\"5.0.2\"")
        buildConfigField("String", "PRIVY_SDK_VERSION", "\"0.12.0\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Murph Dev")
            buildConfigField(
                "String",
                "MURPH_BACKEND_BASE_URL",
                developmentBackend.get().asBuildConfigString(),
            )
            buildConfigField("String", "MURPH_ENVIRONMENT", "\"sandbox\"")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Murph")
            buildConfigField(
                "String",
                "MURPH_BACKEND_BASE_URL",
                productionBackend.get().asBuildConfigString(),
            )
            buildConfigField("String", "MURPH_ENVIRONMENT", "\"production\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.health.connect)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)

    implementation(libs.privy.core)
    implementation(libs.vital.client)
    implementation(libs.vital.health.connect)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
