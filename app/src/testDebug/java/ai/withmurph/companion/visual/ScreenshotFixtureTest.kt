package ai.withmurph.companion.visual

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.FailureSupplementalActions
import ai.withmurph.companion.app.InitialOnboardingStage
import ai.withmurph.companion.app.LaunchConsentRecoveryPhase
import ai.withmurph.companion.core.AddressBookSharingState
import ai.withmurph.companion.core.HealthConnectAvailability
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class ScreenshotFixtureTest {
    @Test
    fun scenarioParserAcceptsEveryDeclaredScenario() {
        ScreenshotScenario.entries.forEach { scenario ->
            assertSame(scenario, ScreenshotScenario.from(scenario.name.lowercase()))
        }
    }

    @Test
    fun scenarioParserFailsClosedForMissingOrUnknownValues() {
        listOf(null, "", "not-a-screenshot-scenario").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenshotScenario.from(value)
            }
        }
    }

    @Test
    fun otpResendingFixtureRetainsTheCodeStageWhileTheRequestIsInFlight() {
        val state = ScreenshotScenario.OtpResending.loginState()

        assertTrue(state.codeSent)
        assertTrue(state.isInFlight)
        assertEquals("123456", state.code)
        assertEquals("+15555550100", state.normalizedDestination)
        assertFalse(state.canConfirmCode)
    }

    @Test
    fun timestampBearingFixturesUseTheCaptureTimeAsTheirObservationBoundary() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val timestampScenarios = listOf(
            ScreenshotScenario.Synced,
            ScreenshotScenario.SavedStatus,
            ScreenshotScenario.PermissionVerificationFailed,
            ScreenshotScenario.Delayed,
            ScreenshotScenario.Attention,
            ScreenshotScenario.FriendlyNames,
        )

        timestampScenarios.forEach { scenario ->
            assertEquals(scenario.name, now, scenario.appState(now).healthStatusObservedAt)
        }
        assertEquals(
            now.minus(Duration.ofHours(48)),
            (ScreenshotScenario.Delayed.appState(now).healthSync as HealthSyncState.Delayed)
                .lastDataReceivedAt,
        )
        assertEquals(
            now.minus(Duration.ofHours(96)),
            (ScreenshotScenario.Attention.appState(now).healthSync as HealthSyncState.NeedsAttention)
                .lastDataReceivedAt,
        )
    }

    @Test
    fun onboardingEdgeScenariosMapToDeterministicProductionStates() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val loading = ScreenshotScenario.from("onboardingLoading").appState(now)
        val error = ScreenshotScenario.from("onboardingError").appState(now)
        val contactError = ScreenshotScenario.from("onboardingContactError").appState(now)
        val saving = ScreenshotScenario.from("onboardingSaving").appState(now)

        assertEquals(AppPhase.Launching, loading.phase)
        assertNull(loading.initialOnboarding)
        assertFalse(ScreenshotScenario.OnboardingLoading.isInitialOnboarding())

        assertEquals(AppPhase.Ready, error.phase)
        assertEquals(InitialOnboardingStage.Tone, error.initialOnboardingStage)
        assertNotNull(error.initialOnboarding)
        assertFalse(error.isInitialOnboardingSaving)
        assertTrue(ScreenshotScenario.OnboardingError.isInitialOnboarding())
        assertEquals(
            "Couldn't save. Your choices are still here.",
            error.initialOnboardingMessage,
        )

        assertEquals(InitialOnboardingStage.Contact, contactError.initialOnboardingStage)
        assertEquals(
            "We couldn't open the contact card. Check your connection and try again.",
            contactError.initialOnboardingMessage,
        )

        assertEquals(AppPhase.Ready, saving.phase)
        assertEquals(InitialOnboardingStage.Tone, saving.initialOnboardingStage)
        assertNotNull(saving.initialOnboarding)
        assertTrue(saving.isInitialOnboardingSaving)
        assertTrue(ScreenshotScenario.OnboardingSaving.isInitialOnboarding())
        assertNull(saving.initialOnboardingMessage)
    }

    @Test
    fun recoveryEdgeScenariosMapToDeterministicProductionStates() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val banner = ScreenshotScenario.ConsentBanner.appState(now)
        val unavailable = ScreenshotScenario.DisconnectedUnavailable.appState(now)

        assertEquals(LaunchConsentRecoveryPhase.Required, banner.launchConsentRecovery?.phase)
        assertFalse(requireNotNull(banner.launchConsentRecovery).showSheet)
        assertEquals(
            HealthConnectAvailability.TemporarilyUnavailable,
            unavailable.healthAvailability,
        )
        assertEquals(HealthSyncState.NotConnected, unavailable.healthSync)

        val permissionFailure = ScreenshotScenario.PermissionVerificationFailed.appState(now)
        assertTrue(permissionFailure.healthSync is HealthSyncState.Synced)
        assertTrue(permissionFailure.healthStatusIsStale)
        assertTrue(permissionFailure.authVerifiedOnline)
        assertEquals(
            "Murph couldn't verify current Health Connect permissions. Saved status is still shown.",
            permissionFailure.healthMessage,
        )

        val onboardingBanner = ScreenshotScenario.OnboardingConsentBanner.appState(now)
        assertNotNull(onboardingBanner.initialOnboarding)
        assertEquals(
            LaunchConsentRecoveryPhase.Required,
            onboardingBanner.launchConsentRecovery?.phase,
        )
        assertFalse(requireNotNull(onboardingBanner.launchConsentRecovery).showSheet)

        val onboardingReconnect = ScreenshotScenario.OnboardingReconnectRequired.appState(now)
        assertNotNull(onboardingReconnect.initialOnboarding)
        assertTrue(onboardingReconnect.healthReconnectRequired)
        assertEquals(HealthSyncState.NotConnected, onboardingReconnect.healthSync)
    }

    @Test
    fun rejectedAdmissionFixtureUsesSupportOnlyRecovery() {
        val phase = ScreenshotScenario.Failure
            .appState(Instant.parse("2026-08-06T12:00:00Z"))
            .phase as AppPhase.Failed

        assertEquals("This sign-in doesn't have access to the Murph companion app.", phase.message)
        assertFalse(phase.canRetry)
        assertTrue(phase.canSignOut)
        assertEquals("Try a different sign-in", phase.signOutLabel)
        assertEquals(FailureSupplementalActions.Support, phase.supplementalActions)
    }

    @Test
    fun friendlyNamesReconnectMapsToThePersistedProductionState() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val state = ScreenshotScenario.FriendlyNamesReconnectRequired.appState(now)

        assertEquals(AppPhase.Ready, state.phase)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
        assertTrue(state.healthReconnectRequired)
        assertEquals(HealthSyncState.NotConnected, state.healthSync)
        assertNull(state.healthStatusObservedAt)
        assertEquals(0, state.grantedResourceCount)
        assertEquals(11, state.totalResourceCount)
        assertEquals(
            "Health Connect needs to reconnect before syncing can resume.",
            state.healthMessage,
        )
        assertEquals(
            AddressBookSharingState.Server(
                enabled = false,
                storedContactCount = 0,
                canWrite = true,
                ownedByInstallation = false,
            ),
            state.addressBookSharing,
        )
    }

    @Test
    fun onboardingFixtureMatchesTheCanonicalLiveCatalog() {
        val onboarding = screenshotOnboarding()
        val catalog = requireNotNull(onboarding.catalog)
        val contactCard = requireNotNull(onboarding.contactCard)

        assertEquals(6, catalog.personas.size)
        assertEquals(22, catalog.voices.size)
        assertEquals(2, catalog.tones.size)
        assertEquals(12, contactCard.avatars.size)
        assertEquals(ExpectedPersonaIds, catalog.personas.map { it.id })
        assertEquals(ExpectedVoiceIds, catalog.voices.map { it.id })
        assertEquals(listOf("formal", "casual"), catalog.tones.map { it.id })
        assertEquals(ExpectedAvatarIds, contactCard.avatars.map { it.id })
        assertEquals("classic", contactCard.defaultAvatarId)

        val voiceIds = catalog.voices.map { it.id }.toSet()
        val recommendations = catalog.personas.associate { it.id to it.recommendedVoiceIds }
        assertEquals(ExpectedRecommendations, recommendations)
        catalog.personas.forEach { persona ->
            assertEquals(5, persona.recommendedVoiceIds.size)
            assertEquals(5, persona.recommendedVoiceIds.toSet().size)
            assertTrue(persona.recommendedVoiceIds.all(voiceIds::contains))
        }

        assertEquals(
            canonicalDriftMessage("onboarding catalog"),
            ExpectedOnboardingCatalogSha256,
            canonicalOnboardingCatalog().sha256(),
        )
    }

    @Test
    fun consentFixtureMatchesEveryCanonicalDocumentAndScope() {
        val status = consentStatus()
        val expectedScopeDocuments = listOf(
            listOf(
                "terms-of-service",
                "privacy-policy",
                "health-ai-safety-disclosure",
            ),
            listOf("consumer-health-data-notice"),
        )

        assertFalse(status.launchGranted)
        assertEquals(ExpectedConsentDocumentIds, status.documents.map { it.id })
        assertEquals(
            listOf(LaunchConsentScope.Legal, LaunchConsentScope.HealthData),
            status.launchScopes.map { it.scope },
        )
        assertEquals(
            expectedScopeDocuments,
            status.launchScopes.map { scope -> scope.documents.map { it.id } },
        )
        assertEquals(
            expectedScopeDocuments,
            status.launchScopes.map { scope -> scope.missingDocuments.map { it.id } },
        )
        assertTrue(status.launchScopes.none { it.granted })

        assertEquals(
            canonicalDriftMessage("launch consent"),
            ExpectedConsentSha256,
            canonicalConsentStatus().sha256(),
        )
    }
}

