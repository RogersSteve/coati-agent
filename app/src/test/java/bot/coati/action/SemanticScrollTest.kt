package bot.coati.action

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.*
import org.junit.Test

class SemanticScrollTest {
    private val box = PixelBounds(0, 0, 1000, 1000)
    private val left = AgentAction.Swipe(800, 500, 200, 500)
    private val right = AgentAction.Swipe(200, 500, 800, 500)

    @Test fun `finger direction maps to content direction and explicit actions win`() {
        val c = ScrollCandidate(listOf(0), box, setOf(SemanticScroll.FORWARD, SemanticScroll.BACKWARD), ScrollAxis.HORIZONTAL)
        assertEquals(SemanticScroll.FORWARD, SemanticScroll.action(c, left))
        assertEquals(SemanticScroll.BACKWARD, SemanticScroll.action(c, right))
        assertEquals(SemanticScroll.RIGHT, SemanticScroll.action(c.copy(actions = c.actions + SemanticScroll.RIGHT), left))
        assertNull(SemanticScroll.action(c.copy(actions = c.actions + SemanticScroll.RIGHT), right))
        val v = c.copy(axis = ScrollAxis.VERTICAL, actions = setOf(SemanticScroll.UP, SemanticScroll.DOWN))
        assertEquals(SemanticScroll.DOWN, SemanticScroll.action(v, AgentAction.Swipe(500, 800, 500, 200)))
        assertEquals(SemanticScroll.UP, SemanticScroll.action(v, AgentAction.Swipe(500, 200, 500, 800)))
    }

    @Test fun `nested boundary does not select a scrollable ancestor instead`() {
        val parent = ScrollCandidate(emptyList(), box, setOf(SemanticScroll.FORWARD), ScrollAxis.HORIZONTAL)
        val child = parent.copy(path = listOf(1, 2), bounds = PixelBounds(100, 100, 900, 900), actions = setOf(SemanticScroll.BACKWARD))
        assertEquals(child, SemanticScroll.select(listOf(parent, child), left))
        assertNull(SemanticScroll.action(child, left))
        assertEquals(parent, SemanticScroll.select(listOf(parent, child.copy(axis = ScrollAxis.VERTICAL)), left))
        assertNull(SemanticScroll.select(listOf(child.copy(axis = null)), left))
    }

    @Test fun `geometry requires an unambiguous row or column and diagonal gestures are excluded`() {
        assertEquals(ScrollAxis.HORIZONTAL, SemanticScroll.geometryAxis(listOf(PixelBounds(0, 0, 100, 200), PixelBounds(100, 0, 200, 200))))
        assertEquals(ScrollAxis.VERTICAL, SemanticScroll.geometryAxis(listOf(PixelBounds(0, 0, 200, 100), PixelBounds(0, 100, 200, 200))))
        assertNull(SemanticScroll.geometryAxis(listOf(box)))
        assertNull(SemanticScroll.geometryAxis(listOf(box, box)))
        assertNull(SemanticScroll.axis(AgentAction.Swipe(0, 0, 100, 100)))
        assertNull(SemanticScroll.axis(AgentAction.Swipe(0, 0, 30, 0)))
    }

    @Test fun `visual comparison ignores masked pixels and rejects insufficient or incompatible evidence`() {
        val black = ScrollEvidence("same", IntArray(4096) { 0xff000000.toInt() }, 1000, 2000)
        assertEquals(false, black.visualChangeFrom(black.copy(pixels = black.pixels.clone())))
        assertEquals(true, black.visualChangeFrom(black.copy(pixels = IntArray(4096) { -1 })))
        assertEquals(false, black.visualChangeFrom(black.copy(pixels = IntArray(4096) { if (it < 2000) 0 else 0xff000000.toInt() })))
        assertNull(black.visualChangeFrom(black.copy(width = 2000)))
        assertNull(black.visualChangeFrom(black.copy(pixels = IntArray(4096))))
    }
}
