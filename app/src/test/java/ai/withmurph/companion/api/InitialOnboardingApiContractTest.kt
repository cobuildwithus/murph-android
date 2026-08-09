package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.InitialOnboardingCompletionAction
import ai.withmurph.companion.core.InitialOnboardingCompletionRequest
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

class InitialOnboardingApiContractTest {
    @Test
    fun parsesPendingCatalogCompositePreferenceAndOptionalContactCard() {
        val onboarding = parse(
            preferences = mapOf(
                "persona" to "classic-with-coach",
                "tone" to "formal",
                "voice" to "murph",
            ),
        )

        assertEquals(InitialOnboardingStatus.Pending, onboarding.status)
        assertEquals("classic-with-coach", onboarding.preferences.persona)
        assertEquals("https://www.withmurph.ai/audio/murph.mp3", onboarding.catalog?.voices?.single()?.previewUrl)
        assertEquals("classic", onboarding.contactCard?.defaultAvatarId)
        assertEquals("sms:+15555550123?body=hello", onboarding.contactAction?.href)
    }

    @Test
    fun parsesCompletedProjectionWithoutCatalog() {
        val onboarding = InitialOnboardingApiContract.parse(
            schema = SCHEMA,
            status = "completed",
            completedNow = false,
            preferences = emptyPreferences(),
            catalog = null,
            contactCard = null,
            contactAction = null,
            backendOrigin = ORIGIN,
        )

        assertEquals(InitialOnboardingStatus.Completed, onboarding.status)
        assertEquals(false, onboarding.completedNow)
        assertNull(onboarding.catalog)
    }

    @Test
    fun rejectsInvalidCatalogReferencesAndUnsafeUrls() {
        listOf<() -> Unit>(
            { parse(schema = "future") },
            { parse(status = "future") },
            { parse(completedNow = true) },
            { parse(preferences = mapOf("persona" to "classic-with-unknown", "tone" to null, "voice" to null)) },
            { parse(catalog = catalog(personaDefaultVoice = "missing")) },
            { parse(catalog = catalog(previewUrl = "https://example.com/audio.mp3")) },
            { parse(contactCard = contactCard(defaultAvatarId = "missing")) },
            { parse(contactAction = contactAction(kind = "text", href = "https://example.com")) },
            { parse(contactAction = contactAction(kind = "telegram", href = "https://example.com/bot")) },
            {
                InitialOnboardingApiContract.parse(
                    schema = SCHEMA,
                    status = "completed",
                    completedNow = true,
                    preferences = emptyPreferences(),
                    catalog = catalog(),
                    contactCard = null,
                    contactAction = null,
                    backendOrigin = ORIGIN,
                )
            },
        ).forEach(::assertInvalidResponse)
    }

    @Test
    fun buildsExactSaveAndSkipBodies() {
        val save = InitialOnboardingApiContract.completionBody(
            InitialOnboardingCompletionRequest(
                action = InitialOnboardingCompletionAction.Save,
                preferences = InitialOnboardingPreferences(
                    persona = "classic-with-coach",
                    tone = "casual",
                    voice = "murph",
                ),
            ),
        )
        assertEquals("save", save["action"])
        assertEquals(
            "classic-with-coach",
            (save["preferences"] as Map<*, *>)["persona"],
        )

        val skip = InitialOnboardingApiContract.completionBody(
            InitialOnboardingCompletionRequest(
                action = InitialOnboardingCompletionAction.Skip,
                preferences = null,
            ),
        )
        assertEquals("skip", skip["action"])
        assertEquals(false, skip.containsKey("preferences"))
    }

    private fun parse(
        schema: Any? = SCHEMA,
        status: Any? = "pending",
        completedNow: Any? = null,
        preferences: Map<String, Any?> = emptyPreferences(),
        catalog: Map<String, Any?>? = catalog(),
        contactCard: Map<String, Any?>? = contactCard(),
        contactAction: Map<String, Any?>? = contactAction(),
    ) = InitialOnboardingApiContract.parse(
        schema = schema,
        status = status,
        completedNow = completedNow,
        preferences = preferences,
        catalog = catalog,
        contactCard = contactCard,
        contactAction = contactAction,
        backendOrigin = ORIGIN,
    )

    private fun emptyPreferences(): Map<String, Any?> = mapOf(
        "persona" to null,
        "tone" to null,
        "voice" to null,
    )

    private fun catalog(
        personaDefaultVoice: String = "murph",
        previewUrl: String = "/audio/murph.mp3",
    ): Map<String, Any?> = mapOf(
        "personas" to listOf(
            persona("classic", personaDefaultVoice),
            persona("coach", "murph"),
        ),
        "voices" to listOf(
            mapOf(
                "id" to "murph",
                "label" to "Murph",
                "description" to "Warm and direct",
                "previewURL" to previewUrl,
            ),
        ),
        "tones" to listOf(
            mapOf(
                "id" to "formal",
                "label" to "Formal",
                "sample" to "Want to work on sleep first?",
            ),
            mapOf(
                "id" to "casual",
                "label" to "Casual",
                "sample" to "wanna fix sleep first?",
            ),
        ),
    )

    private fun persona(id: String, defaultVoice: String): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to id.replaceFirstChar(Char::uppercase),
        "description" to "Primary description",
        "supportDescription" to "Supporting description",
        "defaultTone" to "formal",
        "defaultVoiceId" to defaultVoice,
        "recommendedVoiceIds" to listOf("murph"),
    )

    private fun contactCard(defaultAvatarId: String = "classic"): Map<String, Any?> = mapOf(
        "avatars" to listOf(
            mapOf(
                "id" to "classic",
                "kind" to "logo",
                "label" to "Classic",
                "imageURL" to "/images/murph.png",
            ),
        ),
        "defaultAvatarId" to defaultAvatarId,
    )

    private fun contactAction(
        kind: String = "text",
        href: String = "sms:+15555550123?body=hello",
    ): Map<String, Any?> = mapOf(
        "href" to href,
        "kind" to kind,
        "label" to "Text Murph",
    )

    private fun assertInvalidResponse(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid response")
        } catch (_: CompanionApiException.InvalidResponse) {
            // Expected.
        }
    }

    private companion object {
        const val SCHEMA = "murph.companion.initial-onboarding.v1"
        val ORIGIN: URI = URI("https://www.withmurph.ai")
    }
}
