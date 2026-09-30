package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridPerceptionTest {

    @Test
    fun `painted content is unchanged only with the same lines in the same places`() {
        fun line(text: String, left: Int, top: Int) = OcrLine(text, PixelBounds(left, top, left + 60, top + 30))
        val before = listOf(line("Copies", 40, 400), line("2", 900, 400), line("Priority delivery", 40, 480))

        assertTrue(HybridPerception.samePainted(before, listOf(line("Copies", 42, 401), line("2", 899, 400), line("Priority delivery", 40, 482))))
        // The counter moved on.
        assertFalse(HybridPerception.samePainted(before, listOf(line("Copies", 40, 400), line("3", 900, 400), line("Priority delivery", 40, 480))))
        // The page scrolled.
        assertFalse(HybridPerception.samePainted(before, before.map { line(it.text, it.bounds.left, it.bounds.top - 120) }))
        assertFalse(HybridPerception.samePainted(before, before.dropLast(1)))
    }
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float = 0.95f) = OcrLine(text, PixelBounds(l, t, r, b), conf)

    private fun element(id: String, text: String, l: Int, t: Int, r: Int, b: Int) =
        ScreenElement(id, "button", text, "", listOf(ElementOp.TAP), null, PixelBounds(l, t, r, b))

    private fun screen(source: ScreenSource, vararg elements: ScreenElement) =
        ParsedScreen(source, "app", "", false, null, elements.toList(), emptyList())

    private fun box(l: Int, t: Int, r: Int, b: Int, labelled: Boolean = false, image: Boolean = false) =
        HybridPerception.TreeBox(PixelBounds(l, t, r, b), labelled, image)

    @Test fun `text explained by a labelled node is not painted`() {
        val hosts = listOf(box(0, 100, 1080, 200, labelled = true))
        val lines = listOf(line("Submit order", 400, 130, 700, 170))
        assertEquals(emptyList<OcrLine>(), HybridPerception.paintedLines(lines, hosts, statusBarBottom = 60))
    }

    @Test fun `text inside an unlabelled node is painted`() {
        val canvas = box(18, 400, 1062, 550)
        val painted = listOf(line("Copies", 30, 420, 120, 450), line("Priority delivery", 30, 500, 300, 540))
        assertEquals(painted, HybridPerception.paintedLines(painted, listOf(canvas), statusBarBottom = 60))
    }

    @Test fun `text in no node, in images, in the status bar or the keyboard is not painted`() {
        val thumbnail = box(10, 600, 300, 800, image = true)
        val keyboard = PixelBounds(0, 1400, 1080, 1920)
        val lines = listOf(
            line("Gallery", 400, 300, 600, 340), // backdrop outside the tree
            line("WARZONE", 50, 650, 250, 700), // thumbnail artwork
            line("12:45", 10, 5, 80, 40),
            line("When", 100, 1450, 200, 1490),
        )
        val hosts = listOf(thumbnail, box(0, 1400, 1080, 1920))
        assertEquals(emptyList<OcrLine>(), HybridPerception.paintedLines(lines, hosts, 60, listOf(keyboard)))
    }

    @Test fun `merge keeps tree elements and adds only uncovered vision elements`() {
        val tree = screen(
            ScreenSource.ACCESSIBILITY,
            element("E1", "Submit order", 0, 900, 1080, 980),
            element("E2", "Recipient", 0, 700, 1080, 780),
        )
        val vision = screen(
            ScreenSource.VISION,
            element("E1", "Submit", 10, 910, 1070, 970), // duplicate of a native control
            element("E2", "+", 960, 420, 1040, 480), // painted stepper
            element("E3", "Priority toggle", 900, 500, 1060, 560), // painted switch
            element("E4", "12:45", 10, 5, 80, 40), // status bar
        )
        val merged = HybridPerception.merge(tree, vision, statusBarBottom = 60)
        assertEquals(ScreenSource.HYBRID, merged.source)
        assertEquals(listOf("Submit order", "Recipient", "+", "Priority toggle"), merged.elements.map { it.text })
        assertEquals(listOf("E1", "E2", "E3", "E4"), merged.elements.map { it.id })
        assertEquals(PixelBounds(0, 900, 1080, 980), merged.element("E1")!!.bounds)
    }

    @Test fun `a hybrid page is identified by its tree elements only`() {
        val tree = screen(ScreenSource.ACCESSIBILITY, element("E1", "Submit order", 0, 900, 1080, 980))
        val first = HybridPerception.merge(tree, screen(ScreenSource.VISION, element("E1", "+", 960, 420, 1040, 480)), 60)
        val second = HybridPerception.merge(tree, screen(ScreenSource.VISION, element("E1", "Copies 3", 20, 420, 300, 480)), 60)
        assertEquals(first.pageKey, second.pageKey)
    }
}
