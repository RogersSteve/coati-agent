package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import bot.coati.data.RoutingMode
import bot.coati.data.RoutingPolicy
import bot.coati.jev.CandidateAction
import bot.coati.perception.ElementOp
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
    fun `every switch to another app from inside an app goes to the Reasoning model`() {
        // Yudou's ColorOS demo: a 0.82-confidence switch to Keep left Maps before the opening hours were read.
        assertTrue(JevStepExecutor.isAppSwitch(CandidateAction.OpenApp("Keep Notes"), onHomeScreen = false))
        assertTrue(JevStepExecutor.isAppSwitch(CandidateAction.ReturnToApp("com.google.android.apps.maps"), onHomeScreen = false))
    }

    @Test
    fun `launches from a home screen and in-app actions are not switches`() {
        assertFalse(JevStepExecutor.isAppSwitch(CandidateAction.OpenApp("Maps"), onHomeScreen = true))
        assertFalse(JevStepExecutor.isAppSwitch(CandidateAction.Done, onHomeScreen = false))
        assertFalse(JevStepExecutor.isAppSwitch(CandidateAction.Element("add", ElementOp.TAP), onHomeScreen = false))
    }

    @Test
    fun `only the fast mode types text on the fast path`() {
        assertTrue(RoutingPolicy.of(RoutingMode.FAST).fastTextInput)
        assertFalse(RoutingPolicy.of(RoutingMode.BALANCED).fastTextInput)
        assertFalse(RoutingPolicy.of(RoutingMode.ACCURATE).fastTextInput)
        assertFalse(RoutingPolicy.custom(0.7, 1.01).fastTextInput)
    }
}
