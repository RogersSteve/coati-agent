package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrReadingOrderTest {

    @Test
    fun `lines sort by rows then left to right`() {
        val a = OcrLine("a", PixelBounds(300, 102, 400, 140))
        val b = OcrLine("b", PixelBounds(10, 100, 200, 138))
        val c = OcrLine("c", PixelBounds(10, 200, 200, 240))
        val d = OcrLine("d", PixelBounds(500, 10, 600, 40))

        val sorted = OcrReadingOrder.sort(listOf(a, c, b, d))

        assertEquals(listOf("d", "b", "a", "c"), sorted.map { it.text })
    }

    @Test
    fun `native result maps to scaled lines in reading order`() {
        val texts = arrayOf("second", "first", "  ")
        val values = floatArrayOf(
            0.9f, 10f, 100f, 50f, 120f,
            0.8f, 10f, 10f, 60f, 30f,
            0.7f, 0f, 0f, 5f, 5f,
        )

        val lines = OcrReadingOrder.toLines(texts, values, scale = 1.5f)

        assertEquals(listOf("first", "second"), lines.map { it.text })
        assertEquals(PixelBounds(15, 15, 90, 45), lines[0].bounds)
        assertEquals(PixelBounds(15, 150, 75, 180), lines[1].bounds)
        assertEquals(0.8f, lines[0].confidence, 0f)
    }

    @Test
    fun `empty native result gives no lines`() {
        assertTrue(OcrReadingOrder.toLines(emptyArray(), FloatArray(0), 1f).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched native arrays are rejected`() {
        OcrReadingOrder.toLines(arrayOf("x"), FloatArray(4), 1f)
    }
}
