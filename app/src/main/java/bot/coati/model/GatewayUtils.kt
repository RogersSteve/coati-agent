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

        val experienceNote = if (json.has("experienceNote") && !json.isNull("experienceNote")) {
            val noteJson = json.optJSONObject("experienceNote")
            if (noteJson != null) {
                ExperienceNote(
                    page = noteJson.optString("page", ""),
                    observation = noteJson.optString("observation", ""),
                )
            } else null
        } else null

        return ModelResponse(
            reasoning = json.optString("reasoning", ""),
            action = json.getString("action"),
            params = params,
            safetyLevel = json.optString("safetyLevel", "medium"),
            isTaskComplete = json.optBoolean("isTaskComplete", false),
            statusUpdate = json.optString("statusUpdate", ""),
            x = x,
            y = y,
            experienceNote = experienceNote,
            expectedOutcome = json.optString("expectedOutcome", ""),
            isPopupDismissal = json.optBoolean("isPopupDismissal", false),
        )
    }

    fun parseVerifyResponse(text: String): VerifyResult {
        val jsonStr = extractJsonFromText(text)
        val json = JSONObject(jsonStr) // throws JSONException if not valid JSON
        val outcomeStr = json.optString("outcome", "FAILED").uppercase()
        val outcome = try {
            VerifyOutcome.valueOf(outcomeStr)
        } catch (_: IllegalArgumentException) {
            VerifyOutcome.FAILED
        }
        return VerifyResult(
            outcome = outcome,
            reason = json.optString("reason", ""),
            observation = if (json.has("observation") && !json.isNull("observation")) json.optString("observation") else null,
        )
    }

    fun parseQuickCheckResponse(text: String): QuickCheckResult {
        return try {
            val jsonStr = extractJsonFromText(text)
            val json = JSONObject(jsonStr)
            QuickCheckResult(
                answer = json.optBoolean("answer", false),
                category = if (json.has("category") && !json.isNull("category")) json.optString("category") else null,
                confidence = json.optDouble("confidence", 1.0).toFloat(),
            )
        } catch (_: Exception) {
            // Fallback: try to detect yes/true in text
            val lower = text.lowercase()
            QuickCheckResult(
                answer = lower.contains("true") || lower.contains("yes"),
                confidence = 0.5f,
            )
        }
    }
}
