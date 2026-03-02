package bot.coati.agent

import android.util.Log
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.model.Message
import bot.coati.model.ModelRouter
import bot.coati.model.ScreenContext
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.UiTreeNode
import bot.coati.ui.overlay.OverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentLoop(
    private val overlayManager: OverlayManager,
    private val modelRouter: ModelRouter,
    private val actionEngine: ActionEngine,
    private val a11y: CoatiAccessibilityService,
) {
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val conversationHistory = mutableListOf<Message>()
    private var taskStartTime = 0L
    private var stepCount = 0

    @Volatile
    var emergencyStop = false

    fun start(task: String) {
        stop()
        conversationHistory.clear()
        taskStartTime = System.currentTimeMillis()
        stepCount = 0
        emergencyStop = false
        Log.i(TAG, "Starting agent loop for task: $task")

        overlayManager.enterFrameExecuting(task)

        currentJob = scope.launch {
            try {
                runLoop(task)
            } catch (e: CancellationException) {
                Log.i(TAG, "Agent loop cancelled")
                overlayManager.collapse()
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e.message}", e)
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

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        overlayManager.resetBallState()
        Log.i(TAG, "Agent loop stopped")
    }

    private suspend fun runLoop(task: String) {
        var prevNodeCount = 0
        var prevApp = ""

        while (stepCount < MAX_STEPS && !emergencyStop && currentJob?.isActive == true) {
            stepCount++
            Log.i(TAG, "── Step $stepCount/$MAX_STEPS ──")

            // 1. OBSERVE
            overlayManager.updateProgress(task, "Capturing screen...", "", stepCount, MAX_STEPS)
            val screenContext = ScreenContext.capture(a11y)
                ?: throw Exception("Failed to capture screen")

            val uiTree = a11y.getUiTree()
            val nodeCount = uiTree?.flatCount() ?: 0
            val textNodeCount = uiTree?.nodesWithText() ?: 0

            // 2. RULE CHECK: assess quality, detect popup
            val quality = RuleEngine.assessQuality(nodeCount, textNodeCount)
            Log.i(TAG, "UI quality=$quality, nodes=$nodeCount, textNodes=$textNodeCount")

            val popup = RuleEngine.detectKnownPopup(uiTree)
            if (popup != null) {
                Log.i(TAG, "Auto-dismissing popup: ${popup.dismissText}")
                overlayManager.updateProgress(task, "Dismissing popup...", popup.dismissText, stepCount, MAX_STEPS)
                val tapX = popup.bounds.centerX()
                val tapY = popup.bounds.centerY()
                actionEngine.execute(AgentAction.Tap(tapX, tapY))
                delay(randomDelay())
                prevNodeCount = nodeCount
                prevApp = screenContext.appPackage
                continue
            }

            // 3. THINK: Reasoning model
            overlayManager.updateProgress(task, "Analyzing...", "", stepCount, MAX_STEPS)
            Log.i(TAG, "Calling reasoning model")
            val result = modelRouter.reasoning.analyzeScreen(
                screenshot = screenContext.screenshotJpeg,
                uiTreeHint = screenContext.uiTreeJson,
                taskDescription = task,
                conversationHistory = conversationHistory,
            )

            val response = result.getOrElse { e ->
                throw Exception("Model analysis failed: ${e.message}")
            }
            Log.i(TAG, "Model response: action=${response.action}, x=${response.x}, y=${response.y}")

            // Track conversation
            conversationHistory.add(Message("user", task))
            conversationHistory.add(Message("assistant", response.reasoning))

            // Check task complete
            if (response.isTaskComplete) {
                val elapsed = System.currentTimeMillis() - taskStartTime
                Log.i(TAG, "Task complete at step $stepCount!")
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

            // 4. COORDINATE RESOLUTION
            val action = resolveAction(response.action, response.params, response.x, response.y, quality, uiTree, response.statusUpdate)
            Log.i(TAG, "Resolved action: $action")

            // 5. SAFETY CHECK
            val safety = RuleEngine.assessSafety(
                screenContext.appPackage,
                "${response.action}: ${response.statusUpdate}",
                response.safetyLevel,
            )
            if (safety.level == "high") {
                Log.i(TAG, "High safety — requesting confirmation: ${safety.reason}")
                val confirmed = overlayManager.requestConfirmation(
                    title = "Confirm Action",
                    detail = "${response.action}: ${response.reasoning}",
                )
                if (!confirmed) {
                    Log.i(TAG, "User cancelled action")
                    overlayManager.collapse()
                    overlayManager.resetBallState()
                    return
                }
                overlayManager.enterFrameExecuting(task)
            }

            // 6. ACT
            val actionName = response.statusUpdate.ifBlank { response.action }
            overlayManager.updateProgress(task, "Executing: $actionName", response.reasoning, stepCount, MAX_STEPS)
            val actionResult = actionEngine.execute(action)
            Log.i(TAG, "Action result: success=${actionResult.success}, message=${actionResult.message}")

            if (!actionResult.success) {
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

            // 7. VERIFY: rules-first, fallback to Fast model
            delay(randomDelay())
            val currContext = ScreenContext.capture(a11y)
            val currTree = a11y.getUiTree()
            val currNodeCount = currTree?.flatCount() ?: 0
            val currApp = currContext?.appPackage ?: screenContext.appPackage

            val changeResult = RuleEngine.detectScreenChange(nodeCount, screenContext.appPackage, currNodeCount, currApp)
            Log.i(TAG, "Screen change: $changeResult")

            if (changeResult == ChangeResult.UNCERTAIN && currContext != null) {
                Log.i(TAG, "Calling fast model for verification")
                val checkResult = modelRouter.fast.quickCheck(
                    currContext.screenshotJpeg,
                    "Did the screen change after performing action '${response.action}'? Answer true if the screen looks different.",
                )
                val check = checkResult.getOrNull()
                Log.i(TAG, "Fast model check: answer=${check?.answer}, confidence=${check?.confidence}")
            }

            // 8. UPDATE
            prevNodeCount = currNodeCount
            prevApp = currApp
        }

        // Loop ended
        if (emergencyStop) {
            Log.i(TAG, "Emergency stop triggered!")
            overlayManager.collapse()
            overlayManager.resetBallState()
            return
        }

        if (stepCount >= MAX_STEPS) {
            Log.w(TAG, "Step limit reached ($MAX_STEPS)")
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
    }
}
