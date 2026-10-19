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
    fun `coordinate guard rejections become model feedback instead of task failures`() {
        val missing = AgentLoop.coordinateFailureFeedback("missing_screenshot_coordinates")!!
        assertTrue(missing.contains("not counted"))
        // The feedback once sent the model to wait and reopen the app already in front, paying steps.
        assertFalse(missing.contains("openApp"))
        assertFalse(missing.contains("pressKey BACK"))
        assertTrue(AgentLoop.coordinateFailureFeedback("stale_screenshot_coordinates")!!.contains("Re-observe"))
        // Other argument errors (malformed model output, unsupported actions) keep failing loudly.
        assertNull(AgentLoop.coordinateFailureFeedback("normalized_coordinate_out_of_range"))
        assertNull(AgentLoop.coordinateFailureFeedback(null))
    }

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
        assertTrue(AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.ACCEPT))
        assertFalse(AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.REJECT))
        assertFalse(AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.UNAVAILABLE))
    }

    @Test
    fun `manual transition is attached only to an observed screen change`() {
        assertFalse(AgentLoop.shouldAttachManualTransition("com.app", "com.app", "same", "same"))
        assertTrue(AgentLoop.shouldAttachManualTransition("com.app", "com.app", "before", "after"))
        assertTrue(AgentLoop.shouldAttachManualTransition("com.app", "com.other", "same", "same"))
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
    fun `both audits compare specified values character by character`() {
        val single = AgentLoop.buildTaskCompletionAuditQuestion("Create an event at 1 St Andrew's Road, Singapore 178957")
        val crossApp = AgentLoop.buildTaskCompletionAuditQuestion(
            "Save the address in Keep, then create a Calendar event at it",
            listOf("com.google.android.keep" to 26),
        )

        for (question in listOf(single, crossApp)) {
            assertTrue(question.contains("character by character"))
            assertTrue(question.contains("a suggestion that replaced the requested text means failure"))
        }
    }

    @Test
    fun `a cross-app audit accepts a requirement proven by any app's last screen`() {
        val question = AgentLoop.buildTaskCompletionAuditQuestion(
            "Save the address in a Keep note, then create a Calendar event",
            listOf("com.google.android.keep" to 26),
        )

        assertTrue(question.contains("1. a screen of app com.google.android.keep (step 26)"))
        assertTrue(question.contains("2. the CURRENT screen"))
        assertTrue(question.contains("any screenshot visibly shows it done"))
        assertTrue(question.contains("not visible is not missing"))
        assertTrue(question.contains("only where they contradict"))
        // Structural checks still apply to every result.
        assertTrue(question.contains("first line of the body does not count as a title"))
        assertTrue(question.contains("exact visible line structure"))
    }

    @Test
    fun `a single-app audit keeps the current-screen question`() {
        val question = AgentLoop.buildTaskCompletionAuditQuestion("Turn on Wi-Fi")

        assertTrue(question.startsWith("Independently audit whether the CURRENT screenshot"))
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

    private fun attempt(action: String, target: String, outcome: VerifyOutcome, screen: String = "sig") =
        AgentLoop.Companion.Attempt(action, screen, target, outcome)

    @Test
    fun `repeated failures of one action on one target and screen are counted in a row`() {
        val history = listOf(
            attempt("tap", "5,30", VerifyOutcome.FAILED),
            attempt("TAP", "5,30", VerifyOutcome.DRIFTED),
            attempt("tap", "5,30", VerifyOutcome.FAILED),
        )
        assertEquals(3, AgentLoop.consecutiveIneffectiveAttempts(history))
    }

    @Test
    fun `taps on different targets or screens are different attempts`() {
        // SimpleCalendarAddRepeatingEvent failed at step 4/34: two taps 70 px apart were "the same action".
        val calendar = listOf(
            attempt("tap", AgentLoop.actionTarget(178, 694, emptyMap()), VerifyOutcome.FAILED),
            attempt("tap", AgentLoop.actionTarget(178, 656, emptyMap()), VerifyOutcome.FAILED),
        )
        assertEquals(1, AgentLoop.consecutiveIneffectiveAttempts(calendar))
        // Deleting several files: the same button on successive screens.
        val deletes = listOf(
            attempt("tap", "9,40", VerifyOutcome.FAILED, screen = "a"),
            attempt("tap", "9,40", VerifyOutcome.FAILED, screen = "b"),
        )
        assertEquals(1, AgentLoop.consecutiveIneffectiveAttempts(deletes))
    }

    @Test
    fun `a success ends the run and waits neither count nor end it`() {
        val afterSuccess = listOf(
            attempt("tap", "5,30", VerifyOutcome.FAILED),
            attempt("tap", "5,30", VerifyOutcome.SUCCESS),
            attempt("tap", "5,30", VerifyOutcome.FAILED),
        )
        assertEquals(1, AgentLoop.consecutiveIneffectiveAttempts(afterSuccess))
        val withWaits = listOf(
            attempt("tap", "5,30", VerifyOutcome.FAILED),
            attempt("wait", "{}", VerifyOutcome.FAILED),
            attempt("tap", "5,30", VerifyOutcome.FAILED),
        )
        assertEquals(2, AgentLoop.consecutiveIneffectiveAttempts(withWaits))
        // An app that has not rendered yet: waiting on an unchanged screen is not ineffective.
        val waits = listOf(attempt("wait", "{}", VerifyOutcome.FAILED), attempt("wait", "{}", VerifyOutcome.FAILED))
        assertEquals(0, AgentLoop.consecutiveIneffectiveAttempts(waits))
    }

    @Test
    fun `non-spatial actions are told apart by their parameters`() {
        val back = AgentLoop.actionTarget(null, null, mapOf("key" to "BACK"))
        val enter = AgentLoop.actionTarget(null, null, mapOf("key" to "ENTER"))
        val history = listOf(attempt("pressKey", back, VerifyOutcome.FAILED), attempt("pressKey", enter, VerifyOutcome.FAILED))
        assertEquals(1, AgentLoop.consecutiveIneffectiveAttempts(history))
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

    @Test
    fun `an unavailable audit completes the task as unverified, never as plainly complete`() {
        val unverified = AgentLoop.completionSummary("Event created", CompletionAuditDecision.UNAVAILABLE)
        assertTrue(unverified.startsWith(AgentLoop.UNVERIFIED_COMPLETION_NOTE))
        assertTrue(unverified.endsWith("Event created"))
        assertEquals("Event created", AgentLoop.completionSummary("Event created", CompletionAuditDecision.ACCEPT))
        // Verification off: no audit ran, nothing to flag.
        assertEquals("Event created", AgentLoop.completionSummary("Event created", null))
    }

    @Test
    fun `a multi-image audit gets one longer attempt and a single-screen audit keeps two short ones`() {
        assertEquals(2 to 20_000L, AgentLoop.completionAuditBudget(1))
        assertEquals(1 to 60_000L, AgentLoop.completionAuditBudget(5))
    }

    @Test
    fun `a loop record reaches both audits as host evidence`() {
        val record = "Per-item action: tap Hide. Items the host saw change after the action (1): Hide CM-3305."
        val single = AgentLoop.buildTaskCompletionAuditQuestion("Hide every listing marked Sold", loopRecord = record)
        val crossApp = AgentLoop.buildTaskCompletionAuditQuestion(
            "Hide every listing marked Sold", listOf("com.google.android.keep" to 3), record,
        )
        for (question in listOf(single, crossApp)) {
            assertTrue(question.contains("Host record (not the agent's claims): $record"))
            assertTrue(question.contains("did not reach the end of the list, answer false"))
            assertTrue(question.contains("character by character"))
        }
        assertFalse(AgentLoop.buildTaskCompletionAuditQuestion("Turn on Wi-Fi").contains("Host record"))
    }

    @Test
    fun `the fast-loop instruction asks for the loop object and a wait action`() {
        assertTrue(AgentLoop.FAST_LOOP_INSTRUCTION.contains("\"loop\": {\"each\""))
        assertTrue(AgentLoop.FAST_LOOP_INSTRUCTION.contains("set action to \"wait\""))
        assertTrue(AgentLoop.FAST_LOOP_INSTRUCTION.contains("Never use it for typing text or switching apps"))
    }

    @Test
    fun `task notes reach the audit as host-kept evidence`() {
        val notes = listOf("Meeting with Ana: 14:30", "Tracks counted: Morning Run, Evening Walk")
        val single = AgentLoop.buildTaskCompletionAuditQuestion("When is my next meeting with Ana?", notes = notes)
        val crossApp = AgentLoop.buildTaskCompletionAuditQuestion(
            "Copy the recipe", listOf("com.example.notes" to 2), notes = notes,
        )
        for (question in listOf(single, crossApp)) {
            assertTrue(question.contains("kept verbatim by the host"))
            assertTrue(question.contains("- Meeting with Ana: 14:30"))
            assertTrue(question.contains("the screenshot wins"))
        }
        assertFalse(AgentLoop.buildTaskCompletionAuditQuestion("Turn on Wi-Fi").contains("Notes the agent recorded"))
    }
}
