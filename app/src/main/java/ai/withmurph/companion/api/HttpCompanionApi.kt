package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApi
import ai.withmurph.companion.core.CompanionApiException
import ai.withmurph.companion.core.CompanionSyncStatus
import ai.withmurph.companion.core.SignInTokenRequest
import ai.withmurph.companion.core.SignInTokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) : CompanionApi {
    private val baseUri = URI(baseUrl.trimEnd('/')).also { uri ->
        require(uri.scheme == "https") { "Murph backend URL must use HTTPS" }
        require(uri.host != null) { "Murph backend URL must have a host" }
    }

    override suspend fun createJunctionSignInToken(
        request: SignInTokenRequest,
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
        }
        val response = requestJson(
            method = "POST",
            path = "/api/device-sync/companion/sign-in-token",
            body = body,
        )
        val token = response.optString("signInToken").takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
        val environment = response.optString("environment").takeIf(String::isNotBlank)
            ?: throw CompanionApiException.InvalidResponse
        return SignInTokenResponse(token, environment)
    }

    override suspend fun fetchSyncStatus(sourceProviderSlug: String): CompanionSyncStatus {
        val encodedSource = URLEncoder.encode(sourceProviderSlug, StandardCharsets.UTF_8.name())
        val response = requestJson(
            method = "GET",
            path = "/api/device-sync/companion/status?sourceProviderSlug=$encodedSource",
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

    private suspend fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = try {
            identityToken()
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
                throw mapError(status, text)
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

    private fun mapError(status: Int, body: String): CompanionApiException = when (status) {
        401 -> CompanionApiException.Unauthorized
        403 -> when (readErrorCode(body)) {
            "HOSTED_CONSENT_REQUIRED" -> CompanionApiException.ConsentRequired
            "HOSTED_MEMBER_NOT_FOUND" -> CompanionApiException.NoAccount
            else -> CompanionApiException.Server(status)
        }
        409 -> {
            if (readErrorCode(body) == "SDK_SIGN_IN_RECONNECT_REQUIRED") {
                CompanionApiException.ReconnectRequired
            } else {
                CompanionApiException.Server(status)
            }
        }
        else -> CompanionApiException.Server(status)
    }

    private fun readErrorCode(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank)
    }.getOrNull()

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
        const val MAX_RESPONSE_CHARS = 128 * 1024
    }
}
