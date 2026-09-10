package ai.withmurph.companion.auth

import ai.withmurph.companion.api.executeHttpRequest
import ai.withmurph.companion.core.LoginMethod
import org.json.JSONObject
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.TimeZone

/** Fixed native endpoints, explicit bearers and no app-level request replay. */
class HostedAuthApiClient(
    baseUrl: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HostedAuthServing {
    private val origin = URI(baseUrl).also {
        require(it.scheme == "https" || it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost"))
        require(it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
        require(it.path.isNullOrEmpty() || it.path == "/")
    }.toString().trimEnd('/')

    override suspend fun sendCode(method: LoginMethod, value: String) {
        request("otp/send", body = JSONObject().put("kind", method.wireValue()).put("value", value))
    }

    override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession =
        session(request("otp/verify", body = JSONObject()
            .put("kind", method.wireValue()).put("value", value).put("code", code)
            .put("timeZone", TimeZone.getDefault().id)))

    override suspend fun exchange(legacyCredential: String): HostedAuthSession =
        session(request("exchange", credential = legacyCredential))

    override suspend fun renew(credential: String): HostedAuthSessionStatus {
        val response = request("session", credential = credential)
        return decode { HostedAuthSessionStatus(response.string("memberId"), Instant.parse(response.string("expiresAt"))) }
    }

    override suspend fun revoke(credential: String) {
        request("logout", credential = credential)
    }

    private suspend fun request(path: String, body: JSONObject? = null, credential: String? = null): JSONObject {
        // The app has no global cookie jar. Reject one if another component
        // installs it: native bearer requests must never inherit browser auth.
        if (CookieHandler.getDefault() != null) throw HostedAuthException.CredentialsUnavailable
        val response = executeHttpRequest(
            openConnection = openConnection,
            url = URL("$origin/api/device-sync/companion/auth/$path"),
            method = "POST", token = credential, body = body?.toString(),
        )
        if (response.status != 200) throw HostedAuthException.Response(response.status)
        return decode {
            if (response.text.toByteArray(Charsets.UTF_8).size > 16_384) throw HostedAuthException.InvalidResponse
            JSONObject(response.text).also { if (it.get("ok") != true) throw HostedAuthException.InvalidResponse }
        }
    }

    private fun session(response: JSONObject): HostedAuthSession = decode {
        HostedAuthSession(response.string("memberId"), response.string("token"), Instant.parse(response.string("expiresAt")))
            .also { if (!HOSTED_AUTH_CREDENTIAL_PATTERN.matches(it.token)) throw HostedAuthException.InvalidResponse }
    }

    private fun <T> decode(operation: () -> T): T = try {
        operation()
    } catch (_: Exception) {
        throw HostedAuthException.InvalidResponse
    }

    private fun JSONObject.string(key: String): String = (get(key) as? String)
        ?.takeIf { it.isNotBlank() && it.length <= 256 } ?: throw HostedAuthException.InvalidResponse

    private fun LoginMethod.wireValue(): String = when (this) {
        LoginMethod.Email -> "email"
        LoginMethod.Phone -> "phone"
    }
}
