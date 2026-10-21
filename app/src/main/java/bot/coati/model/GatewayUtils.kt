package bot.coati.model

import bot.coati.agent.PromptBuilder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The model's reply could not be read as an action: cut-off or non-JSON text, or values outside the
 * response schema (such as pixel instead of normalized coordinates). Asking again can succeed.
 */
class MalformedModelResponseException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

/**
 * Output budget for the compact verify/quick-check JSON. Thinking models (Gemini 3.x, GPT-5.x)
 * spend part of max_tokens on hidden reasoning; the former 128/256 budgets truncated the JSON to
 * nothing, so completion audits came back "unavailable" and verification fell to UNKNOWN.
 */
internal fun compactJsonMaxTokens(withManualObservation: Boolean): Int = if (withManualObservation) 2048 else 1024

object GatewayUtils {

    /** Entries one response may add to the task notes; the notes themselves are bounded in TaskNotes. */
    private const val MAX_NOTES_PER_RESPONSE = 30

    /** @deprecated Use PromptBuilder.buildReasoningSystemPrompt() instead. */
    @Deprecated("Use PromptBuilder.buildReasoningSystemPrompt()", ReplaceWith("PromptBuilder.buildReasoningSystemPrompt()"))
    val SYSTEM_PROMPT: String get() = PromptBuilder.buildReasoningSystemPrompt()

    /** @deprecated Use PromptBuilder.buildReasoningUserPrompt() instead. */
    @Deprecated("Use PromptBuilder.buildReasoningUserPrompt()", ReplaceWith("PromptBuilder.buildReasoningUserPrompt(uiTreeHint, taskDescription, 0, 0)"))
    fun buildUserPrompt(uiTreeHint: String, taskDescription: String): String {
        return PromptBuilder.buildReasoningUserPrompt(uiTreeHint, taskDescription, 0, 0)
    }

    fun extractJsonFromText(text: String): String {
        // Try to extract JSON from markdown code blocks
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockRegex.find(text)
        if (match != null) {
            return match.groupValues[1]
        }
        // Try to find raw JSON object
        val jsonRegex = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonRegex.find(text)
        if (jsonMatch != null) {
            return jsonMatch.value
        }
        return text
    }

    fun parseModelResponse(text: String): ModelResponse = try {
        parseModelResponseStrict(text)
    } catch (e: JSONException) {
        throw MalformedModelResponseException(malformedMessage(e, text), e)
    } catch (e: IllegalArgumentException) {
        throw MalformedModelResponseException(malformedMessage(e, text), e)
    }

    private fun malformedMessage(error: Exception, text: String): String =
        "${error.message} (response length ${text.length}, starts: ${text.take(40).replace('\n', ' ')})"

    private fun parseModelResponseStrict(text: String): ModelResponse {
        val jsonStr = extractJsonFromText(text)
        val json = JSONObject(jsonStr)

        val params = mutableMapOf<String, Any>()
        val paramsJson = json.optJSONObject("params")
        if (paramsJson != null) {
            for (key in paramsJson.keys()) {
                params[key] = paramsJson.get(key)
            }
        }

        val x = parseCoordinate(json, "x")
        val y = parseCoordinate(json, "y")

        val usedManualHeadings = json.optJSONArray("usedManualHeadings")?.let { array ->
            buildList {
                for (index in 0 until minOf(array.length(), 8)) {
                    val id = array.optString(index, "").trim()
                    if (id.isNotEmpty()) add(id)
                }
            }.distinct()
        }.orEmpty()

        val manualObservation = parseManualObservation(json)

        return ModelResponse(
            reasoning = json.optString("reasoning", ""),
            action = json.getString("action"),
            params = params,
            safetyLevel = json.optString("safetyLevel", "medium"),
            isTaskComplete = json.optBoolean("isTaskComplete", false),
            statusUpdate = json.optString("statusUpdate", ""),
            x = x,
            y = y,
            expectedOutcome = json.optString("expectedOutcome", ""),
            usedManualHeadings = usedManualHeadings,
            manualObservation = manualObservation,
            answer = json.answerText("answer"),
            loop = parseLoopPlan(json),
            notes = parseNotes(json),
        )
    }

