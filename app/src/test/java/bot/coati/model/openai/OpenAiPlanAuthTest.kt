package bot.coati.model.openai

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPlanAuthTest {
    @Test
    fun `browser login registers a client stores scoped tokens and revokes them`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val store = MemoryPlanStore()
            var cleared = false
            val auth = OpenAiPlanAuth(
                store = store,
                client = OkHttpClient(),
                onClearPlan = { cleared = true },
                authBaseUrl = base,
                apiRoot = base,
                verifier = object : OpenAiIdTokenVerifier {
                    override suspend fun verify(idToken: String, clientId: String, nonce: String?, expectedSubject: String?) =
                        PlanIdentity("subject-1", "dev@example.com", "plus", 4_102_444_800L)
                },
            )
            server.enqueue(json("""{"access_token":"access-1","refresh_token":"refresh-1","id_token":"id-1","expires_in":3600,"scope":"openid offline_access resource.invoke chatgpt.tokens.use.direct"}"""))
            server.enqueue(json("""{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"}]}"""))

            val login = auth.beginBrowserLogin()
            val authorize = login.authorizationUrl.toHttpUrl()
            assertEquals(OpenAiPlanProtocol.DYNAMIC_CLIENT_ID, authorize.queryParameter("client_id"))
            assertEquals("S256", authorize.queryParameter("code_challenge_method"))
            assertTrue(authorize.queryParameter("ext_agent_host_id")!!.startsWith("urn:uuid:"))
            assertTrue(authorize.queryParameter("scope")!!.contains(OpenAiPlanProtocol.REQUIRED_SCOPE))

            val finishing = async(Dispatchers.IO) { auth.finishBrowserLogin(login) }
            val callback = login.redirectUri.toHttpUrl().newBuilder()
                .addQueryParameter("code", "authorization-code")
                .addQueryParameter("state", login.state)
                .addQueryParameter("client_id", "oaiapp_coati_test")
                .build()
            OkHttpClient().newCall(Request.Builder().url(callback).get().build()).execute().use { response ->
                assertEquals(200, response.code)
            }
            val snapshot = finishing.await()

            assertEquals("dev@example.com", snapshot.email)
            assertEquals("gpt-6.1-sol", snapshot.selectedModel)
            assertEquals("oaiapp_coati_test", store.record?.clientId)
            assertEquals("refresh-1", store.record?.refreshToken)
            assertFalse(store.record.toString().contains("refresh-1"))
            val tokenRequest = server.takeRequest()
            assertTrue(tokenRequest.path!!.endsWith("/api/accounts/oauth/token"))
            val tokenBody = tokenRequest.body.readUtf8()
            assertTrue(tokenBody.contains("client_id=oaiapp_coati_test"))
            assertTrue(tokenBody.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
            assertTrue(server.takeRequest().path!!.endsWith("/models"))

            server.enqueue(json("""{"issuer":"$base","jwks_uri":"$base/jwks","revocation_endpoint":"$base/revoke"}"""))
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(auth.signOut())
            assertTrue(cleared)
            assertNull(store.record)
            assertEquals("oaiapp_coati_test", store.registration?.clientId)
            server.takeRequest()
            val revoke = server.takeRequest()
            assertEquals("/revoke", revoke.path)
            assertTrue(revoke.body.readUtf8().contains("refresh-1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway uses public responses route without private backend headers`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val gateway = OpenAiPlanGateway(
                client = OkHttpClient(),
                credential = object : OpenAiPlanCredential {
                    override suspend fun authorization() = OpenAiPlanAuthorization("access-token", "account-1")
                },
                modelName = "gpt-6.1-sol",
                responsesUrl = server.url("/responses").toString(),
            )
            server.enqueue(
                MockResponse().setBody(
                    """data: {"type":"response.output_text.done","text":"hello"}

data: {"type":"response.completed","response":{"status":"completed"}}""",
                ),
            )
            assertEquals("hello", gateway.testConnection().getOrThrow())
            val recorded = server.takeRequest()
            assertEquals("Bearer access-token", recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("ChatGPT-Account-Id"))
            assertNull(recorded.getHeader("originator"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"store\":false"))
            assertFalse(body.contains("max_output_tokens"))

            server.enqueue(MockResponse().setBody("""data: {"type":"response.output_text.delta","delta":"partial"}"""))
            val interrupted = gateway.testConnection().exceptionOrNull()
            assertTrue(interrupted is OpenAiPlanException)
            assertTrue(interrupted?.message.orEmpty().contains("ended before completion"))

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                OpenAiPlanGateway(OkHttpClient(), object : OpenAiPlanCredential {
                    override suspend fun authorization() = OpenAiPlanAuthorization("x", "account-1")
                }, "gpt-6.1-sol", "https://evil.example/v1/responses")
            }
            Unit
        } finally {
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

private class MemoryPlanStore : OpenAiPlanTokenStore {
    var record: OpenAiPlanRecord? = null
    var registration: OpenAiPlanRegistration? = null
    private var hostId: String? = null

    override fun read(): OpenAiPlanRecord? = record

    override fun write(record: OpenAiPlanRecord) {
        this.record = record
        registration = OpenAiPlanRegistration(record.clientId, record.subject, record.email)
    }

    override fun clear() {
        record = null
    }

    override fun readRegistration(): OpenAiPlanRegistration? = registration

    override fun getOrCreateHostId(): String = hostId ?: "urn:uuid:00000000-0000-4000-8000-000000000001".also { hostId = it }
}
