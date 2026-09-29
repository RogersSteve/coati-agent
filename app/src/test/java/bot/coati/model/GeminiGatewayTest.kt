package bot.coati.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