    /** The optional "notes": an array of entries, or one string with an entry per line. */
    internal fun parseNotes(json: JSONObject): List<String> {
        if (!json.has("notes") || json.isNull("notes")) return emptyList()
        val values = when (val value = json.get("notes")) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                value.opt(index)?.takeUnless { it == JSONObject.NULL }?.toString()
            }
            is String -> value.lines()
            else -> return emptyList()
        }
        return values.map { it.trim().removePrefix("- ").trim() }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            .take(MAX_NOTES_PER_RESPONSE)
    }

    /** The optional "loop" object; null when absent, malformed, or without a per-item action. */
    internal fun parseLoopPlan(json: JSONObject): LoopPlan? {
        val loop = json.optJSONObject("loop") ?: return null
        val each = loop.optString("each", "").trim().takeIf { it.isNotEmpty() } ?: return null
        val count = loop.opt("count").let { (it as? Number)?.toInt() ?: (it as? String)?.trim()?.toIntOrNull() }
            ?.takeIf { it > 0 }
        fun text(key: String) = loop.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        return LoopPlan(each = each, count = count, until = text("until"), scope = text("scope"))
    }

    private fun parseCoordinate(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite() &&
            value.toDouble() == value.toInt().toDouble() && value.toInt() in 0..1000) { "invalid_normalized_coordinate $key=$value" }
        return value.toInt()
    }

    /** An answer as text: models return counts as numbers and lists as arrays; blank or null gives null. */
    internal fun JSONObject.answerText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val text = when (val value = get(key)) {
            is String -> value
            is Number, is Boolean -> value.toString()
            is JSONArray -> (0 until value.length()).map { value.opt(it)?.toString().orEmpty() }
                .filter(String::isNotBlank).joinToString(", ")
            else -> return null
        }
        return text.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (get(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun JSONObject.stringArray(key: String, limit: Int): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), limit)) {
                val value = array.optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }.distinct()
    }

    fun parseVerifyResponse(text: String): VerifyResult {
        return try {
            val jsonStr = extractJsonFromText(text)
            val json = JSONObject(jsonStr)
            val outcome = parseVerifyOutcome(json.optString("outcome", "UNKNOWN"))
            VerifyResult(
                outcome = outcome,
                reason = json.optString("reason", ""),
                observation = if (json.has("observation") && !json.isNull("observation")) json.optString("observation") else null,
                manualObservation = parseManualObservation(json),
            )
        } catch (error: Exception) {
            // Some OpenAI-compatible relays cut the response after an explicit first-field
            // decision. Recover only that exact enum; never infer an outcome from prose.
            val explicit = VERIFY_OUTCOME_PREFIX.find(text)?.groupValues?.getOrNull(1)
                ?: throw error
            VerifyResult(
                outcome = parseVerifyOutcome(explicit),
                reason = "Verifier response ended after an explicit outcome",
            )
        }
    }

    private fun parseVerifyOutcome(value: String): VerifyOutcome = try {
        VerifyOutcome.valueOf(value.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        VerifyOutcome.UNKNOWN
    }

    fun parseQuickCheckResponse(text: String): QuickCheckResult {
        return try {
            val jsonStr = extractJsonFromText(text)
            val json = JSONObject(jsonStr)
            val answerValue = json.opt("answer")
            val confidence = json.optDouble("confidence", 1.0)
            val valid = answerValue is Boolean && confidence.isFinite() && confidence in 0.0..1.0
            QuickCheckResult(
                answer = answerValue as? Boolean ?: false,
                category = if (json.has("category") && !json.isNull("category")) json.optString("category") else null,
                confidence = confidence.toFloat(),
                isValid = valid,
                manualObservation = parseManualObservation(json),
                taskAnswer = json.answerText("taskAnswer"),
            )
        } catch (_: Exception) {
            // Fallback: try to detect yes/true in text
            val lower = text.lowercase()
            QuickCheckResult(
                answer = lower.contains("true") || lower.contains("yes"),
                confidence = 0.5f,
                isValid = false,
            )
        }
    }

    internal fun parseManualObservation(root: JSONObject): ManualPageObservation? =
        root.optJSONObject("manualObservation")?.let { observation ->
            ManualPageObservation(
                status = observation.optString("status", "UNKNOWN"),
                pageName = observation.optionalString("pageName"),
                matchedHeadingPath = observation.optionalString("matchedHeadingPath"),
                visualSignature = observation.optionalString("visualSignature"),
                reusableFacts = observation.stringArray("reusableFacts", 4),
                navigationFacts = observation.stringArray("navigationFacts", 3),
                popupOrRecoveryFact = observation.optionalString("popupOrRecoveryFact"),
            )
        }

    private val VERIFY_OUTCOME_PREFIX = Regex(
        "[\\{,]\\s*\\\"outcome\\\"\\s*:\\s*\\\"(SUCCESS|FAILED|DRIFTED|UNEXPECTED|UNKNOWN)\\\"",
        RegexOption.IGNORE_CASE,
    )
}
