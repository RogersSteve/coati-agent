package bot.coati.model

import org.json.JSONObject

class StructuredTextRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Double = 0.1,
    val maxOutputTokens: Int,
    /** Optional JPEG attached to the user turn. */
    val imageJpeg: ByteArray? = null,
    /** Ask for minimal model-side thinking; providers that reject the hint are retried without it. */
    val lowLatency: Boolean = false,
)

enum class StructuredTextFinishReason {
    COMPLETE,
    TRUNCATED,
    FILTERED,
    INTERRUPTED,
    UNKNOWN,
}

data class StructuredTextResult(
    val text: String,
    val finishReason: StructuredTextFinishReason,
    val outputTokens: Int? = null,
)

/** Provider response normalization kept separate so it is JVM-testable without network calls. */
internal object StructuredTextResponseParser {
    fun openAi(body: String): StructuredTextResult {
        val root = JSONObject(body)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        return StructuredTextResult(
            text = nullableString(choice.getJSONObject("message"), "content").orEmpty().trim(),
            finishReason = normalizeOpenAi(nullableString(choice, "finish_reason")),
            outputTokens = nullableInt(root.optJSONObject("usage"), "completion_tokens"),
        )
    }

    fun claude(body: String): StructuredTextResult {
        val root = JSONObject(body)
        val content = root.optJSONArray("content")
        return StructuredTextResult(
            text = content?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.let { nullableString(it, "text") }.orEmpty().trim(),
            finishReason = normalizeClaude(nullableString(root, "stop_reason")),
            outputTokens = nullableInt(root.optJSONObject("usage"), "output_tokens"),
        )
    }

    fun gemini(body: String): StructuredTextResult {
        val root = JSONObject(body)
        val candidate = root.getJSONArray("candidates").getJSONObject(0)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        return StructuredTextResult(
            text = parts?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.let { nullableString(it, "text") }.orEmpty().trim(),
            finishReason = normalizeGemini(nullableString(candidate, "finishReason")),
            outputTokens = nullableInt(root.optJSONObject("usageMetadata"), "candidatesTokenCount"),
        )
    }

    private fun normalizeOpenAi(reason: String?): StructuredTextFinishReason = when (reason?.lowercase()) {
        "stop" -> StructuredTextFinishReason.COMPLETE
        "length" -> StructuredTextFinishReason.TRUNCATED
        "content_filter" -> StructuredTextFinishReason.FILTERED
        "tool_calls", "function_call" -> StructuredTextFinishReason.INTERRUPTED
        null -> StructuredTextFinishReason.UNKNOWN
        else -> StructuredTextFinishReason.INTERRUPTED
    }

    private fun normalizeClaude(reason: String?): StructuredTextFinishReason = when (reason?.lowercase()) {
        "end_turn", "stop_sequence" -> StructuredTextFinishReason.COMPLETE
        "max_tokens" -> StructuredTextFinishReason.TRUNCATED
        "refusal" -> StructuredTextFinishReason.FILTERED
        "tool_use", "pause_turn" -> StructuredTextFinishReason.INTERRUPTED
        null -> StructuredTextFinishReason.UNKNOWN
        else -> StructuredTextFinishReason.INTERRUPTED
    }

    private fun normalizeGemini(reason: String?): StructuredTextFinishReason = when (reason?.uppercase()) {
        "STOP" -> StructuredTextFinishReason.COMPLETE
        "MAX_TOKENS" -> StructuredTextFinishReason.TRUNCATED
        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "RECITATION" -> {
            StructuredTextFinishReason.FILTERED
        }
        null -> StructuredTextFinishReason.UNKNOWN
        else -> StructuredTextFinishReason.INTERRUPTED
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.get(key) as? String ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun nullableInt(json: JSONObject?, key: String): Int? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return (json.get(key) as? Number)?.toInt()
            ?: throw IllegalArgumentException("$key must be a number")
    }
}
