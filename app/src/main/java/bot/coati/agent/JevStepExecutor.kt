package bot.coati.agent

import android.content.Intent
import android.util.Log
import bot.coati.action.ActionEngine
import bot.coati.action.ActionFailureCode
import bot.coati.action.ActionResult
import bot.coati.action.AgentAction
import bot.coati.action.InputTextMode
import bot.coati.action.toLogSummary
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.experience.AppManualRuntime
import bot.coati.experience.RenderedAppManual
import bot.coati.jev.BackgroundAdvisor
import bot.coati.jev.Candidate
import bot.coati.jev.CandidateAction
import bot.coati.jev.ExplorationMemory
import bot.coati.jev.JevCandidates
import bot.coati.jev.JevDecisionEngine
import bot.coati.jev.JevStepInput
import bot.coati.jev.PreviousStep
import bot.coati.jev.StepOutcome
import bot.coati.jev.StepRecord
import bot.coati.jev.StepVerifier
import bot.coati.model.ModelRouter
import bot.coati.model.ScreenContext
import bot.coati.perception.A11yScreenParser
import bot.coati.perception.ElementOp
import bot.coati.perception.LumaGrid
import bot.coati.perception.ParsedScreen
import bot.coati.perception.ScreenDiff
import bot.coati.perception.ScreenSource
import bot.coati.perception.VisionScreenParser
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.UiTreeNode
import bot.coati.ui.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/** What one fast step did; [AgentLoop] owns everything that follows. */
sealed interface FastStep {
    /** An action was executed; observe again. */
    data object Acted : FastStep

    /** Nothing was executed (screen not ready, stale decision, wait); observe again. */
    data object Retry : FastStep

    /** JEV claims the task is done; the loop runs the independent completion audit. */
    data class DoneClaimed(val summary: String) : FastStep

    /** The user declined a confirmation; the task is cancelled. */
    data object Declined : FastStep

    /** This step needs the Reasoning model (uncertainty, no progress, cycle, JEV unavailable). */
    data class Escalate(val reason: String) : FastStep
}

/**
 * The JEV fast path of [AgentLoop]. Per step: parse the observed screen (accessibility tree, or
 * one compact vision parse when the tree is unusable), then one JEV round trip that verifies the
 * previous fast action against host evidence and picks the next action among host-built candidates.
 *
 * The Reasoning model is part of the routing rather than a failure fallback: it plans, supplies
 * text values and learns app manuals asynchronously from the first step, and it takes the step
 * whenever this executor escalates.
 */
