import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

abstract class WritePlaySourceMetadata : DefaultTask() {
    @get:Input
    abstract val sourceHead: Property<String>

    @get:Input
    abstract val workingTreeState: Property<String>

    @get:Input
    abstract val releaseConfigurationSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun writeMetadata() {
        val head = sourceHead.get()
        val state = workingTreeState.get()
        val configurationSha256 = releaseConfigurationSha256.get()
        require(Regex("[0-9a-f]{40}").matches(head)) {
            "Could not derive the exact source commit for the Release artifact."
        }
        require(state == "clean" || state == "dirty") {
            "Could not derive the Release working-tree state."
        }
        require(Regex("[0-9a-f]{64}").matches(configurationSha256)) {
            "Could not derive the Release public-configuration digest."
        }
        val output = outputDirectory.file("murph-play/source.properties").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "schema=1\nsourceHead=$head\nworkingTreeClean=${state == "clean"}\n" +
                "configurationSha256=$configurationSha256\n",
        )
    }
}

apply(from = rootProject.file("gradle/play-release.gradle.kts"))

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val privyAppId = providers.gradleProperty("MURPH_PRIVY_APP_ID").orElse("")
val privyAppClientId = providers.gradleProperty("MURPH_PRIVY_APP_CLIENT_ID").orElse("")
val developmentBackend = providers.gradleProperty("MURPH_BACKEND_BASE_URL_DEV")
    .orElse("https://linq-webhook-dev.ourrevolution.wtf")
val productionBackend = providers.gradleProperty("MURPH_BACKEND_BASE_URL_PROD")
    .orElse("https://www.withmurph.ai")

fun publicReleaseConfigurationSha256(
    appId: String,
    appClientId: String,
    backend: String,
): String = MessageDigest.getInstance("SHA-256")
    .digest("$appId\u0000$appClientId\u0000$backend".toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

val validateReleaseConfiguration by tasks.registering {
    doLast {
        val requiredValues = mapOf(
            "MURPH_PRIVY_APP_ID" to privyAppId.orNull,
            "MURPH_PRIVY_APP_CLIENT_ID" to privyAppClientId.orNull,
        )
        val missing = requiredValues
            .filterValues { it.isNullOrBlank() }
            .keys
            .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release configuration is missing: ${missing.joinToString()}. " +
                    "Set the public native-client values in Gradle properties.",
            )
        }

        val backendValue = productionBackend.get()
        val backend = runCatching { URI(backendValue) }.getOrNull()
        if (backend?.scheme != "https" || backend.host == null) {
            throw GradleException(
                "MURPH_BACKEND_BASE_URL_PROD must be an absolute HTTPS URL.",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseConfiguration)
}

android {
    namespace = "ai.withmurph.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.withmurph.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
        create("synthetic") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".synthetic"
            versionNameSuffix = "-synthetic"
            matchingFallbacks += listOf("debug")
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
    testBuildType = "synthetic"

    sourceSets {
        getByName("synthetic") {
            java.srcDir("src/debug/java")
            res.srcDir("src/debug/res")
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
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = false
        managedDevices {
            localDevices {
                create("pixel2Api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

val playSourceHead = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val playWorkingTreeState = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine(
        "sh",
        "-c",
        "if ! git diff-index --quiet HEAD -- || test -n \"$(git ls-files --others --exclude-standard | head -n 1)\"; then printf dirty; else printf clean; fi",
    )
}.standardOutput.asText.map(String::trim)
val writePlaySourceMetadata = tasks.register<WritePlaySourceMetadata>("writePlaySourceMetadata") {
    sourceHead.set(playSourceHead)
    workingTreeState.set(playWorkingTreeState)
    releaseConfigurationSha256.set(providers.provider {
        publicReleaseConfigurationSha256(
            privyAppId.get(),
            privyAppClientId.get(),
            productionBackend.get(),
        )
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/play-release-metadata"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val assets = requireNotNull(variant.sources.assets) {
            "Release assets are required for Play source provenance."
        }
        assets.addGeneratedSourceDirectory(writePlaySourceMetadata) {
            it.outputDirectory
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)
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

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
