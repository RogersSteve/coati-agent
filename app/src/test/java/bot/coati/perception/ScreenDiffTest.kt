package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiffTest {

    private fun grid(w: Int = 90, h: Int = 200, fill: Int = 200) = LumaGrid(w, h, IntArray(w * h) { fill })

    private fun LumaGrid.paint(x0: Int, y0: Int, x1: Int, y1: Int, value: Int): LumaGrid {
        val copy = values.copyOf()
        for (y in y0 until y1) for (x in x0 until x1) copy[y * width + x] = value
        return LumaGrid(width, height, copy)
    }

    @Test
    fun `identical frames and jpeg-level noise report no change`() {
        val before = grid()
        val noisy = LumaGrid(90, 200, IntArray(90 * 200) { 200 + (it % 7) - 3 })

        val change = ScreenDiff.compare(before, noisy, 1080, 2400)!!

        assertEquals(0.0, change.changedFraction, 0.0)
        assertNull(change.bounds)
    }

    @Test
    fun `a toggled switch is localized in display pixels`() {
        val before = grid()
        val after = before.paint(70, 50, 80, 55, 60) // a knob moved inside a 10x5 cell area

        val change = ScreenDiff.compare(before, after, 1080, 2400)!!

        assertEquals(PixelBounds(840, 600, 960, 660), change.bounds)
        assertTrue(change.changedFraction < 0.01)
    }

    @Test
    fun `status bar changes are ignored`() {
        val after = grid().paint(0, 0, 90, 5, 0)

        assertEquals(0.0, ScreenDiff.compare(grid(), after, 1080, 2400)!!.changedFraction, 0.0)
    }

    @Test
    fun `frames of different geometry are not comparable`() {
        assertNull(ScreenDiff.compare(grid(), grid(w = 200, h = 90), 1080, 2400))
    }
}
