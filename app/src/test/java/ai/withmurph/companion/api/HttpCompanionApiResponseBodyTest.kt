package ai.withmurph.companion.api

import ai.withmurph.companion.core.CompanionApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class HttpCompanionApiResponseBodyTest {
    @Test
    fun successAndErrorBodiesBelowAndAtTheLimitAreReturnedWhole() {
        listOf(MAX_RESPONSE_CHARS - 1, MAX_RESPONSE_CHARS).forEach { size ->
            listOf(200, 400).forEach { status ->
                val body = "a".repeat(size)
                val connection = FakeHttpURLConnection(
                    status = status,
                    body = body,
                    reportedContentLength = body.length.toLong(),
                )

                assertEquals(body, readResponseBody(connection, status))
                assertSelectedResponseStream(connection, status)
            }
        }
    }

    @Test
    fun knownLengthMultibyteBodiesAtTheCharacterLimitAreReturnedWhole() {
        val body = "\u0800".repeat(MAX_RESPONSE_CHARS)

        listOf(200, 400).forEach { status ->
            val connection = FakeHttpURLConnection(
                status = status,
                body = body,
                reportedContentLength = body.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )

            assertEquals(body, readResponseBody(connection, status))
            assertSelectedResponseStream(connection, status)
        }
    }

    @Test
    fun knownLengthSuccessAndErrorBodiesAboveTheCharacterLimitAreRejectedWhileStreaming() {
        val body = "a".repeat(MAX_RESPONSE_CHARS + DEFAULT_BUFFER_SIZE * 4)

        listOf(200, 400).forEach { status ->
            val connection = FakeHttpURLConnection(
                status = status,
                body = body,
                reportedContentLength = body.length.toLong(),
            )

            assertInvalidResponse { readResponseBody(connection, status) }
            assertSelectedResponseStream(connection, status)
            assertTrue(connection.bodyStream.bytesRead <= MAX_RESPONSE_CHARS + DEFAULT_BUFFER_SIZE)
            assertTrue(connection.bodyStream.bytesRead < body.length)
        }
    }

    @Test
    fun contentLengthBeyondTheUtf8EnvelopeIsRejectedBeforeReading() {
        listOf(200, 400).forEach { status ->
            val connection = FakeHttpURLConnection(
                status = status,
                body = "unused",
                reportedContentLength = MAX_RESPONSE_CHARS * 4L + 1,
            )

            assertInvalidResponse { readResponseBody(connection, status) }
            assertEquals(0, connection.bodyStream.bytesRead)
            assertEquals(0, connection.inputStreamRequests)
            assertEquals(0, connection.errorStreamRequests)
        }
    }

    @Test
    fun unknownLengthSuccessAndErrorBodiesAboveTheLimitNeverReturnATruncatedJsonPrefix() {
        val validJsonPrefix = "{\"ok\":true}".padEnd(MAX_RESPONSE_CHARS, ' ')
        val oversizedBody = validJsonPrefix + "x".repeat(DEFAULT_BUFFER_SIZE * 4)

        listOf(200, 400).forEach { status ->
            val connection = FakeHttpURLConnection(
                status = status,
                body = oversizedBody,
                reportedContentLength = -1,
            )

            assertInvalidResponse { readResponseBody(connection, status) }
            assertSelectedResponseStream(connection, status)
            assertTrue(connection.bodyStream.bytesRead <= MAX_RESPONSE_CHARS + DEFAULT_BUFFER_SIZE)
            assertTrue(connection.bodyStream.bytesRead < oversizedBody.length)
        }
    }

    private fun assertSelectedResponseStream(
        connection: FakeHttpURLConnection,
        status: Int,
    ) {
        if (status in 200..299) {
            assertEquals(1, connection.inputStreamRequests)
            assertEquals(0, connection.errorStreamRequests)
        } else {
            assertEquals(0, connection.inputStreamRequests)
            assertEquals(1, connection.errorStreamRequests)
        }
    }

    private fun assertInvalidResponse(block: () -> Unit) {
        try {
            block()
            fail("Expected an invalid response")
        } catch (_: CompanionApiException.InvalidResponse) {
            // Expected.
        }
    }
}

private class FakeHttpURLConnection(
    private val status: Int,
    body: String,
    private val reportedContentLength: Long,
) : HttpURLConnection(URL("https://example.invalid")) {
    val bodyStream = CountingInputStream(body.toByteArray(StandardCharsets.UTF_8))
    var inputStreamRequests = 0
        private set
    var errorStreamRequests = 0
        private set

    override fun getResponseCode(): Int = status

    override fun getContentLengthLong(): Long = reportedContentLength

    override fun getInputStream(): InputStream {
        inputStreamRequests += 1
        return bodyStream
    }

    override fun getErrorStream(): InputStream {
        errorStreamRequests += 1
        return bodyStream
    }

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false
}

private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var bytesRead = 0
        private set

    override fun read(): Int = super.read().also { read ->
        if (read >= 0) bytesRead += 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { read ->
            if (read > 0) bytesRead += read
        }
}
