package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextRefinerTest {

    private fun el(id: String, text: String, b: PixelBounds, type: String = "item") =
        ScreenElement(id, type, text, "", listOf(ElementOp.TAP), null, b)

    private fun screen(vararg elements: ScreenElement) = ParsedScreen(
        source = ScreenSource.VISION,
        packageName = "p",
        summary = "",
        keyboardVisible = false,
        overlay = null,
        elements = elements.toList(),
        regions = emptyList(),
    )

    private val base = screen(
        el("E1", "在搞点呢", PixelBounds(600, 1700, 900, 1800)),
        el("E2", "", PixelBounds(20, 20, 80, 80), type = "icon"),
        el("E3", "日为萌", PixelBounds(100, 100, 900, 200)),
    )

    @Test
    fun `ocr lines inside an element replace the model transcription in reading order`() {
        val lines = listOf(
            OcrLine("在高点呢", PixelBounds(640, 1720, 860, 1780), 0.98f),
            OcrLine("月为暮。", PixelBounds(400, 120, 700, 180), 0.95f),
            OcrLine("日为朝，", PixelBounds(120, 122, 390, 178), 0.97f),
        )

        val refined = OcrTextRefiner.refine(base, lines)

        assertEquals("在高点呢", refined.element("E1")!!.text)
        assertEquals("日为朝，\n月为暮。", refined.element("E3")!!.text)
        assertEquals(PixelBounds(600, 1700, 900, 1800), refined.element("E1")!!.bounds) // containers keep bounds
    }

    @Test
    fun `text-like elements snap to the exact text position`() {
        val coarse = screen(el("E1", "Save canvas", PixelBounds(20, 400, 700, 500), type = "text_btn"))
        val refined = OcrTextRefiner.refine(coarse, listOf(OcrLine("Save canvas", PixelBounds(440, 430, 640, 470), 0.99f)))

        assertEquals(PixelBounds(432, 422, 648, 478), refined.element("E1")!!.bounds)
    }

    @Test
    fun `a misplaced element moves to where its text is drawn`() {
        val misplaced = screen(el("E1", "Show grid", PixelBounds(20, 900, 300, 960), type = "text"))
        val refined = OcrTextRefiner.refine(misplaced, listOf(OcrLine("Show grid", PixelBounds(24, 330, 140, 356), 0.99f)))

        assertEquals("Show grid", refined.element("E1")!!.text)
        assertEquals(PixelBounds(16, 322, 148, 364), refined.element("E1")!!.bounds)
    }

    @Test
    fun `text the model left out is added and unfound text is marked unconfirmed`() {
        val parsed = screen(
            el("E1", "Brush color", PixelBounds(20, 150, 300, 190), type = "text"),
            el("E2", "Purple", PixelBounds(500, 600, 600, 640), type = "text"),
        )
        val lines = listOf(
            OcrLine("Brush color", PixelBounds(24, 158, 200, 184), 0.99f),
            OcrLine("Blue", PixelBounds(260, 292, 300, 308), 0.97f),
            OcrLine("12:45", PixelBounds(10, 5, 60, 20), 0.99f), // status bar
            OcrLine("~", PixelBounds(400, 700, 410, 710), 0.99f), // not text
        )

        val refined = OcrTextRefiner.refine(parsed, lines, statusBarBottom = 40)

        assertTrue(refined.element("E2")!!.purpose.contains(OcrTextRefiner.UNCONFIRMED))
        assertEquals(listOf("Brush color", "Purple", "Blue"), refined.elements.map { it.text })
        assertEquals(OcrTextRefiner.ADDED_PURPOSE, refined.element("E3")!!.purpose)
    }

    @Test
    fun `icons without text are left alone`() {
        val refined = OcrTextRefiner.refine(base, listOf(OcrLine("x", PixelBounds(500, 500, 520, 520))))

        assertEquals(base.element("E2"), refined.element("E2"))
    }
}
