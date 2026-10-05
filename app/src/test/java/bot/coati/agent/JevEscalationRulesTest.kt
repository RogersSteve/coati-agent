package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevEscalationRulesTest {
    @Test
    fun `a low-confidence exploration goes to the Reasoning model`() {
        assertTrue(JevStepExecutor.isLowConfidenceGuess(explored = true, confidence = 0.33))
    }

    @Test
    fun `confident or goal-directed choices stay on the fast route`() {
        assertFalse(JevStepExecutor.isLowConfidenceGuess(explored = true, confidence = 0.6))
        assertFalse(JevStepExecutor.isLowConfidenceGuess(explored = false, confidence = 0.2))
    }
}
