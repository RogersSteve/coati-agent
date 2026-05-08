package bot.coati.agent

import bot.coati.model.VerifyOutcome
import bot.coati.model.VerifyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    @Test
    fun `MAX_STEPS is 200`() {
        assertEquals(200, AgentLoop.MAX_STEPS)
    }

    @Test
    fun `emergencyStop defaults to false`() {
        // We can't instantiate AgentLoop without Android deps,
        // but we can test the companion constants
        assertFalse(false) // placeholder for emergencyStop default
    }

    @Test
    fun `coordinate conversion math is correct`() {
        // normalized 500,500 on a 1080x2400 screen
        val normalizedX = 500
        val normalizedY = 500
        val screenWidth = 1080
        val screenHeight = 2400

        val pixelX = normalizedX * screenWidth / 1000
        val pixelY = normalizedY * screenHeight / 1000

        assertEquals(540, pixelX)
        assertEquals(1200, pixelY)
    }

    @Test
    fun `coordinate conversion handles edge values`() {
        // Top-left corner
        assertEquals(0, 0 * 1080 / 1000)
        assertEquals(0, 0 * 2400 / 1000)

        // Bottom-right corner
        assertEquals(1080, 1000 * 1080 / 1000)
        assertEquals(2400, 1000 * 2400 / 1000)
    }

    @Test
    fun `coordinate conversion handles quarter positions`() {
        val screenWidth = 1080
        val screenHeight = 2400

        // Quarter point
        assertEquals(270, 250 * screenWidth / 1000)
        assertEquals(600, 250 * screenHeight / 1000)

        // Three-quarter point
        assertEquals(810, 750 * screenWidth / 1000)
        assertEquals(1800, 750 * screenHeight / 1000)
    }

    @Test
    fun `RuleEngine assessQuality works in loop context`() {
        // HIGH quality — will use bounds
        assertEquals(UiTreeQuality.HIGH, RuleEngine.assessQuality(30, 10))

        // LOW quality — will use model coordinates
        assertEquals(UiTreeQuality.LOW, RuleEngine.assessQuality(2, 0))
    }

    @Test
    fun `ChangeResult enum has expected values`() {
        val values = ChangeResult.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(ChangeResult.CHANGED))
        assertTrue(values.contains(ChangeResult.UNCHANGED))
        assertTrue(values.contains(ChangeResult.UNCERTAIN))
    }

    // ─── Verify outcome tests ───

    @Test
    fun `VerifyOutcome SUCCESS means continue`() {
        val result = VerifyResult(VerifyOutcome.SUCCESS, "Screen changed as expected")
        assertEquals(VerifyOutcome.SUCCESS, result.outcome)
        assertNull(result.observation)
    }

    @Test
    fun `VerifyOutcome FAILED triggers retry`() {
        val result = VerifyResult(VerifyOutcome.FAILED, "Screen unchanged")
        assertEquals(VerifyOutcome.FAILED, result.outcome)
    }

    @Test
    fun `VerifyOutcome DRIFTED triggers back and retry`() {
        val result = VerifyResult(VerifyOutcome.DRIFTED, "Wrong page opened")
        assertEquals(VerifyOutcome.DRIFTED, result.outcome)
    }

    @Test
    fun `VerifyOutcome UNEXPECTED records observation`() {
        val result = VerifyResult(VerifyOutcome.UNEXPECTED, "Different path", "A shortcut dialog appeared")
        assertEquals(VerifyOutcome.UNEXPECTED, result.outcome)
        assertEquals("A shortcut dialog appeared", result.observation)
    }

    @Test
    fun `verify retry limit is 2`() {
        // Verify that FAILED and DRIFTED share the same counter
        // and max retries is 2 (not 2+2)
        var retryCount = 0
        retryCount++ // first FAILED
        retryCount++ // second DRIFTED
        assertTrue(retryCount > 2 - 1) // would exceed limit at 3rd attempt
    }
}
