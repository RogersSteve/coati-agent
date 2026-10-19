package bot.coati.service

enum class DirectTextEditMode {
    CARET,
    APPEND,

    /** Select the whole document through the InputConnection, then type over the selection. */
    REPLACE_ALL,
}

enum class DirectTextEditStatus {
    DISPATCHED,
    LEGACY_UNSUPPORTED,
    UNAVAILABLE,
    PRECONDITION_FAILED,
    SELECTION_NOT_CONFIRMED,
    FAILED,
}

data class DirectTextEditResult(
    val status: DirectTextEditStatus,
    val reason: String,
)

/**
 * ACTION_SET_TEXT is a whole-field replacement, so it is only a safe fallback for an
 * observably empty, simple single-line field. A package/session mismatch or an
 * unconfirmed caret is never fallback-eligible: either condition means that replacing
 * through the accessibility node could target a different editor or destroy content.
 */
internal fun allowsEmptySingleLineSetTextFallback(
    directStatus: DirectTextEditStatus,
    profile: EditorInputProfile,
    observablyEmpty: Boolean,
    textContainsNewline: Boolean,
): Boolean = directStatus in setOf(
    DirectTextEditStatus.LEGACY_UNSUPPORTED,
    DirectTextEditStatus.UNAVAILABLE,
) && profile.allowsWholeFieldReplace && observablyEmpty && !textContainsNewline

/** Snapshot of the Android 13 accessibility input-method channel around a focus gesture. */
data class InputFocusSnapshot(
    val sessionId: Long,
    val selectionSequence: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val packageName: String?,
    val inputStarted: Boolean,
    val hasConnection: Boolean,
) {
    val hasCollapsedCaret: Boolean
        get() = selectionStart >= 0 && selectionStart == selectionEnd
}

internal fun isFreshFocusEvidence(
    baseline: InputFocusSnapshot?,
    current: InputFocusSnapshot?,
    expectedPackage: String?,
): Boolean {
    if (current == null || !current.inputStarted || !current.hasConnection || !current.hasCollapsedCaret) {
        return false
    }
    if (expectedPackage != null && current.packageName != expectedPackage) return false
    if (baseline == null) return true
    return current.sessionId != baseline.sessionId ||
        current.selectionSequence > baseline.selectionSequence
}

enum class AtomicFocusEvidenceKind {
    CALLBACK,
    ABSOLUTE_CONNECTION_CARET,
    COMPLETE_RELATIVE_CONNECTION_CARET,
}

data class FocusGestureEventEvidence(
    val targetSelectionChanged: Boolean,
    val targetEditableClicked: Boolean,
) {
    val confirmsTargetInteraction: Boolean
        get() = targetSelectionChanged && targetEditableClicked
}

data class ConnectionCaretSnapshot(
    val sessionId: Long,
    val selection: ConnectionSelectionEvidence,
    val surroundingTextLength: Int,
    val requestedBeforeLength: Int,
    val requestedAfterLength: Int,
) {
    val hasCompleteDocumentContext: Boolean
        get() = surroundingTextLength >= 0 &&
            requestedBeforeLength >= 0 &&
            requestedAfterLength >= 0 &&
            selection.relativeStart in 0..surroundingTextLength &&
            selection.relativeEnd in selection.relativeStart..surroundingTextLength &&
            selection.relativeStart < requestedBeforeLength &&
            surroundingTextLength - selection.relativeEnd < requestedAfterLength
}

