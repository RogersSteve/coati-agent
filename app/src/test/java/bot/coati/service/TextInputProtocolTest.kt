package bot.coati.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputProtocolTest {

    @Test
    fun `new selection callback in same input session confirms focus gesture`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val current = focusSnapshot(session = 7, sequence = 11, start = 0, end = 0)

        assertTrue(isFreshFocusEvidence(baseline, current, "com.example.notes"))
    }

    @Test
    fun `new input session with collapsed caret confirms focus gesture`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val current = focusSnapshot(session = 8, sequence = 1, start = 0, end = 0)

        assertTrue(isFreshFocusEvidence(baseline, current, "com.example.notes"))
    }

    @Test
    fun `stale selection or package drift cannot confirm focus gesture`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val stale = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val drifted = focusSnapshot(
            session = 8,
            sequence = 11,
            start = 0,
            end = 0,
            packageName = "com.example.other",
        )

        assertFalse(isFreshFocusEvidence(baseline, stale, "com.example.notes"))
        assertFalse(isFreshFocusEvidence(baseline, drifted, "com.example.notes"))
    }

    @Test
    fun `selected text or missing connection cannot confirm focus gesture`() {
        val selected = focusSnapshot(session = 8, sequence = 11, start = 0, end = 3)
        val disconnected = focusSnapshot(
            session = 8,
            sequence = 11,
            start = 0,
            end = 0,
            hasConnection = false,
        )

        assertFalse(isFreshFocusEvidence(null, selected, "com.example.notes"))
        assertFalse(isFreshFocusEvidence(null, disconnected, "com.example.notes"))
    }

    @Test
    fun `atomic focus preserves callback evidence path without gesture events`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val current = focusSnapshot(session = 7, sequence = 11, start = 0, end = 0)

        assertEquals(
            AtomicFocusEvidenceKind.CALLBACK,
            classifyAtomicFocusEvidence(
                baseline = baseline,
                current = current,
                expectedPackage = "com.example.notes",
                baselineConnection = null,
                currentConnection = null,
                gestureEvents = gestureEvents(selection = false, click = false),
            ),
        )
    }

    @Test
    fun `complete relative connection movement plus target events confirms OEM rich editor focus`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val staleCallback = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)

        assertEquals(
            AtomicFocusEvidenceKind.COMPLETE_RELATIVE_CONNECTION_CARET,
            classifyAtomicFocusEvidence(
                baseline = baseline,
                current = staleCallback,
                expectedPackage = "com.example.notes",
                baselineConnection = connectionCaret(session = 7, start = 1, length = 2),
                currentConnection = connectionCaret(session = 7, start = 0, length = 2),
                gestureEvents = gestureEvents(selection = true, click = true),
            ),
        )
    }

    @Test
    fun `relative connection movement rejects either missing target event`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val current = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val before = connectionCaret(session = 7, start = 1, length = 2)
        val after = connectionCaret(session = 7, start = 0, length = 2)

        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", before, after,
                gestureEvents(selection = true, click = false),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", before, after,
                gestureEvents(selection = false, click = true),
            ),
        )
    }

    @Test
    fun `target events cannot authorize unchanged or incomplete relative caret`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val current = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val complete = connectionCaret(session = 7, start = 1, length = 2)
        val incomplete = connectionCaret(
            session = 7,
            start = 64,
            length = 128,
            requestedBefore = 64,
            requestedAfter = 64,
        )

        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", complete, complete,
                gestureEvents(selection = true, click = true),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", incomplete,
                incomplete.copy(selection = incomplete.selection.copy(relativeStart = 63, relativeEnd = 63)),
                gestureEvents(selection = true, click = true),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", complete,
                connectionCaret(session = 7, start = 3, length = 2),
                gestureEvents(selection = true, click = true),
            ),
        )
    }

    @Test
    fun `absolute connection movement requires target events and same session`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 4, end = 4)
        val current = focusSnapshot(session = 7, sequence = 10, start = 4, end = 4)
        val before = connectionCaret(session = 7, start = 4, length = 20, offset = 10)
        val after = connectionCaret(session = 7, start = 2, length = 20, offset = 10)

        assertEquals(
            AtomicFocusEvidenceKind.ABSOLUTE_CONNECTION_CARET,
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", before, after,
                gestureEvents(selection = true, click = true),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", before, after,
                gestureEvents(selection = true, click = false),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, current, "com.example.notes", before, after.copy(sessionId = 8),
                gestureEvents(selection = true, click = true),
            ),
        )
    }

    @Test
    fun `connection evidence rejects composing caret and package drift`() {
        val baseline = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val samePackage = focusSnapshot(session = 7, sequence = 10, start = 1, end = 1)
        val otherPackage = focusSnapshot(
            session = 7,
            sequence = 10,
            start = 1,
            end = 1,
            packageName = "com.example.other",
        )
        val before = connectionCaret(session = 7, start = 1, length = 2)
        val composingAfter = connectionCaret(
            session = 7,
            start = 0,
            length = 2,
            composing = true,
        )

        assertNull(
            classifyAtomicFocusEvidence(
                baseline, samePackage, "com.example.notes", before, composingAfter,
                gestureEvents(selection = true, click = true),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, otherPackage, "com.example.notes", before,
                connectionCaret(session = 7, start = 0, length = 2),
                gestureEvents(selection = true, click = true),
            ),
        )
        assertNull(
            classifyAtomicFocusEvidence(
                baseline, samePackage, null, before,
                connectionCaret(session = 7, start = 0, length = 2),
                gestureEvents(selection = true, click = true),
            ),
        )
    }

    @Test
    fun `negative surrounding offset still confirms a relative collapsed caret`() {
        val evidence = connectionSelectionEvidence(
            offset = -1,
            selectionStart = 4,
            selectionEnd = 4,
            hasComposingRegion = false,
        )

        assertTrue(evidence!!.isCollapsed)
        assertFalse(evidence.hasAbsolutePosition)
        assertNull(evidence.absoluteStart)
    }

    @Test
    fun `nonnegative surrounding offset provides absolute position`() {
        val evidence = connectionSelectionEvidence(
            offset = 10,
            selectionStart = 4,
            selectionEnd = 4,
            hasComposingRegion = false,
        )

        assertEquals(14, evidence!!.absoluteStart)
        assertEquals(14, evidence.absoluteEnd)
        assertTrue(evidence.hasAbsolutePosition)
    }

    @Test
    fun `unknown relative selection cannot confirm a caret`() {
        assertNull(connectionSelectionEvidence(-1, -1, -1, false))
    }

    @Test
    fun `negative offset caret does not compare relative connection to callback document coordinates`() {
        val first = connectionSelectionEvidence(-1, 4, 4, false)
        val second = connectionSelectionEvidence(-1, 4, 4, false)
        val callback = selection(session = 9, sequence = 2, start = 19, end = 19)

        assertTrue(confirmStableConnectionCaret(first, second, callback, 9)!!.isCollapsed)
    }

    @Test
    fun `absolute caret compares callback in document coordinates`() {
        val first = connectionSelectionEvidence(10, 4, 4, false)
        val second = connectionSelectionEvidence(10, 4, 4, false)
        val callback = selection(session = 9, sequence = 2, start = 14, end = 14)

        assertEquals(14, confirmStableConnectionCaret(first, second, callback, 9)!!.absoluteStart)
    }

    @Test
    fun `absolute connection caret rejects conflicting callback or selected text`() {
        val caret = connectionSelectionEvidence(10, 4, 4, false)
        val conflict = selection(session = 9, sequence = 2, start = 2, end = 2)
        val selected = connectionSelectionEvidence(-1, 2, 4, false)

        assertNull(confirmStableConnectionCaret(caret, caret, conflict, 9))
        assertNull(confirmStableConnectionCaret(selected, selected, null, 9))
    }

    @Test
    fun `stable input method callback is a fallback when surrounding text is unavailable`() {
        val first = selection(session = 9, sequence = 2, start = 4, end = 4)
        val second = selection(session = 9, sequence = 2, start = 4, end = 4)

        assertTrue(confirmStableCallbackCaret(first, second, 9)!!.isCollapsed)
        assertNull(confirmStableCallbackCaret(first, second.copy(selectionEnd = 5), 9))
    }

    @Test
    fun `multiline and webview editors reject whole field replacement`() {
        val multiline = profile(isMultiLine = true)
        val webView = profile(hasWebViewAncestor = true)

        assertEquals(EditorInputKind.COMPLEX_OR_MULTILINE, multiline.kind)
        assertEquals(EditorInputKind.COMPLEX_OR_MULTILINE, webView.kind)
        assertFalse(multiline.allowsWholeFieldReplace)
        assertFalse(webView.allowsWholeFieldReplace)
    }

    @Test
    fun `simple single line edit text allows explicit whole field replacement`() {
        val profile = profile()

        assertEquals(EditorInputKind.SIMPLE_SINGLE_LINE, profile.kind)
        assertTrue(profile.allowsWholeFieldReplace)
    }

    @Test
    fun `empty simple field permits set text only when direct backend is unavailable`() {
        val profile = profile()

        assertTrue(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.UNAVAILABLE,
                profile = profile,
                observablyEmpty = true,
                textContainsNewline = false,
            ),
        )
        assertTrue(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.LEGACY_UNSUPPORTED,
                profile = profile,
                observablyEmpty = true,
                textContainsNewline = false,
            ),
        )
    }

    @Test
    fun `set text fallback cannot bypass caret or package preconditions`() {
        val profile = profile()

        assertFalse(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                profile = profile,
                observablyEmpty = true,
                textContainsNewline = false,
            ),
        )
        assertFalse(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.PRECONDITION_FAILED,
                profile = profile,
                observablyEmpty = true,
                textContainsNewline = false,
            ),
        )
    }

    @Test
    fun `set text fallback rejects existing multiline and complex editor content`() {
        val simple = profile()
        val multiline = profile(isMultiLine = true)

        assertFalse(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.UNAVAILABLE,
                profile = simple,
                observablyEmpty = false,
                textContainsNewline = false,
            ),
        )
        assertFalse(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.UNAVAILABLE,
                profile = simple,
                observablyEmpty = true,
                textContainsNewline = true,
            ),
        )
        assertFalse(
            allowsEmptySingleLineSetTextFallback(
                directStatus = DirectTextEditStatus.UNAVAILABLE,
                profile = multiline,
                observablyEmpty = true,
                textContainsNewline = false,
            ),
        )
    }

    @Test
    fun `empty text is an observably empty editor`() {
        assertTrue(
            isObservablyEmptyEditor(
                text = "",
                isShowingHintText = false,
            ),
        )
    }

    @Test
    fun `displayed accessibility hint is not treated as user content`() {
        assertTrue(
            isObservablyEmptyEditor(
                text = "标题",
                isShowingHintText = true,
            ),
        )
    }

    @Test
    fun `text equal to hint is not empty without standard showing-hint evidence`() {
        assertFalse(
            isObservablyEmptyEditor(
                text = "标题",
                isShowingHintText = false,
            ),
        )
    }

    @Test
    fun `real editor content is not observably empty`() {
        assertFalse(
            isObservablyEmptyEditor(
                text = "Meeting notes",
                isShowingHintText = false,
            ),
        )
    }

    @Test
    fun `document bounds require confirmed home then end in same session`() {
        val home = selection(session = 4, sequence = 10, start = 0, end = 0)
        val end = selection(session = 4, sequence = 11, start = 25, end = 25)

        assertTrue(confirmsDocumentBounds(home, end))
    }

    @Test
    fun `document bounds reject selected text`() {
        val home = selection(session = 4, sequence = 10, start = 0, end = 0)
        val end = selection(session = 4, sequence = 11, start = 20, end = 25)

        assertFalse(confirmsDocumentBounds(home, end))
    }

    @Test
    fun `document bounds reject editor session change`() {
        val home = selection(session = 4, sequence = 10, start = 0, end = 0)
        val end = selection(session = 5, sequence = 11, start = 25, end = 25)

        assertFalse(confirmsDocumentBounds(home, end))
    }

    @Test
    fun `document bounds reject unconfirmed document start`() {
        val home = selection(session = 4, sequence = 10, start = 5, end = 5)
        val end = selection(session = 4, sequence = 11, start = 25, end = 25)

        assertFalse(confirmsDocumentBounds(home, end))
    }

    private fun selection(
        session: Long,
        sequence: Long,
        start: Int,
        end: Int,
    ) = EditorSelectionState(
        sessionId = session,
        sequence = sequence,
        selectionStart = start,
        selectionEnd = end,
        observedAtMillis = 0,
    )

    private fun focusSnapshot(
        session: Long,
        sequence: Long,
        start: Int,
        end: Int,
        packageName: String = "com.example.notes",
        hasConnection: Boolean = true,
    ) = InputFocusSnapshot(
        sessionId = session,
        selectionSequence = sequence,
        selectionStart = start,
        selectionEnd = end,
        packageName = packageName,
        inputStarted = true,
        hasConnection = hasConnection,
    )

    private fun profile(
        isMultiLine: Boolean = false,
        hasWebViewAncestor: Boolean = false,
    ) = EditorInputProfile(
        isEditable = true,
        isMultiLine = isMultiLine,
        className = "android.widget.EditText",
        hasWebViewAncestor = hasWebViewAncestor,
        supportsSetText = true,
        hasDirectInput = true,
    )

    private fun gestureEvents(
        selection: Boolean,
        click: Boolean,
    ) = FocusGestureEventEvidence(
        targetSelectionChanged = selection,
        targetEditableClicked = click,
    )

    private fun connectionCaret(
        session: Long,
        start: Int,
        length: Int,
        offset: Int = -1,
        requestedBefore: Int = 64,
        requestedAfter: Int = 64,
        composing: Boolean = false,
    ) = ConnectionCaretSnapshot(
        sessionId = session,
        selection = requireNotNull(
            connectionSelectionEvidence(
                offset = offset,
                selectionStart = start,
                selectionEnd = start,
                hasComposingRegion = composing,
            ),
        ),
        surroundingTextLength = length,
        requestedBeforeLength = requestedBefore,
        requestedAfterLength = requestedAfter,
    )

    @Test
    fun `a short one-line value in a multiline-capable field may be replaced explicitly`() {
        val renameField = profile(isMultiLine = true)
        // Markor's file-name field: flagged multiline, holding "my_note.md".
        assertTrue(renameField.allowsShortPlainReplace("my_note.md"))
        assertTrue(renameField.allowsShortPlainReplace(null))
        assertFalse(renameField.allowsShortPlainReplace("line one\nline two"))
        assertFalse(renameField.allowsShortPlainReplace("x".repeat(EditorInputProfile.SHORT_PLAIN_REPLACE_MAX_CHARS + 1)))
        assertFalse(profile(hasWebViewAncestor = true).allowsShortPlainReplace("short"))
        assertFalse(renameField.copy(supportsSetText = false).allowsShortPlainReplace("short"))
    }

    @Test
    fun `select-all replacement needs a direct input connection outside web content`() {
        assertTrue(profile(isMultiLine = true).allowsSelectAllReplace)
        assertFalse(profile(isMultiLine = true).copy(hasDirectInput = false).allowsSelectAllReplace)
        assertFalse(profile(hasWebViewAncestor = true).allowsSelectAllReplace)
    }

    @Test
    fun `select-all is confirmed only for a selection from the document start`() {
        assertTrue(confirmsSelectAll(selection(session = 4, sequence = 9, start = 0, end = 120), 4, documentEmpty = false))
        assertTrue(confirmsSelectAll(selection(session = 4, sequence = 9, start = 0, end = 0), 4, documentEmpty = true))
        assertFalse(confirmsSelectAll(selection(session = 4, sequence = 9, start = 0, end = 0), 4, documentEmpty = false))
        assertFalse(confirmsSelectAll(selection(session = 4, sequence = 9, start = 3, end = 120), 4, documentEmpty = false))
        assertFalse(confirmsSelectAll(selection(session = 3, sequence = 9, start = 0, end = 120), 4, documentEmpty = false))
        assertFalse(confirmsSelectAll(null, 4, documentEmpty = true))
    }
}
