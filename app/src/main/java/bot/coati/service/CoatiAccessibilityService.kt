package bot.coati.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.text.Spannable
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowInsets
import android.view.WindowManager
import bot.coati.coordinates.PixelBounds
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class CoatiAccessibilityService : AccessibilityService() {

    var onEmergencyStop: (() -> Unit)? = null

    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private val textEditMutex = Mutex()
    private val focusGestureProbeLock = Any()
    private var nextFocusGestureProbeId = 0L
    private var activeFocusGestureProbe: FocusGestureProbe? = null

    @Volatile
    private var trackingInputMethod: TrackingInputMethod? = null

    private class FocusGestureProbe(
        val id: Long,
        val startedAtMillis: Long,
        val targetX: Int,
        val targetY: Int,
        val expectedPackage: String?,
        var targetSelectionChanged: Boolean = false,
        var targetEditableClicked: Boolean = false,
    )

    private data class ConnectionCaretRead(
        val selection: ConnectionSelectionEvidence,
        val surroundingTextLength: Int,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        return TrackingInputMethod(this).also { trackingInputMethod = it }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        onConnected?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundWindowChanged?.invoke(
                event.packageName?.toString(),
                event.className?.toString(),
            )
        }

        if (!isFocusProbeEvent(event.eventType)) return

        val probe = synchronized(focusGestureProbeLock) {
            activeFocusGestureProbe
        } ?: return
        val packageName = event.packageName?.toString()
        if (event.eventTime < probe.startedAtMillis) return
        if (probe.expectedPackage != null && packageName != probe.expectedPackage) return

        val source = try {
            event.source
        } catch (_: RuntimeException) {
            null
        }
        try {
            val bounds = Rect()
            source?.getBoundsInScreen(bounds)
            val containsTarget = source != null && bounds.contains(probe.targetX, probe.targetY)
            val targetSelectionChanged = containsTarget &&
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            val targetEditableClicked = containsTarget &&
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                source?.isEditable == true && source.isFocused
            synchronized(focusGestureProbeLock) {
                activeFocusGestureProbe?.takeIf { it.id == probe.id }?.let { active ->
                    active.targetSelectionChanged = active.targetSelectionChanged || targetSelectionChanged
                    active.targetEditableClicked = active.targetEditableClicked || targetEditableClicked
                }
            }
            if (targetSelectionChanged || targetEditableClicked) {
                Log.i(
                    TAG,
                    "Atomic target event: probe=${probe.id}, " +
                        "kind=${if (targetSelectionChanged) "selection" else "editableClick"}, " +
                        "eventDeltaMs=${event.eventTime - probe.startedAtMillis}, " +
                        "sourceClass=${source?.className}, bounds=${bounds.toShortString()}",
                )
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Focus gesture event metadata unavailable: probe=${probe.id}, ${e.message}")
        } finally {
            source?.recycle()
        }
    }

    /**
     * What the app windows currently show: each window's identity and the class, text, state and bounds
     * of its visible nodes. A press highlight leaves it unchanged; navigation, a new window, a selection
     * or updated content changes it. Coati's own windows, keyboards and system bars are left out.
     */
    fun appScreenSnapshot(): String {
        val out = StringBuilder()
        val bounds = Rect()
        val windowList = try {
            windows
        } catch (e: RuntimeException) {
            return ""
        }
        for (window in windowList.sortedBy { it.id }) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == packageName) continue
            out.append("#").append(window.id).append('\n')
            var budget = MAX_SNAPSHOT_NODES
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (stack.isNotEmpty() && budget-- > 0) {
                val node = stack.removeLast()
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(bounds)
                out.append(node.className).append('|').append(node.text).append('|')
                    .append(node.contentDescription).append('|').append(node.isChecked).append(node.isSelected)
                    .append('|').append(bounds.toShortString()).append('\n')
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            }
        }
        return out.toString()
    }

    /**
     * The list item a tap (or, with [longClick], a long press) at a screen point would activate: the
     * deepest visible node under the point in the topmost app window, or its nearest ancestor that offers
     * the click action, when that node is an item of a list or grid. Other controls return null. Coati's own windows and accessibility overlays are skipped, and a keyboard returns
     * null: a key press reports its text change from the editing app, never from the keyboard itself,
     * so a fallback click there would type the key twice.
     */
    fun actionableNodeAt(x: Int, y: Int, longClick: Boolean): AccessibilityNodeInfo? {
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var current: AccessibilityNodeInfo? = deepestNodeAt(x, y) ?: return null
        while (current != null) {
            if (current.isEnabled && current.actionList.any { it.id == action }) {
                return current.takeIf { it.collectionItemInfo != null || it.parent?.collectionInfo != null }
            }
            current = current.parent
        }
        return null
    }

    /**
     * Sets the slider under (x, y) to its minimum or maximum when the point lies within the outer 5%
     * of its track, and returns the value set; null when there is no such slider. A tap or drag to
     * the end of a slider landed a few pixels short: AndroidWorld's "brightness to maximum" ended at
     * a displayed 100% that was not the maximum value.
     */
    fun snapSliderAt(x: Int, y: Int): Float? {
        val bounds = Rect()
        val slack = (SLIDER_SLACK_DP * resources.displayMetrics.density).toInt()
        val slider = sliderNear(x, y, slack) ?: run {
            Log.d(TAG, "No slider at ($x, $y)")
            return null
        }
        val range = slider.rangeInfo ?: return null
        slider.getBoundsInScreen(bounds)
        val value = sliderEndValue(bounds.left, bounds.top, bounds.right, bounds.bottom, x, y, range.min, range.max)
            ?: return null
        val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        if (slider.actionList.any { it.id == setProgress }) {
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            if (slider.performAction(setProgress, args)) return value
        }
        // Sliders without SET_PROGRESS still step with the scroll actions TalkBack uses.
        val step = if (value == range.max) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        repeat(MAX_SLIDER_SCROLL_STEPS) {
            slider.refresh()
            if (slider.rangeInfo?.current == value) return value
            if (!slider.performAction(step)) {
                Log.i(TAG, "Slider at ($x, $y) stopped stepping toward $value at ${slider.rangeInfo?.current}")
                return null
            }
        }
        slider.refresh()
        return value.takeIf { slider.rangeInfo?.current == value }
    }

    /**
     * The smallest visible range node (slider, seek bar) of the topmost app or system window under
     * (x, y) whose bounds, widened by [slack] pixels, contain the point. The whole window is searched:
     * in the Quick Settings shade the deepest node under a tap at the end of the brightness slider is
     * not inside the slider, so walking up from it never found one (0 of 6 runs snapped there), and a
     * tap at the very end of a track often lands just past its bounds.
     */
    private fun sliderNear(x: Int, y: Int, slack: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        val candidates = try {
            windows.sortedByDescending { it.layer }.filter { window ->
                window.getBoundsInScreen(bounds)
                window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    bounds.contains(x, y) && window.root?.packageName?.toString() != packageName
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Window list unavailable: ${e.message}")
            return null
        }
        for (window in candidates) {
            val root = window.root ?: continue
            var best: AccessibilityNodeInfo? = null
            var bestArea = Long.MAX_VALUE
            var budget = MAX_SNAPSHOT_NODES
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (stack.isNotEmpty() && budget-- > 0) {
                val node = stack.removeLast()
                if (!node.isVisibleToUser) continue
                if (node.rangeInfo != null) {
                    node.getBoundsInScreen(bounds)
                    val area = bounds.width().toLong() * bounds.height()
                    if (area in 1 until bestArea && sliderReaches(bounds.left, bounds.top, bounds.right, bounds.bottom, x, y, slack)) {
                        best = node
                        bestArea = area
                    }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            }
            if (best != null) return best
        }
        return null
    }

    /** The deepest visible node of the topmost app window under (x, y); never Coati's own windows or the keyboard. */
    private fun deepestNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        val window = try {
            windows.sortedByDescending { it.layer }.firstOrNull { window ->
                window.getBoundsInScreen(bounds)
                window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    bounds.contains(x, y) && window.root?.packageName?.toString() != packageName
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Window list unavailable: ${e.message}")
            null
        } ?: return null
        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        var node = window.root ?: return null
        while (true) {
            val child = (0 until node.childCount).asSequence().mapNotNull { node.getChild(it) }
                .lastOrNull { child ->
                    child.getBoundsInScreen(bounds)
                    child.isVisibleToUser && bounds.contains(x, y)
                } ?: break
            node = child
        }
        return node
    }

    /** Largest direction-matching container in the top App window, excluding overlays and IME. */
    fun scrollableContainerBounds(vertical: Boolean): PixelBounds? = ScrollAccessibility(this).containerBounds(vertical)

    internal fun prepareScroll(swipe: bot.coati.action.AgentAction.Swipe): bot.coati.action.ScrollSession? =
        ScrollAccessibility(this).session(swipe)

    internal suspend fun scrollEvidence(session: bot.coati.action.ScrollSession): bot.coati.action.ScrollEvidence? =
        ScrollAccessibility(this).evidence(session)

    internal fun performSemanticScroll(session: bot.coati.action.ScrollSession): Boolean? =
        ScrollAccessibility(this).perform(session)

    /** Display size, density and the system navigation gesture insets (bottom bar, side back edges). */
    data class GestureArea(val width: Int, val height: Int, val density: Float, val insets: PixelBounds)

    fun systemGestureArea(): GestureArea? = try {
        val metrics = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
        val i = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemGestures() or WindowInsets.Type.mandatorySystemGestures() or
                WindowInsets.Type.navigationBars(),
        )
        GestureArea(
            metrics.bounds.width(), metrics.bounds.height(), resources.displayMetrics.density,
            PixelBounds(i.left, i.top, i.right, i.bottom),
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "System gesture area unavailable: ${e.message}")
        null
    }

    override fun onInterrupt() {
        // No-op for Phase 1
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpPressed = event.action == KeyEvent.ACTION_DOWN
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressed = event.action == KeyEvent.ACTION_DOWN
            }
        }

        if (volumeUpPressed && volumeDownPressed) {
            Log.i(TAG, "Emergency stop: volume up + down pressed simultaneously")
            volumeUpPressed = false
            volumeDownPressed = false
            onEmergencyStop?.invoke()
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        onEmergencyStop = null
        synchronized(focusGestureProbeLock) {
            activeFocusGestureProbe = null
        }
        trackingInputMethod = null
        instance = null
        super.onDestroy()
    }

    suspend fun commitTextThroughInputConnection(
        text: CharSequence,
        mode: DirectTextEditMode,
        expectedPackage: CharSequence? = null,
    ): DirectTextEditResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DirectTextEditResult(
                DirectTextEditStatus.LEGACY_UNSUPPORTED,
                "Direct text editing requires Android 13 or newer",
            )
        }

        return textEditMutex.withLock {
            commitTextThroughInputConnectionApi33(text, mode, expectedPackage)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun commitTextThroughInputConnectionApi33(
        text: CharSequence,
        mode: DirectTextEditMode,
        expectedPackage: CharSequence?,
    ): DirectTextEditResult {
        return try {
            val editor = trackingInputMethod
                ?: return DirectTextEditResult(
                    DirectTextEditStatus.UNAVAILABLE,
                    "Accessibility input method is not active",
                )
            if (!editor.currentInputStarted) {
                return DirectTextEditResult(
                    DirectTextEditStatus.UNAVAILABLE,
                    "Focused editor has not started an input session",
                )
            }
            val connection = editor.currentInputConnection
                ?: return DirectTextEditResult(
                    DirectTextEditStatus.UNAVAILABLE,
                    "Focused editor did not provide an InputConnection",
                )
            val actualPackage = editor.currentInputEditorInfo?.packageName
            if (expectedPackage != null && actualPackage?.toString() != expectedPackage.toString()) {
                return DirectTextEditResult(
                    DirectTextEditStatus.PRECONDITION_FAILED,
                    "InputConnection belongs to a different package; no text was committed",
                )
            }
            val sessionId = editor.currentSessionId()

            when (mode) {
                DirectTextEditMode.REPLACE_ALL -> {
                    val baseline = editor.currentSelection()
                    connection.performContextMenuAction(android.R.id.selectAll)
                    val selected = awaitSelectionAfter(
                        editor = editor,
                        sessionId = sessionId,
                        sequence = baseline?.sequence ?: -1L,
                    ) ?: baseline
                    val documentEmpty = readConnectionCaret(connection)?.surroundingTextLength == 0
                    if (!confirmsSelectAll(selected, sessionId, documentEmpty)) {
                        return DirectTextEditResult(
                            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                            "Editor did not confirm select-all (selection=${selected?.selectionStart}.." +
                                "${selected?.selectionEnd}); no text was committed",
                        )
                    }
                }
                DirectTextEditMode.CARET -> {
                    val selection = confirmStableCollapsedSelection(editor, connection)
                        ?: return DirectTextEditResult(
                            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                            "Current collapsed caret could not be verified; no text was committed",
                        )
                    if (selection.hasComposingRegion) {
                        return DirectTextEditResult(
                            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                            "Editor has active composing text; no text was committed",
                        )
                    }
                }
                DirectTextEditMode.APPEND -> {
                    val baseline = editor.currentSelection()
                    sendCtrlMove(connection, KeyEvent.KEYCODE_MOVE_HOME)
                    val home = awaitSelectionAfter(
                        editor = editor,
                        sessionId = sessionId,
                        sequence = baseline?.sequence ?: -1L,
                    ) ?: baseline?.takeIf {
                        it.sessionId == sessionId && it.isCollapsed && it.selectionStart == 0
                    } ?: return DirectTextEditResult(
                        DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                        "Editor did not confirm Ctrl+Home; no text was committed",
                    )

                    sendCtrlMove(connection, KeyEvent.KEYCODE_MOVE_END)
                    val end = awaitSelectionAfter(
                        editor = editor,
                        sessionId = sessionId,
                        sequence = home.sequence,
                    ) ?: return DirectTextEditResult(
                        DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                        "Editor did not confirm Ctrl+End; no text was committed",
                    )

                    if (!confirmsDocumentBounds(home, end)) {
                        return DirectTextEditResult(
                            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                            "Editor did not expose stable document bounds " +
                                "(home=${home.selectionStart}..${home.selectionEnd}, " +
                                "end=${end.selectionStart}..${end.selectionEnd}); no text was committed",
                        )
                    }
                }
            }

            if (
                !editor.currentInputStarted ||
                editor.currentSessionId() != sessionId ||
                editor.currentInputConnection == null ||
                editor.currentInputEditorInfo?.packageName != actualPackage
            ) {
                return DirectTextEditResult(
                    DirectTextEditStatus.PRECONDITION_FAILED,
                    "Focused editor changed before commit; no text was committed",
                )
            }

            connection.commitText(text, 1, null)
            Log.i(
                TAG,
                "Text commit dispatched: mode=${mode.name.lowercase()}, length=${text.length}, " +
                    "multiline=${text.contains('\n')}",
            )
            DirectTextEditResult(
                DirectTextEditStatus.DISPATCHED,
                "Text commit dispatched",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Direct text edit failed: ${e.message}")
            DirectTextEditResult(
                DirectTextEditStatus.FAILED,
                e.message ?: "InputConnection edit failed",
            )
        }
    }

    suspend fun performEditorActionOrEnter(): DirectTextEditResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DirectTextEditResult(
                DirectTextEditStatus.LEGACY_UNSUPPORTED,
                "Editor actions require Android 13 or newer",
            )
        }

        return textEditMutex.withLock {
            performEditorActionOrEnterApi33()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun performEditorActionOrEnterApi33(): DirectTextEditResult {
        return try {
            val editor = trackingInputMethod
                ?: return DirectTextEditResult(
                    DirectTextEditStatus.UNAVAILABLE,
                    "Accessibility input method is not active",
                )
            val connection = editor.currentInputConnection
                ?: return DirectTextEditResult(
                    DirectTextEditStatus.UNAVAILABLE,
                    "Focused editor did not provide an InputConnection",
                )
            val editorInfo = editor.currentInputEditorInfo
            val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_UNSPECIFIED
            if (action in EDITOR_ACTIONS) {
                connection.performEditorAction(action)
            } else {
                sendKey(connection, KeyEvent.KEYCODE_ENTER, metaState = 0)
            }
            DirectTextEditResult(
                DirectTextEditStatus.DISPATCHED,
                if (action in EDITOR_ACTIONS) "Editor action $action dispatched" else "Enter key dispatched",
            )
        } catch (e: RuntimeException) {
            DirectTextEditResult(
                DirectTextEditStatus.FAILED,
                e.message ?: "Editor action failed",
            )
        }
    }

    fun hasActiveInputConnection(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            trackingInputMethod?.currentInputStarted == true &&
                trackingInputMethod?.currentInputConnection != null
        } catch (_: RuntimeException) {
            false
        }
    }

    fun currentInputSelectionSummary(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "unavailable"
        return try {
            val editor = trackingInputMethod ?: return "inputMethodMissing"
            val selection = editor.currentSelection()
            when {
                !editor.currentInputStarted -> "sessionInactive"
                editor.currentInputConnection == null -> "connectionMissing"
                selection == null -> "unknown"
                !selection.isKnown -> "unknown"
                else -> "${selection.selectionStart}..${selection.selectionEnd};" +
                    "collapsed=${selection.isCollapsed}"
            }
        } catch (_: RuntimeException) {
            "unknown"
        }
    }

    fun captureInputFocusSnapshot(): InputFocusSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            trackingInputMethod?.let(::inputFocusSnapshot)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun currentInputSessionId(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            trackingInputMethod?.currentSessionId()
        } catch (_: RuntimeException) {
            null
        }
    }

    fun beginFocusGestureProbe(
        targetX: Int,
        targetY: Int,
        expectedPackage: String?,
    ): Long {
        val probe = synchronized(focusGestureProbeLock) {
            FocusGestureProbe(
                id = ++nextFocusGestureProbeId,
                startedAtMillis = SystemClock.uptimeMillis(),
                targetX = targetX,
                targetY = targetY,
                expectedPackage = expectedPackage,
            ).also { activeFocusGestureProbe = it }
        }
        Log.i(
            TAG,
            "Focus gesture probe started: probe=${probe.id}, target=($targetX,$targetY), " +
                "expectedPackage=$expectedPackage",
        )
        return probe.id
    }

    fun finishFocusGestureProbe(probeId: Long) {
        val probe = synchronized(focusGestureProbeLock) {
            activeFocusGestureProbe?.takeIf { it.id == probeId }?.also {
                activeFocusGestureProbe = null
            }
        } ?: return
        Log.i(
            TAG,
            "Focus gesture probe finished: probe=$probeId, " +
                "targetSelection=${probe.targetSelectionChanged}, " +
                "targetEditableClick=${probe.targetEditableClicked}",
        )
    }

    fun focusGestureEventEvidence(probeId: Long): FocusGestureEventEvidence {
        return synchronized(focusGestureProbeLock) {
            activeFocusGestureProbe?.takeIf { it.id == probeId }?.let {
                FocusGestureEventEvidence(
                    targetSelectionChanged = it.targetSelectionChanged,
                    targetEditableClicked = it.targetEditableClicked,
                )
            }
        } ?: FocusGestureEventEvidence(
            targetSelectionChanged = false,
            targetEditableClicked = false,
        )
    }

    suspend fun captureStableConnectionCaret(): ConnectionCaretSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val editor = trackingInputMethod ?: return null
            if (!editor.currentInputStarted) return null
            val connection = editor.currentInputConnection ?: return null
            confirmStableConnectionCaretSnapshot(editor, connection)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Commits only after the focus gesture produces fresh editor evidence. The normal path trusts
     * a new selection callback. OEM rich editors that keep that callback stale must instead show
     * a stable InputConnection caret move and both target-bound selection and editable-click
     * events; a successful gesture by itself never authorizes text input.
     */
    suspend fun commitTextAfterFocusGesture(
        text: CharSequence,
        baseline: InputFocusSnapshot?,
        baselineConnection: ConnectionCaretSnapshot?,
        expectedPackage: CharSequence?,
        gestureProbeId: Long,
    ): DirectTextEditResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DirectTextEditResult(
                DirectTextEditStatus.LEGACY_UNSUPPORTED,
                "Atomic caret input requires Android 13 or newer",
            )
        }
        return textEditMutex.withLock {
            commitTextAfterFocusGestureApi33(
                text = text,
                baseline = baseline,
                baselineConnection = baselineConnection,
                expectedPackage = expectedPackage?.toString(),
                gestureProbeId = gestureProbeId,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun commitTextAfterFocusGestureApi33(
        text: CharSequence,
        baseline: InputFocusSnapshot?,
        baselineConnection: ConnectionCaretSnapshot?,
        expectedPackage: String?,
        gestureProbeId: Long,
    ): DirectTextEditResult {
        val deadline = SystemClock.uptimeMillis() + FOCUS_EVIDENCE_TIMEOUT_MS
        var lastSnapshot: InputFocusSnapshot? = null
        var lastConnection: ConnectionCaretSnapshot? = null
        var connectionEvidenceReads = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val editor = trackingInputMethod
            val current = editor?.let(::inputFocusSnapshot)
            lastSnapshot = current
            val callbackEvidence = classifyAtomicFocusEvidence(
                baseline = baseline,
                current = current,
                expectedPackage = expectedPackage,
                baselineConnection = baselineConnection,
                currentConnection = null,
                gestureEvents = focusGestureEventEvidence(gestureProbeId),
            )
            if (callbackEvidence == AtomicFocusEvidenceKind.CALLBACK) {
                Log.i(
                    TAG,
                    "Atomic focus evidence ready: kind=$callbackEvidence, session=${current!!.sessionId}, " +
                        "sequence=${current.selectionSequence}, selection=" +
                        "${current.selectionStart}..${current.selectionEnd}, packageMatches=true",
                )
                val committed = commitTextThroughInputConnectionApi33(
                    text = text,
                    mode = DirectTextEditMode.CARET,
                    expectedPackage = expectedPackage,
                )
                if (committed.status != DirectTextEditStatus.PRECONDITION_FAILED) return committed
                // Some editors restart their input session right after gaining focus (board: session
                // 7 -> 8 within 16 ms of the focus callback). Nothing was committed; once the restarted
                // session is live for the same app, the caret is still in the field just focused.
                delay(SESSION_RESTART_SETTLE_MS)
                val restarted = trackingInputMethod
                if (
                    restarted == null || !restarted.currentInputStarted ||
                    restarted.currentInputConnection == null ||
                    restarted.currentInputEditorInfo?.packageName != expectedPackage
                ) {
                    return committed
                }
                Log.i(TAG, "Input session restarted after focus; committing into session ${restarted.currentSessionId()}")
                return commitTextThroughInputConnectionApi33(
                    text = text,
                    mode = DirectTextEditMode.CARET,
                    expectedPackage = expectedPackage,
                )
            }

            val gestureEvents = focusGestureEventEvidence(gestureProbeId)
            if (
                baselineConnection != null &&
                gestureEvents.confirmsTargetInteraction &&
                connectionEvidenceReads < MAX_ATOMIC_CONNECTION_EVIDENCE_READS
            ) {
                connectionEvidenceReads += 1
                val currentConnection = captureStableConnectionCaret()
                lastConnection = currentConnection
                val refreshedCurrent = trackingInputMethod?.let(::inputFocusSnapshot)
                lastSnapshot = refreshedCurrent
                val connectionEvidence = classifyAtomicFocusEvidence(
                    baseline = baseline,
                    current = refreshedCurrent,
                    expectedPackage = expectedPackage,
                    baselineConnection = baselineConnection,
                    currentConnection = currentConnection,
                    gestureEvents = gestureEvents,
                )
                if (connectionEvidence != null) {
                    Log.i(
                        TAG,
                        "Atomic focus evidence ready: kind=$connectionEvidence, session=" +
                            "${refreshedCurrent?.sessionId}, connectionSelection=" +
                            "${baselineConnection.selection.relativeStart}.." +
                            "${baselineConnection.selection.relativeEnd}->" +
                            "${currentConnection?.selection?.relativeStart}.." +
                            "${currentConnection?.selection?.relativeEnd}, " +
                            "targetSelection=${gestureEvents.targetSelectionChanged}, " +
                            "targetEditableClick=${gestureEvents.targetEditableClicked}",
                    )
                    return commitTextThroughInputConnectionApi33(
                        text = text,
                        mode = DirectTextEditMode.CARET,
                        expectedPackage = expectedPackage,
                    )
                }
            }
            delay(FOCUS_EVIDENCE_POLL_MS)
        }

        val gestureEvents = focusGestureEventEvidence(gestureProbeId)
        val state = lastSnapshot?.let {
            "session=${it.sessionId}, sequence=${it.selectionSequence}, " +
                "selection=${it.selectionStart}..${it.selectionEnd}, " +
                "inputStarted=${it.inputStarted}, connection=${it.hasConnection}, " +
                "packageMatches=${expectedPackage == null || it.packageName == expectedPackage}, " +
                "connectionSelection=${lastConnection?.selection?.relativeStart}.." +
                "${lastConnection?.selection?.relativeEnd}, " +
                "targetSelection=${gestureEvents.targetSelectionChanged}, " +
                "targetEditableClick=${gestureEvents.targetEditableClicked}"
        } ?: "none"
        return DirectTextEditResult(
            DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
            "Focus gesture produced no fresh collapsed-caret evidence ($state); no text was committed",
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun inputFocusSnapshot(editor: TrackingInputMethod): InputFocusSnapshot {
        val selection = editor.currentSelection()
        return InputFocusSnapshot(
            sessionId = editor.currentSessionId(),
            selectionSequence = selection?.sequence ?: -1L,
            selectionStart = selection?.selectionStart ?: -1,
            selectionEnd = selection?.selectionEnd ?: -1,
            packageName = editor.currentInputEditorInfo?.packageName,
            inputStarted = editor.currentInputStarted,
            hasConnection = editor.currentInputConnection != null,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun confirmStableCollapsedSelection(
        editor: TrackingInputMethod,
        connection: InputMethod.AccessibilityInputConnection,
    ): ConnectionSelectionEvidence? {
        val first = readConnectionSelection(connection)
        val firstCallback = editor.currentSelection()
        delay(SELECTION_STABILITY_INTERVAL_MS)
        val second = readConnectionSelection(connection)
        val secondCallback = editor.currentSelection()

        return confirmStableConnectionCaret(
            first = first,
            second = second,
            callback = secondCallback,
            currentSessionId = editor.currentSessionId(),
        ) ?: if (first == null && second == null) {
            confirmStableCallbackCaret(
                first = firstCallback,
                second = secondCallback,
                currentSessionId = editor.currentSessionId(),
            )
        } else {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun confirmStableConnectionCaretSnapshot(
        editor: TrackingInputMethod,
        connection: InputMethod.AccessibilityInputConnection,
    ): ConnectionCaretSnapshot? {
        val initialSessionId = editor.currentSessionId()
        val first = readConnectionCaret(connection) ?: return null
        delay(SELECTION_STABILITY_INTERVAL_MS)
        val second = readConnectionCaret(connection) ?: return null
        val currentSessionId = editor.currentSessionId()
        if (currentSessionId != initialSessionId) return null
        val confirmed = confirmStableConnectionCaret(
            first = first.selection,
            second = second.selection,
            callback = editor.currentSelection(),
            currentSessionId = currentSessionId,
        ) ?: return null
        return ConnectionCaretSnapshot(
            sessionId = currentSessionId,
            selection = confirmed,
            surroundingTextLength = second.surroundingTextLength,
            requestedBeforeLength = SELECTION_CONTEXT_CHARS,
            requestedAfterLength = SELECTION_CONTEXT_CHARS,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun readConnectionSelection(
        connection: InputMethod.AccessibilityInputConnection,
    ): ConnectionSelectionEvidence? = readConnectionCaret(connection)?.selection

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun readConnectionCaret(
        connection: InputMethod.AccessibilityInputConnection,
    ): ConnectionCaretRead? {
        return withContext(Dispatchers.IO) {
            val styled = connection.getSurroundingText(
                SELECTION_CONTEXT_CHARS,
                SELECTION_CONTEXT_CHARS,
                InputConnection.GET_TEXT_WITH_STYLES,
            )
            val surrounding = styled ?: connection.getSurroundingText(
                SELECTION_CONTEXT_CHARS,
                SELECTION_CONTEXT_CHARS,
                0,
            ) ?: return@withContext null
            val selection = connectionSelectionEvidence(
                offset = surrounding.offset,
                selectionStart = surrounding.selectionStart,
                selectionEnd = surrounding.selectionEnd,
                hasComposingRegion = styled?.text?.let(::hasComposingRegion) == true,
            ) ?: return@withContext null
            ConnectionCaretRead(
                selection = selection,
                surroundingTextLength = surrounding.text.length,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun awaitSelectionAfter(
        editor: TrackingInputMethod,
        sessionId: Long,
        sequence: Long,
    ): EditorSelectionState? {
        val deadline = SystemClock.uptimeMillis() + SELECTION_ACK_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val current = editor.currentSelection()
            if (current != null && current.sessionId == sessionId && current.sequence > sequence) {
                return current
            }
            delay(SELECTION_POLL_INTERVAL_MS)
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun sendCtrlMove(
        connection: InputMethod.AccessibilityInputConnection,
        keyCode: Int,
    ) {
        sendKey(connection, keyCode, KeyEvent.META_CTRL_ON)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun sendKey(
        connection: InputMethod.AccessibilityInputConnection,
        keyCode: Int,
        metaState: Int,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val commonFlags = KeyEvent.FLAG_SOFT_KEYBOARD
        connection.sendKeyEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                commonFlags,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
        connection.sendKeyEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                commonFlags,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        // The platform occasionally never calls back (observed on emulators after many captures);
        // without a bound the agent loop would hang forever instead of retrying.
        var completed = false
        val bitmap = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { awaitScreenshot().also { completed = true } }
        if (!completed) Log.w(TAG, "Screenshot timed out after ${SCREENSHOT_TIMEOUT_MS}ms")
        return bitmap
    }

    private suspend fun awaitScreenshot(): Bitmap? {
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
            hintText = node.hintText?.toString(),
            bounds = bounds,
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isFocused = node.isFocused,
            isMultiLine = node.isMultiLine,
            isShowingHintText = node.isShowingHintText,
            viewIdResourceName = node.viewIdResourceName,
            actions = node.actionList.mapNotNull(::actionName).distinct(),
            children = children,
            isLongClickable = node.isLongClickable,
            isChecked = node.isChecked,
            isSelected = node.isSelected,
            isVisibleToUser = node.isVisibleToUser,
        )
    }

    private fun actionName(action: AccessibilityNodeInfo.AccessibilityAction): String? {
        return when (action.id) {
            AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "CLEAR_FOCUS"
            AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "SET_SELECTION"
            AccessibilityNodeInfo.ACTION_COPY -> "COPY"
            AccessibilityNodeInfo.ACTION_CUT -> "CUT"
            else -> action.label?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun isFocusProbeEvent(eventType: Int): Boolean {
        return eventType in FOCUS_PROBE_EVENT_TYPES
    }

    companion object {
        private const val SLIDER_END_FRACTION = 0.05f

        /** How far past a track's bounds a tap or drag still counts as reaching its end. */
        private const val SLIDER_SLACK_DP = 24f
        private const val MAX_SLIDER_SCROLL_STEPS = 100

        /**
         * Whether (x, y) lies on a slider with bounds [left, top, right, bottom] widened by [slack]: along
         * the track by the full slack, across it by half.
         */
        internal fun sliderReaches(left: Int, top: Int, right: Int, bottom: Int, x: Int, y: Int, slack: Int): Boolean {
            val horizontal = right - left >= bottom - top
            val along = if (horizontal) slack else slack / 2
            val across = if (horizontal) slack / 2 else slack
            return x in (left - along) until (right + along) && y in (top - across) until (bottom + across)
        }

        /**
         * [max] when (x, y) is within the outer 5% of the track at its high end (right, or top for a
         * vertical slider) or past it, [min] at the low end, null in between.
         */
        internal fun sliderEndValue(left: Int, top: Int, right: Int, bottom: Int, x: Int, y: Int, min: Float, max: Float): Float? {
            val width = right - left
            val height = bottom - top
            val fraction = if (width >= height) (x - left).toFloat() / width.coerceAtLeast(1)
            else (bottom - y).toFloat() / height.coerceAtLeast(1)
            return when {
                fraction >= 1f - SLIDER_END_FRACTION -> max
                fraction <= SLIDER_END_FRACTION -> min
                else -> null
            }
        }

        private const val TAG = "CoatiA11y"
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val SELECTION_CONTEXT_CHARS = 64
        private const val SELECTION_STABILITY_INTERVAL_MS = 80L
        private const val SELECTION_ACK_TIMEOUT_MS = 750L
        private const val SELECTION_POLL_INTERVAL_MS = 25L
        private const val FOCUS_EVIDENCE_TIMEOUT_MS = 2_000L
        private const val SESSION_RESTART_SETTLE_MS = 150L
        private const val FOCUS_EVIDENCE_POLL_MS = 25L
        private const val MAX_ATOMIC_CONNECTION_EVIDENCE_READS = 3

        private const val MAX_SNAPSHOT_NODES = 600

        private val FOCUS_PROBE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
        )

        private val EDITOR_ACTIONS = setOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
        )

        internal fun hasComposingRegion(text: CharSequence): Boolean {
            val spannable = text as? Spannable ?: return false
            return BaseInputConnection.getComposingSpanStart(spannable) >= 0 ||
                BaseInputConnection.getComposingSpanEnd(spannable) >= 0
        }

        @Volatile
        var instance: CoatiAccessibilityService? = null
            private set

        var onConnected: (() -> Unit)? = null
        var onForegroundWindowChanged: ((packageName: String?, className: String?) -> Unit)? = null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class TrackingInputMethod(
    service: AccessibilityService,
) : InputMethod(service) {
    private val stateLock = Any()
    private var sessionId = 0L
    private var sequence = 0L
    private var selection: EditorSelectionState? = null

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        synchronized(stateLock) {
            sessionId += 1
            selection = if (attribute.initialSelStart >= 0 && attribute.initialSelEnd >= 0) {
                sequence += 1
                EditorSelectionState(
                    sessionId = sessionId,
                    sequence = sequence,
                    selectionStart = attribute.initialSelStart,
                    selectionEnd = attribute.initialSelEnd,
                    observedAtMillis = SystemClock.uptimeMillis(),
                )
            } else {
                null
            }
            Log.i(
                "CoatiA11y",
                "Input session started: session=$sessionId restarting=$restarting " +
                    "fieldId=${attribute.fieldId} inputType=${attribute.inputType} " +
                    "imeOptions=${attribute.imeOptions}",
            )
        }
    }

    override fun onFinishInput() {
        synchronized(stateLock) {
            sessionId += 1
            selection = null
            Log.i("CoatiA11y", "Input session finished: nextSession=$sessionId")
        }
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        synchronized(stateLock) {
            sequence += 1
            selection = EditorSelectionState(
                sessionId = sessionId,
                sequence = sequence,
                selectionStart = newSelStart,
                selectionEnd = newSelEnd,
                observedAtMillis = SystemClock.uptimeMillis(),
            )
            Log.i(
                "CoatiA11y",
                "Selection update: session=$sessionId sequence=$sequence " +
                    "old=$oldSelStart..$oldSelEnd new=$newSelStart..$newSelEnd " +
                    "candidates=$candidatesStart..$candidatesEnd",
            )
        }
    }

    fun currentSessionId(): Long = synchronized(stateLock) { sessionId }

    fun currentSelection(): EditorSelectionState? = synchronized(stateLock) { selection }
}