internal fun classifyAtomicFocusEvidence(
    baseline: InputFocusSnapshot?,
    current: InputFocusSnapshot?,
    expectedPackage: String?,
    baselineConnection: ConnectionCaretSnapshot?,
    currentConnection: ConnectionCaretSnapshot?,
    gestureEvents: FocusGestureEventEvidence,
): AtomicFocusEvidenceKind? {
    if (isFreshFocusEvidence(baseline, current, expectedPackage)) {
        return AtomicFocusEvidenceKind.CALLBACK
    }
    if (baseline == null || current == null) return null
    if (!current.inputStarted || !current.hasConnection || !current.hasCollapsedCaret) return null
    // The OEM fallback combines several indirect signals, so it is enabled only when both
    // snapshots can be bound to the package that owned the target before the gesture.
    if (expectedPackage == null) return null
    if (baseline.packageName != expectedPackage || current.packageName != expectedPackage) return null
    if (!gestureEvents.confirmsTargetInteraction) return null

    val before = baselineConnection ?: return null
    val after = currentConnection ?: return null
    if (before.sessionId != baseline.sessionId || after.sessionId != current.sessionId) return null
    if (before.sessionId != after.sessionId) return null
    if (!before.selection.isCollapsed || !after.selection.isCollapsed) return null
    if (before.selection.hasComposingRegion || after.selection.hasComposingRegion) return null

    if (before.selection.hasAbsolutePosition && after.selection.hasAbsolutePosition) {
        val moved = before.selection.absoluteStart != after.selection.absoluteStart
        return AtomicFocusEvidenceKind.ABSOLUTE_CONNECTION_CARET.takeIf { moved }
    }
    if (before.selection.hasAbsolutePosition || after.selection.hasAbsolutePosition) return null

    val comparableCompleteDocument = before.hasCompleteDocumentContext &&
        after.hasCompleteDocumentContext &&
        before.surroundingTextLength == after.surroundingTextLength
    val moved = before.selection.relativeStart != after.selection.relativeStart
    return AtomicFocusEvidenceKind.COMPLETE_RELATIVE_CONNECTION_CARET.takeIf {
        comparableCompleteDocument && moved
    }
}

enum class EditorInputKind {
    SIMPLE_SINGLE_LINE,
    COMPLEX_OR_MULTILINE,
    UNKNOWN,
}

data class EditorInputProfile(
    val isEditable: Boolean,
    val isMultiLine: Boolean,
    val className: String?,
    val hasWebViewAncestor: Boolean,
    val supportsSetText: Boolean,
    val hasDirectInput: Boolean,
) {
    val kind: EditorInputKind
        get() = when {
            !isEditable -> EditorInputKind.UNKNOWN
            isMultiLine || hasWebViewAncestor || className == "android.webkit.WebView" ->
                EditorInputKind.COMPLEX_OR_MULTILINE
            className?.endsWith("EditText") == true ||
                className?.endsWith("AutoCompleteTextView") == true ->
                EditorInputKind.SIMPLE_SINGLE_LINE
            else -> EditorInputKind.UNKNOWN
        }

    val allowsWholeFieldReplace: Boolean
        get() = kind == EditorInputKind.SIMPLE_SINGLE_LINE && supportsSetText

    private val isWebContent: Boolean
        get() = hasWebViewAncestor || className == "android.webkit.WebView"

    /**
     * An explicit whole-field replacement through ACTION_SET_TEXT of a field Android flags multiline
     * but whose [currentText] is short and on one line: a rename or title field. Markor's file-name
     * field is multiline-capable, and refusing it there wasted the last step of a rename task.
     */
    fun allowsShortPlainReplace(currentText: CharSequence?): Boolean =
        isEditable && supportsSetText && !isWebContent && kind != EditorInputKind.UNKNOWN &&
            (currentText?.length ?: 0) <= SHORT_PLAIN_REPLACE_MAX_CHARS && currentText?.contains('\n') != true

    /** An explicit whole-content replacement by select-all and typing, through the real InputConnection. */
    val allowsSelectAllReplace: Boolean
        get() = isEditable && hasDirectInput && !isWebContent

    companion object {
        const val SHORT_PLAIN_REPLACE_MAX_CHARS = 120
    }
}

/**
 * Whether a select-all left the whole document selected: the selection of the current session
 * starts at the document start and is not collapsed, unless the document is empty.
 */
internal fun confirmsSelectAll(selection: EditorSelectionState?, sessionId: Long, documentEmpty: Boolean): Boolean =
    selection != null && selection.sessionId == sessionId && selection.isKnown && selection.selectionStart == 0 &&
        (selection.selectionEnd > 0 || documentEmpty)

