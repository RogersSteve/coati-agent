package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextRefinerTest {

    private fun el(id: String, text: String, b: PixelBounds) =
        ScreenElement(id, "item", text, "", listOf(ElementOp.TAP), null, b)

    private val screen = ParsedScreen(
        source = ScreenSource.VISION,
        packageName = "p",
        summary = "",
        keyboardVisible = false,
        overlay = null,
        elements = listOf(
            el("E1", "在搞点呢", PixelBounds(600, 1700, 900, 1800)),
            el("E2", "", PixelBounds(20, 20, 80, 80)),
            el("E3", "日为萌", PixelBounds(100, 100, 900, 200)),
        ),
        regions = emptyList(),
    )

    @Test
    fun `ocr lines inside an element replace the model transcription in reading order`() {
        val lines = listOf(
            OcrLine("在高点呢", PixelBounds(640, 1720, 860, 1780), 0.98f),
            OcrLine("月为暮。", PixelBounds(400, 120, 700, 180), 0.95f),
            OcrLine("日为朝，", PixelBounds(120, 122, 390, 178), 0.97f),
        )

        val refined = OcrTextRefiner.refine(screen, lines)

        assertEquals("在高点呢", refined.element("E1")!!.text)
        assertEquals("日为朝，\n月为暮。", refined.element("E3")!!.text)
    }

    @Test
    fun `elements without ocr evidence keep their text`() {
        val refined = OcrTextRefiner.refine(screen, listOf(OcrLine("x", PixelBounds(500, 500, 520, 520))))

        assertEquals(screen.elements, refined.elements)
    }
}
