package bot.coati.agent

import android.util.Log
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.ModelCoordinateAction
import bot.coati.coordinates.ScreenshotCoordinates
import bot.coati.action.ActionEngine
import bot.coati.action.ActionFailureCode
import bot.coati.action.AgentAction
import bot.coati.action.toLogSummary
import bot.coati.data.SafetyMode
import bot.coati.experience.AppManualRuntime
import bot.coati.model.Message
import bot.coati.model.ModelCallPurpose
import bot.coati.model.MODEL_REQUEST_TIMEOUT_MS
import bot.coati.model.ModelResponse
import bot.coati.model.ModelUsageMeter
import bot.coati.model.ModelRouter
import bot.coati.model.QuickCheckResult
import bot.coati.model.ScreenContext
import bot.coati.model.VerifyOutcome
import bot.coati.model.VerifyResult
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.HomeScreens
import bot.coati.service.UiTreeNode
import bot.coati.state.StateManager
import bot.coati.state.TaskState
import bot.coati.ui.overlay.OverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class CompletionAuditDecision {
    ACCEPT,
    REJECT,
    UNAVAILABLE,
}

class AgentLoop(
    private val overlayManager: OverlayManager,
    private val modelRouter: ModelRouter,
    private val actionEngine: ActionEngine,
    private val a11y: CoatiAccessibilityService,
    private val stateManager: StateManager? = null,
    private val manualRuntime: AppManualRuntime? = null,
    private val safetyModeProvider: () -> SafetyMode = { SafetyMode.STANDARD },
    /**
     * Optional JEV fast path. When present it takes each step first and escalates to the
     * Reasoning step below when it is unsure or stuck; when absent every step uses Reasoning.
     */
    private val fastExecutor: JevStepExecutor? = null,
) {
    private data class ActionRecord(
        val action: String,
        val statusUpdate: String,
        val screenSignature: String,
        val outcome: VerifyOutcome,
        val target: String = "",
    )

    /**
     * A real UI transition captured by the host. The next normal Reasoning turn describes the
     * destination page; Fast Verify is deliberately not a source of manual content.
     */
    private data class PendingManualTransition(
        val destinationPackage: String,
        val action: AgentAction,
        val beforeTree: UiTreeNode?,
    )

    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val conversationHistory = mutableListOf<Message>()
    private val actionHistory = mutableListOf<ActionRecord>()
    private var taskStartTime = 0L
    private var stepCount = 0

    private val autocompact = Autocompact()
    private val exceptionHandler = ExceptionHandler()
    private val capabilityFailureLedger = CapabilityFailureLedger()
    private val screenshotCache = mutableListOf<ByteArray>()
    private val appFrames = AppResultFrames()
    // Home screens and system chrome hold no task results.
    private var framelessPackages: Set<String> = emptySet()
    private var currentTask: TaskState? = null
    private var resumedFromPause = false
    private var replanHint: String? = null
    private var lastStepFeedback: String? = null
    private var taskCompletionAuditRejects = 0
    private var runOptions = TaskRunOptions()
    private val maxSteps: Int get() = runOptions.maxSteps
    private var doneClaims = 0
    private var lastAuditDecision: CompletionAuditDecision? = null
    private var fastSteps = 0
    private var escalations = 0
    private var routedTask = false
    private var routingLabel: String? = null
    private var verifyEnabled = true
    private var treeWithheld = false
    private var finalAnswer: String? = null
    private var refundedSteps = 0

    @Volatile
    var emergencyStop = false

    fun start(task: String, options: TaskRunOptions = TaskRunOptions()) {
        stop()
        runOptions = options
        val taskSafetyMode = safetyModeProvider()
        // Evaluation switches; the user-facing app always runs with the defaults (see TaskHooks).
        verifyEnabled = TaskHooks.installed.verifyEnabled()
        if (!verifyEnabled) Log.w(TAG, "Verification is off for this task (ablation baseline)")
        treeWithheld = TaskHooks.installed.treeWithheld()
        if (treeWithheld) Log.w(TAG, "Accessibility tree withheld from perception for this task (perturbation)")
        conversationHistory.clear()
        screenshotCache.clear()
        appFrames.clear()
        framelessPackages = HomeScreens.packages(a11y.packageManager) + setOf(a11y.packageName, SYSTEM_UI_PACKAGE)
        actionHistory.clear()
        taskStartTime = System.currentTimeMillis()
        stepCount = 0
        emergencyStop = false
        exceptionHandler.reset()
        capabilityFailureLedger.reset()
        resumedFromPause = false
        replanHint = null
        lastStepFeedback = null
        taskCompletionAuditRejects = 0
        doneClaims = 0
        finalAnswer = null
        refundedSteps = 0
        lastAuditDecision = null
        fastSteps = 0
        escalations = 0
        ModelUsageMeter.beginTask()
        manualRuntime?.startTask(task)
        fastExecutor?.startTask(task)
        // Captured now: stopping the task clears the executor before a stopped task's result is written.
        routedTask = fastExecutor?.activeForTask == true
        routingLabel = fastExecutor?.takeIf { routedTask }?.routing?.label
        Log.i(TAG, "Starting agent loop for task: $task (confirmationMode=$taskSafetyMode, runId=${options.runId}, maxSteps=${options.maxSteps})")

        overlayManager.enterFrameExecuting(task)

        currentJob = scope.launch {
            val myJob = coroutineContext[Job]
            try {
                runLoop(task, taskSafetyMode)
            } catch (e: CancellationException) {
                Log.i(TAG, if (emergencyStop) "Emergency stop triggered!" else "Agent loop cancelled")
                // Guard: only do cleanup if no new task has started since we were launched
                if (currentJob == null || currentJob === myJob) {
                    updateTaskStatus("paused")
                    overlayManager.collapse()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e.message}", e)
                // Guard: only show failure dialog if no new task has started since we were launched
                if (currentJob == null || currentJob === myJob) {
                    updateTaskStatus("failed", e.message)
                    val retry = overlayManager.awaitTaskFailed(
                        taskName = task,
                        reason = e.message ?: "Unknown error",
                        stepsCompleted = stepCount,
                        totalSteps = maxSteps,
                    )
                    if (retry) {
                        start(task, runOptions)
                    } else {
                        overlayManager.collapse()
                    }
                }
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        manualRuntime?.discardTask()
        fastExecutor?.finishTask()
        overlayManager.resetBallState()
        Log.i(TAG, "Agent loop stopped")
    }

    fun resumeFromPause() {
        val task = currentTask
        if (task != null) {
            resumedFromPause = true
            start(task.goal)
        }
    }

    private suspend fun runLoop(task: String, safetyMode: SafetyMode) {
        // INIT: create TaskState
        val taskState = TaskState.newTask(task)
        currentTask = taskState
        writeTaskState(taskState)

        var prevNodeCount = 0
        var prevApp = ""
        // Carry the verify screenshot forward as the next step's observe input, so two
        // back-to-back takeScreenshot() calls never happen within the same second (BUG-12).
        var carryOverContext: ScreenContext? = null
        // Learning evidence is collected by the already-required Reasoning turn from its current
        // screen. A compact Fast verifier may fail or be truncated without breaking this path.
        var pendingManualTransition: PendingManualTransition? = null

        while (stepCount < maxSteps && !emergencyStop && currentJob?.isActive == true) {
            stepCount++
            Log.i(TAG, "── Step $stepCount/$maxSteps ──")

            // a. REORIENT: if resumed from pause
            if (resumedFromPause && stepCount == 1) {
                Log.i(TAG, "Reorienting after pause...")
                resumedFromPause = false
                val targetPkg = currentTask?.targetPackage
                if (targetPkg != null) {
                    actionEngine.execute(AgentAction.OpenApp(packageName = targetPkg))
                    delay(randomDelay())
                }
            }

            // b. OBSERVE
            // Reuse the verify screenshot from the previous step when available, to avoid
            // two rapid takeScreenshot() calls hitting the system rate limit (BUG-12).
            overlayManager.updateProgress(task, "Capturing screen...", "", stepCount, maxSteps)
            // On the first step, wait for the keyboard/clipboard panel to fully dismiss after
            // the user pressed Execute (keyboard hide animation takes ~300ms).
            if (stepCount == 1) delay(500)
            // Hide overlay before capturing so the Coati UI doesn't appear in the screenshot
            // sent to the model (BUG-5). Same alpha/updateViewLayout pattern as gesture
            // passthrough; 50ms gives the compositor time to re-render without the overlay.
            // A carried-over frame without a coordinate mapping is never reused: the model would be
            // shown a screen it cannot tap, and every spatial answer would be rejected unexecuted.
            val carried = carryOverContext.also { carryOverContext = null }
            if (carried != null && carried.coordinates == null) {
                Log.i(TAG, "Carried-over frame has no coordinate mapping; capturing a fresh one")
            }
            val screenContext: ScreenContext = (carried?.takeIf { it.coordinates != null }
                ?: captureScreen()
                ?: throw Exception("Failed to capture screen"))
                .let { if (treeWithheld) it.copy(uiTreeJson = "") else it }

            screenshotCache.add(screenContext.screenshotJpeg)

            // b2. SYSTEM AUTHORIZATION: permission and install dialogs belong to the user unless
            // they fully trust the agent (TRUST).
            val onSecurityDialog = SecurityDialogs.isSecurityDialog(screenContext.appPackage)
            if (onSecurityDialog && !SecurityDialogs.agentMayAnswer(safetyMode)) {
                if (!awaitUserAnswersSecurityDialog(task)) {
                    updateTaskStatus("failed", "Timed out waiting for the user to answer a system dialog")
                    throw Exception("No answer to the system dialog within ${SecurityDialogs.USER_RESPONSE_TIMEOUT_MS / 1000}s")
                }
                lastStepFeedback = SecurityDialogs.USER_HANDLED_FEEDBACK
                continue
            }

            val uiTree = if (treeWithheld) null else screenContext.uiTree ?: a11y.getUiTree()
            val nodeCount = uiTree?.flatCount() ?: 0
            val textNodeCount = uiTree?.nodesWithText() ?: 0
            val screenSig = RuleEngine.buildScreenSignature(uiTree)
            appFrames.record(
                packageName = screenContext.appPackage,
                step = stepCount,
                jpeg = screenContext.screenshotJpeg,
                signature = screenSig,
                ignored = screenContext.appPackage in framelessPackages ||
                    SecurityDialogs.isSecurityDialog(screenContext.appPackage),
            )
            val inputSessionId = a11y.currentInputSessionId()
            capabilityFailureLedger.activateScope(
                capabilityScope(
                    packageName = screenContext.appPackage,
                    screenSignature = screenSig,
                    inputSessionId = inputSessionId,
                ),
            )

            // c. RULE CHECK: assess observation quality. Experience is advisory only and
            // never performs an action before the reasoning/safety/verification pipeline.
            val quality = RuleEngine.assessQuality(nodeCount, textNodeCount)
            Log.i(TAG, "UI quality=$quality, nodes=$nodeCount, textNodes=$textNodeCount")

            // c2. FAST PATH: JEV decides when configured; Reasoning takes the step on escalation.
            val fast = fastExecutor?.takeIf { it.activeForTask }
            if (fast != null) {
                overlayManager.updateProgress(task, "Deciding...", "", stepCount, maxSteps)
                val outcome = fast.step(stepCount, task, screenContext, uiTree) { pkg, actionName, label, level ->
                    val fastSafety = RuleEngine.assessSafety(pkg, actionName, label, level)
                    confirmIfNeeded(task, safetyMode, onSecurityDialog, fastSafety.level, fastSafety.reason, label)
                }
                when (outcome) {
                    FastStep.Acted -> {
                        fastSteps++
                        recordStepProgress(screenContext.appPackage)
                        continue
                    }
                    FastStep.Retry -> continue
                    FastStep.NotReady -> {
                        refundStep()
                        continue
                    }
                    FastStep.Declined -> {
                        cancelByUser()
                        return
                    }
                    is FastStep.DoneClaimed -> {
                        doneClaims++
                        val audit = auditCompletion(task, screenContext)
                        val decision = classifyTaskCompletionAudit(audit)
                        lastAuditDecision = decision.takeIf { verifyEnabled }
                        if (decision != CompletionAuditDecision.REJECT) {
                            finalAnswer = audit?.taskAnswer
                            acceptCompletion(task, completionSummary(
                                finalAnswer ?: outcome.summary.ifBlank { "Task complete" }, lastAuditDecision))
                            return
                        }
                        fast.doneRejected()
                        escalations++
                        val auditReason = audit?.category ?: "completion evidence unavailable"
                        if (++taskCompletionAuditRejects >= MAX_TASK_COMPLETION_AUDIT_REJECTS) {
                            failTask(task, "Task completion could not be verified after $taskCompletionAuditRejects attempts: $auditReason")
                            return
                        }
                        // JEV cannot read the audit's reason (a wrong count, an unsaved value): the
                        // Reasoning model takes this step to fix exactly what the audit found.
                        lastStepFeedback = "Task completion audit REJECTED: $auditReason. " +
                            "The screens seen so far do not yet prove every explicit user requirement."
                        replanHint = "A fast executor claimed the task was done but the audit rejected it. " +
                            "Its recent actions:\n${fast.recentHistory()}\nRe-check the original task against " +
                            "the current screenshot and fix only the missing or wrong result (redo a submit or " +
                            "save if its values were wrong)."
                    }
                    is FastStep.Escalate -> {
                        escalations++
                        replanHint = "A fast executor handed this step to you: ${outcome.reason}. " +
                            "Its recent actions:\n${fast.recentHistory()}\nChoose one action that makes progress."
                    }
                }
            }

            // d. CONTEXT: bounded advisory manual. OFF/SHADOW return no prompt content.
            val renderedManual = manualRuntime?.promptFor(screenContext.appPackage, screenContext.uiTreeJson)
            val learningObservationInstruction = manualRuntime?.learningObservationInstruction(
                screenContext.appPackage,
                uiTree,
                renderedManual,
            )

            // e. THINK: Reasoning model with timeout + exception handling
            overlayManager.updateProgress(task, "Analyzing...", "", stepCount, maxSteps)
            Log.i(TAG, "Calling reasoning model")

            val taskDescriptionWithContext = buildTaskDescription(
                task,
                renderedManual?.text,
                // LEARNING/SHADOW observations ride on the normal Reasoning call. This creates no
                // additional request and keeps manual maintenance independent of Fast Verify.
                learningObservationInstruction,
            )

            val result = withTimeoutOrNull(MODEL_REQUEST_TIMEOUT_MS) {
                modelRouter.reasoning.analyzeScreen(
                    screenshot = screenContext.screenshotJpeg,
                    uiTreeHint = screenContext.uiTreeJson,
                    taskDescription = taskDescriptionWithContext,
                    conversationHistory = conversationHistory,
                )
            }

            val response = handleModelResult(result, task)
            if (response == null) continue
            response.loop?.let { plan -> fastExecutor?.takeIf { it.activeForTask }?.startLoop(plan) }
            manualRuntime?.recordAttribution(renderedManual, response.usedManualHeadings)
            val transition = pendingManualTransition
                ?.takeIf { it.destinationPackage == screenContext.appPackage }
            val currentObservationAccepted = if (learningObservationInstruction != null) {
                manualRuntime?.recordObservedCurrentPage(
                    packageName = screenContext.appPackage,
                    tree = uiTree,
                    observation = response.manualObservation,
                    knownHeadingPaths = renderedManual?.headingPaths.orEmpty(),
                    precedingAction = transition?.action,
                    beforeTree = transition?.beforeTree,
                ) ?: false
            } else {
                false
            }
            // A model failure above leaves the transition pending for the next observation. Once
            // a response has been handled, any following action establishes a new destination.
            pendingManualTransition = null
            Log.i(TAG, "Model response: action=${response.action}, x=${response.x}, y=${response.y}")

            // Track conversation
            conversationHistory.add(Message("user", task))
            conversationHistory.add(Message("assistant", response.reasoning))

            // f. COORDINATE RESOLUTION
            val action = try {
                resolveAction(response.action, response.params, response.x, response.y, quality, uiTree, response.statusUpdate, screenContext.coordinates)
            } catch (e: IllegalArgumentException) {
                // e.g. a dialog or split-screen window cannot be coordinate-bound: fail this step, not the task.
                if (!recordCoordinateFailure(e, response.action)) throw e
                refundStep()
                continue
            }
            Log.i(TAG, "Resolved action: ${action.toLogSummary()}")

            // g. SAFETY CHECK: use shouldConfirm with safety mode
            val safety = RuleEngine.assessSafety(
                appPackage = screenContext.appPackage,
                actionName = response.action,
                actionContext = response.statusUpdate,
                modelSafetyLevel = response.safetyLevel,
            )
            if (!confirmIfNeeded(task, safetyMode, onSecurityDialog, safety.level, safety.reason,
                    "${response.action}: ${response.reasoning}")) {
                cancelByUser()
                return
            }

            // Save before-context for verification
            val beforeScreenshot = screenContext.screenshotJpeg
            val beforeUiTree = uiTree
            // h. ACT
            // FLAG_NOT_TOUCHABLE must be set before dispatchGesture so injected touch events
            // pass through the full-screen overlay to the target app. Without this, the overlay
            // consumes the gesture and the target app never receives it (same fix as AgentRunner).
            val actionName = response.statusUpdate.ifBlank { response.action }
            overlayManager.updateProgress(task, "Executing: $actionName", response.reasoning, stepCount, maxSteps)
            val actionResult = try {
                overlayManager.withGesturePassthrough {
                    // Check after safety confirmation and overlay settling, immediately before execution.
                    if (ModelCoordinateAction.isSpatial(response.action)) {
                        requireNotNull(screenContext.coordinates) { "missing_screenshot_coordinates" }
                            .dispatch({ DisplaySpaceReader.read(a11y) }) { actionEngine.execute(action, screenContext.coordinates) }
                    } else {
                        actionEngine.execute(action)
                    }
                }
            } catch (e: IllegalArgumentException) {
                // The screen moved on (or cannot be bound) between capture and dispatch: nothing was executed.
                if (!recordCoordinateFailure(e, response.action)) throw e
                refundStep()
                continue
            }
            Log.i(TAG, "Action result: success=${actionResult.success}, message=${actionResult.message}")

            if (!actionResult.success) {
                val actionFailure = "Previous action FAILED: ${actionResult.message}"
                lastStepFeedback = actionFailure
                val capabilityDecision = actionResult.failureCode?.let { failureCode ->
                    val key = capabilityFailureKey(
                        actionName = response.action,
                        failureCode = failureCode,
                    )
                    val decision = capabilityFailureLedger.recordFailure(
                        key = key,
                        constraint = capabilityConstraint(failureCode),
                    )
                    Log.w(
                        TAG,
                        "Capability failure: package=${screenContext.appPackage}, " +
                            "code=$failureCode, count=${capabilityFailureLedger.count(key)}, " +
                            "decision=$decision",
                    )
                    decision
                }
                val failAction = exceptionHandler.recordFailure()
                val shouldAbort = capabilityDecision == CapabilityFailureDecision.ABORT ||
                    failAction == FailureAction.ABORT
                val shouldReplan = capabilityDecision == CapabilityFailureDecision.REPLAN ||
                    failAction == FailureAction.REPLAN
                when {
                    shouldAbort -> {
                        val reason = if (capabilityDecision == CapabilityFailureDecision.ABORT) {
                            "The same text-input capability failed repeatedly; stopped to avoid an action loop. " +
                                actionResult.message
                        } else {
                            "Action failed: ${actionResult.message}"
                        }
                        updateTaskStatus("failed", reason)
                        val retry = overlayManager.awaitTaskFailed(
                            taskName = task,
                            reason = reason,
                            stepsCompleted = stepCount,
                            totalSteps = maxSteps,
                        )
                        if (retry) {
                            start(task, runOptions)
                            return
                        } else {
                            overlayManager.collapse()
                            overlayManager.resetBallState()
                            return
                        }
                    }
                    shouldReplan -> {
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = "FAILED",
                                reason = actionResult.message,
                            )
                        } else {
                            "$actionFailure\n${exceptionHandler.buildReplanHint()}"
                        }
                        Log.i(TAG, "Replan after action failure: $replanHint")
                        continue
                    }
                    else -> {
                        Log.i(TAG, "Retrying after action failure...")
                        continue
                    }
                }
            }

            if (actionResult.noOp) {
                // openApp of the app already in front changes nothing: no verify call, no step charged,
                // and the unchanged frame is observed again.
                refundStep()
                lastStepFeedback = "openApp did nothing: ${actionResult.message}. The current screen already " +
                    "belongs to that app; continue from it with taps, scrolls or BACK instead of opening it again."
                carryOverContext = screenContext.takeIf { it.coordinates != null }
                continue
            }

            // Check task complete. Completion is only accepted when action == "screenshot",
            // which means the model already saw the post-action state and confirmed the result.
            // Any other action + isTaskComplete=true is intercepted: the action executes normally
            // and the VERIFY step captures a fresh screenshot (stored as carryOverContext), so
            // the next step the model sees the result and can issue screenshot + isTaskComplete=true.
            if (response.isTaskComplete && response.action.lowercase() == "screenshot") {
                doneClaims++
                // An audit normally answers in about 5 s; one stalled call (47 s on the board) must not
                // hold a finished task for the gateway's full limit, so a slow attempt is retried.
                if (!verifyEnabled) Log.i(TAG, "Verification off: accepting the completion claim without an audit")
                // screenshot is a read-only completion action, so the current overlay-free OBSERVE
                // frame is the authoritative final state.
                val audit = if (verifyEnabled) requestCompletionAudit(task, screenContext) else null
                val auditDecision = classifyTaskCompletionAudit(audit)
                lastAuditDecision = auditDecision.takeIf { verifyEnabled }
                Log.i(
                    TAG,
                    "Task completion audit: decision=$auditDecision, valid=${audit?.isValid}, " +
                        "answer=${audit?.answer}, confidence=${audit?.confidence}, " +
                        "category=${audit?.category ?: "unavailable"}",
                )
                if (auditDecision != CompletionAuditDecision.REJECT) {
                    if (auditDecision == CompletionAuditDecision.UNAVAILABLE) {
                        Log.w(TAG, "Independent completion audit unavailable; completing as unverified")
                    }
                    val elapsed = System.currentTimeMillis() - taskStartTime
                    Log.i(TAG, "Task complete at step $stepCount in ${elapsed}ms")
                    finalAnswer = response.answer ?: audit?.taskAnswer
                    updateTaskStatus("completed")
                    // Freeze and enqueue an immutable update request before waiting for the
                    // result dialog. Enqueue is non-blocking and cannot change task outcome.
                    // Normally the current Reasoning observation has already been admitted. If it
                    // was unusable, an independently accepted audit may still admit that same
                    // visible page as terminal evidence; UNAVAILABLE never creates evidence.
                    if (!currentObservationAccepted) {
                        manualRuntime?.recordCompletedObservation(
                            packageName = screenContext.appPackage,
                            tree = uiTree,
                            observation = response.manualObservation,
                            knownHeadingPaths = renderedManual?.headingPaths.orEmpty(),
                            independentlyVerified = shouldRecordTerminalManualObservation(auditDecision),
                        )
                    }
                    manualRuntime?.completeTask()
                    overlayManager.awaitTaskComplete(
                        taskName = task,
                        summary = completionSummary(finalAnswer ?: response.statusUpdate, lastAuditDecision),
                        steps = stepCount,
                        durationMs = elapsed,
                    )
                    overlayManager.collapse()
                    overlayManager.resetBallState()
                    return
                }

                taskCompletionAuditRejects++
                val auditReason = audit?.category ?: "completion evidence unavailable"
                lastStepFeedback = "Task completion audit REJECTED: $auditReason. " +
                    "The current screen does not yet prove every explicit user requirement."
                replanHint = "Re-check the original task against the current screenshot. Fix only the " +
                    "missing or misplaced result; do not declare completion again without visible evidence."
                carryOverContext = screenContext
                if (taskCompletionAuditRejects >= MAX_TASK_COMPLETION_AUDIT_REJECTS) {
                    val reason = "Task completion could not be verified after " +
                        "$taskCompletionAuditRejects attempts: $auditReason"
                    updateTaskStatus("failed", reason)
                    val retry = overlayManager.awaitTaskFailed(
                        taskName = task,
                        reason = reason,
                        stepsCompleted = stepCount,
                        totalSteps = maxSteps,
                    )
                    if (retry) {
                        start(task, runOptions)
                    } else {
                        overlayManager.collapse()
                        overlayManager.resetBallState()
                    }
                    return
                }
                continue
            } else if (response.isTaskComplete) {
                Log.i(TAG, "isTaskComplete with action '${response.action}' — deferring: model must confirm via screenshot")
            }

            // i. VERIFY: action verification is compact and independent of manual learning.

            if (!verifyEnabled) {
                // Ablation baseline: no post-action check. Let the screen settle, then observe afresh.
                delay(randomDelay())
                exceptionHandler.recordSuccess()
                replanHint = null
                lastStepFeedback = null
                pendingManualTransition = null
                actionHistory.add(ActionRecord(response.action, response.statusUpdate, screenSig, VerifyOutcome.UNKNOWN,
                    actionTarget(response.x, response.y, response.params)))
            }
            verifyLoop@ while (verifyEnabled) {
                delay(randomDelay())
                val afterContext = captureFrame()?.let { withCoordinateMapping(it) }
                    ?: throw Exception("Failed to capture verification screenshot")
                val afterTree = if (treeWithheld) null else afterContext.uiTree ?: a11y.getUiTree()
                val afterScreenSig = RuleEngine.buildScreenSignature(afterTree)
                pendingManualTransition = if (shouldAttachManualTransition(
                        beforePackage = screenContext.appPackage,
                        afterPackage = afterContext.appPackage,
                        beforeSignature = screenSig,
                        afterSignature = afterScreenSig,
                    )) {
                    PendingManualTransition(
                        destinationPackage = afterContext.appPackage,
                        action = action,
                        beforeTree = beforeUiTree,
                    )
                } else {
                    null
                }

                // All attempts share one budget; retries must not multiply the waiting limit.
                val actionDesc = "${response.action}: ${response.reasoning}"
                val verifyResult = withTimeoutOrNull(MODEL_REQUEST_TIMEOUT_MS) {
                    (1..3).firstNotNullOfOrNull { attempt ->
                        val res = modelRouter.fast.verifyAction(
                            beforeScreenshot = beforeScreenshot,
                            afterScreenshot = afterContext.screenshotJpeg,
                            actionDescription = actionDesc,
                            expectedOutcome = response.expectedOutcome,
                            manualObservationInstruction = null,
                        )
                        if (res.isSuccess) res.getOrThrow()
                        else {
                            Log.w(TAG, "Verify attempt $attempt/3 failed: ${res.exceptionOrNull()?.message}")
                            null
                        }
                    }
                } ?: run {
                    Log.w(TAG, "Verify unavailable after timeout or 3 failures, treating as UNKNOWN")
                    VerifyResult(VerifyOutcome.UNKNOWN, "Verification unavailable after timeout or repeated parse/API failures")
                }

                Log.i(TAG, "Verify: ${verifyResult.outcome} - ${verifyResult.reason}")

                // Record action history
                actionHistory.add(ActionRecord(
                    action = response.action,
                    statusUpdate = response.statusUpdate,
                    screenSignature = screenSig,
                    outcome = verifyResult.outcome,
                    target = actionTarget(response.x, response.y, response.params),
                ))

                when (verifyResult.outcome) {
                    VerifyOutcome.SUCCESS -> {
                        exceptionHandler.recordSuccess()
                        replanHint = null
                        lastStepFeedback = "Previous action SUCCEEDED: ${verifyResult.reason}"
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.UNEXPECTED -> {
                        exceptionHandler.recordSuccess()
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = verifyResult.outcome.name,
                                reason = verifyResult.reason,
                            )
                        } else {
                            null
                        }
                        lastStepFeedback = "Previous action had an UNEXPECTED but task-compatible result: ${verifyResult.reason}"
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.UNKNOWN -> {
                        replanHint = null
                        lastStepFeedback = "Previous action result UNKNOWN: ${verifyResult.reason}. " +
                            "Re-observe the current screenshot and do not assume the previous action succeeded."
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.FAILED -> {
                        lastStepFeedback = null
                        val sameActionFailCount = consecutiveIneffectiveAttempts(actionHistory.map {
                            Attempt(it.action, it.screenSignature, it.target, it.outcome)
                        })
                        if (sameActionFailCount >= MAX_INEFFECTIVE_ATTEMPTS) {
                            updateTaskStatus("failed", "Action repeatedly ineffective: ${verifyResult.reason}")
                            val retry = overlayManager.awaitTaskFailed(
                                taskName = task,
                                reason = "Action repeatedly ineffective: ${verifyResult.reason}",
                                stepsCompleted = stepCount,
                                totalSteps = maxSteps,
                            )
                            if (retry) { start(task, runOptions); return } else { overlayManager.collapse(); overlayManager.resetBallState(); return }
                        }
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = "FAILED",
                                reason = verifyResult.reason,
                            )
                        } else {
                            "Previous action FAILED (attempt $sameActionFailCount/$MAX_INEFFECTIVE_ATTEMPTS): " +
                                "${verifyResult.reason}. The screen did not change. Re-observe and try a different approach."
                        }
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.DRIFTED -> {
                        lastStepFeedback = null
                        val sameActionFailCount = consecutiveIneffectiveAttempts(actionHistory.map {
                            Attempt(it.action, it.screenSignature, it.target, it.outcome)
                        })
                        if (sameActionFailCount >= MAX_INEFFECTIVE_ATTEMPTS) {
                            updateTaskStatus("failed", "Action repeatedly drifted: ${verifyResult.reason}")
                            val retry = overlayManager.awaitTaskFailed(
                                taskName = task,
                                reason = "Action repeatedly drifted: ${verifyResult.reason}",
                                stepsCompleted = stepCount,
                                totalSteps = maxSteps,
                            )
                            if (retry) { start(task, runOptions); return } else { overlayManager.collapse(); overlayManager.resetBallState(); return }
                        }
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = "DRIFTED",
                                reason = verifyResult.reason,
                            )
                        } else {
                            "Previous action DRIFTED (attempt $sameActionFailCount/$MAX_INEFFECTIVE_ATTEMPTS): " +
                                "${verifyResult.reason}. The screen changed to an unexpected state. " +
                                "Assess current screen and decide how to recover."
                        }
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                }
            }

            fastExecutor?.recordReasoningStep(
                response.statusUpdate.ifBlank { response.action },
                when (actionHistory.lastOrNull()?.outcome) {
                    VerifyOutcome.SUCCESS, VerifyOutcome.UNEXPECTED -> true
                    VerifyOutcome.FAILED, VerifyOutcome.DRIFTED -> false
                    else -> null
                },
            )

            // j. UPDATE: write step to task.json
            currentTask = currentTask?.copy(
                currentStep = stepCount,
                totalSteps = maxSteps,
                lastUpdated = currentTimestamp(),
                targetPackage = screenContext.appPackage,
            )
            writeTaskState(currentTask)

            // k. AUTOCOMPACT
            if (autocompact.shouldCompact(stepCount) && stateManager != null) {
                Log.i(TAG, "Running autocompact at step $stepCount")
                try {
                    autocompact.compact(
                        modelRouter.reasoning,
                        conversationHistory,
                        stateManager,
                        currentTask?.id ?: "unknown",
                    )
                    autocompact.pruneScreenshotCache(screenshotCache)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Autocompact failed: ${e.message}")
                }
            }

            // l. UPDATE TRACKING
            prevNodeCount = nodeCount
            prevApp = carryOverContext?.appPackage ?: screenContext.appPackage
        }

        // Loop ended
        if (emergencyStop) {
            Log.i(TAG, "Emergency stop triggered!")
            updateTaskStatus("paused")
            overlayManager.collapse()
            overlayManager.resetBallState()
            return
        }

        if (stepCount >= maxSteps) {
            Log.w(TAG, "Step limit reached ($maxSteps)")
            updateTaskStatus("failed", "Step limit reached")
            val retry = overlayManager.awaitTaskFailed(
                taskName = task,
                reason = "Step limit reached ($maxSteps steps)",
                stepsCompleted = stepCount,
                totalSteps = maxSteps,
            )
            if (retry) {
                start(task, runOptions)
            } else {
                overlayManager.collapse()
                overlayManager.resetBallState()
            }
        }
    }

    private suspend fun handleModelResult(result: Result<bot.coati.model.ModelResponse>?, task: String): bot.coati.model.ModelResponse? {
        if (result == null) {
            Log.w(TAG, "Model analysis timed out")
            val failAction = exceptionHandler.recordFailure()
            when (failAction) {
                FailureAction.ABORT -> {
                    updateTaskStatus("failed", "Model timeout")
                    throw Exception("Model analysis timed out after $MAX_CONSECUTIVE_FAILURES consecutive failures")
                }
                FailureAction.REPLAN -> {
                    replanHint = exceptionHandler.buildReplanHint()
                    Log.i(TAG, "Replan hint set: $replanHint")
                    return null
                }
                FailureAction.RETRY -> {
                    Log.i(TAG, "Retrying after timeout...")
                    return null
                }
            }
        }

        return result.getOrNull() ?: run {
            val e = result.exceptionOrNull()
            val failAction = exceptionHandler.recordFailure()
            when (failAction) {
                FailureAction.ABORT -> {
                    updateTaskStatus("failed", e?.message)
                    throw Exception("Model analysis failed: ${e?.message}")
                }
                FailureAction.REPLAN -> {
                    replanHint = exceptionHandler.buildReplanHint()
                    Log.i(TAG, "Replan hint set after model failure: $replanHint")
                    null
                }
                FailureAction.RETRY -> {
                    Log.i(TAG, "Retrying after model failure...")
                    null
                }
            }
        }
    }

    private fun buildTaskDescription(
        task: String,
        manualSection: String?,
        learningObservationInstruction: String?,
    ): String {
        val sb = StringBuilder(task)
        if (lastStepFeedback != null) {
            sb.append("\n\n[LAST STEP] $lastStepFeedback")
        }
        if (replanHint != null) {
            sb.append("\n\n[REPLAN] $replanHint")
        }
        val capabilityConstraints = capabilityFailureLedger.persistentConstraints()
        if (capabilityConstraints.isNotEmpty()) {
            sb.append("\n\n[CAPABILITY CONSTRAINTS]")
            capabilityConstraints.forEach { constraint ->
                sb.append("\n- $constraint")
            }
        }
        if (manualSection != null) {
            sb.append("\n\n$manualSection")
        }
        if (learningObservationInstruction != null) {
            sb.append("\n\n$learningObservationInstruction")
        }
        // Routed runs only: the classic flow never sees the loop field.
        if (fastExecutor?.activeForTask == true && fastExecutor.loopActive.not()) {
            sb.append("\n\n$FAST_LOOP_INSTRUCTION")
        }
        return sb.toString()
    }

    private suspend fun writeTaskState(taskState: TaskState?) {
        if (taskState == null || stateManager == null) return
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                stateManager.writeJson("task", taskState.toJson())
            }
        } catch (_: CancellationException) {
            // Block completed successfully; CancellationException is normal propagation after cancellation.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write task state: ${e.message}")
        }
    }

    private suspend fun updateTaskStatus(status: String, error: String? = null) {
        if (status in TERMINAL_STATUSES) {
            ModelUsageMeter.finishTask()?.let { usage -> reportTaskResult(status, error, usage) }
        }
        val task = currentTask ?: return
        val errors = if (error != null) task.errors + error else task.errors
        currentTask = task.copy(
            status = status,
            lastUpdated = currentTimestamp(),
            errors = errors,
        )
        writeTaskState(currentTask)
    }

    /** Hands the task's result to [TaskHooks]; the user-facing app records nothing. */
    private suspend fun reportTaskResult(status: String, error: String?, usage: org.json.JSONObject) {
        val result = TaskResult(
            runId = runOptions.runId,
            goal = currentTask?.goal.orEmpty(),
            status = status,
            reason = error ?: currentTask?.errors?.lastOrNull(),
            route = if (routedTask) "routed" else "per_step",
            steps = stepCount,
            maxSteps = maxSteps,
            durationMs = System.currentTimeMillis() - taskStartTime,
            doneClaims = doneClaims,
            auditRejects = taskCompletionAuditRejects,
            finalAudit = lastAuditDecision,
            fastSteps = fastSteps,
            escalations = escalations,
            routing = routingLabel,
            verify = verifyEnabled,
            treeWithheld = treeWithheld,
            answer = finalAnswer,
            refundedSteps = refundedSteps,
            usage = usage,
        )
        try {
            withContext(NonCancellable + Dispatchers.IO) { TaskHooks.installed.onTaskFinished(result) }
        } catch (e: Exception) {
            Log.w(TAG, "Task result hook failed: ${e.message}")
        }
    }

    private fun currentTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    private fun resolveAction(
        actionType: String,
        params: Map<String, Any>,
        normalizedX: Int?,
        normalizedY: Int?,
        quality: UiTreeQuality,
        uiTree: UiTreeNode?,
        statusUpdate: String,
        coordinates: ScreenshotCoordinates?,
    ): AgentAction {
        // Preserve existing matching policy. getBoundsInScreen already uses screen pixels.
        val treeTarget = if (quality == UiTreeQuality.HIGH && uiTree != null && normalizedX != null && normalizedY != null &&
            actionType.lowercase() in setOf("tap", "longpress", "long_press")) {
            findBestMatchNode(uiTree, statusUpdate)?.bounds?.let { bounds ->
                require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= (coordinates?.rawWidth ?: 0) &&
                    bounds.bottom <= (coordinates?.rawHeight ?: 0) && bounds.width() > 0 && bounds.height() > 0) { "invalid_ui_bounds" }
                bounds.centerX() to bounds.centerY()
            }
        } else null
        return ModelCoordinateAction.resolve(actionType, params, normalizedX, normalizedY, coordinates, treeTarget)
    }

    private fun findBestMatchNode(tree: UiTreeNode, target: String): UiTreeNode? {
        if (target.isBlank()) return null
        return findNodeRecursive(tree, target.lowercase())
    }

    private fun findNodeRecursive(node: UiTreeNode, target: String): UiTreeNode? {
        val nodeText = (node.text ?: node.contentDescription)?.lowercase()
        if (nodeText != null && node.isClickable && nodeText.contains(target)) {
            return node
        }
        // Also check partial match for short targets
        if (nodeText != null && node.isClickable && target.length >= 2 && nodeText.contains(target.take(10))) {
            return node
        }
        for (child in node.children) {
            val found = findNodeRecursive(child, target)
            if (found != null) return found
        }
        return null
    }

    /**
     * Captures the screen, retrying briefly: the platform occasionally fails a single capture
     * (e.g. right after window transitions) and one miss should not end the task.
     */
    private suspend fun captureScreen(): ScreenContext? {
        repeat(CAPTURE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "Screen capture failed, retrying (${attempt + 1}/$CAPTURE_ATTEMPTS)")
                delay(CAPTURE_RETRY_DELAY_MS)
            }
            val context = captureFrame()
            if (context != null) return withCoordinateMapping(context)
        }
        return null
    }

    /** One overlay-free capture. */
    private suspend fun captureFrame(): ScreenContext? {
        overlayManager.hideForScreenshot()
        delay(50)
        return try {
            ScreenContext.capture(a11y)
        } finally {
            overlayManager.showAfterScreenshot()
        }
    }

    /**
     * Recaptures a frame whose coordinate mapping is missing. The display or window reading changes
     * while a toast, keyboard or transition is settling (typically on the screenshot right after a
     * save or BACK), and such a frame makes every tap fail closed: one AndroidWorld episode paid 17
     * steps for waits and openApp calls of the app already in front. The settled screen almost
     * always binds within a few hundred milliseconds.
     */
    private suspend fun withCoordinateMapping(first: ScreenContext): ScreenContext {
        var context = first
        var attempt = 0
        while (context.coordinates == null && attempt < COORDINATE_RECAPTURES) {
            attempt++
            delay(COORDINATE_RECAPTURE_DELAY_MS)
            Log.i(TAG, "Frame has no coordinate mapping; recapturing ($attempt/$COORDINATE_RECAPTURES)")
            context = captureFrame() ?: return context
        }
        return context
    }

    /**
     * Waits while a system authorization dialog is in front, for the user to answer it by hand.
     * Returns false if it is still showing after [SecurityDialogs.USER_RESPONSE_TIMEOUT_MS].
     */
    private suspend fun awaitUserAnswersSecurityDialog(task: String): Boolean {
        Log.i(TAG, "System dialog in front: waiting for the user to answer it")
        // Hidden by the system while the dialog shows; visible again once it is answered.
        overlayManager.updateProgress(task, "Waiting for you to answer the system dialog", "", stepCount, maxSteps)
        val answered = withTimeoutOrNull(SecurityDialogs.USER_RESPONSE_TIMEOUT_MS) {
            while (SecurityDialogs.isSecurityDialog(a11y.rootInActiveWindow?.packageName?.toString())) {
                delay(SECURITY_DIALOG_POLL_MS)
            }
        } != null
        Log.i(TAG, "System dialog answered by the user: $answered")
        return answered
    }

    /**
     * Turns a coordinate-binding rejection into step feedback for the next Reasoning turn. The
     * fail-closed guards stay intact: no gesture is sent, but the task continues. Returns false for
     * any other argument error, which keeps its previous (task-failing) behavior.
     */
    /**
     * The step budget counts actions sent to the screen. A loop pass that sent nothing because the
     * screen was not ready (no coordinate mapping, or it changed before dispatch) is given back, up to
     * [MAX_REFUNDED_STEPS] per task so a screen that never settles still ends the task.
     */
    private fun refundStep() {
        if (refundedSteps >= MAX_REFUNDED_STEPS) return
        refundedSteps++
        stepCount--
        Log.i(TAG, "Step not counted: nothing was executed ($refundedSteps/$MAX_REFUNDED_STEPS)")
    }

    private fun recordCoordinateFailure(e: IllegalArgumentException, actionName: String): Boolean {
        val feedback = coordinateFailureFeedback(e.message) ?: return false
        Log.w(TAG, "Coordinate guard rejected '$actionName': ${e.message}")
        lastStepFeedback = "Previous action NOT EXECUTED: $feedback"
        if (exceptionHandler.recordFailure() == FailureAction.ABORT) {
            throw IllegalStateException("Actions repeatedly could not be bound to the screen: ${e.message}")
        }
        return true
    }

    /**
     * Asks the user to confirm an action at [level] when the safety mode requires it. Over a system
     * dialog Android hides every overlay, so no confirmation could be seen; reaching an action there
     * means the user chose TRUST and pre-authorized the answer. Returns false when declined.
     */
    private suspend fun confirmIfNeeded(
        task: String,
        safetyMode: SafetyMode,
        onSecurityDialog: Boolean,
        level: String,
        reason: String?,
        detail: String,
    ): Boolean {
        if (onSecurityDialog) {
            Log.i(TAG, "Answering system dialog without confirmation ($level/$safetyMode)")
            return true
        }
        if (!RuleEngine.shouldConfirm(level, safetyMode)) return true
        Log.i(TAG, "Safety confirmation needed ($level/$safetyMode): $reason")
        val confirmed = overlayManager.requestConfirmation(
            title = when {
                level !in setOf("low", "medium", "high") -> "Unknown Risk Confirmation"
                safetyMode == SafetyMode.STANDARD -> "High-Risk Action"
                else -> "Risk Confirmation"
            },
            detail = detail,
        )
        if (!confirmed) return false
        overlayManager.enterFrameExecuting(task)
        // Dialog dismissal + overlay layout change are async WMS operations.
        // Without this delay the subsequent gesture dispatches before FLAG_NOT_TOUCHABLE
        // takes effect and gets consumed by the overlay instead of the target app.
        delay(300)
        return true
    }

    private suspend fun cancelByUser() {
        Log.i(TAG, "User cancelled action")
        updateTaskStatus("cancelled")
        overlayManager.collapse()
        overlayManager.resetBallState()
    }

    /** Independent Fast-model check that the screens seen so far prove the task is done. */
    private suspend fun auditCompletion(task: String, context: ScreenContext): QuickCheckResult? {
        if (!verifyEnabled) return null
        overlayManager.updateProgress(task, "Checking result...", "", stepCount, maxSteps)
        val audit = requestCompletionAudit(task, context)
        Log.i(TAG, "Task completion audit: decision=${classifyTaskCompletionAudit(audit)}, category=${audit?.category}")
        return audit
    }

    /**
     * Audits the current screen together with the last screen of every other app the task used:
     * a result saved in one app cannot stay on screen while the next app is in use, and auditing the
     * current screen alone rejected cross-app tasks in turn for whichever app was not in front.
     */
    private suspend fun requestCompletionAudit(task: String, context: ScreenContext): QuickCheckResult? {
        val earlier = appFrames.evidenceFor(context.appPackage, stepCount)
        val question = buildTaskCompletionAuditQuestion(
            task,
            earlier.map { it.packageName to it.step },
            fastExecutor?.loopEvidence(),
        )
        if (earlier.isNotEmpty()) {
            Log.i(TAG, "Completion audit includes earlier screens ${earlier.map { "${it.packageName}@${it.step}" }}")
        }
        // An audit normally answers in about 5 s; one stalled call (47 s on the board) must not hold a
        // finished task for the gateway's full limit, so a slow attempt is retried.
        val images = earlier.size + 1
        val (attempts, timeoutMs) = completionAuditBudget(images)
        for (attempt in 1..attempts) {
            val started = System.currentTimeMillis()
            Log.i(TAG, "Completion audit starting: attempt=$attempt images=$images " +
                "bytes=${earlier.sumOf { it.jpeg.size } + context.screenshotJpeg.size} budgetMs=$timeoutMs")
            val result = withTimeoutOrNull(timeoutMs) {
                withContext(ModelCallPurpose.COMPLETION_AUDIT) {
                    // Page learning was already handled by the current Reasoning response; the audit
                    // stays compact and single-purpose.
                    if (earlier.isEmpty()) {
                        modelRouter.fast.quickCheck(context.screenshotJpeg, question, null)
                    } else {
                        modelRouter.fast.quickCheckScreens(earlier.map { it.jpeg } + context.screenshotJpeg, question)
                    }
                }
            }
            Log.i(TAG, "Completion audit finished: attempt=$attempt elapsedMs=${System.currentTimeMillis() - started} " +
                "returned=${result != null}")
            if (result == null) {
                Log.w(TAG, "Task completion audit attempt $attempt timed out")
                continue
            }
            return result.getOrNull()
        }
        return null
    }

    private suspend fun acceptCompletion(task: String, summary: String) {
        val elapsed = System.currentTimeMillis() - taskStartTime
        Log.i(TAG, "Task complete at step $stepCount in ${elapsed}ms")
        // Fast-path page observations for the app manual may still be in flight (bounded wait).
        fastExecutor?.finishObservations()
        updateTaskStatus("completed")
        manualRuntime?.completeTask()
        overlayManager.awaitTaskComplete(taskName = task, summary = summary, steps = stepCount, durationMs = elapsed)
        overlayManager.collapse()
        overlayManager.resetBallState()
    }

    private suspend fun failTask(task: String, reason: String) {
        updateTaskStatus("failed", reason)
        val retry = overlayManager.awaitTaskFailed(taskName = task, reason = reason, stepsCompleted = stepCount, totalSteps = maxSteps)
        if (retry) {
            start(task, runOptions)
        } else {
            overlayManager.collapse()
            overlayManager.resetBallState()
        }
    }

    private suspend fun recordStepProgress(appPackage: String) {
        currentTask = currentTask?.copy(
            currentStep = stepCount,
            totalSteps = maxSteps,
            lastUpdated = currentTimestamp(),
            targetPackage = appPackage,
        )
        writeTaskState(currentTask)
    }

    private fun isTextInputAction(action: String): Boolean {
        return action.lowercase() in setOf(
            "inputtext",
            "input_text",
            "focusandinputtext",
            "focus_and_input_text",
        )
    }

    private fun buildTextInputReplanHint(outcome: String, reason: String): String {
        return "Previous inputText $outcome: $reason. Re-observe the focused editor metadata. " +
            "Do not recover by selecting all, guessing offsets, or replacing multiline/rich text. " +
            "Use mode=type only after focusing the intended caret. For separate visible title/body fields, " +
            "focus and fill each once. If caret input or append was blocked, do not repeat the same input " +
            "strategy on the unchanged editor; choose a non-destructive alternative or stop."
    }

    private fun capabilityConstraint(failureCode: ActionFailureCode): String {
        return when (failureCode) {
            ActionFailureCode.TEXT_REPLACE_UNSAFE ->
                "The current editor is not a confirmed simple single-line field. Do not use whole-field " +
                    "replacement; preserve its structure and use mode=type only at a verified caret."
            ActionFailureCode.TEXT_DIRECT_INPUT_UNAVAILABLE,
            ActionFailureCode.TEXT_LEGACY_UNSUPPORTED,
            -> "This app does not currently expose the safe direct-input backend. Do not retry " +
                "type or append in the same editor. Do not fall back to clipboard or rich-text replacement."
            ActionFailureCode.TEXT_CARET_NOT_CONFIRMED ->
                "This editor does not expose a stable collapsed caret. Do not retry typing or guess offsets; " +
                    "refocus once using visible evidence, then stop if the caret remains unconfirmed."
            ActionFailureCode.TEXT_NO_FOCUS ->
                "No editable field is focused. Focus one visible target field before any further inputText."
            ActionFailureCode.TEXT_NOT_EDITABLE ->
                "The focused accessibility node is not editable. Do not retry text replacement until " +
                    "a different editable field is focused."
            ActionFailureCode.TEXT_EDIT_FAILED ->
                "The current editor rejected the requested text-edit backend. Do not repeat the same " +
                    "input strategy on the unchanged page."
        }
    }

    private fun randomDelay(): Long = (300L..1500L).random()

    companion object {
        private const val TAG = "CoatiLoop"
        private val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "paused")
        private const val SECURITY_DIALOG_POLL_MS = 1_000L
        private const val CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_RETRY_DELAY_MS = 1_000L
        private const val COORDINATE_RECAPTURES = 3
        private const val COORDINATE_RECAPTURE_DELAY_MS = 250L
        const val MAX_STEPS = 200
        private const val MAX_REFUNDED_STEPS = 20
        private const val MAX_CONSECUTIVE_FAILURES = 3
        /**
         * Consecutive failures of one action on one target and screen before the task is failed. The
         * former gate (2 failures of the same action type anywhere on a screen, counted over the whole
         * task, waits included) ended 14 of 32 failed AndroidWorld episodes, most with budget left:
         * two taps on different buttons, or two waits for an app to render, were "the same action".
         */
        private const val MAX_INEFFECTIVE_ATTEMPTS = 3

        /** Actions whose unchanged screen is expected: they neither count nor break a failure run. */
        private val NEUTRAL_ACTIONS = setOf("wait", "screenshot")
        private val INEFFECTIVE_OUTCOMES = setOf(VerifyOutcome.FAILED, VerifyOutcome.DRIFTED)

        /** Coordinates within one cell (4% of the 0..1000 model space) count as the same target. */
        private const val TARGET_CELL = 40
        private const val MAX_TASK_COMPLETION_AUDIT_REJECTS = 3
        private const val AUDIT_ATTEMPTS = 2

        /**
         * Handing a repetition to the fast executor. Per-step routing alone failed loops of simple
         * steps on Car Market (rows skipped, one of six items handled, scrolling instead of refreshing).
         */
        internal const val FAST_LOOP_INSTRUCTION =
            "[FAST LOOP] For a repetitive sub-task of many similar simple actions in one list or page " +
                "(the same kind of button on many items, refreshing until something changes), hand the " +
                "repetition to the fast executor: add \"loop\": {\"each\": \"<the action for one item>\", " +
                "\"count\": <how many items, or null>, \"until\": \"<a visible stop condition, or null>\", " +
                "\"scope\": \"<which items, when the task says every or all; else null>\"} and set action to " +
                "\"wait\". It acts on each item, keeps count, and reports back with [LOOP DONE] and the items it " +
                "handled. Never use it for typing text or switching apps, or for fewer than three items."
        private const val AUDIT_ATTEMPT_TIMEOUT_MS = 20_000L
        private const val MULTI_IMAGE_AUDIT_TIMEOUT_MS = 60_000L

        /**
         * Attempts and per-attempt limit of a completion audit. A multi-image (cross-app) audit gets one
         * 60 s attempt: on a ColorOS phone both 20 s attempts of a 5-screenshot audit timed out in 2 of 2
         * runs, each restart discarding a request that was nearly done (found by Yudou).
         */
        internal fun completionAuditBudget(images: Int): Pair<Int, Long> =
            if (images > 1) 1 to MULTI_IMAGE_AUDIT_TIMEOUT_MS else AUDIT_ATTEMPTS to AUDIT_ATTEMPT_TIMEOUT_MS

        internal const val UNVERIFIED_COMPLETION_NOTE =
            "Done, but the result could not be independently verified. Please check it before relying on it."

        /**
         * The completion summary shown to the user. When the audit was unavailable the task still ends
         * as completed (the result may well be saved, and retrying could duplicate it) but never
         * reads as verified: it used to show a plain "Task complete" (2 of 116 formal AndroidWorld
         * episodes and both of Yudou's National Museum runs).
         */
        internal fun completionSummary(summary: String, decision: CompletionAuditDecision?): String =
            if (decision == CompletionAuditDecision.UNAVAILABLE) "$UNVERIFIED_COMPLETION_NOTE\n$summary" else summary
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** Model-facing explanation for a rejected spatial action, or null when [message] is not a binding guard. */
        internal fun coordinateFailureFeedback(message: String?): String? = when (message) {
            "missing_screenshot_coordinates" ->
                "the screenshot was taken while the screen was still changing (a toast, keyboard or transition), " +
                    "so its coordinates could not be mapped. Nothing happened on the device and this step was not " +
                    "counted. A fresh screenshot follows: choose the same kind of action again on it. Do not " +
                    "wait, reopen the app or go back because of this."
            "stale_screenshot_coordinates" ->
                "the screen changed between the screenshot and the action. Nothing happened on the device. " +
                    "Re-observe the current screen and choose again on it."
            else -> null
        }

        /**
         * [earlierScreens] are (package, step) of the screenshots sent before the current one: the
         * last screen of each other app the task used and the current app's earlier screens.
         */
        internal fun buildTaskCompletionAuditQuestion(
            task: String,
            earlierScreens: List<Pair<String, Int>> = emptyList(),
            loopRecord: String? = null,
        ): String {
            val question = if (earlierScreens.isNotEmpty()) {
                buildCrossAppCompletionAuditQuestion(task, earlierScreens)
            } else {
                buildSingleScreenCompletionAuditQuestion(task)
            }
            return if (loopRecord == null) question else question + "\n\n" + loopEvidence(loopRecord)
        }

        /**
         * A loop over list items ends on a screen that shows only the last rows: the record of what
         * the host saw change is the evidence for the rest, and an "every item" loop that never reached
         * the end of the list leaves rows below unchecked (one of six Sold listings hidden, accepted).
         */
        private fun loopEvidence(record: String): String = """
            |The fast executor repeated an action over list items during this task. Host record (not the agent's claims): $record
            |Use it as evidence for items no longer on screen. If the task covers every matching item and the record says the loop did not reach the end of the list, answer false unless a screenshot shows the remaining items handled.
        """.trimMargin()

        private fun buildSingleScreenCompletionAuditQuestion(task: String): String {
            return """
                Independently audit whether the CURRENT screenshot visibly satisfies every explicit requirement of this original Android task:
                $task

                Return answer=true only when the result itself is visibly complete and correctly structured. Do not trust the agent's status, history, or claim of completion.
                $EXACT_VALUES_RULE
                If the task specifies a title and body, require a genuinely distinct rendered title region/style and the body in its body region. Text merely appearing as the first line of the body does not count as a title. A still-visible empty title placeholder means failure.
                If the task specifies multiple lines, require the exact visible line structure; collapsed, missing, duplicated, or misplaced text means failure.
                Use category to name the missing or incorrect requirement briefly.
                If the task asks for information (a question, a count, a date, a value, a list), also return "taskAnswer": the exact answer as this screenshot shows it, as short as possible, or null when the screenshot does not show it. For other tasks return "taskAnswer": null.
            """.trimIndent()
        }

        private fun buildCrossAppCompletionAuditQuestion(task: String, earlierScreens: List<Pair<String, Int>>): String {
            val listing = earlierScreens.mapIndexed { i, (pkg, step) -> "${i + 1}. a screen of app $pkg (step $step)" }
                .joinToString("\n")
            val current = earlierScreens.size + 1
            return """
                |Independently audit whether these screenshots together visibly satisfy every explicit requirement of this original Android task:
                |$task
                |
                |Only one screen is visible at a time, so a finished result is often no longer on the current screen. The $current screenshots, taken during the task, are in order:
                |$listing
                |$current. the CURRENT screen
                |A requirement is satisfied when any screenshot visibly shows it done. Do not fail it only because a later screen (another app, a list, a calendar view) does not show it: not visible is not missing. A later screenshot of the same app overrides an earlier one only where they contradict. A value entered in a form counts when a later screenshot shows that item saved (for example in a list); a form that was left without saving does not count.
                |Return answer=true only when every result itself is visibly complete and correctly structured. Do not trust the agent's status, history, or claim of completion.
                |$EXACT_VALUES_RULE
                |If the task specifies a title and body, require a genuinely distinct rendered title region/style and the body in its body region. Text merely appearing as the first line of the body does not count as a title. A still-visible empty title placeholder means failure.
                |If the task specifies multiple lines, require the exact visible line structure; collapsed, missing, duplicated, or misplaced text means failure.
                |Use category to name the missing or incorrect requirement briefly.
                |If the task asks for information (a question, a count, a date, a value, a list), also return "taskAnswer": the exact answer as the screenshots show it, as short as possible, or null when they do not show it. For other tasks return "taskAnswer": null.
            """.trimMargin()
        }

        /**
         * Apps silently replace typed values: a calendar location autocomplete saved a nearby place whose
         * postal code differed from the address the task gave, and the audit accepted it.
         */
        private const val EXACT_VALUES_RULE =
            "Compare every value the task specifies (names, addresses with house numbers and postal codes, numbers, " +
                "dates, times, amounts) character by character with the value the result shows; a different digit or " +
                "a suggestion that replaced the requested text means failure."

        internal fun classifyTaskCompletionAudit(result: QuickCheckResult?): CompletionAuditDecision {
            if (result == null || !result.isValid || result.confidence < 0.7f) {
                return CompletionAuditDecision.UNAVAILABLE
            }
            return if (result.answer) {
                CompletionAuditDecision.ACCEPT
            } else {
                CompletionAuditDecision.REJECT
            }
        }

        internal fun shouldRecordTerminalManualObservation(
            decision: CompletionAuditDecision,
        ): Boolean = decision == CompletionAuditDecision.ACCEPT

        internal fun shouldAttachManualTransition(
            beforePackage: String,
            afterPackage: String,
            beforeSignature: String,
            afterSignature: String,
        ): Boolean = beforePackage != afterPackage || beforeSignature != afterSignature

        internal fun capabilityScope(
            packageName: String,
            screenSignature: String,
            inputSessionId: Long?,
        ): String = "$packageName:$screenSignature:session=${inputSessionId ?: "none"}"

        internal fun capabilityFailureKey(
            actionName: String,
            failureCode: ActionFailureCode,
        ): String {
            val capability = when (failureCode) {
                ActionFailureCode.TEXT_DIRECT_INPUT_UNAVAILABLE,
                ActionFailureCode.TEXT_LEGACY_UNSUPPORTED,
                -> "direct-input-unavailable"
                ActionFailureCode.TEXT_CARET_NOT_CONFIRMED -> "caret-not-confirmed"
                ActionFailureCode.TEXT_REPLACE_UNSAFE -> "replace-unsafe"
                ActionFailureCode.TEXT_NO_FOCUS -> "input-focus"
                ActionFailureCode.TEXT_NOT_EDITABLE -> "editable-node"
                ActionFailureCode.TEXT_EDIT_FAILED -> "text-edit"
            }
            return "${actionName.lowercase()}:$capability"
        }

        internal data class Attempt(
            val action: String,
            val screenSignature: String,
            val target: String,
            val outcome: VerifyOutcome,
        )

        /** What an action acted on: its coordinate cell, or its parameters for non-spatial actions. */
        internal fun actionTarget(x: Int?, y: Int?, params: Map<String, Any>): String =
            if (x != null && y != null) "${x / TARGET_CELL},${y / TARGET_CELL}"
            else params.toSortedMap().toString().take(120)

        /**
         * How many times in a row the latest attempt's action failed on the same target and screen.
         * A success ends the run; waits and screenshots are skipped; failures of other actions in
         * between neither count nor end it.
         */
        internal fun consecutiveIneffectiveAttempts(history: List<Attempt>): Int {
            val current = history.lastOrNull() ?: return 0
            if (current.action.lowercase() in NEUTRAL_ACTIONS) return 0
            var count = 0
            for (attempt in history.asReversed()) {
                if (attempt.action.lowercase() in NEUTRAL_ACTIONS) continue
                if (attempt.outcome !in INEFFECTIVE_OUTCOMES) break
                if (attempt.action.equals(current.action, ignoreCase = true) &&
                    attempt.screenSignature == current.screenSignature && attempt.target == current.target
                ) count++
            }
            return count
        }
    }
}
