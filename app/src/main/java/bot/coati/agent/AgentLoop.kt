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
import bot.coati.model.MODEL_REQUEST_TIMEOUT_MS
import bot.coati.model.ModelResponse
import bot.coati.model.ModelRouter
import bot.coati.model.QuickCheckResult
import bot.coati.model.ScreenContext
import bot.coati.model.VerifyOutcome
import bot.coati.model.VerifyResult
import bot.coati.service.CoatiAccessibilityService
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
    private var currentTask: TaskState? = null
    private var resumedFromPause = false
    private var replanHint: String? = null
    private var lastStepFeedback: String? = null
    private var taskCompletionAuditRejects = 0

    @Volatile
    var emergencyStop = false

    fun start(task: String) {
        stop()
        val taskSafetyMode = safetyModeProvider()
        conversationHistory.clear()
        screenshotCache.clear()
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
        manualRuntime?.startTask(task)
        fastExecutor?.startTask(task)
        Log.i(TAG, "Starting agent loop for task: $task (confirmationMode=$taskSafetyMode)")

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
                        totalSteps = MAX_STEPS,
                    )
                    if (retry) {
                        start(task)
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

        while (stepCount < MAX_STEPS && !emergencyStop && currentJob?.isActive == true) {
            stepCount++
            Log.i(TAG, "── Step $stepCount/$MAX_STEPS ──")

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
            overlayManager.updateProgress(task, "Capturing screen...", "", stepCount, MAX_STEPS)
            // On the first step, wait for the keyboard/clipboard panel to fully dismiss after
            // the user pressed Execute (keyboard hide animation takes ~300ms).
            if (stepCount == 1) delay(500)
            // Hide overlay before capturing so the Coati UI doesn't appear in the screenshot
            // sent to the model (BUG-5). Same alpha/updateViewLayout pattern as gesture
            // passthrough; 50ms gives the compositor time to re-render without the overlay.
            val screenContext: ScreenContext = carryOverContext.also { carryOverContext = null }
                ?: captureScreen()
                ?: throw Exception("Failed to capture screen")

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

            val uiTree = a11y.getUiTree()
            val nodeCount = uiTree?.flatCount() ?: 0
            val textNodeCount = uiTree?.nodesWithText() ?: 0
            val screenSig = RuleEngine.buildScreenSignature(uiTree)
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
                overlayManager.updateProgress(task, "Deciding...", "", stepCount, MAX_STEPS)
                val outcome = fast.step(stepCount, task, screenContext, uiTree) { pkg, actionName, label, level ->
                    val fastSafety = RuleEngine.assessSafety(pkg, actionName, label, level)
                    confirmIfNeeded(task, safetyMode, onSecurityDialog, fastSafety.level, fastSafety.reason, label)
                }
                when (outcome) {
                    FastStep.Acted -> {
                        recordStepProgress(screenContext.appPackage)
                        continue
                    }
                    FastStep.Retry -> continue
                    FastStep.Declined -> {
                        cancelByUser()
                        return
                    }
                    is FastStep.DoneClaimed -> {
                        val audit = auditCompletion(task, screenContext.screenshotJpeg)
                        if (classifyTaskCompletionAudit(audit) != CompletionAuditDecision.REJECT) {
                            acceptCompletion(task, outcome.summary.ifBlank { "Task complete" })
                            return
                        }
                        fast.doneRejected()
                        val auditReason = audit?.category ?: "completion evidence unavailable"
                        if (++taskCompletionAuditRejects >= MAX_TASK_COMPLETION_AUDIT_REJECTS) {
                            failTask(task, "Task completion could not be verified after $taskCompletionAuditRejects attempts: $auditReason")
                            return
                        }
                        // JEV cannot read the audit's reason (a wrong count, an unsaved value): the
                        // Reasoning model takes this step to fix exactly what the audit found.
                        lastStepFeedback = "Task completion audit REJECTED: $auditReason. " +
                            "The current screen does not yet prove every explicit user requirement."
                        replanHint = "A fast executor claimed the task was done but the audit rejected it. " +
                            "Its recent actions:\n${fast.recentHistory()}\nRe-check the original task against " +
                            "the current screenshot and fix only the missing or wrong result (redo a submit or " +
                            "save if its values were wrong)."
                    }
                    is FastStep.Escalate -> {
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
            overlayManager.updateProgress(task, "Analyzing...", "", stepCount, MAX_STEPS)
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
            overlayManager.updateProgress(task, "Executing: $actionName", response.reasoning, stepCount, MAX_STEPS)
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
                            totalSteps = MAX_STEPS,
                        )
                        if (retry) {
                            start(task)
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

            // Check task complete. Completion is only accepted when action == "screenshot",
            // which means the model already saw the post-action state and confirmed the result.
            // Any other action + isTaskComplete=true is intercepted: the action executes normally
            // and the VERIFY step captures a fresh screenshot (stored as carryOverContext), so
            // the next step the model sees the result and can issue screenshot + isTaskComplete=true.
            if (response.isTaskComplete && response.action.lowercase() == "screenshot") {
                val audit = withTimeoutOrNull(MODEL_REQUEST_TIMEOUT_MS) {
                    modelRouter.fast.quickCheck(
                        // screenshot is a read-only completion action, so the current
                        // overlay-free OBSERVE frame is the authoritative final state.
                        screenshot = screenContext.screenshotJpeg,
                        question = buildTaskCompletionAuditQuestion(task),
                        // Page learning was already handled by the current Reasoning response.
                        // Keep this independent audit compact and single-purpose.
                        manualObservationInstruction = null,
                    )
                }?.getOrNull()
                val auditDecision = classifyTaskCompletionAudit(audit)
                Log.i(
                    TAG,
                    "Task completion audit: decision=$auditDecision, valid=${audit?.isValid}, " +
                        "answer=${audit?.answer}, confidence=${audit?.confidence}, " +
                        "category=${audit?.category ?: "unavailable"}",
                )
                if (auditDecision != CompletionAuditDecision.REJECT) {
                    if (auditDecision == CompletionAuditDecision.UNAVAILABLE) {
                        Log.w(
                            TAG,
                            "Independent completion audit unavailable; accepting the reasoning " +
                                "model's current-screen completion evidence",
                        )
                    }
                    val elapsed = System.currentTimeMillis() - taskStartTime
                    Log.i(TAG, "Task complete at step $stepCount in ${elapsed}ms")
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
                        summary = response.statusUpdate,
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
                        totalSteps = MAX_STEPS,
                    )
                    if (retry) {
                        start(task)
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

            verifyLoop@ while (true) {
                delay(randomDelay())
                overlayManager.hideForScreenshot()
                delay(50)
                val afterContext = try {
                    ScreenContext.capture(a11y)
                } finally {
                    overlayManager.showAfterScreenshot()
                } ?: throw Exception("Failed to capture verification screenshot")
                val afterTree = a11y.getUiTree()
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
                        val sameActionFailCount = actionHistory.count {
                            matchesIneffectiveAttempt(
                                recordedAction = it.action,
                                currentAction = response.action,
                                recordedScreenSignature = it.screenSignature,
                                currentScreenSignature = screenSig,
                                outcome = it.outcome,
                            )
                        }
                        if (sameActionFailCount >= MAX_VERIFY_RETRIES) {
                            updateTaskStatus("failed", "Action repeatedly ineffective: ${verifyResult.reason}")
                            val retry = overlayManager.awaitTaskFailed(
                                taskName = task,
                                reason = "Action repeatedly ineffective: ${verifyResult.reason}",
                                stepsCompleted = stepCount,
                                totalSteps = MAX_STEPS,
                            )
                            if (retry) { start(task); return } else { overlayManager.collapse(); overlayManager.resetBallState(); return }
                        }
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = "FAILED",
                                reason = verifyResult.reason,
                            )
                        } else {
                            "Previous action FAILED (attempt $sameActionFailCount/$MAX_VERIFY_RETRIES): " +
                                "${verifyResult.reason}. The screen did not change. Re-observe and try a different approach."
                        }
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.DRIFTED -> {
                        lastStepFeedback = null
                        val sameActionFailCount = actionHistory.count {
                            matchesIneffectiveAttempt(
                                recordedAction = it.action,
                                currentAction = response.action,
                                recordedScreenSignature = it.screenSignature,
                                currentScreenSignature = screenSig,
                                outcome = it.outcome,
                            )
                        }
                        if (sameActionFailCount >= MAX_VERIFY_RETRIES) {
                            updateTaskStatus("failed", "Action repeatedly drifted: ${verifyResult.reason}")
                            val retry = overlayManager.awaitTaskFailed(
                                taskName = task,
                                reason = "Action repeatedly drifted: ${verifyResult.reason}",
                                stepsCompleted = stepCount,
                                totalSteps = MAX_STEPS,
                            )
                            if (retry) { start(task); return } else { overlayManager.collapse(); overlayManager.resetBallState(); return }
                        }
                        replanHint = if (isTextInputAction(response.action)) {
                            buildTextInputReplanHint(
                                outcome = "DRIFTED",
                                reason = verifyResult.reason,
                            )
                        } else {
                            "Previous action DRIFTED (attempt $sameActionFailCount/$MAX_VERIFY_RETRIES): " +
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
                totalSteps = MAX_STEPS,
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

        if (stepCount >= MAX_STEPS) {
            Log.w(TAG, "Step limit reached ($MAX_STEPS)")
            updateTaskStatus("failed", "Step limit reached")
            val retry = overlayManager.awaitTaskFailed(
                taskName = task,
                reason = "Step limit reached ($MAX_STEPS steps)",
                stepsCompleted = stepCount,
                totalSteps = MAX_STEPS,
            )
            if (retry) {
                start(task)
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
        val task = currentTask ?: return
        val errors = if (error != null) task.errors + error else task.errors
        currentTask = task.copy(
            status = status,
            lastUpdated = currentTimestamp(),
            errors = errors,
        )
        writeTaskState(currentTask)
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
            overlayManager.hideForScreenshot()
            delay(50)
            val context = try {
                ScreenContext.capture(a11y)
            } finally {
                overlayManager.showAfterScreenshot()
            }
            if (context != null) return context
        }
        return null
    }

    /**
     * Waits while a system authorization dialog is in front, for the user to answer it by hand.
     * Returns false if it is still showing after [SecurityDialogs.USER_RESPONSE_TIMEOUT_MS].
     */
    private suspend fun awaitUserAnswersSecurityDialog(task: String): Boolean {
        Log.i(TAG, "System dialog in front: waiting for the user to answer it")
        // Hidden by the system while the dialog shows; visible again once it is answered.
        overlayManager.updateProgress(task, "Waiting for you to answer the system dialog", "", stepCount, MAX_STEPS)
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
            title = when (safetyMode) {
                SafetyMode.TRUST -> "High-Risk Action"
                SafetyMode.STANDARD -> "Risk Confirmation"
                SafetyMode.CAUTIOUS -> "Review Next Action"
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

    /** Independent Fast-model check that the current screen proves the task is done. */
    private suspend fun auditCompletion(task: String, screenshot: ByteArray): QuickCheckResult? {
        overlayManager.updateProgress(task, "Checking result...", "", stepCount, MAX_STEPS)
        // An audit normally answers in about 5 s; one stalled call (47 s on the board) must not hold a
        // finished task for the gateway's full limit, so a slow attempt is retried.
        var audit: QuickCheckResult? = null
        for (attempt in 1..AUDIT_ATTEMPTS) {
            val result = withTimeoutOrNull(AUDIT_ATTEMPT_TIMEOUT_MS) {
                modelRouter.fast.quickCheck(screenshot, buildTaskCompletionAuditQuestion(task), null)
            }
            if (result == null) {
                Log.w(TAG, "Task completion audit attempt $attempt timed out")
                continue
            }
            audit = result.getOrNull()
            break
        }
        Log.i(TAG, "Task completion audit: decision=${classifyTaskCompletionAudit(audit)}, category=${audit?.category}")
        return audit
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
        val retry = overlayManager.awaitTaskFailed(taskName = task, reason = reason, stepsCompleted = stepCount, totalSteps = MAX_STEPS)
        if (retry) {
            start(task)
        } else {
            overlayManager.collapse()
            overlayManager.resetBallState()
        }
    }

    private suspend fun recordStepProgress(appPackage: String) {
        currentTask = currentTask?.copy(
            currentStep = stepCount,
            totalSteps = MAX_STEPS,
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
        private const val SECURITY_DIALOG_POLL_MS = 1_000L
        private const val CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_RETRY_DELAY_MS = 1_000L
        const val MAX_STEPS = 200
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_VERIFY_RETRIES = 2
        private const val MAX_TASK_COMPLETION_AUDIT_REJECTS = 3
        private const val AUDIT_ATTEMPTS = 2
        private const val AUDIT_ATTEMPT_TIMEOUT_MS = 20_000L

        /** Model-facing explanation for a rejected spatial action, or null when [message] is not a binding guard. */
        internal fun coordinateFailureFeedback(message: String?): String? = when (message) {
            "missing_screenshot_coordinates" ->
                "the current window (for example a dialog, popup or split screen) cannot be mapped to screen " +
                    "coordinates, so taps and swipes are unavailable. Use a non-spatial action such as " +
                    "pressKey BACK, wait, or openApp, or wait for a full-screen page."
            "stale_screenshot_coordinates" ->
                "the screen changed between the screenshot and the action. Re-observe the current screen " +
                    "before choosing again."
            else -> null
        }

        internal fun buildTaskCompletionAuditQuestion(task: String): String {
            return """
                Independently audit whether the CURRENT screenshot visibly satisfies every explicit requirement of this original Android task:
                $task

                Return answer=true only when the result itself is visibly complete and correctly structured. Do not trust the agent's status, history, or claim of completion.
                If the task specifies a title and body, require a genuinely distinct rendered title region/style and the body in its body region. Text merely appearing as the first line of the body does not count as a title. A still-visible empty title placeholder means failure.
                If the task specifies multiple lines, require the exact visible line structure; collapsed, missing, duplicated, or misplaced text means failure.
                Use category to name the missing or incorrect requirement briefly.
            """.trimIndent()
        }

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

        internal fun matchesIneffectiveAttempt(
            recordedAction: String,
            currentAction: String,
            recordedScreenSignature: String,
            currentScreenSignature: String,
            outcome: VerifyOutcome,
        ): Boolean {
            return recordedAction.equals(currentAction, ignoreCase = true) &&
                recordedScreenSignature == currentScreenSignature &&
                outcome in setOf(VerifyOutcome.FAILED, VerifyOutcome.DRIFTED)
        }
    }
}
