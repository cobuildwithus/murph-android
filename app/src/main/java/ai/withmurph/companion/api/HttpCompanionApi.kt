package ai.withmurph.companion.api

import ai.withmurph.companion.core.AddressBookDeletionRequest
import ai.withmurph.companion.core.AddressBookReplacementRequest
import ai.withmurph.companion.core.AddressBookServerStatus
import ai.withmurph.companion.core.AddressBookWriteCapability
import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.InitialOnboarding
import ai.withmurph.companion.core.InitialOnboardingCatalog
import ai.withmurph.companion.core.InitialOnboardingCompletionRequest
import ai.withmurph.companion.core.InitialOnboardingContactAction
import ai.withmurph.companion.core.InitialOnboardingContactAvatar
import ai.withmurph.companion.core.InitialOnboardingContactAvatarKind
import ai.withmurph.companion.core.InitialOnboardingContactCard
import ai.withmurph.companion.core.InitialOnboardingContactCardHandoff
import ai.withmurph.companion.core.InitialOnboardingContactCardRequest
import ai.withmurph.companion.core.InitialOnboardingContactKind
import ai.withmurph.companion.core.InitialOnboardingPersona
import ai.withmurph.companion.core.InitialOnboardingPreferences
import ai.withmurph.companion.core.InitialOnboardingStatus
import ai.withmurph.companion.core.InitialOnboardingTone
import ai.withmurph.companion.core.InitialOnboardingVoice
import ai.withmurph.companion.core.LaunchConsentAcceptanceRequest
import ai.withmurph.companion.core.LaunchConsentDocument
import ai.withmurph.companion.core.LaunchConsentScope
import ai.withmurph.companion.core.LaunchConsentScopeStatus
import ai.withmurph.companion.core.LaunchConsentStatus
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class HttpCompanionApi(
    baseUrl: String,
    private val identityToken: suspend () -> String,
    private val identityTokenForMember: suspend (String) -> String = { identityToken() },
) : CompanionApi {
    private val baseUri = URI(baseUrl.trimEnd('/')).also { uri ->
        require(uri.scheme == "https") { "Murph backend URL must use HTTPS" }
        require(uri.host != null) { "Murph backend URL must have a host" }
    }

    override suspend fun createJunctionSignInToken(
        request: SignInTokenRequest,
    ): SignInTokenResponse = createJunctionSignInToken(request, identityToken)

    override suspend fun createJunctionSignInToken(
        memberKey: String,
        request: SignInTokenRequest,
    ): SignInTokenResponse = createJunctionSignInToken(
        request,
        authenticate = { identityTokenForMember(memberKey) },
    )

    private suspend fun createJunctionSignInToken(
        request: SignInTokenRequest,
        authenticate: suspend () -> String,
    ): SignInTokenResponse {
        val sdkVersionsJson = JSONObject().apply {
            request.sdkVersions.forEach(::put)
        }
        val body = JSONObject().apply {
            put("platform", request.platform.wireValue)
            put("appInstallationId", request.appInstallationId)
            put("appVersion", request.appVersion)
            put("connectionIntent", request.connectionIntent.wireValue)
            put("sdkVersions", sdkVersionsJson)
            put("timeZone", request.timeZone)
        }
        val response = requestJson(
            method = "POST",
            path = "/api/device-sync/companion/sign-in-token",
            body = body,
            authenticate = authenticate,
        )
        val token = response.optString("signInToken").takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
        val environment = response.optString("environment").takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
        return SignInTokenResponse(token, environment)
    }

    override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus {
        return fetchSyncStatus(sourceProviderSlug, identityToken)
    }

    override suspend fun fetchSyncStatus(
        memberKey: String,
        sourceProviderSlug: String,
    ): CompanionSyncStatus = fetchSyncStatus(
        sourceProviderSlug,
        authenticate = { identityTokenForMember(memberKey) },
    )

    private suspend fun fetchSyncStatus(
        sourceProviderSlug: String,
        authenticate: suspend () -> String,
    ): CompanionSyncStatus {
        val encodedSource = URLEncoder.encode(sourceProviderSlug, StandardCharsets.UTF_8.name())
        val response = requestJson(
            method = "GET",
            path = "/api/device-sync/companion/status?sourceProviderSlug=$encodedSource",
            authenticate = authenticate,
        )
        val lastReceivedAt = response.optNullableString("lastDataReceivedAt")?.parseInstant()
        val resourcesObject = response.optJSONObject("resources") ?: JSONObject()
        val resources = buildMap {
            resourcesObject.keys().forEach { key ->
                val resource = resourcesObject.optJSONObject(key) ?: return@forEach
                put(
                    key,
                    CompanionSyncStatus.ResourceStatus(
                        lastReceivedAt = resource.optNullableString("lastReceivedAt")?.parseInstant(),
                    ),
                )
            }
        }
        return CompanionSyncStatus(lastReceivedAt, resources)
    }

    override suspend fun fetchInitialOnboarding(memberKey: String): InitialOnboarding =
        InitialOnboardingApiJson.parse(
            requestJson(
                method = "GET",
                path = INITIAL_ONBOARDING_PATH,
                authenticate = { identityTokenForMember(memberKey) },
            ),
            baseUri,
        )

    override suspend fun completeInitialOnboarding(
        memberKey: String,
        request: InitialOnboardingCompletionRequest,
    ): InitialOnboarding = InitialOnboardingApiJson.parse(
        requestJson(
            method = "POST",
            path = INITIAL_ONBOARDING_PATH,
            body = InitialOnboardingApiJson.completionBody(request),
            authenticate = { identityTokenForMember(memberKey) },
        ),
        baseUri,
    )

    override suspend fun prepareInitialOnboardingContactCard(
        memberKey: String,
        request: InitialOnboardingContactCardRequest,
    ): InitialOnboardingContactCardHandoff = InitialOnboardingApiJson.parseContactCardHandoff(
        requestJson(
            method = "POST",
            path = INITIAL_ONBOARDING_CONTACT_CARD_PATH,
            body = JSONObject(mapOf("avatarId" to request.avatarId)),
            authenticate = { identityTokenForMember(memberKey) },
        ),
        baseUri,
    )

    override suspend fun fetchLaunchConsentStatus(memberKey: String): LaunchConsentStatus =
        LaunchConsentApiJson.parseStatus(
            requestJson(
                method = "GET",
                path = LAUNCH_CONSENT_PATH,
                authenticate = { identityTokenForMember(memberKey) },
            ),
            baseUri,
        )

    override suspend fun acceptLaunchConsent(
        memberKey: String,
        request: LaunchConsentAcceptanceRequest,
    ): LaunchConsentStatus =
        LaunchConsentApiJson.parseStatus(
            requestJson(
                method = "POST",
                path = LAUNCH_CONSENT_PATH,
                body = LaunchConsentApiJson.acceptanceBody(request),
                authenticate = { identityTokenForMember(memberKey) },
            ),
            baseUri,
        )

    override suspend fun fetchAddressBookStatus(memberKey: String): AddressBookServerStatus =
        AddressBookApiJson.parseStatus(
            requestJson(
                method = "GET",
                path = ADDRESS_BOOK_PATH,
                authenticate = { identityTokenForMember(memberKey) },
            ),
        )

    override suspend fun replaceAddressBook(
        memberKey: String,
        request: AddressBookReplacementRequest,
    ): AddressBookServerStatus {
        val status = AddressBookApiJson.parseStatus(
            requestJson(
                method = "PUT",
                path = ADDRESS_BOOK_PATH,
                body = AddressBookApiJson.replacementBody(request),
                authenticate = { identityTokenForMember(memberKey) },
                revisionConflict = true,
            ),
        )
        return AddressBookApiContract.validateReplacementResponse(request, status)
    }

    override suspend fun deleteAddressBook(
        memberKey: String,
        request: AddressBookDeletionRequest,
    ): AddressBookServerStatus {
        val status = AddressBookApiJson.parseStatus(
            requestJson(
                method = "DELETE",
                path = ADDRESS_BOOK_PATH,
                body = AddressBookApiJson.deletionBody(request),
                authenticate = { identityTokenForMember(memberKey) },
                revisionConflict = true,
            ),
        )
        return AddressBookApiContract.validateDeletionResponse(request, status)
    }

    private suspend fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticate: suspend () -> String = identityToken,
        revisionConflict: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = try {
            authenticate()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw CompanionApiException.Unauthorized
        }

        val connection = (baseUri.resolve(path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val text = readResponseBody(connection, status)
            if (status !in 200..299) {
                throw mapCompanionApiError(status, text, revisionConflict)
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (error: CompanionApiException) {
            throw error
        } catch (_: IOException) {
            throw CompanionApiException.Network
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
            reader.readText().take(MAX_RESPONSE_CHARS)
        }.orEmpty()
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf(String::isNotBlank)
    }

    private fun String.parseInstant(): Instant = try {
        Instant.parse(this)
    } catch (_: Exception) {
        throw CompanionApiException.InvalidResponse
    }

    private companion object {
        const val ADDRESS_BOOK_PATH = "/api/device-sync/companion/address-book"
        const val INITIAL_ONBOARDING_PATH = "/api/device-sync/companion/initial-onboarding"
        const val INITIAL_ONBOARDING_CONTACT_CARD_PATH =
            "/api/device-sync/companion/initial-onboarding/contact-card"
        const val LAUNCH_CONSENT_PATH = "/api/device-sync/companion/legal-consent"
        const val MAX_RESPONSE_CHARS = 128 * 1024
    }
}

internal object InitialOnboardingApiJson {
    fun parse(json: JSONObject, backendOrigin: URI): InitialOnboarding =
        InitialOnboardingApiContract.parse(
            schema = json.strictValue("schema"),
            status = json.strictValue("status"),
            completedNow = json.optionalValue("completedNow"),
            preferences = json.strictObject("preferences").toValueMap(),
            catalog = json.optionalObject("catalog")?.toCatalogMap(),
            contactCard = json.optionalObject("contactCard")?.toContactCardMap(),
            contactAction = json.optionalObject("contactAction")?.toValueMap(),
            backendOrigin = backendOrigin,
        )

    fun parseContactCardHandoff(
        json: JSONObject,
        backendOrigin: URI,
    ): InitialOnboardingContactCardHandoff = InitialOnboardingContactCardHandoff(
        url = InitialOnboardingApiContract.safeSameOriginHttpsUrl(
            json.strictValue("url"),
            backendOrigin,
        ),
    )

    fun completionBody(request: InitialOnboardingCompletionRequest): JSONObject =
        JSONObject(InitialOnboardingApiContract.completionBody(request))

    private fun JSONObject.toCatalogMap(): Map<String, Any?> = mapOf(
        "personas" to strictArray("personas").toObjectMaps(),
        "voices" to strictArray("voices").toObjectMaps(),
        "tones" to strictArray("tones").toObjectMaps(),
    )

    private fun JSONObject.toContactCardMap(): Map<String, Any?> = mapOf(
        "avatars" to strictArray("avatars").toObjectMaps(),
        "defaultAvatarId" to strictValue("defaultAvatarId"),
    )

    private fun JSONObject.strictValue(key: String): Any {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return try {
            get(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun JSONObject.strictObject(key: String): JSONObject {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return optJSONObject(key) ?: throw CompanionApiException.InvalidResponse
    }

    private fun JSONObject.optionalObject(key: String): JSONObject? {
        if (!has(key) || isNull(key)) return null
        return optJSONObject(key) ?: throw CompanionApiException.InvalidResponse
    }

    private fun JSONObject.strictArray(key: String): JSONArray {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return try {
            getJSONArray(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun JSONObject.optionalValue(key: String): Any? =
        if (!has(key) || isNull(key)) null else strictValue(key)

    private fun JSONObject.toValueMap(): Map<String, Any?> = buildMap {
        keys().forEach { key -> put(key, valueForMap(opt(key))) }
    }

    private fun JSONArray.toObjectMaps(): List<Map<String, Any?>> = buildList {
        for (index in 0 until length()) {
            add(
                (opt(index) as? JSONObject)?.toValueMap()
                    ?: throw CompanionApiException.InvalidResponse,
            )
        }
    }

    private fun valueForMap(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toValueMap()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                add(valueForMap(value.opt(index)))
            }
        }
        else -> value
    }
}

internal object InitialOnboardingApiContract {
    private const val SCHEMA = "murph.companion.initial-onboarding.v1"

    fun parse(
        schema: Any?,
        status: Any?,
        completedNow: Any?,
        preferences: Map<String, Any?>,
        catalog: Map<String, Any?>?,
        contactCard: Map<String, Any?>?,
        contactAction: Map<String, Any?>?,
        backendOrigin: URI,
    ): InitialOnboarding {
        if (strictString(schema) != SCHEMA) throw CompanionApiException.InvalidResponse
        val parsedStatus = when (strictString(status)) {
            InitialOnboardingStatus.Pending.wireValue -> InitialOnboardingStatus.Pending
            InitialOnboardingStatus.Completed.wireValue -> InitialOnboardingStatus.Completed
            else -> throw CompanionApiException.InvalidResponse
        }
        val parsedCompletedNow = completedNow?.let {
            it as? Boolean ?: throw CompanionApiException.InvalidResponse
        }
        val parsedPreferences = InitialOnboardingPreferences(
            persona = optionalString(preferences["persona"]),
            tone = optionalString(preferences["tone"]),
            voice = optionalString(preferences["voice"]),
        )
        val parsedCatalog = catalog?.let { parseCatalog(it, backendOrigin) }
        val parsedContactCard = contactCard?.let { parseContactCard(it, backendOrigin) }
        val parsedContactAction = contactAction?.let(::parseContactAction)

        when (parsedStatus) {
            InitialOnboardingStatus.Pending -> {
                if (parsedCompletedNow != null || parsedCatalog == null) {
                    throw CompanionApiException.InvalidResponse
                }
                validatePreferences(parsedPreferences, parsedCatalog)
            }
            InitialOnboardingStatus.Completed -> {
                if (parsedCatalog != null || parsedContactCard != null || parsedContactAction != null) {
                    throw CompanionApiException.InvalidResponse
                }
            }
        }
        if (parsedContactCard != null && parsedContactAction?.kind != InitialOnboardingContactKind.Text) {
            throw CompanionApiException.InvalidResponse
        }
        return InitialOnboarding(
            status = parsedStatus,
            completedNow = parsedCompletedNow,
            preferences = parsedPreferences,
            catalog = parsedCatalog,
            contactCard = parsedContactCard,
            contactAction = parsedContactAction,
        )
    }

    fun safeSameOriginHttpsUrl(rawValue: Any?, backendOrigin: URI): String =
        LaunchConsentApiContract.safeDocumentUrl(rawValue, backendOrigin)

    fun completionBody(request: InitialOnboardingCompletionRequest): Map<String, Any> =
        buildMap {
            put("action", request.action.wireValue)
            request.preferences?.let { preferences ->
                put(
                    "preferences",
                    mapOf(
                        "persona" to preferences.persona,
                        "tone" to preferences.tone,
                        "voice" to preferences.voice,
                    ),
                )
            }
        }

    private fun parseCatalog(
        catalog: Map<String, Any?>,
        backendOrigin: URI,
    ): InitialOnboardingCatalog {
        val personas = requiredMaps(catalog["personas"]).map { persona ->
            InitialOnboardingPersona(
                id = strictString(persona["id"]),
                label = strictString(persona["label"]),
                description = strictString(persona["description"]),
                supportDescription = strictString(persona["supportDescription"]),
                defaultTone = strictString(persona["defaultTone"]),
                defaultVoiceId = strictString(persona["defaultVoiceId"]),
                recommendedVoiceIds = requiredStrings(persona["recommendedVoiceIds"]),
            )
        }
        val voices = requiredMaps(catalog["voices"]).map { voice ->
            InitialOnboardingVoice(
                id = strictString(voice["id"]),
                label = strictString(voice["label"]),
                description = strictString(voice["description"]),
                previewUrl = safeSameOriginHttpsUrl(voice["previewURL"], backendOrigin),
            )
        }
        val tones = requiredMaps(catalog["tones"]).map { tone ->
            InitialOnboardingTone(
                id = strictString(tone["id"]),
                label = strictString(tone["label"]),
                sample = strictString(tone["sample"]),
            )
        }
        requireUniqueNonEmpty(personas.map { it.id })
        requireUniqueNonEmpty(voices.map { it.id })
        requireUniqueNonEmpty(tones.map { it.id })
        val voiceIds = voices.map { it.id }.toSet()
        val toneIds = tones.map { it.id }.toSet()
        if (personas.any { persona ->
                persona.defaultVoiceId !in voiceIds ||
                    persona.defaultTone !in toneIds ||
                    persona.recommendedVoiceIds.isEmpty() ||
                    persona.recommendedVoiceIds.toSet().size != persona.recommendedVoiceIds.size ||
                    persona.recommendedVoiceIds.any { it !in voiceIds }
            }
        ) {
            throw CompanionApiException.InvalidResponse
        }
        return InitialOnboardingCatalog(personas, voices, tones)
    }

    private fun parseContactCard(
        contactCard: Map<String, Any?>,
        backendOrigin: URI,
    ): InitialOnboardingContactCard {
        val avatars = requiredMaps(contactCard["avatars"]).map { avatar ->
            InitialOnboardingContactAvatar(
                id = strictString(avatar["id"]),
                kind = when (strictString(avatar["kind"])) {
                    InitialOnboardingContactAvatarKind.Headshot.wireValue ->
                        InitialOnboardingContactAvatarKind.Headshot
                    InitialOnboardingContactAvatarKind.Logo.wireValue ->
                        InitialOnboardingContactAvatarKind.Logo
                    InitialOnboardingContactAvatarKind.Blank.wireValue ->
                        InitialOnboardingContactAvatarKind.Blank
                    else -> throw CompanionApiException.InvalidResponse
                },
                label = strictString(avatar["label"]),
                imageUrl = avatar["imageURL"]?.let {
                    safeSameOriginHttpsUrl(it, backendOrigin)
                },
            )
        }
        requireUniqueNonEmpty(avatars.map { it.id })
        val defaultAvatarId = strictString(contactCard["defaultAvatarId"])
        if (avatars.none { it.id == defaultAvatarId }) {
            throw CompanionApiException.InvalidResponse
        }
        return InitialOnboardingContactCard(avatars, defaultAvatarId)
    }

    private fun parseContactAction(action: Map<String, Any?>): InitialOnboardingContactAction {
        val kind = when (strictString(action["kind"])) {
            InitialOnboardingContactKind.Text.wireValue -> InitialOnboardingContactKind.Text
            InitialOnboardingContactKind.Telegram.wireValue -> InitialOnboardingContactKind.Telegram
            InitialOnboardingContactKind.Email.wireValue -> InitialOnboardingContactKind.Email
            else -> throw CompanionApiException.InvalidResponse
        }
        val href = strictString(action["href"])
        val uri = try {
            URI(href)
        } catch (_: Exception) {
            throw CompanionApiException.InvalidResponse
        }
        val valid = when (kind) {
            InitialOnboardingContactKind.Text -> uri.scheme.equals("sms", ignoreCase = true)
            InitialOnboardingContactKind.Email -> uri.scheme.equals("mailto", ignoreCase = true)
            InitialOnboardingContactKind.Telegram ->
                uri.scheme.equals("https", ignoreCase = true) &&
                    uri.host.equals("t.me", ignoreCase = true)
        }
        if (!valid || uri.rawUserInfo != null) throw CompanionApiException.InvalidResponse
        return InitialOnboardingContactAction(
            href = uri.toASCIIString(),
            kind = kind,
            label = strictString(action["label"]),
        )
    }

    private fun validatePreferences(
        preferences: InitialOnboardingPreferences,
        catalog: InitialOnboardingCatalog,
    ) {
        val personaIds = catalog.personas.map { it.id }.toSet()
        if (
            preferences.persona?.let { id -> !isValidPersonaPreference(id, personaIds) } == true ||
            preferences.voice?.let { id -> catalog.voices.none { it.id == id } } == true ||
            preferences.tone?.let { id -> catalog.tones.none { it.id == id } } == true
        ) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun isValidPersonaPreference(value: String, personaIds: Set<String>): Boolean {
        if (value in personaIds) return true
        return personaIds.any { main ->
            val prefix = "$main-with-"
            value.startsWith(prefix) &&
                value.removePrefix(prefix).let { supporting ->
                    supporting != main && supporting in personaIds
                }
        }
    }

    private fun requireUniqueNonEmpty(ids: List<String>) {
        if (ids.isEmpty() || ids.toSet().size != ids.size) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun requiredMaps(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>)?.map { raw ->
            val source = raw as? Map<*, *> ?: throw CompanionApiException.InvalidResponse
            buildMap {
                source.forEach { (key, item) ->
                    put(key as? String ?: throw CompanionApiException.InvalidResponse, item)
                }
            }
        } ?: throw CompanionApiException.InvalidResponse

    private fun requiredStrings(value: Any?): List<String> =
        (value as? List<*>)?.map(::strictString)
            ?: throw CompanionApiException.InvalidResponse

    private fun optionalString(value: Any?): String? = when (value) {
        null -> null
        else -> strictString(value)
    }

    private fun strictString(value: Any?): String =
        (value as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
}

internal object LaunchConsentApiJson {
    fun parseStatus(json: JSONObject, backendOrigin: URI): LaunchConsentStatus =
        LaunchConsentApiContract.parseStatus(
            schema = json.strictValue("schema"),
            launchGranted = json.strictValue("launchGranted"),
            documents = json.strictArray("documents").toDocumentMaps(),
            launchScopes = json.strictArray("launchScopes").toScopeMaps(),
            scopes = json.strictArray("scopes").toCanonicalScopeMaps(),
            backendOrigin = backendOrigin,
        )

    fun acceptanceBody(request: LaunchConsentAcceptanceRequest): JSONObject =
        JSONObject(LaunchConsentApiContract.acceptanceBody(request))

    private fun JSONObject.strictValue(key: String): Any {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return try {
            get(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun JSONObject.strictArray(key: String): JSONArray {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return try {
            getJSONArray(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
    }

    private fun JSONArray.toDocumentMaps(): List<Map<String, Any?>> =
        buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: throw CompanionApiException.InvalidResponse
                add(
                    mapOf(
                        "id" to item.strictValue("id"),
                        "title" to item.strictValue("title"),
                        "version" to item.strictValue("version"),
                        "href" to item.strictValue("href"),
                        "pdfHref" to item.optionalValue("pdfHref"),
                    ),
                )
            }
        }

    private fun JSONArray.toScopeMaps(): List<Map<String, Any?>> =
        buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: throw CompanionApiException.InvalidResponse
                add(
                    mapOf(
                        "scope" to item.strictValue("scope"),
                        "granted" to item.strictValue("granted"),
                        "missingDocuments" to item.strictArray("missingDocuments").toDocumentMaps(),
                    ),
                )
            }
        }

    private fun JSONArray.toCanonicalScopeMaps(): List<Map<String, Any?>> =
        buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: throw CompanionApiException.InvalidResponse
                add(
                    mapOf(
                        "scope" to item.strictValue("scope"),
                        "documents" to item.strictArray("documents").toDocumentMaps(),
                    ),
                )
            }
        }

    private fun JSONObject.optionalValue(key: String): Any? =
        if (!has(key) || isNull(key)) null else try {
            get(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
}

internal object LaunchConsentApiContract {
    private const val SCHEMA = "murph.hosted-consent-status.v1"
    private const val SOURCE = "android-companion"
    private val REQUIRED_SCOPES = LaunchConsentScope.entries.toSet()

    @Suppress("UNCHECKED_CAST")
    fun parseStatus(
        schema: Any?,
        launchGranted: Any?,
        documents: List<Map<String, Any?>>,
        launchScopes: List<Map<String, Any?>>,
        scopes: List<Map<String, Any?>>,
        backendOrigin: URI,
    ): LaunchConsentStatus {
        if (strictString(schema) != SCHEMA) throw CompanionApiException.InvalidResponse
        val parsedLaunchGranted = launchGranted as? Boolean
            ?: throw CompanionApiException.InvalidResponse
        val parsedDocuments = documents.map { parseDocument(it, backendOrigin) }
        val documentsById = parsedDocuments.associateBy { it.id }
        if (documentsById.size != parsedDocuments.size) {
            throw CompanionApiException.InvalidResponse
        }
        val canonicalScopesById = scopes.associateBy { strictString(it["scope"]) }
        if (canonicalScopesById.size != scopes.size) {
            throw CompanionApiException.InvalidResponse
        }
        val parsedScopes = launchScopes.map { scope ->
            val parsedScope = parseScope(strictString(scope["scope"]))
            val granted = scope["granted"] as? Boolean
                ?: throw CompanionApiException.InvalidResponse
            val canonicalScope = canonicalScopesById[parsedScope.wireValue]
                ?: throw CompanionApiException.InvalidResponse
            val documents = (canonicalScope["documents"] as? List<Map<String, Any?>>)
                ?.map { parseDocument(it, backendOrigin) }
                ?: throw CompanionApiException.InvalidResponse
            val missingDocuments = (scope["missingDocuments"] as? List<Map<String, Any?>>)
                ?.map { parseDocument(it, backendOrigin) }
                ?: throw CompanionApiException.InvalidResponse
            if (
                documents.isEmpty() ||
                documents.map { it.id }.toSet().size != documents.size ||
                documents.any { documentsById[it.id] != it } ||
                missingDocuments.map { it.id }.toSet().size != missingDocuments.size ||
                missingDocuments.any { it !in documents }
            ) {
                throw CompanionApiException.InvalidResponse
            }
            if (granted && missingDocuments.isNotEmpty()) {
                throw CompanionApiException.InvalidResponse
            }
            if (!granted && missingDocuments.isEmpty()) {
                throw CompanionApiException.InvalidResponse
            }
            LaunchConsentScopeStatus(
                scope = parsedScope,
                granted = granted,
                documents = documents,
                missingDocuments = missingDocuments,
            )
        }
        val seenScopes = parsedScopes.map { it.scope }
        if (seenScopes.toSet() != REQUIRED_SCOPES || seenScopes.size != REQUIRED_SCOPES.size) {
            throw CompanionApiException.InvalidResponse
        }
        if (parsedLaunchGranted != parsedScopes.all { it.granted }) {
            throw CompanionApiException.InvalidResponse
        }
        return LaunchConsentStatus(
            launchGranted = parsedLaunchGranted,
            documents = parsedDocuments,
            launchScopes = parsedScopes,
        )
    }

    fun acceptanceBody(request: LaunchConsentAcceptanceRequest): Map<String, Any> =
        mapOf(
            "scope" to request.scope.wireValue,
            "acceptedDocumentVersions" to request.acceptedDocumentVersions,
            "source" to SOURCE,
        )

    fun acceptanceRequest(scopeStatus: LaunchConsentScopeStatus): LaunchConsentAcceptanceRequest =
        LaunchConsentAcceptanceRequest(
            scope = scopeStatus.scope,
            acceptedDocumentVersions = scopeStatus.documents.associate {
                it.id to it.version
            },
        )

    fun safeDocumentUrl(rawValue: Any?, backendOrigin: URI): String {
        val raw = strictString(rawValue)
        val rawUri = try {
            URI(raw)
        } catch (_: Exception) {
            throw CompanionApiException.InvalidResponse
        }
        val resolved = try {
            backendOrigin.resolve(rawUri).normalize()
        } catch (_: Exception) {
            throw CompanionApiException.InvalidResponse
        }
        val backendScheme = backendOrigin.scheme?.lowercase()
        val resolvedScheme = resolved.scheme?.lowercase()
        if (
            backendScheme != "https" ||
            resolvedScheme != "https" ||
            backendOrigin.host.isNullOrBlank() ||
            resolved.host.isNullOrBlank() ||
            resolved.rawUserInfo != null ||
            rawUri.rawUserInfo != null ||
            !backendOrigin.host.equals(resolved.host, ignoreCase = true) ||
            effectiveHttpsPort(backendOrigin) != effectiveHttpsPort(resolved)
        ) {
            throw CompanionApiException.InvalidResponse
        }
        return resolved.toASCIIString()
    }

    private fun parseDocument(
        document: Map<String, Any?>,
        backendOrigin: URI,
    ): LaunchConsentDocument =
        LaunchConsentDocument(
            id = strictString(document["id"]),
            title = strictString(document["title"]),
            version = strictString(document["version"]),
            href = safeDocumentUrl(document["href"], backendOrigin),
            pdfHref = document["pdfHref"]?.let { safeDocumentUrl(it, backendOrigin) },
        )

    private fun parseScope(value: String): LaunchConsentScope =
        LaunchConsentScope.entries.firstOrNull { it.wireValue == value }
            ?: throw CompanionApiException.InvalidResponse

    private fun strictString(value: Any?): String =
        (value as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse

    private fun effectiveHttpsPort(uri: URI): Int =
        if (uri.port == -1) 443 else uri.port
}

internal object AddressBookApiJson {
    fun parseStatus(json: JSONObject): AddressBookServerStatus =
        AddressBookApiContract.parseStatus(
            schemaVersion = json.strictValue("schemaVersion"),
            writeCapability = json.strictValue("writeCapability"),
            enabled = json.strictValue("enabled"),
            revision = json.strictValue("revision"),
            storedContactCount = json.strictValue("storedContactCount"),
        )

    fun replacementBody(request: AddressBookReplacementRequest): JSONObject =
        JSONObject(AddressBookApiContract.replacementBody(request))

    fun deletionBody(request: AddressBookDeletionRequest): JSONObject =
        JSONObject(AddressBookApiContract.deletionBody(request))

    private fun JSONObject.strictValue(key: String): Any {
        if (!has(key) || isNull(key)) throw CompanionApiException.InvalidResponse
        return try {
            get(key)
        } catch (_: org.json.JSONException) {
            throw CompanionApiException.InvalidResponse
        }
    }
}

internal object AddressBookApiContract {
    private const val SCHEMA_VERSION = 1
    private const val MAX_STORED_CONTACTS = 1_000

    fun parseStatus(
        schemaVersion: Any?,
        writeCapability: Any?,
        enabled: Any?,
        revision: Any?,
        storedContactCount: Any?,
    ): AddressBookServerStatus {
        if (strictInt(schemaVersion) != SCHEMA_VERSION) {
            throw CompanionApiException.InvalidResponse
        }
        val parsedWriteCapability = when (strictString(writeCapability)) {
            AddressBookWriteCapability.Enabled.wireValue -> AddressBookWriteCapability.Enabled
            AddressBookWriteCapability.Disabled.wireValue -> AddressBookWriteCapability.Disabled
            else -> throw CompanionApiException.InvalidResponse
        }
        val parsedEnabled = enabled as? Boolean ?: throw CompanionApiException.InvalidResponse
        val parsedRevision = strictInt(revision).takeIf { it >= 0 }
            ?: throw CompanionApiException.InvalidResponse
        val parsedStoredContactCount = strictInt(storedContactCount)
            .takeIf { it in 0..MAX_STORED_CONTACTS }
            ?: throw CompanionApiException.InvalidResponse
        if (!parsedEnabled && parsedStoredContactCount != 0) {
            throw CompanionApiException.InvalidResponse
        }
        return AddressBookServerStatus(
            writeCapability = parsedWriteCapability,
            enabled = parsedEnabled,
            revision = parsedRevision,
            storedContactCount = parsedStoredContactCount,
        )
    }

    fun replacementBody(request: AddressBookReplacementRequest): Map<String, Any> = mapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "baseRevision" to request.mutation.baseRevision,
        "mutationId" to request.mutation.mutationId,
        "contacts" to request.contacts.map { contact ->
            mapOf(
                "phoneNumber" to contact.phoneNumber,
                "advisoryName" to contact.advisoryName,
            )
        },
    )

    fun deletionBody(request: AddressBookDeletionRequest): Map<String, Any> = mapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "baseRevision" to request.mutation.baseRevision,
        "mutationId" to request.mutation.mutationId,
    )

    fun validateReplacementResponse(
        request: AddressBookReplacementRequest,
        status: AddressBookServerStatus,
    ): AddressBookServerStatus {
        if (!status.enabled || status.revision <= request.mutation.baseRevision) {
            throw CompanionApiException.InvalidResponse
        }
        // A replay can return the result of an earlier request body with the same
        // mutation id, so its stored count need not equal the freshly projected list.
        return status
    }

    fun validateDeletionResponse(
        request: AddressBookDeletionRequest,
        status: AddressBookServerStatus,
    ): AddressBookServerStatus {
        if (
            status.enabled ||
            status.revision <= request.mutation.baseRevision ||
            status.storedContactCount != 0
        ) {
            throw CompanionApiException.InvalidResponse
        }
        return status
    }

    private fun strictInt(value: Any?): Int {
        val longValue = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw CompanionApiException.InvalidResponse
        }
        return longValue.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: throw CompanionApiException.InvalidResponse
    }

    private fun strictString(value: Any?): String =
        (value as? String)?.takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
}

internal fun mapCompanionApiError(
    status: Int,
    body: String,
    revisionConflict: Boolean = false,
): CompanionApiException = mapCompanionApiErrorCode(
    status = status,
    errorCode = readCompanionApiErrorCode(body),
    revisionConflict = revisionConflict,
)

internal fun mapCompanionApiErrorCode(
    status: Int,
    errorCode: String?,
    revisionConflict: Boolean = false,
): CompanionApiException = when (status) {
    401 -> CompanionApiException.Unauthorized
    403 -> when (errorCode) {
        "HOSTED_CONSENT_REQUIRED" -> CompanionApiException.ConsentRequired
        "HOSTED_MEMBER_NOT_FOUND" -> CompanionApiException.NoAccount
        else -> CompanionApiException.Server(status)
    }
    409 -> when {
        errorCode == "PRIVY_IDENTITY_CONFLICT" || errorCode == "PRIVY_USER_MISMATCH" ->
            CompanionApiException.AccountConflict
        revisionConflict -> CompanionApiException.Conflict
        errorCode == "SDK_SIGN_IN_RECONNECT_REQUIRED" ->
            CompanionApiException.ReconnectRequired
        errorCode == STALE_CONSENT_DOCUMENT_CODE ->
            CompanionApiException.StaleConsentDocuments
        else -> CompanionApiException.Server(status)
    }
    else -> CompanionApiException.Server(status)
}

private const val STALE_CONSENT_DOCUMENT_CODE = "CONSENT_DOCUMENT_VERSIONS_STALE"

internal fun normalizeCompanionApiErrorCode(value: Any?): String? =
    (value as? String)?.trim()?.takeIf(String::isNotBlank)

private fun readCompanionApiErrorCode(body: String): String? = runCatching {
    val error = JSONObject(body).optJSONObject("error") ?: return@runCatching null
    if (!error.has("code") || error.isNull("code")) return@runCatching null
    normalizeCompanionApiErrorCode(error.get("code"))
}.getOrNull()