class JevStepExecutor(
    private val overlayManager: OverlayManager,
    private val modelRouter: ModelRouter,
    private val actionEngine: ActionEngine,
    private val a11y: CoatiAccessibilityService,
    private val decisionEngine: JevDecisionEngine,
    private val manualRuntime: AppManualRuntime? = null,
) {
    /** Performs a safety confirmation for a candidate; false when the user declined. */
    fun interface Confirmer {
        suspend fun confirm(appPackage: String, actionName: String, label: String, baseLevel: String): Boolean
    }

    private val visionParser = VisionScreenParser(modelRouter.fast)
    private var scope = newScope()
    private var advisor = BackgroundAdvisor(modelRouter.reasoning, scope)
    private var memory = ExplorationMemory()
    private val history = mutableListOf<StepRecord>()
    private val textValues = linkedMapOf<String, String>()
    private val apps = mutableListOf<String>()
    private val excluded = mutableSetOf<String>()
    private val noChangeCounts = mutableMapOf<String, Int>()
    private var subgoals = listOf<String>()
    private var hint: String? = null
    private var planRequested = false
    private var planReceived = false
    private var lastAdvisedPage: Set<String>? = null
    private var taskPackage: String? = null
    private var launchers: Set<String> = emptySet()
    private var consecutiveBad = 0
    private var consecutiveRetries = 0
    private var auditRejects = 0

    // The previous fast action, verified on the next fast step.
    private var previous: PreviousStep? = null
    private var previousScreen: ParsedScreen? = null
    private var previousCandidate: CandidateAction? = null
    private var previousTyped: String? = null
    private var previousPage: Set<String>? = null
    private var previousIdentity: ExplorationMemory.ActionIdentity? = null
    private var previousKey: String? = null
    private var previousAction: AgentAction? = null
    private var previousTree: UiTreeNode? = null
    private var previousLuma: LumaGrid? = null

    /** Escalations in this task; past [MAX_ESCALATIONS] the Reasoning model keeps the task. */
    var escalations = 0
        private set
    val activeForTask: Boolean get() = escalations < MAX_ESCALATIONS

    fun startTask(task: String) {
        finishTask()
        scope = newScope()
        advisor = BackgroundAdvisor(modelRouter.reasoning, scope)
        memory = ExplorationMemory()
        history.clear()
        textValues.clear()
        apps.clear()
        excluded.clear()
        noChangeCounts.clear()
        subgoals = listOf(task)
        hint = null
        planRequested = false
        planReceived = false
        lastAdvisedPage = null
        taskPackage = null
        launchers = launcherPackages()
        consecutiveBad = 0
        consecutiveRetries = 0
        auditRejects = 0
        escalations = 0
        forgetPrevious()
        previousLuma = null
    }

    fun finishTask() {
        scope.cancel()
    }

    /** A Reasoning step ran in between: record it and verify the next fast action from scratch. */
    fun recordReasoningStep(label: String, succeeded: Boolean?) {
        val outcome = when (succeeded) {
            true -> StepOutcome.SUCCESS
            false -> StepOutcome.NO_CHANGE
            null -> null
        }
        history += StepRecord("reasoning: $label", outcome)
        forgetPrevious()
        consecutiveBad = 0
        memory.breakCycle()
    }

    /** Recent fast actions and their verified outcomes, for a Reasoning step that takes over. */
    fun recentHistory(limit: Int = 6): String =
        history.takeLast(limit).joinToString("\n") { "- ${it.action} -> ${it.outcome ?: "unverified"}" }

    /** The independent audit rejected a DONE claim. */
    fun doneRejected() {
        auditRejects++
        history += StepRecord("DONE rejected by independent audit")
        if (auditRejects >= MAX_AUDIT_REJECTS) excluded += CandidateAction.Done.key
        previous = null
    }

    suspend fun step(stepNumber: Int, task: String, context: ScreenContext, tree: UiTreeNode?, confirmer: Confirmer): FastStep {
        if (!planRequested) {
            planRequested = true
            advisor.plan(task, context.screenshotJpeg)
        }
        val coordinates = context.coordinates
        if (coordinates == null) {
            // Windows are changing (splash, animation, dialog closing): no gesture can be bound yet.
            return retryOrEscalate("screen coordinates unavailable")
        }
        absorb()

        // Perception: the tree when usable, otherwise one compact vision parse.
        val started = System.currentTimeMillis()
        val luma = ScreenDiff.lumaGrid(context.screenshotJpeg)
        val visual = if (luma != null && previousLuma != null) {
            ScreenDiff.compare(previousLuma!!, luma, coordinates.rawWidth, coordinates.rawHeight, coordinates.display.insets.top)
        } else {
            null
        }
        previousLuma = luma
        val useTree = A11yScreenParser.isUsable(tree)
        val lastScreen = previousScreen
        val reusedParse = !useTree && lastScreen?.source == ScreenSource.VISION &&
            lastScreen.packageName == context.appPackage &&
            visual != null && visual.changedFraction < StepVerifier.NO_CHANGE_FRACTION
        val screen: ParsedScreen = when {
            // An unchanged frame keeps the previous vision parse and skips a model call.
            reusedParse -> lastScreen!!
            useTree -> A11yScreenParser.parse(tree!!, context.appPackage, coordinates.rawWidth, coordinates.rawHeight)
            else -> visionParser.parse(
                context.screenshotJpeg,
                coordinates.imageWidth,
                coordinates.imageHeight,
                context.appPackage,
            ) { x, y -> coordinates.normalized(x, y) }.getOrElse { e ->
                Log.w(TAG, "Vision parse failed: ${e.message}")
                return FastStep.Escalate("the screen could not be parsed")
            }
        }
        val perceiveMs = System.currentTimeMillis() - started
        consecutiveRetries = 0
        val here = screen.pageKey

        // Host evidence for the previous fast action: pixel diff, texts, target state (no model).
        val before = previousScreen
        val beforeAction = previousCandidate
        if (previous != null && before != null && beforeAction != null) {
            val observation = StepVerifier.observe(before, screen, beforeAction, previousTyped, visual)
            previous = previous!!.copy(observation = observation)
            Log.i(TAG, "Verify evidence (reusedParse=$reusedParse): ${observation.note}")
        }
        if (previousPage != null && previousIdentity != null) {
            memory.record(previousPage!!, previousIdentity!!, here, screen.summary.ifBlank { here.take(4).joinToString() })
        }
        memory.visit(here)

        val currentPackage = context.appPackage
        if (taskPackage == null && currentPackage !in launchers) taskPackage = currentPackage
        val strayed = taskPackage != null && currentPackage != taskPackage
        val manual = manualRuntime?.promptFor(context.appPackage, context.uiTreeJson)
        // Typing is offered only when text exists or the plan that would provide it is pending.
        val canType = textValues.isNotEmpty() || !planReceived
        val candidates = JevCandidates.build(
            screen, memory, excluded, apps, canType = canType,
            returnTo = taskPackage.takeIf { strayed },
        )
        memory.recordOffered(here, JevCandidates.explorationIdentities(candidates, screen))
        val decision = decisionEngine.decide(
            JevStepInput(
                task = task,
                subgoals = subgoals,
                advisorHint = hint,
                appGuide = manual?.text,
                history = history,
                screen = screen,
                candidates = candidates,
                textValues = textValues,
                previous = previous,
            ),
        ).getOrElse { e ->
            Log.w(TAG, "JEV decision failed: ${e.message}")
            return escalate("JEV unavailable (${e.message})")
        }
        Log.i(
            TAG,
            "Step $stepNumber [${screen.source} ${perceiveMs}ms, ${candidates.size} cands]: " +
                "${decision.candidate.key} conf=${"%.2f".format(decision.confidence)} " +
                "explored=${decision.explored} prev=${decision.previousOutcome}" +
                (if (decision.outcomeFromHost) "(host)" else "") +
                (if (decision.reconsidered) " reconsidered" else "") + " jevCalls=${decision.jevCalls}",
        )

        // Verification of the previous fast action, from the same round trip.
        decision.previousOutcome?.let { outcome ->
            if (history.isNotEmpty()) history[history.lastIndex] = history.last().copy(outcome = outcome)
            consecutiveBad = if (outcome == StepOutcome.SUCCESS) 0 else consecutiveBad + 1
            val key = previousKey
            if (outcome == StepOutcome.NO_CHANGE && key != null) {
                val count = (noChangeCounts[key] ?: 0) + 1
                noChangeCounts[key] = count
                if (count >= MAX_NO_CHANGE_REPEATS) excluded += key
            }
        }
        adviseOnNewPage(stepNumber, task, context, tree, here, manual)

        val chosen: Candidate = decision.candidate
        // A DONE claim is always audited first: a finished task naturally stops finding new pages.
        val cycling = chosen.action !is CandidateAction.Done && (memory.isCycling() || memory.isStagnant())
        when {
            chosen.action is CandidateAction.Escalate -> return escalate("JEV asked for the Reasoning model")
            consecutiveBad >= MAX_CONSECUTIVE_BAD -> return escalate("$consecutiveBad fast actions in a row had no effect")
            cycling -> return escalate("no new page for several steps")
            // Finding where something lives is a reasoning problem: blind exploration by JEV wanders,
            // while the Reasoning model knows common app layouts. JEV keeps the confident steps.
            decision.explored && decision.confidence < EXPLORE_CONFIDENCE ->
                return escalate("JEV would explore with confidence ${"%.2f".format(decision.confidence)}")
            chosen.action is CandidateAction.Done -> return FastStep.DoneClaimed(screen.summary)
            chosen.action is CandidateAction.Wait -> {
                delay(WAIT_MS)
                return FastStep.Retry
            }
        }

        val element = chosen.action as? CandidateAction.Element
        var text: String? = null
        if (element?.op == ElementOp.INPUT) {
            if (textValues.isEmpty()) {
                advisor.await(TEXT_WAIT_MS)
                absorb()
            }
            text = decision.textKey?.let(textValues::get) ?: textValues.values.firstOrNull()
            if (text == null) {
                history += StepRecord("${chosen.description}: no text available to type")
                excluded += chosen.key
                return FastStep.Retry
            }
        }

        val action = toAgentAction(chosen.action, screen, text)
        if (chosen.action is CandidateAction.OpenApp) taskPackage = null // re-anchored on the next screen
        val label = chosen.description.substringBefore(" [") + (text?.let { " typing '$it'" } ?: "")
        if (!confirmer.confirm(context.appPackage, actionName(chosen.action), label, baseSafetyLevel(chosen.action))) {
            return FastStep.Declined
        }

        val result = try {
            // Injected gestures must pass through Coati's own full-screen overlay to reach the app.
            overlayManager.withGesturePassthrough {
                if (action is AgentAction.FocusAndInputText) executeInput(action, context) else dispatch(action, context)
            }
        } catch (e: IllegalArgumentException) {
            if (e.message != STALE_COORDINATES) throw e
            // The screen moved on between capture and dispatch: the decision is void, not failed.
            Log.i(TAG, "Screen changed before '$label' could run; re-observing")
            return FastStep.Retry
        }
        Log.i(TAG, "Executed ${action.toLogSummary()}: success=${result.success} ${result.message}")
        history += StepRecord(if (result.success) label else "$label (failed: ${result.message})")
        previous = PreviousStep(
            actionLabel = label,
            screenDigest = JevDecisionEngine.digest(screen),
            expectation = StepVerifier.expectation(chosen.action, screen, text),
        )
        previousScreen = screen
        previousCandidate = chosen.action
        previousTyped = text
        previousPage = here
        previousIdentity = JevCandidates.identityOf(chosen.action, screen)
        previousKey = chosen.key
        previousAction = action
        previousTree = tree
        delay(SETTLE_MS)
        return FastStep.Acted
    }

    private fun absorb() {
        val guidance = advisor.poll() ?: return
        planReceived = true
        if (guidance.subgoals.isNotEmpty()) subgoals = guidance.subgoals
        guidance.hint?.let { hint = it }
        textValues.putAll(guidance.textValues)
        guidance.apps.filterNot(apps::contains).forEach(apps::add)
        Log.i(TAG, "Guidance(step=${guidance.forStep}): subgoals=${subgoals.size}, texts=${textValues.keys}, hint=${hint?.take(60)}")
    }

    /** Background guidance (and manual learning) whenever a new page appears. */
    private fun adviseOnNewPage(
        stepNumber: Int,
        task: String,
        context: ScreenContext,
        tree: UiTreeNode?,
        here: Set<String>,
        manual: RenderedAppManual?,
    ) {
        val advised = lastAdvisedPage
        if (advised != null && ParsedScreen.samePage(advised, here)) return
        if (advisor.busy) return
        lastAdvisedPage = here
        val learning = manualRuntime?.learningObservationInstruction(context.appPackage, tree, manual)
        val headings = manual?.headingPaths.orEmpty()
        val precedingAction = previousAction
        val beforeTree = previousTree
        advisor.advise(stepNumber, task, context.screenshotJpeg, subgoals, history.toList(), learning) {
            // Page learning evidence for THIS step's screen, admitted by the manual layer's own guards.
            manualRuntime?.recordObservedCurrentPage(
                packageName = context.appPackage,
                tree = tree,
                observation = it,
                knownHeadingPaths = headings,
                precedingAction = precedingAction,
                beforeTree = beforeTree,
            )
        }
    }

    private fun escalate(reason: String): FastStep {
        escalations++
        consecutiveBad = 0
        Log.i(TAG, "Escalating to the Reasoning model ($escalations/$MAX_ESCALATIONS): $reason")
        return FastStep.Escalate(reason)
    }

    private suspend fun retryOrEscalate(reason: String): FastStep {
        if (++consecutiveRetries >= MAX_CONSECUTIVE_RETRIES) {
            consecutiveRetries = 0
            return escalate(reason)
        }
        Log.i(TAG, "Fast step skipped ($consecutiveRetries/$MAX_CONSECUTIVE_RETRIES): $reason")
        delay(RETRY_DELAY_MS)
        return FastStep.Retry
    }

    private fun forgetPrevious() {
        previous = null
        previousScreen = null
        previousCandidate = null
        previousTyped = null
        previousPage = null
        previousIdentity = null
        previousKey = null
        previousAction = null
        previousTree = null
    }

    /** Home screens never anchor a task: the task starts in whatever app is opened from them. */
    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return a11y.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun toAgentAction(action: CandidateAction, screen: ParsedScreen, text: String?): AgentAction = when (action) {
        is CandidateAction.Element -> {
            val e = requireNotNull(screen.element(action.elementId)) { "stale_candidate" }
            when (action.op) {
                ElementOp.TAP -> AgentAction.Tap(e.centerX, e.centerY)
                ElementOp.LONG -> AgentAction.LongPress(e.centerX, e.centerY)
                ElementOp.INPUT -> AgentAction.FocusAndInputText(e.centerX, e.centerY, requireNotNull(text))
            }
        }
        is CandidateAction.Scroll -> {
            // Swipe inside the region itself so carousels and nested lists scroll, not the page.
            val r = requireNotNull(screen.region(action.regionId)) { "stale_region" }.bounds
            val cx = (r.left + r.right) / 2
            val cy = (r.top + r.bottom) / 2
            val dx = (r.right - r.left) * 3 / 10
            val dy = (r.bottom - r.top) * 3 / 10
            when (action.direction) {
                "DOWN" -> AgentAction.Swipe(cx, cy + dy, cx, cy - dy)
                "UP" -> AgentAction.Swipe(cx, cy - dy, cx, cy + dy)
                "RIGHT" -> AgentAction.Swipe(cx + dx, cy, cx - dx, cy)
                else -> AgentAction.Swipe(cx - dx, cy, cx + dx, cy)
            }
        }
        is CandidateAction.OpenApp -> AgentAction.OpenApp(appName = action.appName)
        is CandidateAction.ReturnToApp -> AgentAction.OpenApp(packageName = action.packageName)
        CandidateAction.Back -> AgentAction.PressKey("BACK")
        CandidateAction.Enter -> AgentAction.PressKey("ENTER")
        CandidateAction.Wait, CandidateAction.Done, CandidateAction.Escalate -> error("not_an_executable_candidate")
    }

    private suspend fun dispatch(action: AgentAction, context: ScreenContext): ActionResult {
        val coordinates = context.coordinates
        return if (coordinates != null && action.isSpatial()) {
            coordinates.dispatch({ DisplaySpaceReader.read(a11y) }) { actionEngine.execute(action, coordinates) }
        } else {
            actionEngine.execute(action)
        }
    }

    /**
     * A chosen text value replaces a simple single-line field (search boxes keep stale queries
     * otherwise). ActionEngine refuses replacement in multiline, WebView and rich editors; those
     * fall back to the caret-first FocusAndInputText path.
     */
    private suspend fun executeInput(action: AgentAction.FocusAndInputText, context: ScreenContext): ActionResult {
        val focused = dispatch(AgentAction.Tap(action.x, action.y), context)
        if (!focused.success) return focused
        delay(FOCUS_SETTLE_MS)
        val replaced = actionEngine.execute(AgentAction.InputText(action.text, InputTextMode.REPLACE))
        if (replaced.success || replaced.failureCode != ActionFailureCode.TEXT_REPLACE_UNSAFE) return replaced
        Log.i(TAG, "Replacement unsafe for this editor; typing at the caret instead")
        return dispatch(action, context)
    }

    private fun AgentAction.isSpatial() = this is AgentAction.Tap || this is AgentAction.LongPress ||
        this is AgentAction.Swipe || this is AgentAction.FocusAndInputText

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "CoatiJev"
        private const val MAX_CONSECUTIVE_BAD = 3
        private const val MAX_CONSECUTIVE_RETRIES = 4
        private const val MAX_NO_CHANGE_REPEATS = 2
        private const val MAX_AUDIT_REJECTS = 2
        const val MAX_ESCALATIONS = 10
        private const val EXPLORE_CONFIDENCE = 0.5
        private const val SETTLE_MS = 800L
        private const val FOCUS_SETTLE_MS = 600L
        private const val WAIT_MS = 1500L
        private const val RETRY_DELAY_MS = 1_000L
        private const val TEXT_WAIT_MS = 30_000L
        private const val STALE_COORDINATES = "stale_screenshot_coordinates"

        /** Navigation is reversible; typing and long-press menus are standard interactions. */
        internal fun baseSafetyLevel(action: CandidateAction): String =
            if (action is CandidateAction.Element && action.op != ElementOp.TAP) "medium" else "low"

        internal fun actionName(action: CandidateAction): String = when (action) {
            is CandidateAction.Element -> action.op.name.lowercase()
            is CandidateAction.Scroll -> "scroll"
            is CandidateAction.OpenApp, is CandidateAction.ReturnToApp -> "openApp"
            else -> "pressKey"
        }
    }
}
