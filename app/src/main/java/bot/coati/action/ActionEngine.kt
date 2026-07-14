package bot.coati.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ActionEngine(
    private val a11y: CoatiAccessibilityService,
    private val appResolver: AppResolver = AppResolver(a11y.packageManager),
) {

    suspend fun execute(action: AgentAction): ActionResult {
        Log.i(TAG, "Executing action: $action")
        return try {
            when (action) {
                is AgentAction.Tap -> executeTap(action.x, action.y)
                is AgentAction.LongPress -> executeLongPress(action.x, action.y, action.durationMs)
                is AgentAction.Swipe -> executeSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
                is AgentAction.InputText -> executeInputText(action.text)
                is AgentAction.PressKey -> executePressKey(action.keyCode)
                is AgentAction.Scroll -> executeScroll(action.direction, action.amount)
                is AgentAction.Wait -> executeWait(action.ms)
                is AgentAction.Screenshot -> executeScreenshot()
                is AgentAction.OpenApp -> executeOpenApp(action)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action failed: ${e.message}", e)
            ActionResult(success = false, message = "Action failed: ${e.message}")
        }
    }

    private suspend fun executeTap(x: Int, y: Int): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Tapped ($x, $y)" else "Tap failed")
    }

    private suspend fun executeLongPress(x: Int, y: Int, durationMs: Long): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Long pressed ($x, $y)" else "Long press failed")
    }

    private suspend fun executeSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Swiped ($x1,$y1)->($x2,$y2)" else "Swipe failed")
    }

    private suspend fun executeInputText(text: String): ActionResult {
        val focusedNode = findFocusedNode()
            ?: return ActionResult(success = false, message = "No focused input field found")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
        return ActionResult(success = result, message = if (result) "Input text set" else "Failed to set text")
    }

    private suspend fun executePressKey(keyCode: String): ActionResult {
        val globalAction = when (keyCode.uppercase()) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "POWER_DIALOG" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "ENTER" -> {
                // Simulate Enter key via IME action on focused node
                val node = findFocusedNode()
                if (node != null) {
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                    node.recycle()
                    return ActionResult(success = result, message = "Enter pressed")
                }
                return ActionResult(success = false, message = "No focused node for Enter key")
            }
            else -> return ActionResult(success = false, message = "Unknown key: $keyCode")
        }
        val result = a11y.performGlobalAction(globalAction)
        return ActionResult(success = result, message = if (result) "Pressed $keyCode" else "Key press failed")
    }

    private suspend fun executeScroll(direction: String, amount: Int): ActionResult {
        val displayMetrics = a11y.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val centerY = displayMetrics.heightPixels / 2

        val (x1, y1, x2, y2) = when (direction.uppercase()) {
            "UP" -> listOf(centerX, centerY + amount / 2, centerX, centerY - amount / 2)
            "DOWN" -> listOf(centerX, centerY - amount / 2, centerX, centerY + amount / 2)
            "LEFT" -> listOf(centerX + amount / 2, centerY, centerX - amount / 2, centerY)
            "RIGHT" -> listOf(centerX - amount / 2, centerY, centerX + amount / 2, centerY)
            else -> return ActionResult(success = false, message = "Unknown scroll direction: $direction")
        }

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Scrolled $direction" else "Scroll failed")
    }

    private suspend fun executeWait(ms: Long): ActionResult {
        delay(ms)
        return ActionResult(success = true, message = "Waited ${ms}ms")
    }

    private suspend fun executeScreenshot(): ActionResult {
        val bitmap = a11y.takeScreenshot()
            ?: return ActionResult(success = false, message = "Screenshot failed")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        bitmap.recycle()
        return ActionResult(success = true, message = "Screenshot captured", screenshotBytes = stream.toByteArray())
    }

    private suspend fun executeOpenApp(action: AgentAction.OpenApp): ActionResult {
        val packageName = action.packageName?.trim()?.takeIf { it.isNotBlank() }
        val appName = action.appName?.trim()?.takeIf { it.isNotBlank() }

        if (packageName != null && AppResolver.looksLikePackageName(packageName)) {
            val intent = a11y.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                return launchApp(intent, packageName, appName)
            }
        }

        val query = appName ?: packageName
            ?: return ActionResult(success = false, message = "openApp requires packageName or appName")

        return when (val resolved = appResolver.resolve(query)) {
            is AppResolver.Result.Resolved -> {
                val resolvedPackage = resolved.app.packageName
                val intent = a11y.packageManager.getLaunchIntentForPackage(resolvedPackage)
                    ?: return ActionResult(
                        success = false,
                        message = "Resolved ${resolved.app.label} to $resolvedPackage, but no launch intent was available",
                    )
                launchApp(intent, resolvedPackage, resolved.app.label)
            }
            is AppResolver.Result.Ambiguous -> {
                val candidates = resolved.candidates
                    .take(MAX_APP_CANDIDATES_IN_ERROR)
                    .joinToString { "${it.label} (${it.packageName})" }
                ActionResult(
                    success = false,
                    message = "App name '${resolved.query}' is ambiguous. Candidates: $candidates",
                )
            }
            is AppResolver.Result.NotFound -> {
                val packageFailure = packageName?.let { " Package not found: $it." }.orEmpty()
                ActionResult(
                    success = false,
                    message = "App not found for '${resolved.query}'.$packageFailure Use openApp with appName from the user's request instead of guessing packageName.",
                )
            }
        }
    }

    private suspend fun launchApp(intent: Intent, packageName: String, appName: String?): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Return to home screen before launching to avoid cross-app permission dialogs
        // covering Coati's overlay during app transitions.
        a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(300)
        a11y.startActivity(intent)
        delay(500)
        val label = appName?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return ActionResult(success = true, message = "Opened $packageName$label")
    }

    private suspend fun dispatchGesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGestureAsync(gesture)
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        return a11y.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    companion object {
        private const val TAG = "CoatiAction"
        private const val MAX_APP_CANDIDATES_IN_ERROR = 5
    }
}
