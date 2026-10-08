package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import ai.withmurph.companion.core.LoginMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on device: the adapter needs the platform org.json implementation. */
@RunWith(AndroidJUnit4::class)
class HostedAuthApiClientMessagingErrorTest {
    @Test fun serverErrorsAfterSubmissionStayIndeterminate() = runBlocking {
        val cases = listOf(
            Triple(502, "", "response:502"),
            Triple(504, """{"error":{"code":"UPSTREAM_TIMEOUT"}}""", "response:504"),
            Triple(500, """{"error":{"code":"AUTH_CONTACT_IN_USE"}}""", "ContactInUse"),
            Triple(400, """{"error":{"code":"AUTH_CODE_INVALID"}}""", "InvalidCode"),
            Triple(429, "{}", "RateLimited"),
            Triple(400, "{}", "Unavailable"),
        )
        for ((status, body, expected) in cases) {
            val client = HostedAuthApiClient("https://example.invalid") { StubConnection(status, body) }
            for (submit in listOf<suspend () -> Unit>(
                { client.verifyMessagingPhoneCode("+12025550101", "123456", "synthetic-credential") },
                { client.completeMessagingTelegram("a".repeat(43), "synthetic-id-token", "synthetic-credential") },
            )) {
                try {
                    submit()
                    fail("$status accepted")
                } catch (error: HostedAuthException.Response) {
                    assertEquals("$status $body", expected, "response:${error.status}")
                } catch (error: MessagingLinkException) {
                    assertEquals("$status $body", expected, error.reason.name)
                }
            }
        }
    }

    @Test fun gatewayErrorAfterVerifySubmissionIsUnknown() = runBlocking {
        val client = HostedAuthApiClient("https://example.invalid") { url ->
            if (url.path.endsWith("/messaging/phone/send")) StubConnection(200, """{"ok":true}""") else StubConnection(502, "")
        }
        val model = MessagingSetupCoordinator(SignedInAuth(), client)
        model.setPhone("+12025550101")
        model.sendCode()
        model.setCode("123456")
        assertEquals(MessagingOutcome.Unknown, model.verifyCode())
    }

    private class SignedInAuth : AuthProvider {
        override suspend fun currentState() = AuthSessionState.SignedIn("synthetic-member", true)
        override suspend fun identityToken() = "synthetic-credential"
        override suspend fun signOut() {}
        override suspend fun sendCode(method: LoginMethod, destination: String) {}
        override suspend fun confirmCode(method: LoginMethod, destination: String, code: String) {}
    }

    private class StubConnection(private val status: Int, private val body: String) :
        HttpURLConnection(URL("https://example.invalid")) {
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = body.toByteArray().size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }
}
