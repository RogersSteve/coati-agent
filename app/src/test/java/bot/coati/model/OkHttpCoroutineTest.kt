package bot.coati.model

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OkHttpCoroutineTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val headers = CountDownLatch(1)
    private val ended = CountDownLatch(1)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
                override fun callEnd(call: Call) { ended.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { ended.countDown() }
            }).build()
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun call() = client.newCall(Request.Builder().url(server.url("/")).build())

    @Test fun `normal and HTTP error bodies are fully read and released`() = runBlocking {
        for (code in listOf(200, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("answer-$code"))
            val result = withTimeout(3_000) { call().awaitResponseBody() }
            assertEquals(code, result.code)
            assertEquals("answer-$code", result.body)
            assertEquals(code == 200, result.isSuccessful)
        }
        assertTrue(ended.await(3, TimeUnit.SECONDS))
    }

    @Test fun `deadline cancels a request that never returns headers`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = call()
        val pending = async(Dispatchers.IO) {
            withTimeoutOrNull(500) { call.awaitResponseBody() }
        }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertNull(withTimeout(3_000) { pending.await() })
        assertTrue(call.isCanceled())
        assertTrue("HTTP call must finish after cancellation", ended.await(3, TimeUnit.SECONDS))
    }

    @Test fun `parent cancellation interrupts stalled body after headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("answer").setBodyDelay(2, TimeUnit.SECONDS))
        val call = call()
        val pending = async(Dispatchers.IO) { call.awaitResponseBody() }
        assertTrue(headers.await(3, TimeUnit.SECONDS))
        assertFalse(pending.isCompleted)
        withTimeout(1_000) { pending.cancelAndJoin() }
        assertTrue(call.isCanceled())
        assertTrue(ended.await(1, TimeUnit.SECONDS))
    }

    @Test fun `deadline interrupts continuously trickling body`() = runBlocking {
        server.enqueue(MockResponse().setBody("a".repeat(100)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val call = call()
        val result = withTimeoutOrNull(500) { call.awaitResponseBody() }
        assertNull(result)
        assertTrue(call.isCanceled())
        assertTrue(ended.await(2, TimeUnit.SECONDS))
    }

    @Test fun `cancelling one request leaves another on the same client usable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val oldCall = call()
        val old = async(Dispatchers.IO) { oldCall.awaitResponseBody() }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("new task"))
        val newCall = call()
        val fresh = async(Dispatchers.IO) { newCall.awaitResponseBody() }
        old.cancelAndJoin()
        assertEquals("new task", withTimeout(3_000) { fresh.await() }.body)
        assertTrue(oldCall.isCanceled())
        assertFalse(newCall.isCanceled())
    }

    @Test fun `cancelled context does not enqueue a request`() = runBlocking {
        val call = call()
        val pending = async(Dispatchers.IO) {
            currentCoroutineContext().cancel()
            call.awaitResponseBody()
        }
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(call.isExecuted())
        assertEquals(0, server.requestCount)
    }

    @Test fun `response cancellation race never delivers after cancellation`() = runBlocking {
        repeat(30) {
            server.enqueue(MockResponse().setBody("answer"))
            val call = call()
            val pending = async(Dispatchers.IO) { call.awaitResponseBody() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            pending.cancel()
            try {
                val result = withTimeout(3_000) { pending.await() }
                // Completion may win before cancel, but an active cancellation cannot return success.
                assertFalse(pending.isCancelled)
                assertEquals("answer", result.body)
            } catch (_: CancellationException) {
                assertTrue(pending.isCancelled)
                assertTrue(call.isCanceled())
            }
        }
    }
}
