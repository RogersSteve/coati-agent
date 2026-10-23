package bot.coati.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import bot.coati.action.*
import bot.coati.coordinates.PixelBounds
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ScrollAccessibilityTest {
    private val swipe = AgentAction.Swipe(800, 500, 200, 500)
    private val box = PixelBounds(0, 0, 1000, 1000)
    private fun node(pkg: String = "fixture", b: PixelBounds = box, actions: Set<Int> = emptySet(),
        children: List<AccessibilityNodeInfo> = emptyList(), editable: Boolean = false,
        range: Boolean = false): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { packageName } returns pkg
        every { windowId } returns 7
        every { isVisibleToUser } returns true
        every { isEnabled } returns true
        every { isEditable } returns editable
        every { rangeInfo } returns if (range) mockk() else null
        every { isScrollable } returns actions.isNotEmpty()
        every { collectionInfo } returns null
        every { actionList } returns actions.map { a -> mockk<AccessibilityNodeInfo.AccessibilityAction> { every { id } returns a } }
        every { childCount } returns children.size
        every { getChild(any()) } answers { children.getOrNull(firstArg()) }
        every { getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left=b.left;top=b.top;right=b.right;bottom=b.bottom }; Unit
        }
        every { performAction(any()) } returns true
    }
    private fun window(id: Int, type: Int, layer: Int, root: AccessibilityNodeInfo?, focused: Boolean = false) =
        mockk<AccessibilityWindowInfo>(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { this@mockk.layer } returns layer
            every { this@mockk.root } returns root
            every { isFocused } returns focused
        }
    private fun adapter(windows: List<AccessibilityWindowInfo>) = ScrollAccessibility(mockk(relaxed = true) {
        every { this@mockk.windows } returns windows
        every { packageName } returns "bot.coati"
    })
    @Test fun `top App window selected even under focused own overlay`() {
        val list = node(actions = setOf(SemanticScroll.LEFT, SemanticScroll.RIGHT))
        val app = window(7, 1, 0, list, true)
        val overlay = window(8, 3, 9, node(pkg = "bot.coati"), true)
        val a = adapter(listOf(app, overlay))
        val s = requireNotNull(a.session(swipe))
        assertEquals("fixture", s.packageName)
        assertEquals(7, s.windowId)
        assertEquals(SemanticScroll.RIGHT, s.actionId)
        assertEquals(true, a.perform(s))
        verify(exactly = 1) { list.performAction(SemanticScroll.RIGHT) }
    }
    @Test fun `keyboard and focused external system dialog block semantic fallback`() {
        val app = window(7, 1, 0, node(actions = setOf(SemanticScroll.LEFT, SemanticScroll.RIGHT)), true)
        assertNull(adapter(listOf(app, window(9, 2, 3, node(pkg = "keyboard")))).session(swipe))
        assertNull(adapter(listOf(app, window(9, 3, 3, node(pkg = "permission"), true))).session(swipe))
    }
    @Test fun `top modal App window prevents selecting the underlying list`() {
        val list = node(actions = setOf(SemanticScroll.LEFT, SemanticScroll.RIGHT))
        val modal = node(b = PixelBounds(300, 300, 700, 700))
        val a = adapter(listOf(window(7, 1, 0, list), window(9, 1, 2, modal, true)))
        assertNull(a.session(swipe))
        verify(exactly = 0) { list.performAction(any()) }
    }
    @Test fun `text and slider drags are excluded`() {
        for (child in listOf(node(editable = true), node(range = true))) {
            val root = node(actions = setOf(SemanticScroll.LEFT, SemanticScroll.RIGHT), children = listOf(child))
            assertNull(adapter(listOf(window(7, 1, 0, root))).session(swipe))
        }
    }
    @Test fun `target action removed after gesture is never performed`() {
        val root = node(actions = setOf(SemanticScroll.LEFT, SemanticScroll.RIGHT))
        val a = adapter(listOf(window(7, 1, 0, root)))
        val s = requireNotNull(a.session(swipe))
        every { root.actionList } returns emptyList()
        assertNull(a.perform(s))
        verify(exactly = 0) { root.performAction(any()) }
    }
}