private fun canonicalOnboardingCatalog(): String {
    val onboarding = screenshotOnboarding()
    val catalog = requireNotNull(onboarding.catalog)
    val contactCard = requireNotNull(onboarding.contactCard)
    return CanonicalFields().apply {
        field("murph-companion-onboarding-catalog-v1")
        field(CanonicalMurphSourceRevision)
        field(catalog.personas.size)
        catalog.personas.forEach { persona ->
            field(persona.id)
            field(persona.label)
            field(persona.description)
            field(persona.supportDescription)
            field(persona.defaultTone)
            field(persona.defaultVoiceId)
            fields(persona.recommendedVoiceIds)
        }
        field(catalog.voices.size)
        catalog.voices.forEach { voice ->
            field(voice.id)
            field(voice.label)
            field(voice.description)
            field(voice.previewUrl)
        }
        field(catalog.tones.size)
        catalog.tones.forEach { tone ->
            field(tone.id)
            field(tone.label)
            field(tone.sample)
        }
        field(contactCard.defaultAvatarId)
        field(contactCard.avatars.size)
        contactCard.avatars.forEach { avatar ->
            field(avatar.id)
            field(avatar.kind.wireValue)
            field(avatar.label)
            field(avatar.imageUrl)
        }
    }.serialize()
}

