package bot.coati.model

import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GatewayCancellationTest(private val format: String, private val operation: String) {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var gateway: CloudModelGateway
    private val headers = CountDownLatch(1)
    private val ended = CountDownLatch(1)

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            override fun callEnd(call: Call) { ended.countDown() }
            override fun callFailed(call: Call, ioe: IOException) { ended.countDown() }
        }).build()
        val endpoint = server.url("/").toString().trimEnd('/')
        gateway = when (format) {
            "openai" -> OpenAiCompatibleGateway(client, "test-key", endpoint, "test-model")
            "claude" -> ClaudeCompatibleGateway(client, "test-key", endpoint, "test-model")
            else -> GeminiGateway(client, "test-key", endpoint, "test-model")
        }
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
        unmockkStatic(Log::class)
        unmockkStatic(Base64::class)
    }

    private suspend fun invoke(): Result<*> = when (operation) {
        "analyze" -> gateway.analyzeScreen(byteArrayOf(1), "{}", "task", emptyList())
        "quick" -> gateway.quickCheck(byteArrayOf(1), "question")
        "verify" -> gateway.verifyAction(byteArrayOf(1), byteArrayOf(2), "tap", "changed")
        "summary" -> gateway.summarizeText("summarize")
        "structured" -> gateway.generateStructuredText(StructuredTextRequest("system", "user", maxOutputTokens = 64))
        else -> gateway.testConnection()
    }

    @Test fun `cancellation while reading body propagates without failure result`() = runBlocking {
        server.enqueue(MockResponse().setBody("response").setBodyDelay(2, TimeUnit.SECONDS))
        var returnedResult = false
        val pending = async(Dispatchers.IO) {
            invoke()
            returnedResult = true
        }
        assertTrue(headers.await(5, TimeUnit.SECONDS))
        withTimeout(1_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertFalse("Cancellation must not become Result.failure", returnedResult)
        assertTrue("Underlying HTTP must terminate", ended.await(1, TimeUnit.SECONDS))
    }

    @Test fun `ordinary HTTP failure remains Result failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val result = withTimeout(5_000) { invoke() }
        assertTrue(result.exceptionOrNull() is ApiHttpException)
        assertTrue(ended.await(1, TimeUnit.SECONDS))
    }

    @Test fun `successful provider response retains existing parsing`() = runBlocking {
        val text = when (operation) {
            "analyze" -> """{"action":"screenshot","reasoning":"ok","params":{},"isTaskComplete":false}"""
            "quick" -> """{"answer":true,"confidence":1.0}"""
            "verify" -> """{"outcome":"SUCCESS","reason":"ok"}"""
            else -> "hello"
        }
        val quoted = JSONObject.quote(text)
        val body = when (format) {
            "openai" -> """{"choices":[{"message":{"content":$quoted},"finish_reason":"stop"}]}"""
            "claude" -> """{"content":[{"type":"text","text":$quoted}],"stop_reason":"end_turn"}"""
            else -> """{"candidates":[{"content":{"parts":[{"text":$quoted}]},"finishReason":"STOP"}]}"""
        }
        server.enqueue(MockResponse().setBody(body))
        val value = withTimeout(5_000) { invoke().getOrThrow() }
        when (operation) {
            "analyze" -> assertEquals("screenshot", (value as ModelResponse).action)
            "quick" -> assertTrue((value as QuickCheckResult).answer)
            "verify" -> assertEquals(VerifyOutcome.SUCCESS, (value as VerifyResult).outcome)
            "structured" -> assertEquals("hello", (value as StructuredTextResult).text)
            else -> assertEquals("hello", value)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<String>> = listOf("openai", "claude", "gemini").flatMap { format ->
            listOf("analyze", "quick", "verify", "summary", "structured", "connection").map { arrayOf(format, it) }
        }
    }
}
