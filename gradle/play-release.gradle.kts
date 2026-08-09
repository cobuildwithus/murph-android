import groovy.json.JsonOutput
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.api.tasks.Exec
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import javax.xml.parsers.DocumentBuilderFactory

val junctionAndroidCommercialLicenseConfirmed = providers
    .gradleProperty("MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED")
    .orElse(providers.environmentVariable("MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED"))
    .orElse("false")
val playPrivyAppId = providers.gradleProperty("MURPH_PRIVY_APP_ID").orElse("")
val playPrivyAppClientId = providers.gradleProperty("MURPH_PRIVY_APP_CLIENT_ID").orElse("")
val playProductionBackend = providers.gradleProperty("MURPH_BACKEND_BASE_URL_PROD")
    .orElse("https://www.withmurph.ai")
val releaseRuntimeClasspath = providers.provider {
    configurations.getByName("releaseRuntimeClasspath")
}
val releaseDependencyMetadata = layout.buildDirectory.file(
    "reports/licenses/release-runtime-metadata.json",
)
val thirdPartyNotices = layout.buildDirectory.file(
    "reports/licenses/THIRD_PARTY_NOTICES.txt",
)
val releaseMergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
)
val debugMergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
)
val debugBundle = layout.buildDirectory.file("outputs/bundle/debug/app-debug.aab")
val bundletoolCli = configurations.create("bundletoolCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(bundletoolCli.name, "com.android.tools.build:bundletool:1.18.0")

fun playPublicConfigurationSha256(
    appId: String,
    appClientId: String,
    backend: String,
): String = MessageDigest.getInstance("SHA-256")
    .digest("$appId\u0000$appClientId\u0000$backend".toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

fun readPomLicenses(pomFile: File): List<Map<String, String>> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(pomFile)
    val nodes = document.getElementsByTagName("license")
    return buildList {
        for (index in 0 until nodes.length) {
            val children = nodes.item(index).childNodes
            var name = ""
            var url = ""
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex)
                when (child.nodeName) {
                    "name" -> name = child.textContent.trim()
                    "url" -> url = child.textContent.trim()
                }
            }
            if (name.isNotBlank() || url.isNotBlank()) {
                add(mapOf("name" to name, "url" to url))
            }
        }
    }.distinct()
}

val writeReleaseDependencyLicenseMetadata by tasks.registering {
    description = "Resolves release dependencies and records their published POM licenses."
    group = "verification"
    inputs.files(releaseRuntimeClasspath)
    outputs.file(releaseDependencyMetadata)

    doLast {
        val componentIds = releaseRuntimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .toSet()
        val pomResult = dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()
        val pomLicenses = pomResult.resolvedComponents.associate { component ->
            val id = component.id as ModuleComponentIdentifier
            val coordinate = "${id.group}:${id.module}:${id.version}"
            val pom = component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
                .singleOrNull()
            coordinate to pom?.let { readPomLicenses(it.file) }.orEmpty()
        }
        val unresolved = pomResult.components
            .filterIsInstance<UnresolvedComponentResult>()
            .mapNotNull { component ->
                val id = component.id as? ModuleComponentIdentifier
                id?.let { "${it.group}:${it.module}:${it.version}" }
            }
            .toSet()
        val metadata = componentIds
            .map { id ->
                val coordinate = "${id.group}:${id.module}:${id.version}"
                mapOf(
                    "coordinate" to coordinate,
                    "licenses" to pomLicenses[coordinate].orEmpty(),
                    "pomResolved" to (coordinate !in unresolved && coordinate in pomLicenses),
                )
            }
            .sortedBy { it["coordinate"].toString() }
        val output = releaseDependencyMetadata.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(metadata)) + "\n")
    }
}

val checkThirdPartyLicenses by tasks.registering(Exec::class) {
    description = "Rejects unknown or prohibited release dependency licenses and writes notices."
    group = "verification"
    dependsOn(writeReleaseDependencyLicenseMetadata)
    inputs.file(rootProject.file("config/third-party-license-policy.json"))
    inputs.file(rootProject.file("scripts/check-third-party-licenses.mjs"))
    inputs.file(releaseDependencyMetadata)
    outputs.file(thirdPartyNotices)
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "scripts/check-third-party-licenses.mjs",
        "--metadata",
        "app/build/reports/licenses/release-runtime-metadata.json",
        "--policy",
        "config/third-party-license-policy.json",
        "--notices",
        "app/build/reports/licenses/THIRD_PARTY_NOTICES.txt",
    )
}

val checkReleaseThirdPartyLicenses by tasks.registering(Exec::class) {
    description = "Applies commercial-license assertions before any release build."
    group = "verification"
    dependsOn(checkThirdPartyLicenses)
    inputs.property(
        "junctionAndroidCommercialLicenseConfirmed",
        junctionAndroidCommercialLicenseConfirmed,
    )
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "scripts/check-third-party-licenses.mjs",
        "--metadata",
        "app/build/reports/licenses/release-runtime-metadata.json",
        "--policy",
        "config/third-party-license-policy.json",
        "--release",
    )
    doFirst {
        environment(
            "MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED",
            junctionAndroidCommercialLicenseConfirmed.get(),
        )
    }
}

