package bot.coati.model

internal enum class StructuredPayloadFormat { JSON, MARKDOWN }

internal data class StructuredTextPayload(
    val text: String,
    val removedThinkingPrefix: Boolean,
    val removedCodeFence: Boolean,
)

internal class StructuredPayloadException(val reasonCode: String) : IllegalArgumentException(reasonCode)

/**
 * Unwraps a known model-service envelope, NOT a JSON repair/parser.
 * The caller must still validate finish reason, exactly one payload, schema and semantics.
 * Never search for braces, remove arbitrary prose, or rewrite anything inside the payload.
 */
internal object StructuredTextPayloadExtractor {
    private const val MAX_RESPONSE_CHARS = 200_000

    fun extract(raw: String, format: StructuredPayloadFormat): StructuredTextPayload {
        if (raw.length > MAX_RESPONSE_CHARS) fail("RESPONSE_TOO_LARGE")
        var text = raw.trim()
        var thinking = false
        var fence = false
        if (text.startsWith("<think>")) {
            val end = text.indexOf("</think>", startIndex = 7)
            if (end < 0) fail("UNCLOSED_THINK_PREFIX")
            if (text.substring(7, end).contains("<think>")) fail("NESTED_THINK_PREFIX")
            text = text.substring(end + 8).trim()
            thinking = true
        }
        if (text.startsWith("<think>") || text.startsWith("</think>")) fail("MULTIPLE_THINK_PREFIXES")
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline < 0) fail("UNCLOSED_CODE_FENCE")
            val language = text.substring(3, firstNewline).trim().lowercase()
            val allowed = when (format) {
                StructuredPayloadFormat.JSON -> setOf("", "json")
                StructuredPayloadFormat.MARKDOWN -> setOf("", "md", "markdown")
            }
            if (language !in allowed) fail("UNSUPPORTED_CODE_FENCE")
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline <= firstNewline || text.substring(lastNewline + 1) != "```") fail("UNCLOSED_CODE_FENCE")
            text = text.substring(firstNewline + 1, lastNewline).trim()
            fence = true
        }
        if (text.isEmpty()) fail("EMPTY_PAYLOAD")
        val validStart = when (format) {
            StructuredPayloadFormat.JSON -> text.startsWith("{")
            StructuredPayloadFormat.MARKDOWN -> text.startsWith("# ")
        }
        if (!validStart) fail("UNEXPECTED_PAYLOAD_ENVELOPE")
        return StructuredTextPayload(text, thinking, fence)
    }

    private fun fail(reason: String): Nothing = throw StructuredPayloadException(reason)
}
