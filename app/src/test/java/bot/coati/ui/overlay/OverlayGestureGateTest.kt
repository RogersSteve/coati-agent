package bot.coati.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGestureGateTest {

    @Test
    fun `controls remain blocked until outermost passthrough exits`() {
        val gate = OverlayGestureGate()

        assertFalse(gate.blocksTaskControls)
        gate.enter()
        gate.enter()
        assertTrue(gate.blocksTaskControls)

        gate.exit()
        assertTrue(gate.blocksTaskControls)
        gate.exit()
        assertFalse(gate.blocksTaskControls)
    }

    @Test
    fun `a touch on the overlay during passthrough is a swallowed gesture`() {
        val gate = OverlayGestureGate()

        assertFalse(gate.noteOverlayTouch())
        assertFalse(gate.takeSwallowed())

        gate.enter()
        assertTrue(gate.noteOverlayTouch())
        assertTrue(gate.takeSwallowed())
        assertFalse(gate.takeSwallowed())
    }

    @Test
    fun `extra exit cannot make later scope unprotected`() {
        val gate = OverlayGestureGate()

        gate.exit()
        gate.enter()

        assertTrue(gate.blocksTaskControls)
    }
}
