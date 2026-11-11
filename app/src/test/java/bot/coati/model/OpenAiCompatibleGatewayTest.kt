package bot.coati.model

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleGatewayTest {
    @Test
    fun `output budget leaves room for thinking but stays within 8192`() {
        assertEquals(8192, OpenAiCompatibleGateway.withThinking(4096))
        assertEquals(5120, OpenAiCompatibleGateway.withThinking(1024))
        assertEquals(8192, OpenAiCompatibleGateway.withThinking(8192))
        assertEquals(12000, OpenAiCompatibleGateway.withThinking(12000))
    }

    @Test
    fun `only the official OpenAI endpoint selects Responses API`() {
        assertTrue(OpenAiCompatibleGateway.usesResponsesApi("https://api.openai.com"))
        assertTrue(OpenAiCompatibleGateway.usesResponsesApi("https://api.openai.com/v1"))
        assertFalse(OpenAiCompatibleGateway.usesResponsesApi("http://api.openai.com"))
        assertFalse(OpenAiCompatibleGateway.usesResponsesApi("https://api.openai.com.example/v1"))
        assertFalse(OpenAiCompatibleGateway.usesResponsesApi("https://openrouter.ai/api"))
    }

    @Test
    fun `compatible endpoint keeps the Chat Completions request contract`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    JSONObject()
                        .put("choices", org.json.JSONArray().put(
                            JSONObject()
                                .put("message", JSONObject().put("content", "hello"))
                                .put("finish_reason", "stop"),
                        ))
                        .toString(),
                ),
            )
            val endpoint = server.url("/").toString().trimEnd('/')
            val gateway = OpenAiCompatibleGateway(OkHttpClient(), "test-key", endpoint, "test-model")

            assertEquals("hello", gateway.testConnection().getOrThrow())

            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertTrue(body.has("messages"))
            assertFalse(body.has("input"))
            assertFalse(body.has("stream"))
        } finally {
            server.shutdown()
        }
    }
}
