package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridPerceptionTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float = 0.95f) = OcrLine(text, PixelBounds(l, t, r, b), conf)

    private fun element(id: String, text: String, l: Int, t: Int, r: Int, b: Int) =
        ScreenElement(id, "button", text, "", listOf(ElementOp.TAP), null, PixelBounds(l, t, r, b))

    private fun screen(source: ScreenSource, vararg elements: ScreenElement) =
        ParsedScreen(source, "app", "", false, null, elements.toList(), emptyList())

    @Test fun `text explained by tree nodes is covered`() {
        val labelled = listOf(PixelBounds(0, 100, 1080, 200))
        val lines = listOf(line("Submit order", 400, 130, 700, 170))
        assertEquals(emptyList<OcrLine>(), HybridPerception.uncoveredLines(lines, labelled, statusBarBottom = 60))
    }

    @Test fun `painted text outside the tree is uncovered`() {
        val labelled = listOf(PixelBounds(0, 100, 1080, 200))
        val painted = line("Priority delivery", 30, 500, 300, 540)
        assertEquals(listOf(painted), HybridPerception.uncoveredLines(listOf(painted), labelled, statusBarBottom = 60))
    }

    @Test fun `status bar, low confidence and symbol-only lines are ignored`() {
        val lines = listOf(
            line("12:45", 10, 5, 80, 40),
            line("Blurry", 30, 500, 200, 540, conf = 0.5f),
            line("+", 900, 600, 940, 640),
        )
        assertEquals(emptyList<OcrLine>(), HybridPerception.uncoveredLines(lines, emptyList(), statusBarBottom = 60))
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
}
