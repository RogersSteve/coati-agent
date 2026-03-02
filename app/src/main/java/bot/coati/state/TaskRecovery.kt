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

    fun getInterruptedAgo(task: TaskState): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val lastUpdated = sdf.parse(task.lastUpdated) ?: return "unknown"
            val now = System.currentTimeMillis()
            val diffMs = now - lastUpdated.time
            val minutes = diffMs / 60_000
            val hours = minutes / 60
            val days = hours / 24
            when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes minutes ago"
                hours < 24 -> "$hours hours ago"
                else -> "$days days ago"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
