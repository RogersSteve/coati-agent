package bot.coati.agent

import android.util.Log
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.data.SafetyMode
import bot.coati.model.ExperienceNote
import bot.coati.model.Message
import bot.coati.model.ModelResponse
import bot.coati.model.ModelRouter
import bot.coati.model.ScreenContext
import bot.coati.model.VerifyOutcome
import bot.coati.model.VerifyResult
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.UiTreeNode
import bot.coati.state.AppExperienceManager
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

class AgentLoop(
    private val overlayManager: OverlayManager,
    private val modelRouter: ModelRouter,
    private val actionEngine: ActionEngine,
    private val a11y: CoatiAccessibilityService,
    private val stateManager: StateManager? = null,
    private val experienceManager: AppExperienceManager? = null,
    private val safetyMode: SafetyMode = SafetyMode.STANDARD,
) {
    private data class ActionRecord(
        val action: String,
        val statusUpdate: String,
        val screenSignature: String,
        val outcome: VerifyOutcome,
    )

    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val conversationHistory = mutableListOf<Message>()
    private val actionHistory = mutableListOf<ActionRecord>()
    private var taskStartTime = 0L
    private var stepCount = 0

    private val autocompact = Autocompact()
    private val exceptionHandler = ExceptionHandler()
    private val screenshotCache = mutableListOf<ByteArray>()
    private var currentTask: TaskState? = null
    private var resumedFromPause = false
    private var replanHint: String? = null
    private var lastStepFeedback: String? = null

    @Volatile
    var emergencyStop = false

    fun start(task: String) {
        stop()
        conversationHistory.clear()
        screenshotCache.clear()
        actionHistory.clear()
        taskStartTime = System.currentTimeMillis()
        stepCount = 0
        emergencyStop = false
        exceptionHandler.reset()
        resumedFromPause = false
        replanHint = null
        lastStepFeedback = null
        Log.i(TAG, "Starting agent loop for task: $task")

        overlayManager.enterFrameExecuting(task)

        currentJob = scope.launch {
            val myJob = coroutineContext[Job]
            try {
                runLoop(task)
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

    private suspend fun runLoop(task: String) {
        // INIT: create TaskState
        val taskState = TaskState.newTask(task)
        currentTask = taskState
        writeTaskState(taskState)

        var prevNodeCount = 0
        var prevApp = ""
        // Carry the verify screenshot forward as the next step's observe input, so two
        // back-to-back takeScreenshot() calls never happen within the same second (BUG-12).
        var carryOverContext: ScreenContext? = null

        while (stepCount < MAX_STEPS && !emergencyStop && currentJob?.isActive == true) {
            stepCount++
            Log.i(TAG, "── Step $stepCount/$MAX_STEPS ──")

            // a. REORIENT: if resumed from pause
            if (resumedFromPause && stepCount == 1) {
                Log.i(TAG, "Reorienting after pause...")
                resumedFromPause = false
                val targetPkg = currentTask?.targetPackage
                if (targetPkg != null) {
                    actionEngine.execute(AgentAction.OpenApp(targetPkg))
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
                ?: run {
                    overlayManager.hideForScreenshot()
                    delay(50)
                    try {
                        ScreenContext.capture(a11y)
                    } finally {
                        overlayManager.showAfterScreenshot()
                    }
                }
                ?: throw Exception("Failed to capture screen")

            screenshotCache.add(screenContext.screenshotJpeg)

            val uiTree = a11y.getUiTree()
            val nodeCount = uiTree?.flatCount() ?: 0
            val textNodeCount = uiTree?.nodesWithText() ?: 0

            // c. RULE CHECK: assess quality, detect popup (experience-based only)
            val quality = RuleEngine.assessQuality(nodeCount, textNodeCount)
            Log.i(TAG, "UI quality=$quality, nodes=$nodeCount, textNodes=$textNodeCount")

            val popupExperience = experienceManager?.loadPopupPatterns(screenContext.appPackage) ?: emptyList()
            val popup = RuleEngine.detectKnownPopup(uiTree, popupExperience)
            if (popup != null) {
                Log.i(TAG, "Auto-handling known popup: ${popup.dismissText}")
                overlayManager.updateProgress(task, "Dismissing popup...", popup.dismissText, stepCount, MAX_STEPS)
                val tapX = popup.bounds.centerX()
                val tapY = popup.bounds.centerY()
                overlayManager.setGesturePassthrough(true)
                delay(50)
                try {
                    actionEngine.execute(AgentAction.Tap(tapX, tapY))
                } finally {
                    overlayManager.setGesturePassthrough(false)
                }
                delay(randomDelay())
                if (popup.popupSignature != null) {
                    experienceManager?.recordPopup(
                        screenContext.appPackage, popup.popupSignature,
                        RuleEngine.extractPopupTitle(uiTree!!),
                        RuleEngine.extractPopupKeywords(uiTree),
                        popup.dismissText,
                    )
                }
                prevNodeCount = nodeCount
                prevApp = screenContext.appPackage
                continue
            }

            // d. CONTEXT: load app experience
            val experienceSection = experienceManager?.let { mgr ->
                val markdown = mgr.loadForPrompt(screenContext.appPackage)
                PromptBuilder.buildExperiencePromptSection(screenContext.appPackage, markdown)
            }

            // e. THINK: Reasoning model with timeout + exception handling
            overlayManager.updateProgress(task, "Analyzing...", "", stepCount, MAX_STEPS)
            Log.i(TAG, "Calling reasoning model")

            val taskDescriptionWithContext = buildTaskDescription(task, experienceSection)

            val result = withTimeoutOrNull(30_000) {
                modelRouter.reasoning.analyzeScreen(
                    screenshot = screenContext.screenshotJpeg,
                    uiTreeHint = screenContext.uiTreeJson,
                    taskDescription = taskDescriptionWithContext,
                    conversationHistory = conversationHistory,
                )
            }

            val response = handleModelResult(result, task)
            if (response == null) continue
            Log.i(TAG, "Model response: action=${response.action}, x=${response.x}, y=${response.y}")

            // Track conversation
            conversationHistory.add(Message("user", task))
            conversationHistory.add(Message("assistant", response.reasoning))

            // f. COORDINATE RESOLUTION
            val action = resolveAction(response.action, response.params, response.x, response.y, quality, uiTree, response.statusUpdate)
            Log.i(TAG, "Resolved action: $action")

            // g. SAFETY CHECK: use shouldConfirm with safety mode
            val safety = RuleEngine.assessSafety(
                screenContext.appPackage,
                "${response.action}: ${response.statusUpdate}",
                response.safetyLevel,
            )
            if (RuleEngine.shouldConfirm(safety.level, safetyMode)) {
                Log.i(TAG, "Safety confirmation needed (${safety.level}/$safetyMode): ${safety.reason}")
                val confirmed = overlayManager.requestConfirmation(
                    title = "Confirm Action",
                    detail = "${response.action}: ${response.reasoning}",
                )
                if (!confirmed) {
                    Log.i(TAG, "User cancelled action")
                    updateTaskStatus("cancelled")
                    overlayManager.collapse()
                    overlayManager.resetBallState()
                    return
                }
                overlayManager.enterFrameExecuting(task)
                // Dialog dismissal + overlay layout change are async WMS operations.
                // Without this delay the subsequent gesture dispatches before FLAG_NOT_TOUCHABLE
                // takes effect and gets consumed by the overlay instead of the target app.
                delay(300)
            }

            // Save before-context for verification
            val beforeScreenshot = screenContext.screenshotJpeg
            val beforeUiTree = uiTree
            val screenSig = RuleEngine.buildScreenSignature(uiTree)

            // h. ACT
            // FLAG_NOT_TOUCHABLE must be set before dispatchGesture so injected touch events
            // pass through the full-screen overlay to the target app. Without this, the overlay
            // consumes the gesture and the target app never receives it (same fix as AgentRunner).
            val actionName = response.statusUpdate.ifBlank { response.action }
            overlayManager.updateProgress(task, "Executing: $actionName", response.reasoning, stepCount, MAX_STEPS)
            overlayManager.setGesturePassthrough(true)
            // WMS processes updateViewLayout() asynchronously; wait 50ms for the InputDispatcher
            // to pick up FLAG_NOT_TOUCHABLE before dispatchGesture() injects events.
            delay(50)
            val actionResult = try {
                actionEngine.execute(action)
            } finally {
                overlayManager.setGesturePassthrough(false)
            }
            Log.i(TAG, "Action result: success=${actionResult.success}, message=${actionResult.message}")

            if (!actionResult.success) {
                val failAction = exceptionHandler.recordFailure()
                when (failAction) {
                    FailureAction.ABORT -> {
                        updateTaskStatus("failed", "Action failed: ${actionResult.message}")
                        val retry = overlayManager.awaitTaskFailed(
                            taskName = task,
                            reason = "Action failed: ${actionResult.message}",
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
                    FailureAction.REPLAN -> {
                        replanHint = exceptionHandler.buildReplanHint()
                        Log.i(TAG, "Replan after action failure: $replanHint")
                        continue
                    }
                    FailureAction.RETRY -> {
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
                val elapsed = System.currentTimeMillis() - taskStartTime
                Log.i(TAG, "Task complete at step $stepCount!")
                updateTaskStatus("completed")
                overlayManager.awaitTaskComplete(
                    taskName = task,
                    summary = response.statusUpdate,
                    steps = stepCount,
                    durationMs = elapsed,
                )
                overlayManager.collapse()
                overlayManager.resetBallState()
                return
            } else if (response.isTaskComplete) {
                Log.i(TAG, "isTaskComplete with action '${response.action}' — deferring: model must confirm via screenshot")
            }

            // i. VERIFY: action verification with popup fast path + replan on failure
            var popupDismissCount = 0
            var verifyObservation: String? = null
            var shouldRecordExperience = false

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

                // Fast path: known popup from experience
                val verifyPopupExp = experienceManager?.loadPopupPatterns(screenContext.appPackage) ?: emptyList()
                val verifyPopup = RuleEngine.detectKnownPopup(afterTree, verifyPopupExp)
                if (verifyPopup != null) {
                    popupDismissCount++
                    if (popupDismissCount > MAX_POPUP_DISMISSALS) {
                        updateTaskStatus("failed", "Persistent popup could not be dismissed")
                        val retry = overlayManager.awaitTaskFailed(
                            taskName = task,
                            reason = "Persistent popup could not be dismissed",
                            stepsCompleted = stepCount,
                            totalSteps = MAX_STEPS,
                        )
                        if (retry) { start(task); return } else { overlayManager.collapse(); overlayManager.resetBallState(); return }
                    }
                    // Dismiss popup
                    overlayManager.setGesturePassthrough(true)
                    delay(50)
                    try { actionEngine.execute(AgentAction.Tap(verifyPopup.bounds.centerX(), verifyPopup.bounds.centerY())) }
                    finally { overlayManager.setGesturePassthrough(false) }
                    delay(randomDelay())
                    // Re-execute original action
                    overlayManager.setGesturePassthrough(true)
                    delay(50)
                    try { actionEngine.execute(action) }
                    finally { overlayManager.setGesturePassthrough(false) }
                    if (verifyPopup.popupSignature != null && afterTree != null) {
                        experienceManager?.recordPopup(
                            screenContext.appPackage, verifyPopup.popupSignature,
                            RuleEngine.extractPopupTitle(afterTree),
                            RuleEngine.extractPopupKeywords(afterTree),
                            verifyPopup.dismissText,
                        )
                    }
                    continue@verifyLoop
                }

                // Model verification: send BEFORE + AFTER screenshots (retry up to 3 times)
                val actionDesc = "${response.action}: ${response.reasoning}"
                val verifyResult = (1..3).firstNotNullOfOrNull { attempt ->
                    val res = modelRouter.fast.verifyAction(
                        beforeScreenshot = beforeScreenshot,
                        afterScreenshot = afterContext.screenshotJpeg,
                        actionDescription = actionDesc,
                        expectedOutcome = response.expectedOutcome,
                    )
                    if (res.isSuccess) res.getOrThrow()
                    else {
                        Log.w(TAG, "Verify attempt $attempt/3 failed: ${res.exceptionOrNull()?.message}")
                        null
                    }
                } ?: run {
                    Log.w(TAG, "Verify unavailable after 3 failures, treating as UNKNOWN")
                    VerifyResult(VerifyOutcome.UNKNOWN, "Verification unavailable after repeated parse/API failures")
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
                        shouldRecordExperience = true

                        // Learn popup experience
                        if (response.isPopupDismissal && beforeUiTree != null) {
                            val sig = RuleEngine.buildPopupSignature(beforeUiTree)
                            val title = RuleEngine.extractPopupTitle(beforeUiTree)
                            val keywords = RuleEngine.extractPopupKeywords(beforeUiTree)
                            val clickedButton = response.statusUpdate
                            if (sig != null) {
                                experienceManager?.recordPopup(
                                    screenContext.appPackage, sig, title, keywords, clickedButton)
                                Log.i(TAG, "Learned popup: title=$title, button=$clickedButton")
                            }
                        }

                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.UNEXPECTED -> {
                        exceptionHandler.recordSuccess()
                        replanHint = null
                        lastStepFeedback = "Previous action had an UNEXPECTED but task-compatible result: ${verifyResult.reason}"
                        verifyObservation = verifyResult.observation
                        shouldRecordExperience = true
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
                            it.statusUpdate == response.statusUpdate &&
                            it.screenSignature == screenSig &&
                            it.outcome in listOf(VerifyOutcome.FAILED, VerifyOutcome.DRIFTED)
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
                        replanHint = "Previous action FAILED (attempt $sameActionFailCount/$MAX_VERIFY_RETRIES): " +
                            "${verifyResult.reason}. The screen did not change. Re-observe and try a different approach."
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                    VerifyOutcome.DRIFTED -> {
                        lastStepFeedback = null
                        val sameActionFailCount = actionHistory.count {
                            it.statusUpdate == response.statusUpdate &&
                            it.screenSignature == screenSig &&
                            it.outcome in listOf(VerifyOutcome.FAILED, VerifyOutcome.DRIFTED)
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
                        replanHint = "Previous action DRIFTED (attempt $sameActionFailCount/$MAX_VERIFY_RETRIES): " +
                            "${verifyResult.reason}. The screen changed to an unexpected state. " +
                            "Assess current screen and decide how to recover."
                        carryOverContext = afterContext
                        break@verifyLoop
                    }
                }
            }

            // j. LEARN: record experience (UNEXPECTED observation takes priority)
            val learnNote = if (!shouldRecordExperience) {
                null
            } else if (verifyObservation != null) {
                val pageName = response.experienceNote?.page
                    ?: response.statusUpdate.ifBlank { screenContext.appPackage }
                ExperienceNote(page = pageName, observation = verifyObservation!!)
            } else {
                response.experienceNote
            }
            if (learnNote != null && experienceManager != null) {
                experienceManager.recordObservation(screenContext.appPackage, learnNote.page, learnNote.observation)
                Log.i(TAG, "Recorded experience: page=${learnNote.page}")
            }

            // k. UPDATE: write step to task.json
            currentTask = currentTask?.copy(
                currentStep = stepCount,
                totalSteps = MAX_STEPS,
                lastUpdated = currentTimestamp(),
                targetPackage = screenContext.appPackage,
            )
            writeTaskState(currentTask)

            // l. AUTOCOMPACT
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
                } catch (e: Exception) {
                    Log.w(TAG, "Autocompact failed: ${e.message}")
                }
            }

            // m. UPDATE TRACKING
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

    private fun buildTaskDescription(task: String, experienceSection: String?): String {
        val sb = StringBuilder(task)
        if (lastStepFeedback != null) {
            sb.append("\n\n[LAST STEP] $lastStepFeedback")
        }
        if (replanHint != null) {
            sb.append("\n\n[REPLAN] $replanHint")
        }
        if (experienceSection != null) {
            sb.append("\n\n$experienceSection")
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
    ): AgentAction {
        // For tap/longpress with HIGH quality, try to match UI tree bounds
        if (quality == UiTreeQuality.HIGH && uiTree != null && normalizedX != null && normalizedY != null) {
            val matchedNode = findBestMatchNode(uiTree, statusUpdate)
            if (matchedNode != null) {
                val cx = matchedNode.bounds.centerX()
                val cy = matchedNode.bounds.centerY()
                Log.i(TAG, "Using UI tree bounds: ($cx, $cy) for '${matchedNode.text ?: matchedNode.contentDescription}'")

                return when (actionType.lowercase()) {
                    "tap" -> AgentAction.Tap(cx, cy)
                    "longpress", "long_press" -> AgentAction.LongPress(
                        cx, cy,
                        (params["durationMs"] as? Number)?.toLong() ?: 1000,
                    )
                    else -> convertWithCoordinates(actionType, params, normalizedX, normalizedY)
                }
            }
        }

        // For MEDIUM/LOW or no match, convert normalized 0-1000 → pixel coords
        if (normalizedX != null && normalizedY != null) {
            return convertWithCoordinates(actionType, params, normalizedX, normalizedY)
        }

        // Fallback: use raw params (for non-coordinate actions)
        return AgentAction.fromMap(actionType, params)
    }

    private fun convertWithCoordinates(
        actionType: String,
        params: Map<String, Any>,
        normalizedX: Int,
        normalizedY: Int,
    ): AgentAction {
        val displayMetrics = a11y.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val pixelX = normalizedX * screenWidth / 1000
        val pixelY = normalizedY * screenHeight / 1000

        return when (actionType.lowercase()) {
            "tap" -> AgentAction.Tap(pixelX, pixelY)
            "longpress", "long_press" -> AgentAction.LongPress(
                pixelX, pixelY,
                (params["durationMs"] as? Number)?.toLong() ?: 1000,
            )
            "swipe" -> {
                val x1 = ((params["x1"] as? Number)?.toInt() ?: 0) * screenWidth / 1000
                val y1 = ((params["y1"] as? Number)?.toInt() ?: 0) * screenHeight / 1000
                val x2 = ((params["x2"] as? Number)?.toInt() ?: 0) * screenWidth / 1000
                val y2 = ((params["y2"] as? Number)?.toInt() ?: 0) * screenHeight / 1000
                AgentAction.Swipe(x1, y1, x2, y2, (params["durationMs"] as? Number)?.toLong() ?: 400)
            }
            else -> AgentAction.fromMap(actionType, params)
        }
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

    private fun randomDelay(): Long = (300L..1500L).random()

    companion object {
        private const val TAG = "CoatiLoop"
        const val MAX_STEPS = 200
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_VERIFY_RETRIES = 2
        private const val MAX_POPUP_DISMISSALS = 3
    }
}
