package bot.coati.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResultTest {
    private fun result(status: String, finalAudit: CompletionAuditDecision? = CompletionAuditDecision.ACCEPT) = TaskResult(
        runId = "L3_jev_r0",
        goal = "turn on the switch",
        status = status,
        reason = null,
        route = "routed",
        steps = 5,
        maxSteps = 20,
        durationMs = 23_000,
        doneClaims = 2,
        auditRejects = 1,
        finalAudit = finalAudit,
        fastSteps = 4,
        escalations = 1,
        verify = true,
        treeWithheld = false,
        usage = JSONObject().put("critical", JSONObject().put("calls", 7)),
    )

    @Test
    fun `completed task declares done and serializes its counters`() {
        val json = result("completed").toJson()
        assertEquals("L3_jev_r0", json.getString("run_id"))
        assertTrue(json.getBoolean("declared_done"))
        assertEquals(2, json.getInt("done_claims"))
        assertEquals(1, json.getInt("audit_rejects"))
        assertEquals("ACCEPT", json.getString("final_audit"))
        assertEquals(20, json.getInt("max_steps"))
        assertEquals(7, json.getJSONObject("usage").getJSONObject("critical").getInt("calls"))
        assertTrue(json.isNull("reason"))
    }

    @Test
    fun `failed or stopped tasks do not declare done`() {
        assertFalse(result("failed").declaredDone)
        assertFalse(result("paused", finalAudit = null).declaredDone)
        assertTrue(result("paused", finalAudit = null).toJson().isNull("final_audit"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a step budget outside the allowed range is rejected`() {
        TaskRunOptions(maxSteps = 0)
    }

    @Test
    fun `default options use the loop's step limit`() {
        assertEquals(AgentLoop.MAX_STEPS, TaskRunOptions().maxSteps)
    }
}
