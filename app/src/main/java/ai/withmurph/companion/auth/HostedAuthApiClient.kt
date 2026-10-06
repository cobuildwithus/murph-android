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

    override suspend fun sendMessagingPhoneCode(phone: String, credential: String) {
        request("messaging/phone/send", phoneBody(phone), credential)
    }

    override suspend fun verifyMessagingPhoneCode(phone: String, code: String, credential: String) {
        request("messaging/phone/verify", phoneBody(phone).put("code", code), credential)
    }

    override suspend fun startMessagingTelegram(credential: String): TelegramMessagingLink {
        val response = request("messaging/telegram/start", JSONObject(), credential)
        return decode {
        val token = response.string("token")
        val url = response.string("url")
        val uri = URI(url)
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(token) || uri.scheme != "https" || uri.host != "t.me"
            || uri.userInfo != null || uri.port != -1 || uri.fragment != null
            || !Regex("^/[A-Za-z0-9_]+$").matches(uri.path) || uri.rawQuery != "start=link_$token") throw HostedAuthException.InvalidResponse
        TelegramMessagingLink(token, url)
        }
    }

    override suspend fun completeMessagingTelegram(token: String, credential: String): Boolean {
        val response = request("messaging/telegram/complete", JSONObject().put("token", token), credential)
        return decode { response.get("linked") as? Boolean ?: throw HostedAuthException.InvalidResponse }
    }

    private fun phoneBody(phone: String) = JSONObject().put("change", JSONObject()
        .put("method", "phone").put("operation", "set").put("expectedIdentity", JSONObject.NULL).put("value", phone))

    private suspend fun request(path: String, body: JSONObject? = null, credential: String? = null): JSONObject {
        // The app has no global cookie jar. Reject one if another component
        // installs it: native bearer requests must never inherit browser auth.
        if (CookieHandler.getDefault() != null) throw HostedAuthException.CredentialsUnavailable
        val response = executeHttpRequest(
            openConnection = openConnection,
            url = URL("$origin/api/device-sync/companion/auth/$path"),
            method = "POST", token = credential, body = body?.toString(),
        )
        if (response.status != 200) {
            if (path.startsWith("messaging/")) {
                val code = if (response.text.length <= 16_384) runCatching { JSONObject(response.text).optJSONObject("error")?.optString("code") }.getOrNull() else null
                val reason = when (code) {
                    "AUTH_CONTACT_IN_USE" -> MessagingLinkException.Reason.ContactInUse
                    "AUTH_CODE_INVALID" -> MessagingLinkException.Reason.InvalidCode
                    "AUTH_FRESH_LOGIN_REQUIRED" -> MessagingLinkException.Reason.FreshLogin
                    "AUTH_MESSAGING_APPROVAL_REQUIRED" -> MessagingLinkException.Reason.Approval
                    "AUTH_CREDENTIAL_REQUEST_INVALID" -> MessagingLinkException.Reason.InvalidNumber
                    "AUTH_TELEGRAM_LINK_INVALID" -> MessagingLinkException.Reason.ExpiredLink
                    else -> when (response.status) {
                        429 -> MessagingLinkException.Reason.RateLimited
                        401 -> MessagingLinkException.Reason.FreshLogin
                        else -> MessagingLinkException.Reason.Unavailable
                    }
                }
                throw MessagingLinkException(reason)
            }
            throw HostedAuthException.Response(response.status)
        }
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
