package bot.coati.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiGatewayTest {

    @Test
    fun `the answer joins every non-thought text part`() {
        val candidate = JSONObject(
            """{"content":{"parts":[
                {"text":"planning the tap...","thought":true},
                {"text":"{\"action\":\"tap\","},
                {"text":"\"x\":500}","thoughtSignature":"abc"}
            ]},"finishReason":"STOP"}""",
        )

        assertEquals("""{"action":"tap","x":500}""", GeminiGateway.extractAnswerText(candidate))
    }

    @Test
    fun `output budgets leave room for thinking`() {
        assertEquals(4096 + GeminiGateway.THINKING_HEADROOM_TOKENS, GeminiGateway.withThinking(4096))
    }


    @Test
    fun `a body without candidates names its block and finish reasons`() {
        val blocked = JSONObject("""{"promptFeedback":{"blockReason":"OTHER"},"usageMetadata":{"promptTokenCount":51234}}""")
        assertNull(GeminiGateway.firstAnsweringCandidate(blocked))
        val detail = GeminiGateway.emptyResponseDetail(blocked)
        assertTrue(detail.contains("blockReason=OTHER"))
        assertTrue(detail.contains("promptTokens=51234"))

        val cut = JSONObject("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        assertNull(GeminiGateway.firstAnsweringCandidate(cut))
        assertTrue(GeminiGateway.emptyResponseDetail(cut).contains("finishReason=MAX_TOKENS"))

        val ok = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""")
        assertEquals("{}", GeminiGateway.extractAnswerText(GeminiGateway.firstAnsweringCandidate(ok)!!))
    }
}
