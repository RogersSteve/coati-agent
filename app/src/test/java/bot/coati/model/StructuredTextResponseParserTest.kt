package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredTextResponseParserTest {
    @Test
    fun `openai parser normalizes stop and usage`() {
        val result = StructuredTextResponseParser.openAi(
            """{"choices":[{"message":{"content":" {\"ok\":true} "},"finish_reason":"stop"}],"usage":{"completion_tokens":17}}""",
        )

        assertEquals("{\"ok\":true}", result.text)
        assertEquals(StructuredTextFinishReason.COMPLETE, result.finishReason)
        assertEquals(17, result.outputTokens)
    }

    @Test
    fun `claude parser identifies max token truncation`() {
        val result = StructuredTextResponseParser.claude(
            """{"content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens","usage":{"output_tokens":23}}""",
        )

        assertEquals(StructuredTextFinishReason.TRUNCATED, result.finishReason)
        assertEquals(23, result.outputTokens)
    }

    @Test
    fun `gemini parser identifies filtering and missing reason remains compatible`() {
        val filtered = StructuredTextResponseParser.gemini(
            """{"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"candidatesTokenCount":5}}""",
        )
        val unknown = StructuredTextResponseParser.gemini(
            """{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}""",
        )

        assertEquals(StructuredTextFinishReason.FILTERED, filtered.finishReason)
        assertEquals(5, filtered.outputTokens)
        assertEquals(StructuredTextFinishReason.UNKNOWN, unknown.finishReason)
    }
}
