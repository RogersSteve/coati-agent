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

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float = 0.97f) = OcrLine(text, PixelBounds(l, t, r, b), conf)

    @Test
    fun `the model text is kept and OCR only appends what the model missed`() {
        val row = screen(el("E1", "Noah Kahan - The Great Divide", PixelBounds(10, 240, 890, 315)))
        val refined = OcrTextRefiner.refine(row, listOf(
            line("Noah Kahan -The Great Divide", 137, 245, 476, 270), // same string, OCR typo
            line("Noah Kahan", 143, 270, 206, 288),
            line("29K watching", 212, 270, 300, 288),
        ))
        assertEquals("Noah Kahan - The Great Divide\n29K watching", refined.element("E1")!!.text)
    }

    @Test
    fun `garbled glyphs and low-confidence lines never replace a label`() {
        val buttons = screen(
            el("E1", "Background", PixelBounds(500, 740, 580, 795), type = "button"),
            el("E2", "Popup", PixelBounds(870, 740, 920, 795), type = "button"),
        )
        val refined = OcrTextRefiner.refine(buttons, listOf(
            line("F", 528, 741, 555, 771, conf = 0.39f),
            line("Backgr", 507, 771, 546, 789, conf = 0.86f),
            line("□", 882, 746, 912, 770, conf = 0.73f),
        ))
        assertEquals("Background", refined.element("E1")!!.text)
        assertEquals("Popup", refined.element("E2")!!.text)
    }

    @Test
    fun `an element without text takes the OCR text inside it`() {
        val refined = OcrTextRefiner.refine(screen(el("E1", "", PixelBounds(0, 0, 400, 100))), listOf(line("Save canvas", 10, 20, 200, 60)))
        assertEquals("Save canvas", refined.element("E1")!!.text)
    }

    @Test
    fun `text-like elements snap to the exact text position`() {
        val coarse = screen(el("E1", "Save canvas", PixelBounds(20, 400, 700, 500), type = "text_btn"))
        val refined = OcrTextRefiner.refine(coarse, listOf(line("Save canvas", 440, 430, 640, 470)))
        assertEquals(PixelBounds(432, 422, 648, 478), refined.element("E1")!!.bounds)
    }

    @Test
    fun `a misplaced element moves to where its text is drawn when unambiguous`() {
        val misplaced = screen(el("E1", "Show grid", PixelBounds(20, 900, 300, 960), type = "text"))
        val refined = OcrTextRefiner.refine(misplaced, listOf(line("Show grid", 24, 330, 140, 356)))
        assertEquals("Show grid", refined.element("E1")!!.text)
        assertEquals(PixelBounds(16, 322, 148, 364), refined.element("E1")!!.bounds)
    }

    @Test
    fun `duplicate texts are not moved onto each other`() {
        val page = screen(
            el("E1", "资料库", PixelBounds(69, 46, 136, 69), type = "text"),
            el("E2", "资料库", PixelBounds(380, 1881, 432, 1904), type = "tab"),
        )
        val refined = OcrTextRefiner.refine(page, listOf(line("资料库", 372, 1872, 438, 1911, conf = 1f)))
        assertEquals(PixelBounds(69, 46, 136, 69), refined.element("E1")!!.bounds)
        assertTrue(refined.element("E1")!!.purpose.contains(OcrTextRefiner.UNCONFIRMED))
    }

    @Test
    fun `missed text is added and pictures keep their artwork text out of labels`() {
        val parsed = screen(
            el("E1", "Brush color", PixelBounds(20, 150, 300, 190), type = "text"),
            el("E2", "", PixelBounds(600, 500, 900, 700), type = "image"),
        )
        val refined = OcrTextRefiner.refine(parsed, listOf(
            line("Brush color", 24, 158, 200, 184),
            line("Blue", 260, 292, 300, 308),
            line("FIGHTS", 650, 550, 800, 600),
            line("12:45", 10, 5, 60, 20),
        ), statusBarBottom = 40)
        assertEquals(listOf("Brush color", "", "Blue"), refined.elements.map { it.text })
        assertEquals(OcrTextRefiner.ADDED_PURPOSE, refined.element("E3")!!.purpose)
    }

    @Test
    fun `a parse of another image disagrees with OCR`() {
        val parse = screen(
            el("E1", "24 hours", PixelBounds(0, 100, 200, 150)), el("E2", "7 days", PixelBounds(0, 160, 200, 210)),
            el("E3", "90 days", PixelBounds(0, 220, 200, 270)), el("E4", "Custom range", PixelBounds(0, 280, 200, 330)),
        )
        val workbench = listOf(line("Canvas Workbench", 431, 41, 656, 86), line("Layers", 141, 122, 213, 153),
            line("Brush", 503, 119, 564, 153), line("Export", 863, 119, 930, 153))
        assertEquals(0.0, OcrTextRefiner.agreement(parse, workbench)!!, 0.0)
        val right = screen(
            el("E1", "Canvas Workbench", PixelBounds(430, 40, 660, 90)), el("E2", "Layers", PixelBounds(0, 110, 360, 170)),
            el("E3", "Brush", PixelBounds(360, 110, 720, 170)), el("E4", "Export", PixelBounds(720, 110, 1080, 170)),
        )
        assertEquals(1.0, OcrTextRefiner.agreement(right, workbench)!!, 0.0)
    }

    @Test
    fun `a painted counter digit is kept while single glyphs are dropped`() {
        val parsed = screen(el("E1", "Copies", PixelBounds(18, 420, 300, 460), type = "text"))
        val refined = OcrTextRefiner.refine(parsed, listOf(
            line("Copies", 30, 425, 120, 455),
            line("2", 948, 420, 966, 450, conf = 0.99f),
            line("+", 1000, 420, 1030, 450, conf = 0.99f),
        ))
        assertEquals(listOf("Copies", "2"), refined.elements.map { it.text })
    }
}
