package bot.coati.state

class TaskRecovery(private val stateManager: StateManager) {

    private val incompleteStatuses = setOf("executing", "paused", "waiting_retry")

    fun hasIncompleteTask(): Boolean {
        return getIncompleteTask() != null
    }

    fun getIncompleteTask(): TaskState? {
        val json = stateManager.readJson("task") ?: return null
        return try {
            val task = TaskState.fromJson(json)
            if (task.status in incompleteStatuses) task else null
        } catch (_: Exception) {
            null
        }
    }

    fun markCancelled() {
        val json = stateManager.readJson("task") ?: return
        try {
            val task = TaskState.fromJson(json)
            val updated = task.copy(status = "cancelled")
            stateManager.writeJson("task", updated.toJson())
        } catch (_: Exception) {
            // ignore
        }
    }

    fun getInterruptedAgo(task: TaskState): String = when (val age = interruptionAge(task)) {
        InterruptionAge.Unknown -> "unknown"
        InterruptionAge.JustNow -> "just now"
        is InterruptionAge.Minutes -> "${age.count} minutes ago"
        is InterruptionAge.Hours -> "${age.count} hours ago"
        is InterruptionAge.Days -> "${age.count} days ago"
    }

    fun interruptionAge(task: TaskState): InterruptionAge {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val lastUpdated = sdf.parse(task.lastUpdated) ?: return InterruptionAge.Unknown
            val diffMs = System.currentTimeMillis() - lastUpdated.time
            val minutes = diffMs / 60_000
            val hours = minutes / 60
            val days = hours / 24
            when {
                minutes < 1 -> InterruptionAge.JustNow
                minutes < 60 -> InterruptionAge.Minutes(minutes.asCount())
                hours < 24 -> InterruptionAge.Hours(hours.asCount())
                else -> InterruptionAge.Days(days.asCount())
            }
        } catch (_: Exception) {
            InterruptionAge.Unknown
        }
    }
}

sealed class InterruptionAge {
    data object Unknown : InterruptionAge()
    data object JustNow : InterruptionAge()
    data class Minutes(val count: Int) : InterruptionAge()
    data class Hours(val count: Int) : InterruptionAge()
    data class Days(val count: Int) : InterruptionAge()
}

private fun Long.asCount(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
