package bot.coati.model.openai

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPlanProtocolTest {
    @Test
    fun `oauth forms use issued client resource and renewable session fields`() {
        val exchange = OpenAiPlanProtocol.authorizationForm(
            "oaiapp_test",
            "secret-code",
            "secret-verifier",
            "http://127.0.0.1:4321/auth/callback",
        )
        assertTrue(exchange.contains("client_id=oaiapp_test"))
        assertTrue(exchange.contains("code=secret-code"))
        assertTrue(exchange.contains("code_verifier=secret-verifier"))
        assertTrue(exchange.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))

        val refresh = OpenAiPlanProtocol.refreshForm("oaiapp_test", "refresh-secret")
        assertTrue(refresh.contains("grant_type=refresh_token"))
        assertTrue(refresh.contains("client_id=oaiapp_test"))
        val revoke = OpenAiPlanProtocol.revokeForm("oaiapp_test", "refresh-secret")
        assertTrue(revoke.contains("token_type_hint=refresh_token"))
    }

    @Test
    fun `token response keeps scopes and hides secrets`() {
        val tokens = OpenAiPlanProtocol.parseOAuthTokens(
            """{"access_token":"access-secret","refresh_token":"refresh-secret","id_token":"id-secret","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct"}""",
        )
        assertEquals("openid chatgpt.tokens.use.direct", tokens.scope)
        assertEquals(3600L, tokens.expiresInSeconds)
        assertEquals("OAuthTokens", tokens.toString())
        assertFalse(tokens.toString().contains("secret"))
    }

    @Test
    fun `models keep only visible usable entries`() {
        val models = OpenAiPlanProtocol.parseModels(
            """{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"bad model","visibility":"list"}]}""",
        )
        assertEquals(listOf("gpt-6.1-sol"), models.map { it.slug })
    }

    @Test
    fun `response stream prefers the completed text and drops unsafe errors`() {
        val sse = "event: response.output_text.delta\r\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hel\"}\r\n\r\n" +
            "data: {\"type\":\"response.output_text.done\",\"text\":\"hello\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\r\n\r\n" +
            "data: [DONE]"
        val output = OpenAiPlanProtocol.parseResponseStream(sse)
        assertEquals("hello", output.text)
        assertNull(output.failedDetail)
        assertTrue(output.terminal)

        val failed = OpenAiPlanProtocol.parseResponseStream("""data: {"type":"error","error":{"message":"eyJhbGciOiJub25lIn0.secret"}}""")
        assertEquals("The model request was rejected.", failed.failedDetail)
        assertTrue(failed.terminal)
        assertNull(OpenAiPlanProtocol.publicErrorDetail("refresh token leaked"))
    }

    @Test
    fun `interrupted response stream is not terminal`() {
        val output = OpenAiPlanProtocol.parseResponseStream(
            """data: {"type":"response.output_text.delta","delta":"partial"}""",
        )
        assertEquals("partial", output.text)
        assertFalse(output.terminal)
    }

    @Test
    fun `request body uses supported plan fields and record strings hide credentials`() {
        val body = OpenAiPlanProtocol.responsesBody(
            "gpt-6.1-sol",
            "Be concise.",
            JSONArray().put(OpenAiPlanProtocol.textPart("hi")),
        )
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("max_output_tokens"))
        assertFalse(body.toString().contains("access_token"))
        val record = OpenAiPlanRecord(
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
            idToken = null,
            accessExpiryEpochMs = 0,
            clientId = "oaiapp_test",
            subject = "subject-1",
            scopes = "openid chatgpt.tokens.use.direct",
            accountId = "account-1",
            email = null,
            planType = null,
            fedramp = false,
            accountsJson = "[]",
            modelsJson = "[]",
            selectedModel = null,
        )
        assertFalse(record.toString().contains("secret"))
    }
}
