package bot.coati.model

import org.json.JSONObject

object GatewayUtils {

    val SYSTEM_PROMPT = """
        You are Coati, an AI agent that controls an Android phone on behalf of the user.
        You can see the screen via screenshots and understand the UI structure via accessibility trees.

        Rules:
        1. Analyze the screenshot and UI tree to understand the current screen state.
        2. Determine the single best next action to accomplish the user's task.
        3. Use precise coordinates from the UI tree bounds for tap/swipe actions.
        4. Be conservative — prefer safe, reversible actions.
        5. Mark safetyLevel as "high" for any destructive, financial, or irreversible actions.
        6. Set isTaskComplete to true only when the task is fully accomplished.
        7. Always respond with valid JSON only, no additional text.
    """.trimIndent()

    fun buildUserPrompt(uiTreeHint: String, taskDescription: String): String {
        return """
            Task: $taskDescription

            UI Tree (simplified):
            $uiTreeHint

            Analyze the screenshot and UI tree above. Decide the single best action to perform next.

            Respond ONLY with a JSON object in this exact format:
            {
              "reasoning": "Brief explanation of what you see and why you chose this action",
              "action": "tap|longpress|swipe|inputText|pressKey|scroll|wait|screenshot|openApp",
              "params": { ... action-specific parameters ... },
              "safetyLevel": "low|medium|high",
              "isTaskComplete": false,
              "statusUpdate": "Short status message for the user"
            }

            Action params:
            - tap: {"x": int, "y": int}
            - longpress: {"x": int, "y": int, "durationMs": int}
            - swipe: {"x1": int, "y1": int, "x2": int, "y2": int, "durationMs": int}
            - inputText: {"text": "string"}
            - pressKey: {"keyCode": "BACK|HOME|RECENTS|ENTER"}
            - scroll: {"direction": "UP|DOWN|LEFT|RIGHT", "amount": int}
            - wait: {"ms": int}
            - screenshot: {}
            - openApp: {"packageName": "com.example.app"}

            Safety levels:
            - low: Safe reversible actions (scroll, screenshot, wait)
            - medium: Standard interactions (tap, type, open app)
            - high: Destructive or sensitive actions (delete, send, purchase, permission changes)
        """.trimIndent()
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

        return ModelResponse(
            reasoning = json.optString("reasoning", ""),
            action = json.getString("action"),
            params = params,
            safetyLevel = json.optString("safetyLevel", "medium"),
            isTaskComplete = json.optBoolean("isTaskComplete", false),
            statusUpdate = json.optString("statusUpdate", ""),
        )
    }
}
