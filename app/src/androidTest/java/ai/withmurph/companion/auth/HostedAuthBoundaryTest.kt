package ai.withmurph.companion.auth

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HostedAuthBoundaryTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private val credential = "murph_auth_v1." + "a".repeat(32)
    private val record get() = HostedAuthStoredState.Active(
        HostedAuthBinding("member-a", "legacy-local-a"), credential,
        Instant.parse("2026-10-10T12:00:00Z"), Instant.parse("2026-09-10T12:00:00Z"),
    )

    @Before fun setUp() {
        directory = File(app.noBackupFilesDir, "auth-proof-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun realKeystoreRoundTripIsEncryptedAndRetirementSurvivesRecreation() {
        val store = HostedAuthCredentialStore(context)
        assertNull(store.load())
        store.save(record)
        val ciphertext = File(directory, "hosted-auth-v1").readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(credential))
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("legacy-local-a"))
        assertEquals(record, HostedAuthCredentialStore(context).load())
        store.save(HostedAuthStoredState.SignedOut(record.binding))
        assertEquals(HostedAuthStoredState.SignedOut(record.binding), HostedAuthCredentialStore(context).load())
    }

    @Test fun corruptedCiphertextIsPreservedAndCannotRestoreSdkFallback() {
        val store = HostedAuthCredentialStore(context)
        store.save(record)
        val file = File(directory, "hosted-auth-v1")
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        assertThrows(HostedAuthException::class.java) { store.load() }
        assertArrayEquals(corrupted, file.readBytes())
    }

    @Test fun missingKeystoreKeyDoesNotRegenerateOverAnExistingRecord() {
        val store = HostedAuthCredentialStore(context)
        store.save(record)
        val file = File(directory, "hosted-auth-v1")
        val before = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("murph.hosted-auth.v1") }
        assertThrows(HostedAuthException::class.java) { store.load() }
        assertThrows(HostedAuthException::class.java) { store.save(record) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun recordDecoderRejectsUnknownVersionsAndInvalidAuthority() {
        val encoded = HostedAuthCredentialStore.encode(record)
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("version", 2) },
            { it.put("credential", "invalid") },
            { it.remove("localMemberKey") },
            { it.put("state", "signed_out") },
        )) {
            val invalid = JSONObject(encoded.toString(Charsets.UTF_8)).also(mutate)
            assertThrows(Exception::class.java) {
                HostedAuthCredentialStore.decode(invalid.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test fun otpAndExchangeUseExactRoutesWithExclusiveCredentialTransport() = runBlocking {
        val connections = mutableListOf<Connection>()
        val api = HostedAuthApiClient("https://auth-proof.invalid") { url ->
            Connection(url, 200, sessionBody()).also(connections::add)
        }
        assertEquals(credential, api.verifyCode(LoginMethod.Phone, "+12025550152", "123456").token)
        val otp = connections.single()
        assertEquals("/api/device-sync/companion/auth/otp/verify", otp.url.path)
        assertEquals("POST", otp.requestMethod)
        assertNull(otp.getRequestProperty("Authorization"))
        assertNull(otp.getRequestProperty("Cookie"))
        assertFalse(otp.instanceFollowRedirects)
        assertFalse(otp.useCaches)
        val body = JSONObject(otp.sent.toString("UTF-8"))
        assertEquals("phone", body.get("kind"))
        assertEquals("123456", body.get("code"))
        api.exchange("synthetic-legacy-credential")
        val exchange = connections.last()
        assertEquals("/api/device-sync/companion/auth/exchange", exchange.url.path)
        assertEquals("Bearer synthetic-legacy-credential", exchange.getRequestProperty("Authorization"))
        assertEquals(0, exchange.sent.size())
        assertTrue(connections.all { it.wasDisconnected })
    }

    @Test fun non200AndUnacknowledgedOrOversizedSuccessCannotIssueAuthority() = runBlocking {
        for ((status, body) in listOf(
            302 to sessionBody(), 204 to "", 401 to sessionBody(),
            200 to sessionBody().replace("\"ok\":true", "\"ok\":\"true\""),
            200 to sessionBody().replace("\"ok\":true", "\"ok\":false"),
            200 to sessionBody().dropLast(1) + ",\"padding\":\"${"x".repeat(16_384)}\"}",
        )) {
            var calls = 0
            val api = HostedAuthApiClient("https://auth-proof.invalid") { url ->
                calls++; Connection(url, status, body)
            }
            try {
                api.exchange("synthetic-legacy-credential")
                fail("Invalid response issued authority")
            } catch (_: HostedAuthException) {
                assertEquals(1, calls)
            }
        }
    }

    @Test fun globalCookieJarIsRejectedBeforeOpeningAnyAuthRequest() = runBlocking {
        val previous = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(CookieManager())
            val api = HostedAuthApiClient("https://auth-proof.invalid") { error("Network must not run") }
            try {
                api.exchange("synthetic-legacy-credential")
                fail("Expected exclusive native credential boundary")
            } catch (_: HostedAuthException) {
                assertNotNull(CookieHandler.getDefault())
            }
        } finally {
            CookieHandler.setDefault(previous)
        }
    }

    private fun sessionBody(): String = """{"ok":true,"memberId":"member-a","token":"$credential","expiresAt":"2026-10-10T12:00:00Z"}"""

    private class Connection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var wasDisconnected = false
        override fun connect() {}
        override fun disconnect() { wasDisconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = inputStream
        override fun getOutputStream() = sent
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
    }
}
