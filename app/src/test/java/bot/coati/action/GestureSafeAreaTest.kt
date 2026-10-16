package bot.coati.action

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureSafeAreaTest {
    private val insets = PixelBounds(left = 40, top = 0, right = 40, bottom = 66)

    private fun clamp(x1: Int, y1: Int, x2: Int, y2: Int) =
        GestureSafeArea.clamp(GestureSafeArea.Swipe(x1, y1, x2, y2), 1272, 2800, insets, density = 3f)

    @Test
    fun `a horizontal swipe on the bottom gesture bar is moved above it`() {
        // The ColorOS case: y=2734 switched apps. 48 dp at density 3 is 144 px, more than the reported 66 px.
        assertEquals(GestureSafeArea.Swipe(255, 2655, 1017, 2655), clamp(255, 2734, 1017, 2734))
    }

    @Test
    fun `swipes from the side edges stay out of the back gesture`() {
        assertEquals(GestureSafeArea.Swipe(40, 1200, 1000, 1200), clamp(0, 1200, 1000, 1200))
        assertEquals(GestureSafeArea.Swipe(1231, 1200, 300, 1200), clamp(1271, 1200, 300, 1200))
    }

    @Test
    fun `swipes in the content area and from the top edge are unchanged`() {
        assertEquals(GestureSafeArea.Swipe(540, 1900, 540, 600), clamp(540, 1900, 540, 600))
        // Pulling down the notification shade or quick settings starts at the very top.
        assertEquals(GestureSafeArea.Swipe(540, 0, 540, 1600), clamp(540, 0, 540, 1600))
    }
}