val checkPlayReleasePacket by tasks.registering(Exec::class) {
    description = "Checks the code-derived Google Play packet against source."
    group = "verification"
    inputs.files(
        rootProject.file("play/release-facts.json"),
        rootProject.file("play/listing/en-US/title.txt"),
        rootProject.file("play/listing/en-US/short-description.txt"),
        rootProject.file("play/listing/en-US/full-description.txt"),
        rootProject.file("play/listing/en-US/release-notes-1.txt"),
        rootProject.file("play/declarations/data-safety.md"),
        rootProject.file("play/declarations/health-apps.md"),
        rootProject.file("play/declarations/contacts.md"),
        rootProject.file("play/release-checklist.md"),
        rootProject.file("scripts/check-play-release-packet.mjs"),
        project.file("build.gradle.kts"),
        project.file("src/main/AndroidManifest.xml"),
        project.file("src/main/java/ai/withmurph/companion/app/AppConfig.kt"),
        project.file(
            "src/main/java/ai/withmurph/companion/health/JunctionHealthSyncService.kt",
        ),
    )
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/check-play-release-packet.mjs")
}

val checkPlayReleaseTooling by tasks.registering(Exec::class) {
    description = "Tests the Google Play packet and dependency-license gates."
    group = "verification"
    dependsOn("bundleDebug")
    inputs.files(
        bundletoolCli,
        debugBundle,
        debugMergedManifest,
        rootProject.file("config/third-party-license-policy.json"),
        rootProject.file("scripts/check-play-release-packet.mjs"),
        rootProject.file("scripts/check-play-release-packet.test.mjs"),
        rootProject.file("scripts/check-third-party-licenses.mjs"),
        rootProject.file("scripts/check-third-party-licenses.test.mjs"),
    )
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "--test",
        "scripts/check-play-release-packet.test.mjs",
        "scripts/check-third-party-licenses.test.mjs",
    )
    doFirst {
        environment("MURPH_BUNDLETOOL_CLASSPATH", bundletoolCli.asPath)
        environment("MURPH_BUNDLETOOL_TEST_BUNDLE", debugBundle.get().asFile.absolutePath)
        environment(
            "MURPH_BUNDLETOOL_TEST_MANIFEST",
            debugMergedManifest.get().asFile.absolutePath,
        )
    }
}

val checkPlayReleaseMergedManifest by tasks.registering(Exec::class) {
    description = "Checks Play declarations against the exact merged release manifest."
    group = "verification"
    dependsOn("processReleaseMainManifest", checkPlayReleasePacket)
    inputs.file(releaseMergedManifest)
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "scripts/check-play-release-packet.mjs",
        "--merged-manifest",
        "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
}

fun Exec.configurePlayArtifactEnvironment(assertionsRequired: Boolean) {
    inputs.files(bundletoolCli)
    doFirst {
        if (
            assertionsRequired &&
            providers.environmentVariable("MURPH_PLAY_OPERATOR_ASSERTIONS_FILE").orNull.isNullOrBlank()
        ) {
            throw GradleException(
                "MURPH_PLAY_OPERATOR_ASSERTIONS_FILE must point to the ignored exact-candidate assertions file.",
            )
        }
        if (providers.environmentVariable("MURPH_PLAY_RELEASE_ARTIFACT").orNull.isNullOrBlank()) {
            throw GradleException(
                "MURPH_PLAY_RELEASE_ARTIFACT must point to the exact release artifact intended for upload.",
            )
        }

        val publicIds = listOf(playPrivyAppId.get(), playPrivyAppClientId.get())
        if (publicIds.any { value ->
                value.isBlank() || value.contains("placeholder", ignoreCase = true)
            }
        ) {
            throw GradleException(
                "Play submission requires the registered production Privy public configuration.",
            )
        }

        val backend = runCatching { java.net.URI(playProductionBackend.get()) }.getOrNull()
        val host = backend?.host?.lowercase().orEmpty()
        if (
            backend?.scheme != "https" ||
            host.isBlank() ||
            host == "localhost" ||
            host == "127.0.0.1" ||
            host.endsWith(".invalid") ||
            host.endsWith(".localhost") ||
            host.endsWith(".test")
        ) {
            throw GradleException(
                "Play submission requires the intended production HTTPS backend.",
            )
        }
        environment("MURPH_BUNDLETOOL_CLASSPATH", bundletoolCli.asPath)
        environment(
            "MURPH_PLAY_EXPECTED_CONFIGURATION_SHA256",
            playPublicConfigurationSha256(
                playPrivyAppId.get(),
                playPrivyAppClientId.get(),
                playProductionBackend.get(),
            ),
        )
    }
}

val printPlaySubmissionEvidence by tasks.registering(Exec::class) {
    description = "Prints safe hashes from the exact signed AAB intended for Play upload."
    group = "verification"
    dependsOn(checkPlayReleaseMergedManifest, checkReleaseThirdPartyLicenses)
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "scripts/check-play-release-packet.mjs",
        "--print-evidence-hashes",
        "--merged-manifest",
        "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
    configurePlayArtifactEnvironment(assertionsRequired = false)
}

val checkPlaySubmissionReadiness by tasks.registering(Exec::class) {
    description = "Fail-closed gate for the exact signed artifact before any Play upload."
    group = "verification"
    dependsOn(checkPlayReleaseMergedManifest, checkReleaseThirdPartyLicenses)
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "scripts/check-play-release-packet.mjs",
        "--submission",
        "--merged-manifest",
        "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
    configurePlayArtifactEnvironment(assertionsRequired = true)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkThirdPartyLicenses, checkPlayReleasePacket)
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(checkReleaseThirdPartyLicenses)
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkPlayReleasePacket, checkPlayReleaseTooling, checkThirdPartyLicenses)
}
