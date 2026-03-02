package bot.coati.state

data class AgentState(
    val isRunning: Boolean,
    val currentTaskId: String?,
    val stepCount: Int,
    val consecutiveFailures: Int,
    val lastActionTime: Long,
) {
    fun toJson(): String {
        val taskIdJson = if (currentTaskId != null) "\"${escapeJson(currentTaskId)}\"" else "null"
        return """{
  "isRunning": $isRunning,
  "currentTaskId": $taskIdJson,
  "stepCount": $stepCount,
  "consecutiveFailures": $consecutiveFailures,
  "lastActionTime": $lastActionTime
}"""
    }

    companion object {
        fun default(): AgentState = AgentState(
            isRunning = false,
            currentTaskId = null,
            stepCount = 0,
            consecutiveFailures = 0,
            lastActionTime = 0L,
        )

        fun fromJson(json: String): AgentState {
            val isRunning = Regex(""""isRunning"\s*:\s*(true|false)""")
                .find(json)?.groupValues?.get(1) == "true"

            val taskIdNull = Regex(""""currentTaskId"\s*:\s*null""").containsMatchIn(json)
            val currentTaskId = if (taskIdNull) {
                null
            } else {
                Regex(""""currentTaskId"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .find(json)?.groupValues?.get(1)?.unescapeJson()
            }

            val stepCount = Regex(""""stepCount"\s*:\s*(-?\d+)""")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val consecutiveFailures = Regex(""""consecutiveFailures"\s*:\s*(-?\d+)""")
                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val lastActionTime = Regex(""""lastActionTime"\s*:\s*(-?\d+)""")
                .find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            return AgentState(isRunning, currentTaskId, stepCount, consecutiveFailures, lastActionTime)
        }
    }
}
