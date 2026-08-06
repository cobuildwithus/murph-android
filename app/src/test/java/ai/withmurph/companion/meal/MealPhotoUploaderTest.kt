package ai.withmurph.companion.meal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class MealPhotoUploaderTest {
    @Test
    fun activationStatusRequiresHttp200AndAnExplicitTrueBoolean() {
        assertEquals(
            MealPhotoActivationDisposition.Activated,
            MealPhotoActivationStatusPolicy.disposition(200, true),
        )
        listOf<Any?>(false, "true", 1, null).forEach { malformed ->
            assertEquals(
                MealPhotoActivationDisposition.Retry,
                MealPhotoActivationStatusPolicy.disposition(200, malformed),
            )
        }
        listOf<Int?>(201, 204, 400, 403, 409, 500, null).forEach { status ->
            assertEquals(
                MealPhotoActivationDisposition.Retry,
                MealPhotoActivationStatusPolicy.disposition(status, true),
            )
        }
        assertEquals(
            MealPhotoActivationDisposition.CredentialRejected,
            MealPhotoActivationStatusPolicy.disposition(401, true),
        )
    }

    @Test
    fun scopedActivationUsesBodylessPutOnTheEnrollmentEndpoint() = runTest {
        val connection = FakeHttpConnection(responseStatus = 401)
        var requestedUri: URI? = null
        val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) { uri ->
            requestedUri = uri
            connection
        }

        assertEquals(
            MealPhotoActivationDisposition.CredentialRejected,
            uploader.activateScoped(SCOPED_TOKEN),
        )
        assertEquals(ENROLLMENT_PATH, requestedUri?.path)
        assertEquals("PUT", connection.requestMethod)
        assertEquals("Bearer $SCOPED_TOKEN", connection.headers["Authorization"])
        assertEquals("application/json", connection.headers["Accept"])
        assertFalse(connection.doOutput)
        assertFalse(connection.outputStreamOpened)
    }

    @Test
    fun scopedActivationRequiresTheExplicitTrueJsonResponse() = runTest {
        listOf(
            "{\"activated\":true}" to MealPhotoActivationDisposition.Activated,
            "{\"activated\":false}" to MealPhotoActivationDisposition.Retry,
            "{\"activated\":\"true\"}" to MealPhotoActivationDisposition.Retry,
            "{}" to MealPhotoActivationDisposition.Retry,
        ).forEach { (body, expected) ->
            val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) {
                FakeHttpConnection(
                    responseStatus = 200,
                    responseBody = body.toByteArray(),
                )
            }

            assertEquals(expected, uploader.activateScoped(SCOPED_TOKEN))
        }
    }

    @Test
    fun activationTransportFailureRetries() = runTest {
        val connection = FakeHttpConnection(responseFailure = IOException("offline"))
        val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) { connection }

        assertEquals(
            MealPhotoActivationDisposition.Retry,
            uploader.activateScoped(SCOPED_TOKEN),
        )
    }

    @Test
    fun scopedRevocationRemainsABodylessDelete() = runTest {
        val connection = FakeHttpConnection(responseStatus = 200)
        val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) { connection }

        assertEquals(
            MealPhotoRevocationDisposition.Revoked,
            uploader.revokeScoped(SCOPED_TOKEN),
        )
        assertEquals("DELETE", connection.requestMethod)
        assertFalse(connection.doOutput)
        assertFalse(connection.outputStreamOpened)
    }

    @Test
    fun malformedOrOversizedActivationResponseRetries() = runTest {
        listOf(
            "not-json".toByteArray(),
            ByteArray(4_097) { ' '.code.toByte() },
        ).forEach { responseBody ->
            val connection = FakeHttpConnection(
                responseStatus = 200,
                responseBody = responseBody,
            )
            val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) { connection }

            assertEquals(
                MealPhotoActivationDisposition.Retry,
                uploader.activateScoped(SCOPED_TOKEN),
            )
        }
    }

    @Test
    fun malformedScopedTokenDoesNotOpenAConnection() = runTest {
        var requestedUri: URI? = null
        val uploader = HttpMealPhotoUploader(BACKEND_ORIGIN) { uri ->
            requestedUri = uri
            FakeHttpConnection(responseStatus = 200)
        }

        assertEquals(
            MealPhotoActivationDisposition.Retry,
            uploader.activateScoped("identity-token"),
        )
        assertNull(requestedUri)
    }

    private class FakeHttpConnection(
        private val responseStatus: Int = 500,
        private val responseBody: ByteArray = ByteArray(0),
        private val responseFailure: IOException? = null,
    ) : HttpURLConnection(URL(BACKEND_ORIGIN)) {
        val headers = mutableMapOf<String, String>()
        var outputStreamOpened = false

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return responseStatus
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)

        override fun getOutputStream(): java.io.OutputStream {
            outputStreamOpened = true
            return super.getOutputStream()
        }
    }

    private companion object {
        const val BACKEND_ORIGIN = "https://example.test"
        const val ENROLLMENT_PATH =
            "/api/device-sync/companion/meal-photo-capture/enrollment"
        val SCOPED_TOKEN = "murph_meal_photo_${"A".repeat(43)}"
    }
}
