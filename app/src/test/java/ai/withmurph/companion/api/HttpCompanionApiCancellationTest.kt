package ai.withmurph.companion.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpCompanionApiCancellationTest {
    @Test
    fun cancellationWhileWaitingForIdentityTokenNeverOpensAConnection() = runBlocking {
        val tokenEntered = CompletableDeferred<Unit>()
        val tokenGate = CompletableDeferred<String>()
        val openedConnections = AtomicInteger()
        val api = HttpCompanionApi(
            baseUrl = "https://network-must-not-run.invalid",
            identityTokenForMember = {
                tokenEntered.complete(Unit)
                tokenGate.await()
            },
            openConnectionForTest = {
                openedConnections.incrementAndGet()
                error("Connection must not open after token cancellation")
            },
        )

        val request = launch(Dispatchers.Default) {
            api.fetchAddressBookStatus("member-a")
        }
        tokenEntered.await()
        request.cancel()
        withTimeout(5_000) { request.join() }

        assertTrue(request.isCancelled)
        assertEquals(0, openedConnections.get())
    }

    @Test
    fun cancellationClosesAStartedRequestBodyAndDisconnects() = runBlocking {
        val connection = BlockingWriteConnection()

        val request = launch(Dispatchers.Default) {
            executeHttpRequest(
                openConnection = { connection },
                url = URL("https://network-must-not-run.invalid/request"),
                method = "PUT",
                token = "identity-token",
                body = "{}",
            )
        }
        assertTrue(connection.body.writeEntered.await(5, TimeUnit.SECONDS))
        request.cancel()
        withTimeout(5_000) { request.join() }

        assertTrue(request.isCancelled)
        assertTrue(connection.body.closeObserved.await(5, TimeUnit.SECONDS))
        assertTrue(connection.disconnectObserved.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationClosesAStartedResponseBodyAndDisconnects() = runBlocking {
        val connection = BlockingReadConnection()

        val request = launch(Dispatchers.Default) {
            executeHttpRequest(
                openConnection = { connection },
                url = URL("https://network-must-not-run.invalid/request"),
                method = "GET",
                token = "identity-token",
                body = null,
            )
        }
        assertTrue(connection.body.readEntered.await(5, TimeUnit.SECONDS))
        request.cancel()
        withTimeout(5_000) { request.join() }

        assertTrue(request.isCancelled)
        assertTrue(connection.body.closeObserved.await(5, TimeUnit.SECONDS))
        assertTrue(connection.disconnectObserved.await(5, TimeUnit.SECONDS))
    }

}

private abstract class ObservableConnection : HttpURLConnection(
    URL("https://network-must-not-run.invalid"),
) {
    val disconnectObserved = CountDownLatch(1)

    override fun connect() = Unit

    override fun disconnect() {
        disconnectObserved.countDown()
    }

    override fun usingProxy(): Boolean = false
}

private class BlockingWriteConnection : ObservableConnection() {
    val body = CloseReleasedOutputStream()

    override fun getOutputStream(): OutputStream = body

    override fun getResponseCode(): Int = error("Cancellation should stop before a response")
}

private class BlockingReadConnection : ObservableConnection() {
    val body = CloseReleasedInputStream()

    override fun getResponseCode(): Int = 200

    override fun getContentLengthLong(): Long = -1

    override fun getInputStream(): InputStream = body
}

private class CloseReleasedOutputStream : OutputStream() {
    val writeEntered = CountDownLatch(1)
    val closeObserved = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile
    private var closed = false

    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writeEntered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        if (closed) throw IOException("request body closed")
    }

    override fun close() {
        closed = true
        closeObserved.countDown()
        release.countDown()
    }
}

private class CloseReleasedInputStream : InputStream() {
    val readEntered = CountDownLatch(1)
    val closeObserved = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        readEntered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        if (closed) throw IOException("response body closed")
        return -1
    }

    override fun close() {
        closed = true
        closeObserved.countDown()
        release.countDown()
    }
}
