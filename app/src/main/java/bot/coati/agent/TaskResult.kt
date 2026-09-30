package bot.coati.agent

import org.json.JSONObject

/**
 * Per-run settings a benchmark harness passes with a task: [runId] is echoed in the result so the
 * harness can match it, and [maxSteps] is the task's step budget (AndroidWorld's per-task limit).
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

/** How a task ended, as written to files/task_result.json when it reaches a final status. */
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
    /** Decision of the last completion audit (ACCEPT, REJECT, UNAVAILABLE), or null without a claim. */
    val finalAudit: CompletionAuditDecision?,
    val fastSteps: Int,
    val escalations: Int,
    /** False for ablation runs with verification switched off. */
    val verify: Boolean,
    val usage: JSONObject?,
) {
    /**
     * Whether the agent told the user the task is done. A benchmark counts a false completion when
     * this is true and its evaluator judges the task failed.
     */
    val declaredDone: Boolean get() = status == "completed"

    fun toJson(): JSONObject = JSONObject()
        .put("run_id", runId ?: JSONObject.NULL)
        .put("goal", goal)
        .put("status", status)
        .put("reason", reason ?: JSONObject.NULL)
        .put("declared_done", declaredDone)
        .put("done_claims", doneClaims)
        .put("audit_rejects", auditRejects)
        .put("final_audit", finalAudit?.name ?: JSONObject.NULL)
        .put("route", route)
        .put("steps", steps)
        .put("max_steps", maxSteps)
        .put("duration_ms", durationMs)
        .put("fast_steps", fastSteps)
        .put("escalations", escalations)
        .put("verify", verify)
        .put("usage", usage ?: JSONObject.NULL)
}