data class ConnectionSelectionEvidence(
    val relativeStart: Int,
    val relativeEnd: Int,
    val absoluteStart: Int?,
    val absoluteEnd: Int?,
    val hasComposingRegion: Boolean,
) {
    val isCollapsed: Boolean
        get() = relativeStart == relativeEnd

    val hasAbsolutePosition: Boolean
        get() = absoluteStart != null && absoluteEnd != null
}

internal fun connectionSelectionEvidence(
    offset: Int,
    selectionStart: Int,
    selectionEnd: Int,
    hasComposingRegion: Boolean,
): ConnectionSelectionEvidence? {
    if (selectionStart < 0 || selectionEnd < 0) return null
    val absoluteStart = offset.takeIf { it >= 0 }?.plus(selectionStart)
    val absoluteEnd = offset.takeIf { it >= 0 }?.plus(selectionEnd)
    return ConnectionSelectionEvidence(
        relativeStart = selectionStart,
        relativeEnd = selectionEnd,
        absoluteStart = absoluteStart,
        absoluteEnd = absoluteEnd,
        hasComposingRegion = hasComposingRegion,
    )
}

internal fun confirmStableConnectionCaret(
    first: ConnectionSelectionEvidence?,
    second: ConnectionSelectionEvidence?,
    callback: EditorSelectionState?,
    currentSessionId: Long,
): ConnectionSelectionEvidence? {
    if (first == null || second == null) return null
    val firstPosition = first.absoluteStart ?: first.relativeStart
    val secondPosition = second.absoluteStart ?: second.relativeStart
    val stableCaret = firstPosition == secondPosition &&
        first.isCollapsed && second.isCollapsed &&
        !first.hasComposingRegion && !second.hasComposingRegion
    if (!stableCaret) return null

    // AccessibilityInputConnection may return offset=-1 for OEM rich editors. In
    // that case its selection is relative to the returned surrounding-text window,
    // while onUpdateSelection reports a document position. Those coordinate spaces
    // cannot be compared. The connection's two stable reads are authoritative; the
    // callback is used as corroboration only when an absolute connection position is
    // available. If surrounding text is unavailable entirely, the caller falls back
    // to two stable callback reads.
    val callbackAgrees = if (second.hasAbsolutePosition) {
        callback?.takeIf {
            it.sessionId == currentSessionId && it.isKnown
        }?.let {
            it.isCollapsed && it.selectionStart == secondPosition
        } ?: true
    } else {
        true
    }
    return second.takeIf { callbackAgrees }
}

internal fun confirmStableCallbackCaret(
    first: EditorSelectionState?,
    second: EditorSelectionState?,
    currentSessionId: Long,
): ConnectionSelectionEvidence? {
    val stableCaret = first != null && second != null &&
        first.sessionId == currentSessionId &&
        second.sessionId == first.sessionId &&
        first.selectionStart == second.selectionStart &&
        first.selectionEnd == second.selectionEnd &&
        second.isCollapsed
    if (!stableCaret) return null
    return connectionSelectionEvidence(
        offset = 0,
        selectionStart = requireNotNull(second).selectionStart,
        selectionEnd = second.selectionEnd,
        hasComposingRegion = false,
    )
}

data class EditorSelectionState(
    val sessionId: Long,
    val sequence: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val observedAtMillis: Long,
) {
    val isKnown: Boolean
        get() = selectionStart >= 0 && selectionEnd >= 0

    val isCollapsed: Boolean
        get() = isKnown && selectionStart == selectionEnd
}

internal fun confirmsDocumentBounds(
    home: EditorSelectionState,
    end: EditorSelectionState,
): Boolean {
    return home.sessionId == end.sessionId &&
        home.isCollapsed &&
        end.isCollapsed &&
        home.selectionStart == 0 &&
        end.selectionStart > home.selectionStart &&
        end.sequence > home.sequence
}

internal fun isObservablyEmptyEditor(text: CharSequence?, isShowingHintText: Boolean): Boolean =
    text.isNullOrEmpty() || isShowingHintText
