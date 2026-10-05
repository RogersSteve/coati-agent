package bot.coati.agent

import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import bot.coati.action.ActionEngine
import bot.coati.action.ActionFailureCode
import bot.coati.action.ActionResult
import bot.coati.action.AgentAction
import bot.coati.action.InputTextMode
import bot.coati.action.toLogSummary
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.PixelBounds
import bot.coati.coordinates.ScreenshotCoordinates
import bot.coati.experience.AppManualRuntime
import bot.coati.experience.RenderedAppManual
import bot.coati.jev.BackgroundAdvisor
import bot.coati.jev.Candidate
import bot.coati.jev.CandidateAction
import bot.coati.jev.ExplorationMemory
import bot.coati.jev.HostVerdict
import bot.coati.jev.JevCandidates
import bot.coati.jev.JevDecisionEngine
import bot.coati.jev.JevStepInput
import bot.coati.jev.PreCommitCheck
import bot.coati.jev.PreviousStep
import bot.coati.jev.StepOutcome
import bot.coati.jev.StepRecord
import bot.coati.jev.StepVerifier
import bot.coati.model.ManualPageObservation
import bot.coati.model.ModelRouter
import bot.coati.model.ScreenContext
import bot.coati.perception.A11yScreenParser
import bot.coati.perception.HybridPerception
import bot.coati.perception.OcrEngine
import bot.coati.perception.OcrLine
import bot.coati.perception.OcrTextRefiner
import bot.coati.perception.ElementOp
import bot.coati.perception.LumaGrid
import bot.coati.perception.ParsedScreen
import bot.coati.perception.ScreenDiff
import bot.coati.perception.ScreenSource
import bot.coati.perception.VisionScreenParser
import bot.coati.perception.VisualChange
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.UiTreeNode
import bot.coati.ui.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What one fast step did; [AgentLoop] owns everything that follows. */
sealed interface FastStep {
    /** An action was executed; observe again. */
    data object Acted : FastStep

    /** A decision that executed nothing on the screen (wait, no text to type); observe again. */
    data object Retry : FastStep

