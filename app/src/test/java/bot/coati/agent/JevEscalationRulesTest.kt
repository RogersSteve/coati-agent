package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import bot.coati.jev.CandidateAction
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

    @Test
    fun `an unsure switch to another app goes to the Reasoning model`() {
        val openKeep = CandidateAction.OpenApp("Keep Notes")
        assertTrue(JevStepExecutor.isUnsureAppSwitch(openKeep, onHomeScreen = false, explored = true, confidence = 0.47))
        assertTrue(JevStepExecutor.isUnsureAppSwitch(openKeep, onHomeScreen = false, explored = false, confidence = 0.5))
    }

    @Test
    fun `confident switches, launches from home and in-app actions stay on the fast route`() {
        val openKeep = CandidateAction.OpenApp("Keep Notes")
        assertFalse(JevStepExecutor.isUnsureAppSwitch(openKeep, onHomeScreen = false, explored = false, confidence = 0.8))
        assertFalse(JevStepExecutor.isUnsureAppSwitch(openKeep, onHomeScreen = true, explored = true, confidence = 0.3))
        assertFalse(
            JevStepExecutor.isUnsureAppSwitch(CandidateAction.ReturnToApp("com.example"), onHomeScreen = false, explored = true, confidence = 0.3),
        )
    }
}
