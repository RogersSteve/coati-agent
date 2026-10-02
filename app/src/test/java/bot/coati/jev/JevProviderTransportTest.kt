package bot.coati.jev

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class JevProviderTransportTest {
    @Test fun `compatible providers send the pinned model to System One`() = runBlocking {
        JevProviders.all.forEach { provider ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"model":"${provider.model}","answers":{"ping":{"choice":"yes","confidence":1,"probabilities":{"yes":1,"no":0}}},"usage":{"input_tokens":3,"cost":0.001}}"""))
                server.start()
                val prefix = if (provider.id == "openrouter") "/api" else ""
                val result = JevClient(OkHttpClient(), "test-key", server.url(prefix).toString(), provider.model).testConnection()
                assertTrue(result.isSuccess)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("$prefix/v1/systemone", request.path)
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                assertEquals(provider.model, JSONObject(request.body.readUtf8()).getString("model"))
            }
        }
    }

    @Test fun `an empty answers response is not a successful connection check`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"model":"jev-1.13","answers":{},"usage":{}}"""))
            server.start()
            val result = JevClient(OkHttpClient(), "test-key", server.url("/api").toString(), "jev-1.13").testConnection()
            assertTrue(result.isFailure)
        }
    }
}
