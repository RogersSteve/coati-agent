package bot.coati.model

import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResilientModelGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: CloudModelGateway

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        server = MockWebServer()
        server.start()
        val endpoint = server.url("/").toString().trimEnd('/')
        gateway = ResilientModelGateway(
            OpenAiCompatibleGateway(OkHttpClient(), "test-key", endpoint, "test-model"),
            backoffMs = 0,
        )
    }

    @After fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
        unmockkStatic(Base64::class)
    }

    private fun completion(content: String) = MockResponse().setBody(
        JSONObject().put("choices", org.json.JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", content)),
        )).toString(),
    )

    private suspend fun analyze() = gateway.analyzeScreen(byteArrayOf(1), "{}", "task", emptyList())

    @Test fun `truncated reasoning JSON is retried`() = runBlocking {
        server.enqueue(completion("""{"reasoning": "The Allow button is vis"""))
        server.enqueue(completion("""{"reasoning": "ok", "action": "tap", "x": 500, "y": 500}"""))

        val result = analyze()

        assertTrue(result.isSuccess)
        assertEquals("tap", result.getOrThrow().action)
        assertEquals(2, server.requestCount)
    }

    @Test fun `out of schema coordinates are retried`() = runBlocking {
        server.enqueue(completion("""{"action": "tap", "x": 1080, "y": 1860}"""))
        server.enqueue(completion("```json"))
        server.enqueue(completion("""{"action": "tap", "x": 500, "y": 775}"""))

        val result = analyze()

        assertEquals(775, result.getOrThrow().y)
        assertEquals(3, server.requestCount)
    }

    @Test fun `server errors are retried up to the attempt limit`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }

        val result = analyze()

        assertEquals(502, (result.exceptionOrNull() as ApiHttpException).statusCode)
        assertEquals(ResilientModelGateway.DEFAULT_MAX_ATTEMPTS, server.requestCount)
    }

    @Test fun `auth errors are not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = analyze()

        assertTrue(result.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `connection tests pass through without retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        gateway.testConnection()

        assertEquals(1, server.requestCount)
    }

    @Test fun `transient classification`() {
        assertTrue(ResilientModelGateway.isTransient(ApiHttpException(429), retryMalformed = false))
        assertTrue(ResilientModelGateway.isTransient(ApiHttpException(503), retryMalformed = false))
        assertFalse(ResilientModelGateway.isTransient(ApiHttpException(400), retryMalformed = false))
        assertTrue(ResilientModelGateway.isTransient(IOException("reset"), retryMalformed = false))
        assertFalse(ResilientModelGateway.isTransient(SocketTimeoutException("timeout"), retryMalformed = false))
        assertTrue(ResilientModelGateway.isTransient(JSONException("Unterminated object"), retryMalformed = true))
        assertFalse(ResilientModelGateway.isTransient(JSONException("Unterminated object"), retryMalformed = false))
        val malformed = MalformedModelResponseException("invalid_normalized_coordinate", IllegalArgumentException())
        assertTrue(ResilientModelGateway.isTransient(malformed, retryMalformed = true))
        assertFalse(ResilientModelGateway.isTransient(malformed, retryMalformed = false))
        assertFalse(ResilientModelGateway.isTransient(IllegalStateException("bug"), retryMalformed = true))
    }
}
