package bot.coati.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CompletableDeferred

class OverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var composeView: ComposeView? = null
    val overlayState = mutableStateOf<OverlayState>(OverlayState.CollapsedIdle)

    // Background agent state for ball color when collapsed
    private var agentBallState = mutableStateOf<OverlayState>(OverlayState.CollapsedIdle)

    // Callbacks wired by CoatiService
    var onExecuteTask: ((String) -> Unit)? = null
    var onStopTask: (() -> Unit)? = null
    var onPauseTask: (() -> Unit)? = null
    var onResumeTask: (() -> Unit)? = null
    var onConfirmAction: (() -> Unit)? = null
    var onCancelAction: (() -> Unit)? = null
    var onRetryTask: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onRecoveryResume: (() -> Unit)? = null
    var onRecoveryAbandon: (() -> Unit)? = null

    private var lastTask: String = ""

    // Deferred for suspend-based confirmation
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private var taskCompleteDeferred: CompletableDeferred<Unit>? = null
    private var taskFailedDeferred: CompletableDeferred<Boolean>? = null
    private var recoveryDeferred: CompletableDeferred<Boolean>? = null

    private val density = Resources.getSystem().displayMetrics.density
    private val ballSizePx = (48 * density).toInt()
    private val expandedWidthPx = (296 * density).toInt()

    // Saved position for collapsed/expanded (non-fullscreen) states
    private var savedX = 0
    private var savedY = 300

    // Scrim window — sits behind the panel window, shown only when expanded
    private var scrimView: ComposeView? = null
    private val scrimParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0f
    }

    // Layout params — mutated when switching states
    private val layoutParams = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 300
    }

    fun show() {
        if (composeView != null) return

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val state = overlayState.value
                val ballState = agentBallState.value
                OverlayContent(
                    state = state,
                    ballState = ballState,
                    onExecute = { task ->
                        lastTask = task
                        onExecuteTask?.invoke(task)
                    },
                    onStop = { onStopTask?.invoke() },
                    onPause = { onPauseTask?.invoke() },
                    onResume = { onResumeTask?.invoke() },
                    onConfirm = {
                        confirmationDeferred?.complete(true)
                        onConfirmAction?.invoke()
                    },
                    onCancel = {
                        confirmationDeferred?.complete(false)
                        onCancelAction?.invoke()
                    },
                    onRetry = {
                        taskFailedDeferred?.complete(true)
                        onRetryTask?.invoke()
                    },
                    onDismiss = {
                        // Handle dismiss for both TaskComplete and TaskFailed
                        taskCompleteDeferred?.complete(Unit)
                        taskFailedDeferred?.complete(false)
                        collapse()
                    },
                    onCollapse = { collapse() },
                    onSettings = { onSettings?.invoke() },
                    onExit = { onExit?.invoke() },
                    onRecoveryResume = {
                        recoveryDeferred?.complete(true)
                        onRecoveryResume?.invoke()
                    },
                    onRecoveryAbandon = {
                        recoveryDeferred?.complete(false)
                        onRecoveryAbandon?.invoke()
                    },
                )
            }
        }

        // Add scrim FIRST so it sits below the panel in z-order
        val scrim = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .pointerInput(Unit) {
                            detectTapGestures { collapse() }
                        },
                )
            }
        }
        windowManager.addView(scrim, scrimParams)
        scrimView = scrim

        setupTouchListener(view)
        windowManager.addView(view, layoutParams)
        composeView = view
    }

    fun hide() {
        scrimView?.let {
            try { windowManager.removeView(it) } catch (_: IllegalArgumentException) {}
            scrimView = null
        }
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // View might have been removed
            }
            composeView = null
        }
    }

    // ─── STATE TRANSITIONS ───

    fun collapse() {
        overlayState.value = OverlayState.CollapsedIdle
        applyLayout(LayoutMode.COLLAPSED)
        hideScrim()
    }

    fun expand() {
        overlayState.value = OverlayState.ExpandedIdle
        applyLayout(LayoutMode.EXPANDED)
        showScrim()
    }

    fun enterFrameExecuting(taskName: String) {
        hideScrim()
        lastTask = taskName
        val state = OverlayState.FrameExecuting(taskName = taskName)
        agentBallState.value = state
        overlayState.value = state
        applyLayout(LayoutMode.FULLSCREEN_KEEPON)
    }

    fun updateProgress(taskName: String, status: String, reasoning: String, step: Int, totalSteps: Int) {
        val state = OverlayState.FrameExecuting(
            taskName = taskName,
            status = status,
            reasoning = reasoning,
            step = step,
            totalSteps = totalSteps,
        )
        agentBallState.value = state
        overlayState.value = state
        // No layout change needed — already fullscreen
    }

    fun pause(taskName: String, step: Int, totalSteps: Int) {
        val state = OverlayState.Paused(taskName = taskName, step = step, totalSteps = totalSteps)
        agentBallState.value = state
        overlayState.value = state
        applyLayout(LayoutMode.TOP_BAR)
    }

    fun showSafetyConfirm(title: String, detail: String) {
        overlayState.value = OverlayState.SafetyConfirm(title = title, detail = detail)
        applyLayout(LayoutMode.FULLSCREEN)
    }

    fun showTaskComplete(taskName: String, summary: String, steps: Int, durationMs: Long) {
        agentBallState.value = OverlayState.CollapsedIdle
        overlayState.value = OverlayState.TaskComplete(
            taskName = taskName,
            summary = summary,
            steps = steps,
            durationMs = durationMs,
        )
        applyLayout(LayoutMode.FULLSCREEN)
    }

    fun showTaskFailed(taskName: String, reason: String, stepsCompleted: Int, totalSteps: Int) {
        agentBallState.value = OverlayState.TaskFailed(taskName = taskName, reason = reason, stepsCompleted = stepsCompleted, totalSteps = totalSteps)
        overlayState.value = OverlayState.TaskFailed(
            taskName = taskName,
            reason = reason,
            stepsCompleted = stepsCompleted,
            totalSteps = totalSteps,
        )
        applyLayout(LayoutMode.FULLSCREEN)
    }

    fun showRecoveryDialog(taskName: String, step: Int, totalSteps: Int, interruptedAgo: String) {
        overlayState.value = OverlayState.TaskRecovery(
            taskName = taskName,
            step = step,
            totalSteps = totalSteps,
            interruptedAgo = interruptedAgo,
        )
        applyLayout(LayoutMode.FULLSCREEN)
    }

    fun resetBallState() {
        agentBallState.value = OverlayState.CollapsedIdle
    }

    // ─── SUSPEND-BASED API (for AgentRunner) ───

    suspend fun requestConfirmation(title: String, detail: String): Boolean {
        showSafetyConfirm(title, detail)
        confirmationDeferred = CompletableDeferred()
        return try {
            confirmationDeferred!!.await()
        } finally {
            confirmationDeferred = null
        }
    }

    suspend fun awaitTaskComplete(taskName: String, summary: String, steps: Int, durationMs: Long) {
        showTaskComplete(taskName, summary, steps, durationMs)
        taskCompleteDeferred = CompletableDeferred()
        try {
            taskCompleteDeferred!!.await()
        } finally {
            taskCompleteDeferred = null
        }
    }

    suspend fun awaitTaskFailed(taskName: String, reason: String, stepsCompleted: Int, totalSteps: Int): Boolean {
        showTaskFailed(taskName, reason, stepsCompleted, totalSteps)
        taskFailedDeferred = CompletableDeferred()
        return try {
            taskFailedDeferred!!.await()
        } finally {
            taskFailedDeferred = null
        }
    }

    suspend fun awaitRecoveryDialog(taskName: String, step: Int, totalSteps: Int, interruptedAgo: String): Boolean {
        showRecoveryDialog(taskName, step, totalSteps, interruptedAgo)
        recoveryDeferred = CompletableDeferred()
        return try {
            recoveryDeferred!!.await()
        } finally {
            recoveryDeferred = null
        }
    }

    // ─── SCRIM ───

    private fun showScrim() {
        val view = scrimView ?: return
        scrimParams.alpha = 1f
        scrimParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
        try { windowManager.updateViewLayout(view, scrimParams) } catch (_: IllegalArgumentException) {}
    }

    private fun hideScrim() {
        val view = scrimView ?: return
        scrimParams.alpha = 0f
        scrimParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_TOUCHABLE
        try { windowManager.updateViewLayout(view, scrimParams) } catch (_: IllegalArgumentException) {}
    }

    // ─── GESTURE PASSTHROUGH & SCREENSHOT ───

    /**
     * Hide the overlay window from the compositor so screenshots don't include the Coati UI.
     * Call hideForScreenshot() before taking a screenshot, showAfterScreenshot() after.
     * Requires a ~50ms delay after hiding for the compositor to re-render before capturing.
     */
    fun hideForScreenshot() {
        val view = composeView ?: return
        layoutParams.alpha = 0f
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: IllegalArgumentException) {}
    }

    fun showAfterScreenshot() {
        val view = composeView ?: return
        layoutParams.alpha = 1f
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: IllegalArgumentException) {}
    }

    /**
     * Temporarily make the overlay non-touchable so that dispatchGesture injected events
     * pass through to the target app. Call with enable=true before dispatching, false after.
     */
    fun setGesturePassthrough(enable: Boolean) {
        val view = composeView ?: return
        layoutParams.flags = if (enable) {
            layoutParams.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            layoutParams.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: IllegalArgumentException) {
            // View might have been removed
        }
    }

    // ─── LAYOUT MANAGEMENT ───

    private enum class LayoutMode {
        COLLAPSED,
        EXPANDED,
        FULLSCREEN,
        FULLSCREEN_KEEPON,
        TOP_BAR,
    }

    private fun applyLayout(mode: LayoutMode) {
        val view = composeView ?: return

        when (mode) {
            LayoutMode.COLLAPSED -> {
                layoutParams.width = LayoutParams.WRAP_CONTENT
                layoutParams.height = LayoutParams.WRAP_CONTENT
                layoutParams.gravity = Gravity.TOP or Gravity.START
                layoutParams.x = savedX
                layoutParams.y = savedY
                layoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            LayoutMode.EXPANDED -> {
                // Save position for when we go back to collapsed
                savedX = layoutParams.x
                savedY = layoutParams.y
                // Clamp Y so the panel doesn't extend below the screen (~200dp estimated height)
                val panelHeightPx = (200 * density).toInt()
                val screenHeight = Resources.getSystem().displayMetrics.heightPixels
                layoutParams.y = savedY.coerceAtMost((screenHeight - panelHeightPx).coerceAtLeast(0))
                layoutParams.width = expandedWidthPx
                layoutParams.height = LayoutParams.WRAP_CONTENT
                layoutParams.gravity = Gravity.TOP or Gravity.START
                // Remove NOT_FOCUSABLE to enable keyboard input; NOT_TOUCH_MODAL lets
                // touches outside the panel bounds fall through to the scrim window behind
                layoutParams.flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
            LayoutMode.FULLSCREEN -> {
                savedX = layoutParams.x
                savedY = layoutParams.y
                layoutParams.width = LayoutParams.MATCH_PARENT
                layoutParams.height = LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.TOP or Gravity.START
                layoutParams.x = 0
                layoutParams.y = 0
                layoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            LayoutMode.FULLSCREEN_KEEPON -> {
                savedX = layoutParams.x
                savedY = layoutParams.y
                layoutParams.width = LayoutParams.MATCH_PARENT
                layoutParams.height = LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.TOP or Gravity.START
                layoutParams.x = 0
                layoutParams.y = 0
                layoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_KEEP_SCREEN_ON
            }
            LayoutMode.TOP_BAR -> {
                layoutParams.width = LayoutParams.MATCH_PARENT
                layoutParams.height = LayoutParams.WRAP_CONTENT
                layoutParams.gravity = Gravity.TOP
                layoutParams.x = 0
                layoutParams.y = 0
                // Remove KEEP_SCREEN_ON when paused — user controls the phone
                layoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
        }

        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: IllegalArgumentException) {
            // View might have been removed
        }
    }

    // ─── TOUCH HANDLING ───

    private fun setupTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            // Only handle drag/click on collapsed state
            if (overlayState.value !is OverlayState.CollapsedIdle) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge(view)
                    } else {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View) {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val centerX = layoutParams.x + ballSizePx / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - ballSizePx

        val animator = ValueAnimator.ofInt(layoutParams.x, targetX)
        animator.duration = 200
        animator.addUpdateListener { animation ->
            layoutParams.x = animation.animatedValue as Int
            try {
                windowManager.updateViewLayout(view, layoutParams)
            } catch (_: IllegalArgumentException) {
                // View might have been removed
            }
        }
        animator.start()
    }

    private fun onClick() {
        when (overlayState.value) {
            is OverlayState.CollapsedIdle -> expand()
            is OverlayState.ExpandedIdle -> collapse()
            else -> { /* other states don't respond to ball click */ }
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "CoatiOverlay"
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performRestore(null)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
