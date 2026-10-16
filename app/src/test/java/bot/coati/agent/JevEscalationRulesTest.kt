package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import bot.coati.data.RoutingMode
import bot.coati.data.RoutingPolicy
import bot.coati.jev.CandidateAction
import org.junit.Test

class JevEscalationRulesTest {
    @Test
    fun `balanced routing escalates unsure explorations and keeps goal-directed choices fast`() {
        val balanced = RoutingPolicy.of(RoutingMode.BALANCED)
        assertTrue(balanced.escalates(explored = true, confidence = 0.55))
        assertFalse(balanced.escalates(explored = true, confidence = 0.6))
        assertFalse(balanced.escalates(explored = false, confidence = 0.2))
    }

    @Test
    fun `fast routing escalates only explorations below 0_4`() {
        val fast = RoutingPolicy.of(RoutingMode.FAST)
        assertTrue(fast.escalates(explored = true, confidence = 0.33))
        assertFalse(fast.escalates(explored = true, confidence = 0.45))
    }

    @Test
    fun `accurate routing keeps only confident goal-directed choices fast`() {
        val accurate = RoutingPolicy.of(RoutingMode.ACCURATE)
        assertFalse(accurate.escalates(explored = false, confidence = 0.8))
        assertTrue(accurate.escalates(explored = false, confidence = 0.79))
        assertTrue(accurate.escalates(explored = true, confidence = 1.0))
    }

    @Test
    fun `explicit thresholds describe themselves`() {
        val custom = RoutingPolicy.custom(0.7, 1.01)
        assertTrue(custom.escalates(explored = false, confidence = 0.65))
        assertTrue(custom.label.startsWith("CUSTOM(0.7"))
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
