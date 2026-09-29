package bot.coati.model

import org.junit.Assert.*
import org.junit.Test

class StructuredTextPayloadTest {
    private val json = """{"text":"literal <think> and </think>, braces {}, and ``` stay unchanged"}"""

    @Test fun `plain payload is unchanged including wrapper text inside a JSON value`() {
        val result = StructuredTextPayloadExtractor.extract("  $json\n", StructuredPayloadFormat.JSON)
        assertEquals(json, result.text)
        assertFalse(result.removedThinkingPrefix)
        assertFalse(result.removedCodeFence)
    }

    @Test fun `one leading complete thinking block can precede a recognized outer fence`() {
        val result = StructuredTextPayloadExtractor.extract("<think>reason {not JSON}</think>\n```json\n$json\n```", StructuredPayloadFormat.JSON)
        assertEquals(json, result.text)
        assertTrue(result.removedThinkingPrefix)
        assertTrue(result.removedCodeFence)
    }

    @Test fun `CRLF and language-less outer JSON fence are supported`() {
        assertEquals(json, StructuredTextPayloadExtractor.extract("```\r\n$json\r\n```\r\n", StructuredPayloadFormat.JSON).text)
    }

    @Test fun `malformed or ambiguous envelopes fail rather than finding arbitrary braces`() {
        val samples = mapOf(
            "<think>not closed {}" to "UNCLOSED_THINK_PREFIX",
            "<think>a<think>b</think>$json" to "NESTED_THINK_PREFIX",
            "<think>a</think><think>b</think>$json" to "MULTIPLE_THINK_PREFIXES",
            "</think>$json" to "MULTIPLE_THINK_PREFIXES",
            "Here is JSON: $json" to "UNEXPECTED_PAYLOAD_ENVELOPE",
            "```json\n$json" to "UNCLOSED_CODE_FENCE",
            "```json\n$json\n```\ntrailing prose" to "UNCLOSED_CODE_FENCE",
            "```javascript\n$json\n```" to "UNSUPPORTED_CODE_FENCE",
            "<think>only thought</think>" to "EMPTY_PAYLOAD",
            "[]" to "UNEXPECTED_PAYLOAD_ENVELOPE",
            "x".repeat(200_001) to "RESPONSE_TOO_LARGE",
        )
        samples.forEach { (raw, reason) ->
            val error = runCatching { StructuredTextPayloadExtractor.extract(raw, StructuredPayloadFormat.JSON) }.exceptionOrNull()
            assertTrue("Expected $reason", error is StructuredPayloadException)
            assertEquals(reason, (error as StructuredPayloadException).reasonCode)
        }
    }

    @Test fun `extractor leaves trailing payload untouched for strict schema parser to reject`() {
        val raw = "$json\n{}"
        assertEquals(raw, StructuredTextPayloadExtractor.extract("<think>a</think>$raw", StructuredPayloadFormat.JSON).text)
    }

    @Test fun `Markdown envelope is separate and preserves document internals`() {
        val markdown = "# Manual\n\n## Page\n- Literal <think> text.\n\n```text\nexample\n```"
        assertEquals(markdown, StructuredTextPayloadExtractor.extract("<think>summary</think>\n```markdown\n$markdown\n```", StructuredPayloadFormat.MARKDOWN).text)
        assertEquals(markdown, StructuredTextPayloadExtractor.extract(markdown, StructuredPayloadFormat.MARKDOWN).text)
        assertTrue(runCatching { StructuredTextPayloadExtractor.extract(json, StructuredPayloadFormat.MARKDOWN) }.isFailure)
    }

    @org.junit.Test
    fun `prompts with an image name the image so text-keyed caches cannot mix screenshots`() {
        val a = StructuredTextRequest("s", "parse", maxOutputTokens = 10, imageJpeg = byteArrayOf(1, 2, 3))
        val b = StructuredTextRequest("s", "parse", maxOutputTokens = 10, imageJpeg = byteArrayOf(4, 5, 6))
        org.junit.Assert.assertNotEquals(a.promptText, b.promptText)
        org.junit.Assert.assertEquals("parse", StructuredTextRequest("s", "parse", maxOutputTokens = 10).promptText)
    }
}
