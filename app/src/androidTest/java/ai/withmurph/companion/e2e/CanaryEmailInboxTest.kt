package ai.withmurph.companion.e2e

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class CanaryEmailInboxTest {
    private val inbox = CanaryEmailInbox("android@canary.example", "auth@example.com", "re_synthetic_test_key")
    private val now = Instant.now()

    private fun message(): JSONObject = JSONObject()
        .put("id", "00000000-0000-4000-8000-000000000001")
        .put("to", org.json.JSONArray(listOf("android@canary.example")))
        .put("from", "auth@example.com")
        .put("created_at", now.toString())
        .put("subject", "Your Murph sign-in code")
        .put("text", "Your Murph sign-in code is 123456. It expires in 5 minutes. If you did not request this code, you can ignore this email.")
        .put("authentication", JSONObject().put("dkim", "pass"))

    @Test
    fun onlyFreshAuthenticatedCodeForExactMailboxIsAccepted() {
        val snapshot = CanaryEmailInbox.Snapshot(emptySet(), now)
        assertEquals("123456", inbox.code(message(), snapshot))
        listOf(
            "to" to org.json.JSONArray(listOf("ios@canary.example")),
            "to" to org.json.JSONArray(listOf("android@canary.example", "ios@canary.example")),
            "from" to "impostor@example.com",
            "created_at" to "2000-01-01T00:00:00Z",
            "created_at" to "2100-01-01T00:00:00Z",
            "created_at" to "invalid",
            "subject" to "Another message",
            "authentication" to JSONObject().put("dkim", "fail"),
            "authentication" to JSONObject.NULL,
            "text" to "Your Murph sign-in code is 123456. Another code is 654321.",
            "text" to JSONObject.NULL,
        ).forEach { (key, value) ->
            assertNull(inbox.code(message().put(key, value), snapshot))
        }
    }

    @Test
    fun returningLoginCannotReusePreviousMessage() {
        val snapshot = CanaryEmailInbox.Snapshot(setOf(message().getString("id")), now)
        assertNull(inbox.code(message(), snapshot))
        assertEquals("123456", inbox.code(message().put("id", "00000000-0000-4000-8000-000000000002"), snapshot))
    }

    @Test
    fun configurationRejectsHeaderInjectionAndNonMailboxes() {
        listOf("", "not-an-email", "android@canary.example\n", " android@canary.example", "+12025550142").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                CanaryEmailInbox(it, "auth@example.com", "re_synthetic_test_key")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanaryEmailInbox("android@canary.example", "auth@example.com", "re_synthetic_test_key\r\nInjected: value")
        }
    }
}
