package bot.coati.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderGeometryTest {

    // A Quick Settings brightness track from x=54 to x=1000 on a 1080 px wide screen.
    private val left = 54
    private val top = 390
    private val right = 1000
    private val bottom = 450

    @Test
    fun `a tap just past the end of a track still reaches the slider`() {
        // The model tapped at x=1026 for "maximum": past the track's right bound.
        assertTrue(CoatiAccessibilityService.sliderReaches(left, top, right, bottom, 1026, 420, slack = 63))
        assertTrue(CoatiAccessibilityService.sliderReaches(left, top, right, bottom, 30, 420, slack = 63))
        assertFalse(CoatiAccessibilityService.sliderReaches(left, top, right, bottom, 540, 600, slack = 63))
        assertFalse(CoatiAccessibilityService.sliderReaches(left, top, right, bottom, 1026, 420, slack = 0))
    }

    @Test
    fun `only the ends of a track snap and a point past the end counts as the end`() {
        assertEquals(255f, CoatiAccessibilityService.sliderEndValue(left, top, right, bottom, 1026, 420, 0f, 255f))
        assertEquals(255f, CoatiAccessibilityService.sliderEndValue(left, top, right, bottom, 990, 420, 0f, 255f))
        assertEquals(0f, CoatiAccessibilityService.sliderEndValue(left, top, right, bottom, 40, 420, 0f, 255f))
        assertNull(CoatiAccessibilityService.sliderEndValue(left, top, right, bottom, 540, 420, 0f, 255f))
    }

    @Test
    fun `a vertical slider's high end is its top`() {
        assertEquals(100f, CoatiAccessibilityService.sliderEndValue(500, 200, 560, 1200, 530, 205, 0f, 100f))
        assertEquals(0f, CoatiAccessibilityService.sliderEndValue(500, 200, 560, 1200, 530, 1210, 0f, 100f))
    }
}
