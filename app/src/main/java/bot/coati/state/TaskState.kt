package bot.coati.state

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PlanStep(val description: String, val completed: Boolean)

data class TaskState(
    val id: String,
    val status: String,
    val created: String,
    val lastUpdated: String,
    val totalSteps: Int,
    val currentStep: Int,
    val goal: String,
    val plan: List<PlanStep>,
    val collectedData: String,
    val historySummary: String,
    val errors: List<String>,
    val targetPackage: String? = null,
) {
    fun toJson(): String {
        val planJson = plan.joinToString(",") { step ->
            """{"description":"${escapeJson(step.description)}","completed":${step.completed}}"""
        }
        val errorsJson = errors.joinToString(",") { "\"${escapeJson(it)}\"" }
        val targetPkg = if (targetPackage != null) "\"${escapeJson(targetPackage)}\"" else "null"
        return """{
  "id": "${escapeJson(id)}",
  "status": "${escapeJson(status)}",
  "created": "${escapeJson(created)}",
  "lastUpdated": "${escapeJson(lastUpdated)}",
  "totalSteps": $totalSteps,
  "currentStep": $currentStep,
  "goal": "${escapeJson(goal)}",
  "plan": [$planJson],
  "collectedData": "${escapeJson(collectedData)}",
  "historySummary": "${escapeJson(historySummary)}",
  "errors": [$errorsJson],
  "targetPackage": $targetPkg
}"""
    }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# Task: $goal")
        sb.appendLine("- Status: $status")
        sb.appendLine("- Progress: $currentStep / $totalSteps steps")
        sb.appendLine("- Created: $created")
        sb.appendLine("- Updated: $lastUpdated")
        if (plan.isNotEmpty()) {
            sb.appendLine("\n## Plan")
            plan.forEachIndexed { i, step ->
                val mark = if (step.completed) "[x]" else "[ ]"
                sb.appendLine("${i + 1}. $mark ${step.description}")
            }
        }
        if (collectedData.isNotBlank()) {
            sb.appendLine("\n## Collected Data")
            sb.appendLine(collectedData)
        }
        if (historySummary.isNotBlank()) {
            sb.appendLine("\n## History Summary")
            sb.appendLine(historySummary)
        }
        if (errors.isNotEmpty()) {
            sb.appendLine("\n## Errors")
            errors.forEach { sb.appendLine("- $it") }
        }
        if (targetPackage != null) {
            sb.appendLine("\n## Target Package")
            sb.appendLine(targetPackage)
        }
        return sb.toString().trimEnd()
    }

    companion object {
        val VALID_STATUSES = listOf("executing", "paused", "cancelled", "completed", "failed", "waiting_retry")

        fun newTask(goal: String): TaskState {
            val now = timestamp()
            return TaskState(
                id = UUID.randomUUID().toString(),
                status = "executing",
                created = now,
                lastUpdated = now,
                totalSteps = 0,
                currentStep = 0,
                goal = goal,
                plan = emptyList(),
                collectedData = "",
                historySummary = "",
                errors = emptyList(),
            )
        }

        fun fromJson(json: String): TaskState {
            fun extractString(key: String): String {
                val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                return regex.find(json)?.groupValues?.get(1)?.unescapeJson() ?: ""
            }

            fun extractInt(key: String): Int {
                val regex = Regex(""""$key"\s*:\s*(-?\d+)""")
                return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }

            fun extractNullableString(key: String): String? {
                val nullRegex = Regex(""""$key"\s*:\s*null""")
                if (nullRegex.containsMatchIn(json)) return null
                val result = extractString(key)
                return result.ifEmpty { null }
            }

            // Parse plan array
            val planRegex = Regex(""""plan"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            val planMatch = planRegex.find(json)?.groupValues?.get(1) ?: ""
            val plan = if (planMatch.isBlank()) {
                emptyList()
            } else {
                val stepRegex = Regex("""\{"description"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"completed"\s*:\s*(true|false)\}""")
                stepRegex.findAll(planMatch).map { m ->
                    PlanStep(m.groupValues[1].unescapeJson(), m.groupValues[2] == "true")
                }.toList()
            }

            // Parse errors array
            val errorsRegex = Regex(""""errors"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            val errorsMatch = errorsRegex.find(json)?.groupValues?.get(1) ?: ""
            val errors = if (errorsMatch.isBlank()) {
                emptyList()
            } else {
                val strRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
                strRegex.findAll(errorsMatch).map { it.groupValues[1].unescapeJson() }.toList()
            }

            return TaskState(
                id = extractString("id"),
                status = extractString("status"),
                created = extractString("created"),
                lastUpdated = extractString("lastUpdated"),
                totalSteps = extractInt("totalSteps"),
                currentStep = extractInt("currentStep"),
                goal = extractString("goal"),
                plan = plan,
                collectedData = extractString("collectedData"),
                historySummary = extractString("historySummary"),
                errors = errors,
                targetPackage = extractNullableString("targetPackage"),
            )
        }

        private fun timestamp(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            return sdf.format(Date())
        }
    }
}

internal fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

internal fun String.unescapeJson(): String = this
    .replace("\\n", "\n")
    .replace("\\r", "\r")
    .replace("\\t", "\t")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
