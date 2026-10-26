package bot.coati.model

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HedgedCallTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun call() = client.newCall(Request.Builder().url(server.url("/model")).build())

    @Test fun `a stalled call is overtaken by its hedged duplicate`() = runBlocking {
        server.enqueue(MockResponse().setBody("stalled").setHeadersDelay(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("fast"))
        var hedges = 0
        val started = System.nanoTime()
        val body = withTimeout(10_000) { call().awaitHedgedResponseBody(hedgeAfterMs = 300) { hedges++ } }
        assertEquals("fast", body.body)
        assertEquals(1, hedges)
        assertEquals(2, server.requestCount)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test fun `a prompt call sends no duplicate`() = runBlocking {
        server.enqueue(MockResponse().setBody("prompt"))
        var hedges = 0
        val body = withTimeout(10_000) { call().awaitHedgedResponseBody(hedgeAfterMs = 5_000) { hedges++ } }
        assertEquals("prompt", body.body)
        assertEquals(0, hedges)
        assertEquals(1, server.requestCount)
    }

    @Test fun `cancelling the caller cancels every attempt`() = runBlocking {
        server.enqueue(MockResponse().setBody("a").setHeadersDelay(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("b").setHeadersDelay(3, TimeUnit.SECONDS))
        val pending = async(Dispatchers.IO) { call().awaitHedgedResponseBody(hedgeAfterMs = 200) }
        while (server.requestCount < 2) delay(20)
        withTimeout(2_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        withTimeout(2_000) { while (client.dispatcher.runningCallsCount() > 0) delay(20) }
    }
}
