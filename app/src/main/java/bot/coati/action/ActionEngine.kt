package bot.coati.action

import bot.coati.coordinates.ScreenshotCoordinates
import bot.coati.coordinates.ModelCoordinateAction
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.DirectTextEditMode
import bot.coati.service.DirectTextEditResult
import bot.coati.service.DirectTextEditStatus
import bot.coati.service.EditorInputProfile
import bot.coati.service.allowsEmptySingleLineSetTextFallback
import bot.coati.service.isObservablyEmptyEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ActionEngine(
    private val a11y: CoatiAccessibilityService,
    private val appResolver: AppResolver = AppResolver(a11y.packageManager),
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {

    suspend fun execute(action: AgentAction, coordinates: ScreenshotCoordinates? = null): ActionResult {
        Log.i(TAG, "Executing action: ${action.toLogSummary()}")
        return try {
            when (action) {
                is AgentAction.Tap -> withClickFallback(action.x, action.y, longClick = false) {
                    executeTap(action.x, action.y)
                }.withSliderSnap(action.x, action.y)
                is AgentAction.LongPress -> withClickFallback(action.x, action.y, longClick = true) {
                    executeLongPress(action.x, action.y, action.durationMs)
                }
                is AgentAction.Swipe -> executeSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
                    .withSliderSnap(action.x2, action.y2)
                is AgentAction.InputText -> executeInputText(action.text, action.mode)
                is AgentAction.FocusAndInputText -> executeFocusAndInputText(
                    action.x,
                    action.y,
                    action.text,
                )
                is AgentAction.PressKey -> executePressKey(action.keyCode)
                is AgentAction.Scroll -> executeScroll(action, coordinates)
                is AgentAction.Wait -> executeWait(action.ms)
                is AgentAction.Screenshot -> executeScreenshot()
                is AgentAction.OpenApp -> executeOpenApp(action)
            }
        } catch (e: CancellationException) {
            // Cancellation is loop control, not an action failure. Swallowing it causes retries and
            // misleading "StandaloneCoroutine was cancelled" failure logs after Stop/Pause.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Action failed: ${e.message}", e)
            ActionResult(success = false, message = "Action failed: ${e.message}")
        }
    }

    /**
     * Gestures injected by an accessibility service carry TOOL_TYPE_UNKNOWN, and lists that route touch
     * by tool type ignore them: in the Files app (RecyclerView selection) every tap and long press on a
     * file row did nothing while its toolbar worked, failing every AndroidWorld Files and Browser task.
     * The ignored row still reported TYPE_VIEW_CLICKED and a pressed-state content event, so events
     * cannot tell a handled tap from an ignored one; what the app shows can. When a tap on a list item
     * leaves the app screen unchanged for [REACTION_WAIT_MS], the item's accessibility click is
     * performed instead, the path TalkBack uses and the one such lists act on. A list item that handled
     * the tap opens, selects or updates something, so it is not clicked twice.
     */
    internal suspend fun withClickFallback(x: Int, y: Int, longClick: Boolean, gesture: suspend () -> ActionResult): ActionResult {
        val target = a11y.actionableNodeAt(x, y, longClick)
        val before = target?.let { a11y.appScreenSnapshot() }
        val result = gesture()
        if (!result.success || target == null || before.isNullOrEmpty()) return result
        val deadline = uptimeMillis() + REACTION_WAIT_MS
        while (uptimeMillis() < deadline) {
            delay(REACTION_POLL_MS)
            if (a11y.appScreenSnapshot() != before) return result
        }
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val performed = target.performAction(action)
        Log.i(
            TAG,
            "Screen unchanged after the gesture at ($x, $y); accessibility " +
                "${if (longClick) "long click" else "click"} on ${target.className}: performed=$performed",
        )
        return if (performed) result.copy(message = result.message + " (accessibility click: the gesture changed nothing)") else result
    }

    private suspend fun executeTap(x: Int, y: Int): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Tapped ($x, $y)" else "Tap failed")
    }

    private suspend fun executeLongPress(x: Int, y: Int, durationMs: Long): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Long pressed ($x, $y)" else "Long press failed")
    }

    private suspend fun executeSwipe(rawX1: Int, rawY1: Int, rawX2: Int, rawY2: Int, durationMs: Long): ActionResult {
        val requested = GestureSafeArea.Swipe(rawX1, rawY1, rawX2, rawY2)
        val (x1, y1, x2, y2) = a11y.systemGestureArea()?.let { area ->
            GestureSafeArea.clamp(requested, area.width, area.height, area.insets, area.density)
        } ?: requested
        if (x1 != rawX1 || y1 != rawY1 || x2 != rawX2 || y2 != rawY2) {
            Log.i(TAG, "Swipe moved out of the system gesture area: ($rawX1,$rawY1)->($rawX2,$rawY2) to ($x1,$y1)->($x2,$y2)")
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val result = dispatchGesture(stroke)
        return ActionResult(success = result, message = if (result) "Swiped ($x1,$y1)->($x2,$y2)" else "Swipe failed")
    }

    private suspend fun executeInputText(text: String, mode: InputTextMode): ActionResult {
        if (mode != InputTextMode.REPLACE) {
            val expectedPackage = activeWindowPackageName()
                ?: return ActionResult(
                    success = false,
                    message = "Active app could not be identified; no text was committed",
                    failureCode = ActionFailureCode.TEXT_NO_FOCUS,
                )
            val directMode = when (mode) {
                InputTextMode.TYPE -> DirectTextEditMode.CARET
                InputTextMode.APPEND -> DirectTextEditMode.APPEND
                InputTextMode.REPLACE -> error("replace is handled through the accessibility node")
            }
            Log.i(
                TAG,
                "Text route selected: backend=input_connection, mode=${mode.name.lowercase()}, " +
                    "expectedPackage=$expectedPackage, accessibilityNodeRequired=false",
            )
            val direct = a11y.commitTextThroughInputConnection(
                text = text,
                mode = directMode,
                expectedPackage = expectedPackage,
            )
            if (direct.status == DirectTextEditStatus.DISPATCHED) {
                return direct.toActionResult(mode)
            }
            if (mode == InputTextMode.APPEND || direct.status !in ACCESSIBILITY_FALLBACK_STATUSES) {
                return direct.toActionResult(mode)
            }
            return executeEmptySingleLineTypeFallback(
                text = text,
                direct = direct,
                expectedPackage = expectedPackage,
            )
        }

        return executeWholeFieldReplace(text)
    }

    private fun executeWholeFieldReplace(text: String): ActionResult {
        val focusedNode = findFocusedNode()
            ?: return ActionResult(
                success = false,
                message = "No focused input field found",
                failureCode = ActionFailureCode.TEXT_NO_FOCUS,
            )

        if (!focusedNode.isEditable) {
            focusedNode.recycle()
            return ActionResult(
                success = false,
                message = "Focused node is not editable",
                failureCode = ActionFailureCode.TEXT_NOT_EDITABLE,
            )
        }

        val observablyEmpty = isObservablyEmptyEditor(
            text = focusedNode.text,
            isShowingHintText = focusedNode.isShowingHintText,
        )
        val profile = EditorInputProfile(
            isEditable = focusedNode.isEditable,
            isMultiLine = focusedNode.isMultiLine,
            className = focusedNode.className?.toString(),
            hasWebViewAncestor = hasWebViewAncestor(focusedNode),
            supportsSetText = focusedNode.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            },
            hasDirectInput = a11y.hasActiveInputConnection(),
        )
        Log.i(
            TAG,
            "Text editor evidence: mode=replace, editable=${focusedNode.isEditable}, " +
                "multiline=${focusedNode.isMultiLine}, showingHint=${focusedNode.isShowingHintText}, " +
                "textLength=${focusedNode.text?.length ?: 0}, hintLength=${focusedNode.hintText?.length ?: 0}, " +
                "observablyEmpty=$observablyEmpty, kind=${profile.kind}, " +
                "webViewAncestor=${profile.hasWebViewAncestor}, directInput=${profile.hasDirectInput}",
        )

        val result = if (!profile.allowsWholeFieldReplace || text.contains('\n')) {
            ActionResult(
                success = false,
                message = "Whole-field replacement is allowed only for a confirmed simple " +
                    "single-line field; focus the intended caret and use mode=type",
                failureCode = ActionFailureCode.TEXT_REPLACE_UNSAFE,
            )
        } else {
            replaceNodeText(focusedNode, text)
        }
        focusedNode.recycle()
        return result
    }

    /**
     * Places the caret and commits text as one runtime transaction. This mirrors a
     * human tap-then-type gesture while avoiding a model round trip between focus
     * placement and input. Compound OEM rich editors often expose title and body as
     * visual regions inside the same accessibility node, so focus identity alone is
     * not enough to preserve the intended caret.
     */
    private suspend fun executeFocusAndInputText(x: Int, y: Int, text: String): ActionResult {
        val expectedPackage = a11y.rootInActiveWindow?.let { root ->
            try {
                root.packageName?.toString()
            } finally {
                root.recycle()
            }
        }
        val connectionBaseline = a11y.captureStableConnectionCaret()
        val inputBaseline = a11y.captureInputFocusSnapshot()
        Log.i(
            TAG,
            "Atomic focus baseline: session=${inputBaseline?.sessionId}, " +
                "sequence=${inputBaseline?.selectionSequence}, selection=" +
                "${inputBaseline?.selectionStart}..${inputBaseline?.selectionEnd}, " +
                "connectionSelection=${connectionBaseline?.selection?.relativeStart}.." +
                "${connectionBaseline?.selection?.relativeEnd}, " +
                "completeContext=${connectionBaseline?.hasCompleteDocumentContext}, " +
                "active=${inputBaseline?.inputStarted == true && inputBaseline.hasConnection}",
        )
        val focusProbeId = a11y.beginFocusGestureProbe(x, y, expectedPackage)
        try {
            val tapResult = executeTap(x, y)
            if (!tapResult.success) {
                return ActionResult(
                    success = false,
                    message = "Target focus tap failed; no text was committed",
                    failureCode = ActionFailureCode.TEXT_NO_FOCUS,
                )
            }

            val result = a11y.commitTextAfterFocusGesture(
                text = text,
                baseline = inputBaseline,
                baselineConnection = connectionBaseline,
                expectedPackage = expectedPackage,
                gestureProbeId = focusProbeId,
            ).toActionResult(InputTextMode.TYPE)
            return result
        } finally {
            a11y.finishFocusGestureProbe(focusProbeId)
        }
    }

    private fun executeEmptySingleLineTypeFallback(
        text: String,
        direct: DirectTextEditResult,
        expectedPackage: String,
    ): ActionResult {
        val focusedNode = findFocusedNode() ?: return direct.withFallbackUnavailable(
            "no focused accessibility node",
        ).toActionResult(InputTextMode.TYPE)
        if (focusedNode.packageName?.toString() != expectedPackage) {
            focusedNode.recycle()
            return direct.withFallbackUnavailable(
                "focused accessibility node belongs to a different package",
            ).toActionResult(InputTextMode.TYPE)
        }
        if (!focusedNode.isEditable) {
            focusedNode.recycle()
            return direct.withFallbackUnavailable(
                "focused accessibility node is not editable",
            ).toActionResult(InputTextMode.TYPE)
        }

        val observablyEmpty = isObservablyEmptyEditor(
            text = focusedNode.text,
            isShowingHintText = focusedNode.isShowingHintText,
        )
        val profile = EditorInputProfile(
            isEditable = focusedNode.isEditable,
            isMultiLine = focusedNode.isMultiLine,
            className = focusedNode.className?.toString(),
            hasWebViewAncestor = hasWebViewAncestor(focusedNode),
            supportsSetText = focusedNode.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            },
            hasDirectInput = false,
        )
        Log.i(
            TAG,
            "Direct type backend unavailable; accessibility fallback evidence: " +
                "editable=${focusedNode.isEditable}, multiline=${focusedNode.isMultiLine}, " +
                "showingHint=${focusedNode.isShowingHintText}, " +
                "textLength=${focusedNode.text?.length ?: 0}, kind=${profile.kind}, " +
                "webViewAncestor=${profile.hasWebViewAncestor}",
        )

        val result = if (
            allowsEmptySingleLineSetTextFallback(
                directStatus = direct.status,
                profile = profile,
                observablyEmpty = observablyEmpty,
                textContainsNewline = text.contains('\n'),
            )
        ) {
            replaceNodeText(focusedNode, text, backend = "accessibility_set_text_empty_plain_fallback")
        } else {
            direct.toActionResult(InputTextMode.TYPE)
        }
        focusedNode.recycle()
        return result
    }

    private fun DirectTextEditResult.withFallbackUnavailable(detail: String): DirectTextEditResult = copy(
        reason = "$reason; $detail, so no safe ACTION_SET_TEXT fallback was used",
    )

    private fun replaceNodeText(
        focusedNode: AccessibilityNodeInfo,
        text: String,
        backend: String = "accessibility_set_text",
    ): ActionResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(
            TAG,
            "Text edit dispatched: backend=$backend, mode=replace, " +
                "length=${text.length}, multiline=${text.contains('\n')}",
        )
        return ActionResult(
            success = result,
            message = if (result) {
                "Text set ($backend, replace, length=${text.length})"
            } else {
                "Focused editor rejected whole-field replacement"
            },
            failureCode = if (result) null else ActionFailureCode.TEXT_EDIT_FAILED,
        )
    }

    private fun DirectTextEditResult.toActionResult(mode: InputTextMode): ActionResult {
        val modeLabel = mode.name.lowercase()
        return when (status) {
            DirectTextEditStatus.DISPATCHED -> {
                Log.i(TAG, "Text edit dispatched: backend=input_connection, mode=$modeLabel")
                ActionResult(
                    success = true,
                    message = "Text commit dispatched (input_connection, $modeLabel)",
                )
            }
            DirectTextEditStatus.LEGACY_UNSUPPORTED,
            DirectTextEditStatus.UNAVAILABLE,
            DirectTextEditStatus.PRECONDITION_FAILED,
            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
            DirectTextEditStatus.FAILED,
            -> {
                Log.w(TAG, "Text edit blocked: backend=input_connection, mode=$modeLabel, reason=$reason")
                ActionResult(
                    success = false,
                    message = "Safe $modeLabel blocked: $reason",
                    failureCode = when (status) {
                        DirectTextEditStatus.LEGACY_UNSUPPORTED ->
                            ActionFailureCode.TEXT_LEGACY_UNSUPPORTED
                        DirectTextEditStatus.UNAVAILABLE ->
                            ActionFailureCode.TEXT_DIRECT_INPUT_UNAVAILABLE
                        DirectTextEditStatus.PRECONDITION_FAILED ->
                            ActionFailureCode.TEXT_CARET_NOT_CONFIRMED
                        DirectTextEditStatus.SELECTION_NOT_CONFIRMED ->
                            ActionFailureCode.TEXT_CARET_NOT_CONFIRMED
                        DirectTextEditStatus.FAILED ->
                            ActionFailureCode.TEXT_EDIT_FAILED
                        DirectTextEditStatus.DISPATCHED -> null
                    },
                )
            }
        }
    }

    private suspend fun executePressKey(keyCode: String): ActionResult {
        val globalAction = when (keyCode.uppercase()) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "POWER_DIALOG" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "ENTER" -> {
                val node = findFocusedNode()
                if (node != null) {
                    val isMultiLine = node.isMultiLine
                    val direct = if (isMultiLine) {
                        a11y.commitTextThroughInputConnection(
                            text = "\n",
                            mode = DirectTextEditMode.CARET,
                            expectedPackage = node.packageName,
                        )
                    } else {
                        a11y.performEditorActionOrEnter()
                    }
                    node.recycle()
                    return direct.toActionResult(InputTextMode.TYPE).copy(
                        message = if (direct.status == DirectTextEditStatus.DISPATCHED) {
                            if (isMultiLine) "Newline committed" else direct.reason
                        } else {
                            "Enter blocked: ${direct.reason}"
                        },
                    )
                }
                return ActionResult(
                    success = false,
                    message = "No focused node for Enter key",
                    failureCode = ActionFailureCode.TEXT_NO_FOCUS,
                )
            }
            else -> return ActionResult(success = false, message = "Unknown key: $keyCode")
        }
        val result = a11y.performGlobalAction(globalAction)
        return ActionResult(success = result, message = if (result) "Pressed $keyCode" else "Key press failed")
    }

    private suspend fun executeScroll(action: AgentAction.Scroll, coordinates: ScreenshotCoordinates?): ActionResult {
        // Model callers supply their original screenshot binding. Legacy direct callers capture
        // their own binding once; never substitute current metrics for an existing model binding.
        val bound = coordinates ?: bot.coati.model.ScreenContext.capture(a11y)?.coordinates
            ?: return ActionResult(false, "missing_screenshot_coordinates")
        val swipe = ModelCoordinateAction.scrollGesture(action, bound)
        return bound.dispatch({ bot.coati.coordinates.DisplaySpaceReader.read(a11y) }) {
            executeSwipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, swipe.durationMs)
        }
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

    /** A tap or drag that ended at the end of a slider also sets that slider to its extreme value. */
    internal fun ActionResult.withSliderSnap(x: Int, y: Int): ActionResult {
        if (!success) return this
        val value = a11y.snapSliderAt(x, y) ?: return this
        return copy(message = "$message; slider set to its end value $value")
    }

    private suspend fun launchApp(intent: Intent, packageName: String, appName: String?): ActionResult {
        val label = appName?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        // Relaunching the app already in front only costs a home round trip (AndroidWorld: openApp in
        // Broccoli while Broccoli was open, then a wait on the unchanged screen).
        if (a11y.rootInActiveWindow?.packageName?.toString() == packageName) {
            return ActionResult(
                success = true,
                message = "$packageName$label is already open; continue within it",
                noOp = true,
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Return to home screen before launching to avoid cross-app permission dialogs
        // covering Coati's overlay during app transitions.
        a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(300)
        a11y.startActivity(intent)
        delay(500)
        awaitAppRendered(packageName)
        return ActionResult(success = true, message = "Opened $packageName$label")
    }

    /**
     * Waits until the launched app's window has content. 74 of 195 AndroidWorld app launches were
     * observed as a black or blank screen 500 ms after the intent; the model then spent steps on
     * waits that verification judged FAILED.
     */
    private suspend fun awaitAppRendered(packageName: String) {
        val deadline = uptimeMillis() + APP_RENDER_TIMEOUT_MS
        while (uptimeMillis() < deadline) {
            val root = a11y.rootInActiveWindow
            if (root?.packageName?.toString() == packageName && root.childCount > 0) {
                val tree = a11y.getUiTree()
                if (tree != null && tree.flatCount() >= MIN_RENDERED_NODES) {
                    // The tree is published before the first frame is drawn.
                    delay(APP_RENDER_SETTLE_MS)
                    return
                }
            }
            delay(APP_RENDER_POLL_MS)
        }
    }

    private suspend fun dispatchGesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGestureAsync(gesture)
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = a11y.rootInActiveWindow ?: return null
        return try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
    }

    private fun activeWindowPackageName(): String? {
        val root = a11y.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            root.recycle()
        }
    }

    private fun hasWebViewAncestor(node: AccessibilityNodeInfo): Boolean {
        var ancestor = node.parent
        while (ancestor != null) {
            val isWebView = ancestor.className?.toString() == "android.webkit.WebView"
            val next = if (isWebView) null else ancestor.parent
            ancestor.recycle()
            if (isWebView) return true
            ancestor = next
        }
        return false
    }

    companion object {
        private const val TAG = "CoatiAction"
        private const val APP_RENDER_TIMEOUT_MS = 4_000L
        private const val APP_RENDER_POLL_MS = 200L
        private const val APP_RENDER_SETTLE_MS = 300L
        private const val MIN_RENDERED_NODES = 5

        /**
         * A tap is a short press, like adb's `input tap`. Some views drop a longer press: right after
         * Joplin starts, its add button ignored every first press of 50 ms or more (100 ms included)
         * and accepted presses of 1-30 ms in 3 of 3 cold starts.
         */
        private const val TAP_DURATION_MS = 20L

        /** A handled tap on a list item changes what the app shows well within this. */
        private const val REACTION_WAIT_MS = 800L
        private const val REACTION_POLL_MS = 200L
        private const val MAX_APP_CANDIDATES_IN_ERROR = 5
        private val ACCESSIBILITY_FALLBACK_STATUSES = setOf(
            DirectTextEditStatus.LEGACY_UNSUPPORTED,
            DirectTextEditStatus.UNAVAILABLE,
        )
    }
}
