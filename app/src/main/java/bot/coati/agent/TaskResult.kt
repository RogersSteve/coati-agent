package bot.coati.agent

import org.json.JSONObject

/**
 * Per-run settings a caller passes with a task: [runId] identifies the run in its result, and
 * [maxSteps] is the task's step budget (a benchmark's per-task limit).
 */
data class TaskRunOptions(
    val runId: String? = null,
    val maxSteps: Int = AgentLoop.MAX_STEPS,
) {
    init {
        require(maxSteps in 1..MAX_STEP_BUDGET) { "maxSteps must be in 1..$MAX_STEP_BUDGET" }
    }

    companion object {
        const val MAX_STEP_BUDGET = 500
    }
}

/** How a task ended, handed to [TaskHooks.onTaskFinished] when it first reaches a final status. */
internal data class TaskResult(
    val runId: String?,
    val goal: String,
    val status: String,
    val reason: String?,
    val route: String,
    val steps: Int,
    val maxSteps: Int,
    val durationMs: Long,
    /** Completion claims by the agent, including ones the audit rejected. */
    val doneClaims: Int,
    val auditRejects: Int,
    /** Decision of the last completion audit (ACCEPT, REJECT, UNAVAILABLE), or null without an audit. */
    val finalAudit: CompletionAuditDecision?,
    val fastSteps: Int,
    val escalations: Int,
    /** False for ablation runs with verification switched off. */
    val verify: Boolean,
    /** True for perturbation runs that hid the accessibility tree from perception. */
    val treeWithheld: Boolean,
    /** The answer the agent gave for an information task, or null. */
    val answer: String?,
    /** Loop passes that executed nothing because the screen was not ready; not counted in [steps]. */
    val refundedSteps: Int,
    /** Model usage from [bot.coati.model.ModelUsageMeter.finishTask]. */
    val usage: JSONObject,
) {
    /**
     * Whether the agent told the user the task is done. A benchmark counts a false completion when
     * this is true and its evaluator judges the task failed.
     */
    val declaredDone: Boolean get() = status == "completed"
}
