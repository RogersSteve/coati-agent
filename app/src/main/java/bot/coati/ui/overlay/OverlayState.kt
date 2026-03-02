package bot.coati.ui.overlay

sealed class OverlayState {
    data object CollapsedIdle : OverlayState()
    data object ExpandedIdle : OverlayState()

    data class FrameExecuting(
        val taskName: String,
        val status: String = "",
        val reasoning: String = "",
        val step: Int = 0,
        val totalSteps: Int = 0,
    ) : OverlayState()

    data class Paused(
        val taskName: String,
        val step: Int = 0,
        val totalSteps: Int = 0,
    ) : OverlayState()

    data class SafetyConfirm(
        val title: String,
        val detail: String,
    ) : OverlayState()

    data class TaskComplete(
        val taskName: String,
        val summary: String,
        val steps: Int = 0,
        val durationMs: Long = 0,
    ) : OverlayState()

    data class TaskFailed(
        val taskName: String,
        val reason: String,
        val stepsCompleted: Int = 0,
        val totalSteps: Int = 0,
    ) : OverlayState()

    data class TaskRecovery(
        val taskName: String,
        val step: Int = 0,
        val totalSteps: Int = 0,
        val interruptedAgo: String = "",
    ) : OverlayState()
}
