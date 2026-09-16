package bot.coati.model

import bot.coati.agent.PromptBuilder
import org.json.JSONObject

object GatewayUtils {

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

    fun parseModelResponse(text: String): ModelResponse {
        val jsonStr = extractJsonFromText(text)
        val json = JSONObject(jsonStr)

        val params = mutableMapOf<String, Any>()
        val paramsJson = json.optJSONObject("params")
        if (paramsJson != null) {
            for (key in paramsJson.keys()) {
                params[key] = paramsJson.get(key)
            }
        }

        val x = if (json.has("x") && !json.isNull("x")) json.optInt("x") else null
        val y = if (json.has("y") && !json.isNull("y")) json.optInt("y") else null

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
        )
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

    private fun parseManualObservation(root: JSONObject): ManualPageObservation? =
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
