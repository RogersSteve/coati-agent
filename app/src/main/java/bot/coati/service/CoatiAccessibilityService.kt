package bot.coati.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CoatiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for Phase 1
    }

    override fun onInterrupt() {
        // No-op for Phase 1
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        screenshot.hardwareBuffer.close()
                        if (continuation.isActive) {
                            continuation.resume(bitmap)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed with error: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                },
            )
        }
    }

    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture cancelled")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                },
                null,
            )
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture returned false")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    fun getUiTree(): UiTreeNode? {
        val root = rootInActiveWindow ?: return null
        return buildTree(root).also {
            root.recycle()
        }
    }

    private fun buildTree(node: AccessibilityNodeInfo): UiTreeNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = mutableListOf<UiTreeNode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(buildTree(child))
                child.recycle()
            }
        }

        return UiTreeNode(
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = bounds,
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            viewIdResourceName = node.viewIdResourceName,
            children = children,
        )
    }

    companion object {
        private const val TAG = "CoatiA11y"

        @Volatile
        var instance: CoatiAccessibilityService? = null
            private set
    }
}