private fun canonicalConsentStatus(): String {
    val status = consentStatus()
    return CanonicalFields().apply {
        field("murph-launch-consent-status-v1")
        field(CanonicalMurphSourceRevision)
        field(status.launchGranted)
        documents(status.documents)
        field(status.launchScopes.size)
        status.launchScopes.forEach { scope ->
            field(scope.scope.wireValue)
            field(scope.granted)
            documents(scope.documents)
            documents(scope.missingDocuments)
        }
    }.serialize()
}

private class CanonicalFields {
    private val output = StringBuilder()

    fun field(value: Boolean) = field(value.toString())

    fun field(value: Int) = field(value.toString())

    fun field(value: String?) {
        if (value == null) {
            output.append("-1:\n")
            return
        }
        output.append(value.toByteArray(UTF_8).size)
            .append(':')
            .append(value)
            .append('\n')
    }

    fun fields(values: List<String>) {
        field(values.size)
        values.forEach(::field)
    }

    fun documents(documents: List<LaunchConsentDocument>) {
        field(documents.size)
        documents.forEach { document ->
            field(document.id)
            field(document.title)
            field(document.version)
            field(document.href)
            field(document.pdfHref)
        }
    }

    fun serialize(): String = output.toString()
}

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HexDigits[value ushr 4])
            append(HexDigits[value and 0x0f])
        }
    }
}

private fun canonicalDriftMessage(fixture: String) =
    "The $fixture fixture drifted from canonical Murph source revision " +
        "$CanonicalMurphSourceRevision. Compare the authoritative source, then update both the " +
        "revision and SHA-256 intentionally."

private val ExpectedPersonaIds = listOf(
    "classic",
    "navy-seal",
    "stoic-philosopher",
    "scientist",
    "hype-coach",
    "straight-talking-friend",
)

private val ExpectedVoiceIds = listOf(
    "upbeat",
    "classic",
    "drill-sergeant",
    "grandpa",
    "country",
    "jamaican",
    "radio-host",
    "deep-calm",
    "warm",
    "husky",
    "storyteller",
    "british-warm",
    "late-night",
    "easygoing",
    "northern",
    "football-announcer",
    "sweet",
    "mysterious",
    "narrator",
    "expressive",
    "bubbly",
    "smooth",
)

private val ExpectedAvatarIds = listOf(
    "hooded",
    "classic",
    "rancher",
    "referee",
    "gremlin",
    "disco",
    "beanie",
    "headphones",
    "sleepy",
    "logo-dark",
    "logo-light",
    "none",
)

private val ExpectedRecommendations = mapOf(
    "classic" to listOf("upbeat", "warm", "deep-calm", "classic", "expressive"),
    "navy-seal" to listOf(
        "drill-sergeant",
        "husky",
        "country",
        "football-announcer",
        "classic",
    ),
    "stoic-philosopher" to listOf(
        "deep-calm",
        "storyteller",
        "narrator",
        "late-night",
        "smooth",
    ),
    "scientist" to listOf(
        "radio-host",
        "narrator",
        "classic",
        "storyteller",
        "british-warm",
    ),
    "hype-coach" to listOf(
        "football-announcer",
        "upbeat",
        "expressive",
        "bubbly",
        "husky",
    ),
    "straight-talking-friend" to listOf(
        "classic",
        "easygoing",
        "husky",
        "warm",
        "country",
    ),
)

private val ExpectedConsentDocumentIds = listOf(
    "terms-of-service",
    "privacy-policy",
    "consumer-health-data-notice",
    "health-ai-safety-disclosure",
)

private const val CanonicalMurphSourceRevision =
    "7cde19c372cd20c45a338bd17ba84dbb500cc70c"
private const val ExpectedOnboardingCatalogSha256 =
    "b0ff27eb5af28874d4dfc2f34f113e606701cd00ebcd1a64ac8a6e23068d904b"
private const val ExpectedConsentSha256 =
    "6fa1de8ef211539ec2a5f6279db6fbcdde5068e6f600a89bef1b555b7280c9ff"
private const val HexDigits = "0123456789abcdef"
