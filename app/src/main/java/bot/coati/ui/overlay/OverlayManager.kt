package bot.coati.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class OverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var composeView: ComposeView? = null
    private val overlayState = mutableStateOf<OverlayState>(OverlayState.Collapsed)

    // Track the "background" agent state for ball color when collapsed
    private var agentBallState = mutableStateOf<OverlayState>(OverlayState.Collapsed)

    var onExecuteTask: ((String) -> Unit)? = null
    var onStopTask: (() -> Unit)? = null
    var onConfirmAction: (() -> Unit)? = null
    var onCancelAction: (() -> Unit)? = null

    private var lastTask: String = ""

    private val density = Resources.getSystem().displayMetrics.density
    private val ballSizePx = (48 * density).toInt()
    private val expandedWidthPx = (296 * density).toInt() // 280 + 16 padding
    private val expandedHeightPx = LayoutParams.WRAP_CONTENT

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
                    state = if (state is OverlayState.Collapsed) {
                        // Pass the agent ball state to CollapsedBall for color
                        state
                    } else {
                        state
                    },
                    onExecute = { task ->
                        lastTask = task
                        onExecuteTask?.invoke(task)
                    },
                    onStop = { onStopTask?.invoke() },
                    onConfirm = { onConfirmAction?.invoke() },
                    onCancel = { onCancelAction?.invoke() },
                    onRetry = {
                        if (lastTask.isNotBlank()) {
                            onExecuteTask?.invoke(lastTask)
                        }
                    },
                    onDismiss = { collapse() },
                    onCollapse = { collapse() },
                )
            }
        }

        setupTouchListener(view)
        windowManager.addView(view, layoutParams)
        composeView = view
    }

    fun hide() {
        composeView?.let {
            windowManager.removeView(it)
            composeView = null
        }
    }

    fun collapse() {
        overlayState.value = OverlayState.Collapsed
        updateLayoutForState(OverlayState.Collapsed)
    }

    fun setExecuting(status: String, progress: Float? = null) {
        agentBallState.value = OverlayState.Executing(status, progress)
        overlayState.value = OverlayState.Executing(status, progress)
        updateLayoutForState(overlayState.value)
    }

    fun showError(message: String) {
        agentBallState.value = OverlayState.Error(message)
        overlayState.value = OverlayState.Error(message)
        updateLayoutForState(overlayState.value)
    }

    fun showConfirmation(title: String, detail: String) {
        agentBallState.value = OverlayState.Confirmation(title, detail)
        overlayState.value = OverlayState.Confirmation(title, detail)
        updateLayoutForState(overlayState.value)
    }

    fun resetBallState() {
        agentBallState.value = OverlayState.Collapsed
    }

    private fun updateLayoutForState(state: OverlayState) {
        val view = composeView ?: return
        when (state) {
            is OverlayState.Collapsed, is OverlayState.Hidden -> {
                layoutParams.width = LayoutParams.WRAP_CONTENT
                layoutParams.height = LayoutParams.WRAP_CONTENT
                layoutParams.flags = layoutParams.flags or LayoutParams.FLAG_NOT_FOCUSABLE
            }
            is OverlayState.Expanded -> {
                layoutParams.width = expandedWidthPx
                layoutParams.height = expandedHeightPx
                // Remove NOT_FOCUSABLE to enable keyboard input
                layoutParams.flags = layoutParams.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            }
            is OverlayState.Executing, is OverlayState.Confirmation, is OverlayState.Error -> {
                layoutParams.width = expandedWidthPx
                layoutParams.height = expandedHeightPx
                layoutParams.flags = layoutParams.flags or LayoutParams.FLAG_NOT_FOCUSABLE
            }
        }
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: IllegalArgumentException) {
            // View might have been removed
        }
    }

    private fun setupTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            // Only handle drag/click on collapsed state
            if (overlayState.value !is OverlayState.Collapsed) return@setOnTouchListener false

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
        overlayState.value = when (overlayState.value) {
            is OverlayState.Collapsed -> OverlayState.Expanded
            is OverlayState.Expanded -> OverlayState.Collapsed
            else -> OverlayState.Collapsed
        }
        updateLayoutForState(overlayState.value)
    }

    companion object {
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
