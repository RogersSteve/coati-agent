package bot.coati.agent

/**
 * Extension point for evaluation builds. The user-facing app keeps these defaults: verification on,
 * the accessibility tree visible, and nothing recorded when a task ends. Debug builds install
 * benchmark hooks at process start (bot.coati.debug.BenchmarkHooks) for ablation switches,
 * perturbations and the per-task result file that harnesses read.
 */
internal interface TaskHooks {
    /**
     * When false, the Reasoning route runs plain Observe-Think-Act: no post-action verification and
     * no completion audit (the ablation baseline). Read once per task.
     */
    fun verifyEnabled(): Boolean = true

    /**
     * When true, the agent perceives every screen without the accessibility tree (no tree in prompts,
     * the fast route parses pixels), as on custom-drawn interfaces. Actions still use the platform's
     * input services. Read once per task.
     */
    fun treeWithheld(): Boolean = false

    /** Called once when a task first reaches a final status, off the main thread. */
    fun onTaskFinished(result: TaskResult) {}

    companion object {
        private val NONE = object : TaskHooks {}

        @Volatile
        var installed: TaskHooks = NONE
    }
}