    /**
     * The screen was not ready to act on (windows still changing, or it changed before the action
     * could be dispatched): nothing was decided or sent, so the step does not use the task's budget.
     */
    data object NotReady : FastStep

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
    /**
     * On-device GPU OCR once it is ready, or null (no usable GPU, or still loading); perception works
     * without it and never waits for it.
     */
    private val ocrProvider: () -> OcrEngine? = { null },
) {
    /** Performs a safety confirmation for a candidate; false when the user declined. */
    fun interface Confirmer {
        suspend fun confirm(appPackage: String, actionName: String, label: String, baseLevel: String): Boolean
    }

    private val visionParser = VisionScreenParser(modelRouter.fast)
    private val preCommit = PreCommitCheck(modelRouter.fast)
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
    private var planAwaited = false
    private var lastAdvisedPage: Set<String>? = null
    private var taskPackage: String? = null
    private var launchers: Set<String> = emptySet()
    private var consecutiveBad = 0
    private var consecutiveRetries = 0
    private var exploreStreak = 0
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
    private val recentVerdicts = mutableListOf<HostVerdict>()

    // The last native page OCR found free of painted content; later steps on it skip OCR.
    private var plainPage: Pair<String, Set<String>>? = null

    /** The last vision parse merged into a hybrid page, with the painted lines OCR read then. */
    private class HybridCache(
        val packageName: String,
        val painted: List<OcrLine>,
        val vision: ParsedScreen,
        val paintedBounds: List<PixelBounds>,
    )
    private var hybridCache: HybridCache? = null

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
        planAwaited = false
        lastAdvisedPage = null
        taskPackage = null
        launchers = launcherPackages()
        consecutiveBad = 0
        consecutiveRetries = 0
        exploreStreak = 0
        auditRejects = 0
        escalations = 0
        forgetPrevious()
        previousLuma = null
        plainPage = null
        hybridCache = null
        recentVerdicts.clear()
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
        exploreStreak = 0
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
        if (!planAwaited) {
            // Without the plan the selector knows neither the target app nor the text to type, and
            // its first choices are guesses (AndroidWorld: 3-7 of a 10-step budget lost before a plan
            // that arrived after 7-16 s). Wait for it once; a late plan is absorbed when it arrives.
            planAwaited = true
            advisor.await(PLAN_WAIT_MS)
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
        val lastScreen = previousScreen
        // An unchanged frame keeps a previous model-backed parse and skips the model call.
        // Pixels that moved on the element just acted on (a state label flipping) mean the parse is
        // stale even when the change is tiny.
        val lastTarget = (previousCandidate as? CandidateAction.Element)?.let { lastScreen?.element(it.elementId)?.bounds }
        val changedAtLastTarget = lastTarget != null && visual?.bounds?.let { overlaps(it, lastTarget) } == true
        val reusedParse = lastScreen != null && lastScreen.source != ScreenSource.ACCESSIBILITY &&
            lastScreen.packageName == context.appPackage && !changedAtLastTarget &&
            visual != null && visual.bounds == null
        val screen: ParsedScreen = (if (reusedParse) lastScreen else perceive(context, tree, coordinates, visual))
            ?: return FastStep.Escalate("the screen could not be parsed")
        val perceiveMs = System.currentTimeMillis() - started
        consecutiveRetries = 0
        val here = screen.pageKey

        // Host evidence for the previous fast action: pixel diff, texts, target state (no model).
        val before = previousScreen
        val beforeAction = previousCandidate
        if (previous != null && before != null && beforeAction != null) {
            val observation = StepVerifier.observe(before, screen, beforeAction, previousTyped, visual)
            previous = previous!!.copy(observation = observation)
            recentVerdicts += observation.verdict
            if (recentVerdicts.size > PROGRESS_WINDOW) recentVerdicts.removeAt(0)
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
        exploreStreak = if (decision.explored) exploreStreak + 1 else 0

        val chosen: Candidate = decision.candidate
        // A DONE claim is always audited first: a finished task naturally stops finding new pages.
        val cycling = chosen.action !is CandidateAction.Done && !makingProgress() &&
            (memory.isCycling() || memory.isStagnant())
        when {
            chosen.action is CandidateAction.Escalate -> return escalate("JEV asked for the Reasoning model")
            consecutiveBad >= MAX_CONSECUTIVE_BAD -> return escalate("$consecutiveBad fast actions in a row had no effect")
            cycling -> return escalate("no new page for several steps")
            // Every new page resets the cycle check, so blind exploration through a large app never
            // escalates on its own: after a long streak without one confident choice, the task has
            // usually left its target page. One Reasoning step reorients; JEV keeps its memory.
            chosen.action !is CandidateAction.Done && exploreStreak >= MAX_EXPLORE_STREAK -> {
                exploreStreak = 0
                return escalate("$MAX_EXPLORE_STREAK exploratory steps in a row without a confident choice")
            }
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
        val elementType = element?.let { screen.element(it.elementId)?.type }.orEmpty()
        if (element?.op == ElementOp.TAP && PreCommitCheck.isCommit(label, elementType)) {
            val verdict = preCommit.check(task, label, context.screenshotJpeg)
            Log.i(TAG, "Pre-commit check for '$label': ready=${verdict?.ready} missing=${verdict?.missing}")
            if (verdict?.ready == false) {
                return escalate("before '$label' the screen is not ready: ${verdict.missing ?: "a required value is missing"}")
            }
        }
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
            return FastStep.NotReady
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

    /**
     * The tree when it explains the screen; a vision parse when it does not. With GPU OCR, rendered
     * text that no tree node accounts for reveals painted content on a native screen, and the
     * vision parse of it is merged into the tree (hybrid); OCR also corrects vision transcriptions.
     */
    private suspend fun perceive(
        context: ScreenContext,
        tree: UiTreeNode?,
        coordinates: ScreenshotCoordinates,
        visual: VisualChange?,
    ): ParsedScreen? {
        val width = coordinates.rawWidth
        val height = coordinates.rawHeight
        val scale = width.toFloat() / coordinates.imageWidth
        val statusBar = coordinates.display.insets.top
        val ocr = ocrProvider()
        if (tree != null && A11yScreenParser.isUsable(tree)) {
            val treeScreen = A11yScreenParser.parse(tree, context.appPackage, width, height)
            val engine = ocr ?: return treeScreen
            val plain = plainPage
            if (plain != null && plain.first == context.appPackage && ParsedScreen.samePage(plain.second, treeScreen.pageKey)) {
                return treeScreen
            }
            val ocrStarted = System.currentTimeMillis()
            // Only text the tree does not explain matters here, and recognition is the costly part of
            // OCR: text inside labelled nodes and the status bar is detected but not read.
            // The soft keyboard is another window, never part of the app's tree: never read it.
            val hosts = A11yScreenParser.textHosts(tree, width, height)
            val keyboard = keyboardBounds(tree, width, height)
            val labelled = hosts.filter { it.labelled }.map { it.bounds } + keyboard
            // Painted content can only sit in unlabelled, non-image nodes: read just those.
            val candidates = hosts.filter { !it.labelled && !it.image }.map { it.bounds }
            val lines = engine.recognize(context.screenshotJpeg, scale, labelled + PixelBounds(0, 0, width, statusBar), candidates)
            val uncovered = HybridPerception.paintedLines(lines, hosts, statusBar, keyboard)
            Log.i(TAG, "OCR ${System.currentTimeMillis() - ocrStarted}ms: ${uncovered.size} lines not in the tree")
            if (uncovered.size < HybridPerception.MIN_PAINTED_LINES) {
                plainPage = context.appPackage to treeScreen.pageKey
                return treeScreen
            }
            plainPage = null
            // Typing into a native field of a hybrid page leaves its painted part alone: when OCR reads
            // the same painted lines in the same places and no pixel changed over a painted element,
            // the last vision parse still holds and only the tree is fresh (saves 4-6 s per step).
            val cached = hybridCache?.takeIf { cache ->
                visual != null && cache.packageName == context.appPackage &&
                    HybridPerception.samePainted(cache.painted, uncovered) &&
                    cache.paintedBounds.none { painted -> visual.bounds?.let { overlaps(it, painted) } == true }
            }
            val vision = if (cached != null) {
                Log.i(TAG, "Painted content unchanged (${uncovered.size} lines); reusing the vision parse")
                cached.vision
            } else {
                Log.i(TAG, "Painted content on a native screen (${uncovered.take(4).joinToString { it.text }}); merging a vision parse")
                parseVision(context, coordinates)?.let { OcrTextRefiner.refine(it, lines, statusBar) } ?: return treeScreen
            }
            val merged = HybridPerception.merge(treeScreen, vision, statusBar)
            if (cached == null) {
                hybridCache = HybridCache(
                    context.appPackage, uncovered, vision, merged.elements.drop(merged.pageKeyElements).map { it.bounds },
                )
            }
            return merged
        }
        val keyboard = keyboardBounds(tree, width, height)
        return coroutineScope {
            val ocrLines = ocr?.let { engine -> async { engine.recognize(context.screenshotJpeg, scale, keyboard) } }
            var vision = parseVision(context, coordinates)
            if (vision == null) {
                ocrLines?.cancel()
                return@coroutineScope null
            }
            // OCR repairs the parse but must never make the step slower: on text-heavy screens it can
            // outlast the vision parse, and then this step goes without the repair.
            val lines = ocrLines?.let { withTimeoutOrNull(OCR_EXTRA_WAIT_MS) { it.await() } ?: run { it.cancel(); null } }
            if (ocrLines != null && lines == null) Log.i(TAG, "OCR slower than the vision parse; parse used without repair")
            if (lines != null && isForeign(vision, lines)) {
                // The parse describes some other image: acting on it only wastes steps. Ask once more.
                vision = parseVision(context, coordinates)?.takeUnless { isForeign(it, lines) } ?: return@coroutineScope null
            }
            lines?.let { OcrTextRefiner.refine(vision, it, statusBar) } ?: vision
        }
    }

    /**
     * Where the soft keyboard is drawn: the input-method window when the platform lists it (some
     * devices omit it while Coati's own accessibility input method holds the input session).
     * Otherwise, only while that input session is active, everything below the focused field: the
     * platform keeps the focused field visible above the keyboard.
     */
    private fun keyboardBounds(tree: UiTreeNode?, width: Int, height: Int): List<PixelBounds> {
        val windows = a11y.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { window -> Rect().also(window::getBoundsInScreen).let { PixelBounds(it.left, it.top, it.right, it.bottom) } }
        if (windows.isNotEmpty()) return windows
        if (tree == null || !a11y.hasActiveInputConnection()) return emptyList()
        val fieldBottom = focusedFieldBottom(tree) ?: return emptyList()
        return if (fieldBottom < height) listOf(PixelBounds(0, fieldBottom, width, height)) else emptyList()
    }

    private fun focusedFieldBottom(node: UiTreeNode): Int? =
        if (node.isEditable && node.isFocused) node.bounds.bottom
        else node.children.firstNotNullOfOrNull(::focusedFieldBottom)

    private fun isForeign(vision: ParsedScreen, lines: List<OcrLine>): Boolean {
        val agreement = OcrTextRefiner.agreement(vision, lines) ?: return false
        val foreign = agreement < MIN_PARSE_AGREEMENT
        if (foreign) {
            Log.w(TAG, "Vision parse disagrees with OCR (${"%.0f".format(agreement * 100)}% of its texts on screen): " +
                vision.elements.take(6).joinToString { it.text.lineSequence().first().take(24) })
        }
        return foreign
    }

    private suspend fun parseVision(context: ScreenContext, coordinates: ScreenshotCoordinates): ParsedScreen? =
        visionParser.parse(
            context.screenshotJpeg,
            coordinates.imageWidth,
            coordinates.imageHeight,
            context.appPackage,
        ) { x, y -> coordinates.normalized(x, y) }.getOrElse { e ->
            Log.w(TAG, "Vision parse failed: ${e.message}")
            null
        }

    /**
     * Most recent fast actions changed something on their own target: the task is advancing on one
     * page (a form, with the keyboard scrolling it), whatever the page fingerprints look like.
     */
    private fun makingProgress(): Boolean =
        recentVerdicts.size >= PROGRESS_WINDOW / 2 &&
            recentVerdicts.count { it == HostVerdict.CHANGED_AT_TARGET } * 2 > recentVerdicts.size

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
        val learning = manualRuntime?.learningObservationInstruction(context.appPackage, tree, manual)
        // Learning describes every page, so sibling pages count separately; plain guidance keeps the
        // looser page identity (more guidance calls slowed the audits and verifies beside them).
        val advised = lastAdvisedPage
        val samePage = if (learning != null) ParsedScreen::nearlySamePage else ParsedScreen::samePage
        if (advised != null && samePage(advised, here)) return
        val headings = manual?.headingPaths.orEmpty()
        val precedingAction = previousAction
        val beforeTree = previousTree
        // Page learning evidence for THIS step's screen, admitted by the manual layer's own guards.
        val record: (ManualPageObservation) -> Unit = {
            Log.i(TAG, "Manual observation for step $stepNumber: page=${it.pageName}")
            manualRuntime?.recordObservedCurrentPage(
                packageName = context.appPackage,
                tree = tree,
                observation = it,
                knownHeadingPaths = headings,
                precedingAction = precedingAction,
                beforeTree = beforeTree,
            )
        }
        if (advisor.busy) {
            // Guidance can wait for a later page; the manual needs this one, so observe it beside.
            if (learning != null && advisor.observe(stepNumber, task, context.screenshotJpeg, subgoals, history.toList(), learning, record)) {
                lastAdvisedPage = here
            }
            return
        }
        lastAdvisedPage = here
        advisor.advise(stepNumber, task, context.screenshotJpeg, subgoals, history.toList(), learning, record.takeIf { learning != null })
    }

    /** Lets page observations still in flight reach the app manual before the task completes. */
    suspend fun finishObservations() {
        advisor.awaitObservations(OBSERVATION_WAIT_MS)
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
        return FastStep.NotReady
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
     * otherwise). Where replacement is refused or impossible, the caret-first FocusAndInputText
     * path types through the input method.
     */
    private suspend fun executeInput(action: AgentAction.FocusAndInputText, context: ScreenContext): ActionResult {
        val focused = dispatch(AgentAction.Tap(action.x, action.y), context)
        if (!focused.success) return focused
        delay(FOCUS_SETTLE_MS)
        val replaced = actionEngine.execute(AgentAction.InputText(action.text, InputTextMode.REPLACE))
        if (replaced.success) return replaced
        // Unsafe editors (multiline, WebView, rich text) and fields without an accessibility node
        // (custom-drawn inputs) cannot be replaced through the node. The tap above focused the field,
        // so type at its caret through the input method; tapping again would leave no fresh caret
        // evidence and be refused.
        Log.i(TAG, "Field replacement unavailable (${replaced.failureCode ?: replaced.message}); typing at the caret instead")
        if (replaced.failureCode == ActionFailureCode.TEXT_NOT_EDITABLE) {
            val typed = actionEngine.execute(AgentAction.InputText(action.text, InputTextMode.TYPE))
            if (typed.success) return typed
        }
        return dispatch(action, context)
    }

    private fun overlaps(a: PixelBounds, b: PixelBounds) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun AgentAction.isSpatial() = this is AgentAction.Tap || this is AgentAction.LongPress ||
        this is AgentAction.Swipe || this is AgentAction.FocusAndInputText

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "CoatiJev"
        private const val MAX_CONSECUTIVE_BAD = 3
        private const val PROGRESS_WINDOW = 6
        private const val OBSERVATION_WAIT_MS = 20_000L
        /** Successful board runs explored at most 5 steps in a row (one deep search: 14); every timeout 7-27. */
        private const val MAX_EXPLORE_STREAK = 6
        private const val MAX_CONSECUTIVE_RETRIES = 4
        private const val MAX_NO_CHANGE_REPEATS = 2
        private const val MAX_AUDIT_REJECTS = 2
        const val MAX_ESCALATIONS = 10
        private const val SETTLE_MS = 800L
        private const val FOCUS_SETTLE_MS = 600L
        private const val WAIT_MS = 1500L
        private const val RETRY_DELAY_MS = 1_000L
        private const val OCR_EXTRA_WAIT_MS = 1_000L
        /** Below this share of its texts confirmed by OCR, a vision parse describes another image (normal parses scored >= 0.5 on the board set). */
        private const val MIN_PARSE_AGREEMENT = 0.3
        private const val TEXT_WAIT_MS = 30_000L
        private const val PLAN_WAIT_MS = 20_000L
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
