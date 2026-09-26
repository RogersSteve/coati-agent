package bot.coati.debug

import org.junit.Assert.*
import org.junit.Test

class FixtureForegroundDiagnosticsTest {
    private val good = FixtureForegroundState(true, true, false, true, true)
    @Test fun `both lifecycle and focus remain mandatory`() {
        assertNull(good.failure())
        assertEquals("fixture_not_resumed", good.copy(resumed=false).failure())
        assertEquals("fixture_window_unfocused", good.copy(focused=false).failure())
        assertEquals("fixture_not_resumed", good.copy(resumed=false, focused=false).failure())
    }
    @Test fun `overlay and accessibility guards remain mandatory`() {
        assertEquals("stop_coati_overlay_service_first", good.copy(productServiceRunning=true).failure())
        assertEquals("accessibility_not_ready", good.copy(accessibilityPresent=false).failure())
        assertEquals("accessibility_instance_changed", good.copy(accessibilitySame=false).failure())
    }
    @Test fun `later cancellation cannot replace first failed checkpoint`() {
        val failure=FixtureFirstFailure()
        failure.record("fixture_window_unfocused", "capture_before_screenshot:tap")
        failure.record("cancelled", "onPause")
        assertEquals("fixture_window_unfocused", failure.code)
        assertEquals("capture_before_screenshot:tap", failure.step)
    }
}
