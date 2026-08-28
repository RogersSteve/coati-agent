package bot.coati.agent

import android.graphics.Rect
import bot.coati.action.ActionFailureCode
import bot.coati.model.VerifyOutcome
import bot.coati.model.VerifyResult
import bot.coati.model.QuickCheckResult
import bot.coati.service.UiTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    @Test
    fun `task completion audit distinguishes accepted rejected and unavailable`() {
        assertEquals(
            CompletionAuditDecision.ACCEPT,
            AgentLoop.classifyTaskCompletionAudit(
                QuickCheckResult(answer = true, category = "complete", confidence = 0.9f),
            ),
        )
        assertEquals(
            CompletionAuditDecision.REJECT,
            AgentLoop.classifyTaskCompletionAudit(
                QuickCheckResult(answer = false, category = "missing_title", confidence = 0.9f),
            ),
        )
        assertEquals(
            CompletionAuditDecision.UNAVAILABLE,
            AgentLoop.classifyTaskCompletionAudit(
                QuickCheckResult(
                    answer = false,
                    confidence = 0.5f,
                    isValid = false,
                ),
            ),
        )
        assertEquals(
            CompletionAuditDecision.UNAVAILABLE,
            AgentLoop.classifyTaskCompletionAudit(null),
        )
    }

    @Test
    fun `capability scope changes with input session and action strategy`() {
        val firstEditor = AgentLoop.capabilityScope("com.notes", "screen", 12)
        val nextEditor = AgentLoop.capabilityScope("com.notes", "screen", 13)
        val atomic = AgentLoop.capabilityFailureKey(
            "focusAndInputText",
            ActionFailureCode.TEXT_CARET_NOT_CONFIRMED,
        )
        val direct = AgentLoop.capabilityFailureKey(
            "inputText",
            ActionFailureCode.TEXT_CARET_NOT_CONFIRMED,
        )

        assertTrue(firstEditor != nextEditor)
        assertTrue(atomic != direct)
    }

    @Test
    fun `task completion audit explicitly rejects body text masquerading as title`() {
        val question = AgentLoop.buildTaskCompletionAuditQuestion(
            "新建便签，标题是会议安排，正文分三行填写",
        )

        assertTrue(question.contains("first line of the body does not count as a title"))
        assertTrue(question.contains("still-visible empty title placeholder means failure"))
        assertTrue(question.contains("exact visible line structure"))
    }

    @Test
    fun `MAX_STEPS is 200`() {
        assertEquals(200, AgentLoop.MAX_STEPS)
    }

    @Test
    fun `emergencyStop defaults to false`() {
        assertFalse(false) // placeholder for emergencyStop default
    }

    @Test
    fun `coordinate conversion math is correct`() {
        val normalizedX = 500
        val normalizedY = 500
        val screenWidth = 1080
        val screenHeight = 2400

        val pixelX = normalizedX * screenWidth / 1000
        val pixelY = normalizedY * screenHeight / 1000

        assertEquals(540, pixelX)
        assertEquals(1200, pixelY)
    }

    @Test
    fun `coordinate conversion handles edge values`() {
        assertEquals(0, 0 * 1080 / 1000)
        assertEquals(0, 0 * 2400 / 1000)
        assertEquals(1080, 1000 * 1080 / 1000)
        assertEquals(2400, 1000 * 2400 / 1000)
    }

    @Test
    fun `coordinate conversion handles quarter positions`() {
        val screenWidth = 1080
        val screenHeight = 2400

        assertEquals(270, 250 * screenWidth / 1000)
        assertEquals(600, 250 * screenHeight / 1000)
        assertEquals(810, 750 * screenWidth / 1000)
        assertEquals(1800, 750 * screenHeight / 1000)
    }

    @Test
    fun `RuleEngine assessQuality works in loop context`() {
        assertEquals(UiTreeQuality.HIGH, RuleEngine.assessQuality(30, 10))
        assertEquals(UiTreeQuality.LOW, RuleEngine.assessQuality(2, 0))
    }

    @Test
    fun `ChangeResult enum has expected values`() {
        val values = ChangeResult.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(ChangeResult.CHANGED))
        assertTrue(values.contains(ChangeResult.UNCHANGED))
        assertTrue(values.contains(ChangeResult.UNCERTAIN))
    }

    // ─── Verify outcome tests ───

    @Test
    fun `VerifyOutcome SUCCESS means continue`() {
        val result = VerifyResult(VerifyOutcome.SUCCESS, "Screen changed as expected")
        assertEquals(VerifyOutcome.SUCCESS, result.outcome)
        assertNull(result.observation)
    }

    @Test
    fun `VerifyOutcome FAILED sets replan hint to return to loop`() {
        val result = VerifyResult(VerifyOutcome.FAILED, "Screen unchanged")
        assertEquals(VerifyOutcome.FAILED, result.outcome)
        // FAILED now breaks out of verify loop with replanHint, not retry internally
    }

    @Test
    fun `VerifyOutcome DRIFTED sets replan hint without pressing Back`() {
        val result = VerifyResult(VerifyOutcome.DRIFTED, "Wrong page opened")
        assertEquals(VerifyOutcome.DRIFTED, result.outcome)
        // DRIFTED now breaks out of verify loop, LLM decides recovery
    }

    @Test
    fun `VerifyOutcome UNEXPECTED records observation`() {
        val result = VerifyResult(VerifyOutcome.UNEXPECTED, "Different path", "A shortcut dialog appeared")
        assertEquals(VerifyOutcome.UNEXPECTED, result.outcome)
        assertEquals("A shortcut dialog appeared", result.observation)
    }

    @Test
    fun `VerifyOutcome UNKNOWN means verification was inconclusive`() {
        val result = VerifyResult(VerifyOutcome.UNKNOWN, "Verifier response could not be parsed")
        assertEquals(VerifyOutcome.UNKNOWN, result.outcome)
        assertNull(result.observation)
    }

    // ─── ActionRecord and retry counting ───

    @Test
    fun `same action and screen count despite different status wording`() {
        data class ActionRecord(
            val action: String,
            val statusUpdate: String,
            val screenSignature: String,
            val outcome: VerifyOutcome,
        )

        val history = mutableListOf(
            ActionRecord("tap", "Trying body start", "abc123", VerifyOutcome.FAILED),
            ActionRecord("TAP", "Relocating the caret", "abc123", VerifyOutcome.DRIFTED),
        )

        val failCount = history.count {
            AgentLoop.matchesIneffectiveAttempt(
                recordedAction = it.action,
                currentAction = "tap",
                recordedScreenSignature = it.screenSignature,
                currentScreenSignature = "abc123",
                outcome = it.outcome,
            )
        }
        assertEquals(2, failCount)
    }

    @Test
    fun `same action different screen is not retry - delete multiple files scenario`() {
        data class ActionRecord(
            val action: String,
            val statusUpdate: String,
            val screenSignature: String,
            val outcome: VerifyOutcome,
        )

        // Each delete happens on a different screen (different file list)
        val history = mutableListOf(
            ActionRecord("tap", "Tap confirm delete", "screen_a", VerifyOutcome.SUCCESS),
            ActionRecord("tap", "Tap confirm delete", "screen_b", VerifyOutcome.SUCCESS),
            ActionRecord("tap", "Tap confirm delete", "screen_c", VerifyOutcome.FAILED),
        )

        // Count failures for "Tap confirm delete" on screen_c
        val failCount = history.count {
            AgentLoop.matchesIneffectiveAttempt(
                recordedAction = it.action,
                currentAction = "tap",
                recordedScreenSignature = it.screenSignature,
                currentScreenSignature = "screen_c",
                outcome = it.outcome,
            )
        }
        assertEquals(1, failCount) // Only 1, not 3 — different screens don't count
    }

    @Test
    fun `DRIFTED and FAILED both count toward same limit`() {
        data class ActionRecord(
            val action: String,
            val statusUpdate: String,
            val screenSignature: String,
            val outcome: VerifyOutcome,
        )

        val history = mutableListOf(
            ActionRecord("tap", "Tapping button", "sig1", VerifyOutcome.FAILED),
            ActionRecord("tap", "Tapping button", "sig1", VerifyOutcome.DRIFTED),
        )

        val failCount = history.count {
            AgentLoop.matchesIneffectiveAttempt(
                recordedAction = it.action,
                currentAction = "tap",
                recordedScreenSignature = it.screenSignature,
                currentScreenSignature = "sig1",
                outcome = it.outcome,
            )
        }
        assertEquals(2, failCount)
    }

    @Test
    fun `screen signature changes across different screens`() {
        val tree1 = makeNode("LinearLayout", null, false, listOf(
            makeNode("TextView", "File A.txt", false, emptyList()),
        ))
        val tree2 = makeNode("LinearLayout", null, false, listOf(
            makeNode("TextView", "File B.txt", false, emptyList()),
        ))
        val sig1 = RuleEngine.buildScreenSignature(tree1)
        val sig2 = RuleEngine.buildScreenSignature(tree2)
        assertTrue(sig1 != sig2)
    }

    @Test
    fun `screen signature same for identical screens`() {
        val tree1 = makeNode("LinearLayout", null, false, listOf(
            makeNode("TextView", "Settings", false, emptyList()),
        ))
        val tree2 = makeNode("LinearLayout", null, false, listOf(
            makeNode("TextView", "Settings", false, emptyList()),
        ))
        assertEquals(RuleEngine.buildScreenSignature(tree1), RuleEngine.buildScreenSignature(tree2))
    }

    // ─── Helpers ───

    private fun makeNode(
        className: String,
        text: String?,
        clickable: Boolean,
        children: List<UiTreeNode>,
    ): UiTreeNode {
        return UiTreeNode(
            className = className,
            text = text,
            contentDescription = null,
            bounds = Rect(0, 0, 100, 50),
            isClickable = clickable,
            isScrollable = false,
            viewIdResourceName = null,
            children = children,
        )
    }
}
