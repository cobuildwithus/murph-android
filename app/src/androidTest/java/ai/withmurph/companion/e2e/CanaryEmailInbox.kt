package ai.withmurph.companion.e2e

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

// Instrumentation only. Never use a Resend account that contains customer mail.
internal class CanaryEmailInbox(
    val address: String,
    private val sender: String,
    private val apiKey: String,
) {
    init {
        require(isMailbox(address) && isMailbox(sender) &&
            apiKey.matches(Regex("re_[A-Za-z0-9_-]{10,200}"))) {
            "missing_protected_configuration"
        }
    }

    class Snapshot(val ids: Set<String>, val requestedAfter: Instant)

    fun snapshot(): Snapshot = Snapshot(list().map { it.getString("id") }.toSet(), Instant.now())

    fun waitForCode(snapshot: Snapshot): String {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (System.nanoTime() < deadline) {
            Thread.sleep(3_000)
            val candidates = list().filter { isCandidate(it, snapshot) }
            if (candidates.size > 1) unavailable()
            candidates.singleOrNull()?.let { candidate ->
                val id = candidate.getString("id")
                if (!id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) unavailable()
                val message = get("/$id?html_format=cid")
                if (message.optString("id") != id) unavailable()
                return code(message, snapshot) ?: unavailable()
            }
        }
        unavailable()
    }

    fun isCandidate(message: JSONObject, snapshot: Snapshot): Boolean {
        val date = runCatching { Instant.parse(message.getString("created_at")) }.getOrNull()
            ?: return false
        val recipients = message.optJSONArray("to") ?: return false
        return message.optString("id") !in snapshot.ids &&
            recipients.length() == 1 &&
            recipients.optString(0).equals(address, ignoreCase = true) &&
            message.optString("from").equals(sender, ignoreCase = true) &&
            message.optString("subject") == "Your Murph sign-in code" &&
            date >= snapshot.requestedAfter.minusSeconds(1) &&
            date <= Instant.now().plusSeconds(5)
    }

    fun code(message: JSONObject, snapshot: Snapshot): String? {
        if (!isCandidate(message, snapshot) ||
            message.optJSONObject("authentication")?.optString("dkim") != "pass") return null
        return Regex(
            "Your Murph sign-in code is ([0-9]{6})\\. It expires in 5 minutes\\. " +
                "If you did not request this code, you can ignore this email\\.",
        ).matchEntire(message.optString("text").trim())?.groupValues?.get(1)
    }

    private fun list(): List<JSONObject> {
        val rows = get("?limit=100").optJSONArray("data") ?: unavailable()
        if (rows.length() > 100) unavailable()
        return (0 until rows.length()).map { rows.optJSONObject(it) ?: unavailable() }
    }

    private fun get(suffix: String): JSONObject {
        val connection = URL("https://api.resend.com/emails/receiving$suffix")
            .openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (connection.responseCode != 200) unavailable()
            val deadline = System.nanoTime() + 10_000_000_000L
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    if (System.nanoTime() >= deadline) unavailable()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (body.size() + count > 262_144) unavailable()
                    body.write(buffer, 0, count)
                }
            }
            return JSONObject(body.toString("UTF-8"))
        } catch (_: Exception) {
            // Do not expose provider errors, message bodies, keys, or addresses to test output.
            unavailable()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun isMailbox(value: String): Boolean = value.length <= 254 &&
            value.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))

        private fun unavailable(): Nothing = throw IllegalStateException("canary_email_unavailable")
    }
}
