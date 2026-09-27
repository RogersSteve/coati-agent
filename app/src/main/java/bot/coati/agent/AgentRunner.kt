package bot.coati.agent

import android.util.Log
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.ModelCoordinateAction
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.action.toLogSummary
import bot.coati.model.CloudModelGateway
import bot.coati.model.Message
import bot.coati.model.ModelRouter
import bot.coati.model.ScreenContext
import bot.coati.service.CoatiAccessibilityService
import bot.coati.ui.overlay.OverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Deprecated("Use AgentLoop instead for full auto-loop support")
class AgentRunner(
    private val overlayManager: OverlayManager,
    private val gateway: CloudModelGateway,
    private val actionEngine: ActionEngine,
    private val a11y: CoatiAccessibilityService,
) {
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val conversationHistory = mutableListOf<Message>()
    private var taskStartTime = 0L

    fun start(task: String) {
        stop()
        conversationHistory.clear()
        taskStartTime = System.currentTimeMillis()
        Log.i(TAG, "Starting agent for task: $task")

        // Enter frame executing overlay
        overlayManager.enterFrameExecuting(task)

        currentJob = scope.launch {
            try {
                executeSingleStep(task)
            } catch (e: CancellationException) {
                Log.i(TAG, "Agent cancelled")
                overlayManager.collapse()
            } catch (e: Exception) {
                Log.e(TAG, "Agent error: ${e.message}", e)
                val elapsed = System.currentTimeMillis() - taskStartTime
                // Show task failed dialog
                val retry = overlayManager.awaitTaskFailed(
                    taskName = task,
                    reason = e.message ?: "Unknown error",
                    stepsCompleted = 0,
                    totalSteps = 0,
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
        Log.i(TAG, "Agent stopped")
    }

    fun onConfirm() {
        // Handled by CompletableDeferred in OverlayManager
    }

    fun onCancel() {
        // Handled by CompletableDeferred in OverlayManager
    }

    private suspend fun executeSingleStep(task: String) {
        // Step 1: Capture screen
        overlayManager.updateProgress(task, "Capturing screen...", "", 0, 0)
        Log.i(TAG, "Step 1: Capturing screen context")
        val screenContext = ScreenContext.capture(a11y)
            ?: throw Exception("Failed to capture screen")

        // Step 2: Analyze with cloud model
        overlayManager.updateProgress(task, "Analyzing...", "", 0, 0)
        Log.i(TAG, "Step 2: Sending to cloud model")
        val result = gateway.analyzeScreen(
            screenshot = screenContext.screenshotJpeg,
            uiTreeHint = screenContext.uiTreeJson,
            taskDescription = task,
            conversationHistory = conversationHistory,
        )

        val response = result.getOrElse { e ->
            throw Exception("Model analysis failed: ${e.message}")
        }
        Log.i(TAG, "Model response: action=${response.action}, reasoning=${response.reasoning}")

        // Track conversation
        conversationHistory.add(Message("user", task))
        conversationHistory.add(Message("assistant", response.reasoning))

        // Step 3: Parse action
        val action = try {
            ModelCoordinateAction.resolve(response.action, response.params, response.x, response.y, screenContext.coordinates)
        } catch (e: Exception) {
            throw Exception("Failed to parse action '${response.action}': ${e.message}")
        }
        Log.i(TAG, "Parsed action: ${action.toLogSummary()}")

        // Step 4: Safety check
        if (response.safetyLevel == "high") {
            Log.i(TAG, "High safety level - requesting confirmation")
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

            // Back to frame executing after confirmation
            overlayManager.enterFrameExecuting(task)
        }

        // Step 5: Execute action
        // FLAG_NOT_TOUCHABLE is set so that dispatchGesture events pass through the overlay
        // to the target app. Without this, the full-screen overlay consumes injected gestures.
        val actionName = response.statusUpdate.ifBlank { response.action }
        overlayManager.updateProgress(task, "Executing: $actionName", response.reasoning, 0, 0)
        Log.i(TAG, "Step 5: Executing action")
        val actionResult = overlayManager.withGesturePassthrough {
            if (ModelCoordinateAction.isSpatial(response.action)) {
                requireNotNull(screenContext.coordinates) { "missing_screenshot_coordinates" }
                    .dispatch({ DisplaySpaceReader.read(a11y) }) { actionEngine.execute(action, screenContext.coordinates) }
            } else {
                actionEngine.execute(action)
            }
        }
        Log.i(TAG, "Action result: success=${actionResult.success}, message=${actionResult.message}")

        // Step 6: Handle completion
        val elapsed = System.currentTimeMillis() - taskStartTime
        if (response.isTaskComplete) {
            Log.i(TAG, "Task complete!")
            overlayManager.awaitTaskComplete(
                taskName = task,
                summary = response.statusUpdate,
                steps = 1,
                durationMs = elapsed,
            )
            overlayManager.collapse()
            overlayManager.resetBallState()
        } else if (!actionResult.success) {
            val retry = overlayManager.awaitTaskFailed(
                taskName = task,
                reason = "Action failed: ${actionResult.message}",
                stepsCompleted = 0,
                totalSteps = 0,
            )
            if (retry) {
                start(task)
            } else {
                overlayManager.collapse()
                overlayManager.resetBallState()
            }
        } else {
            overlayManager.updateProgress(task, "Done: ${response.statusUpdate}", "", 1, 1)
            kotlinx.coroutines.delay(1500)
            overlayManager.collapse()
            overlayManager.resetBallState()
        }
    }

    companion object {
        private const val TAG = "CoatiAgent"
    }
}
