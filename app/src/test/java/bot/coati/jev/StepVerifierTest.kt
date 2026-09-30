package bot.coati.jev

import bot.coati.coordinates.PixelBounds
import bot.coati.perception.ElementOp
import bot.coati.perception.VisualChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepVerifierTest {

    private val switchBounds = PixelBounds(800, 600, 1000, 700)
    private val before = screen(
        element("E1", "紧凑资料卡片", type = "switch", bounds = switchBounds),
        element("E2", "实验档案"),
        element("E3", "返回"),
    )
    private val tapSwitch = CandidateAction.Element("E1", ElementOp.TAP)

    @Test
    fun `nothing moved in pixels or texts is a certain no-change`() {
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.0002, null))

        assertEquals(HostVerdict.NO_CHANGE, obs.verdict)
    }

    @Test
    fun `a tiny change on the target is never mistaken for no change`() {
        // Device regression: a toggled switch changed 0.39% of the frame and was read as "no change".
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.0039, PixelBounds(900, 630, 960, 670)))

        assertEquals(HostVerdict.CHANGED_AT_TARGET, obs.verdict)
        assertTrue(obs.note.contains("pixels changed at '紧凑资料卡片'"))
    }

    @Test
    fun `a state label flipping below the global threshold still counts at the target`() {
        // Board regression: "Visible" -> "Hidden" on a painted layer row changed 0.04% of the frame.
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.0004, PixelBounds(900, 640, 960, 660)))

        assertEquals(HostVerdict.CHANGED_AT_TARGET, obs.verdict)
    }

    @Test
    fun `a small change next to the target is not a no-change`() {
        // Board regression: "+" raised a painted counter beside it from 1 to 2; judged no change.
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.0002, PixelBounds(700, 620, 760, 680)))

        assertTrue(obs.verdict != HostVerdict.NO_CHANGE)
    }

    @Test
    fun `a custom switch with no tree state still counts via pixels at the target`() {
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.02, PixelBounds(850, 620, 960, 680)))

        assertEquals(HostVerdict.CHANGED_AT_TARGET, obs.verdict)
    }

    @Test
    fun `a native switch whose state flips is a change at the target`() {
        val after = screen(
            element("E7", "紧凑资料卡片", type = "switch", state = "on", bounds = switchBounds),
            element("E8", "实验档案"),
            element("E9", "返回"),
        )

        val obs = StepVerifier.observe(before, after, tapSwitch, null, VisualChange(0.01, PixelBounds(0, 0, 10, 10)))

        assertEquals(HostVerdict.CHANGED_AT_TARGET, obs.verdict)
        assertTrue(obs.note.contains("target state null -> on"))
    }

    @Test
    fun `a change elsewhere is not credited to the target`() {
        val obs = StepVerifier.observe(before, before, tapSwitch, null, VisualChange(0.03, PixelBounds(0, 2000, 1080, 2300)))

        assertEquals(HostVerdict.CHANGED_ELSEWHERE, obs.verdict)
    }

    @Test
    fun `a new page is a page change`() {
        val after = screen(element("E1", "设置"), element("E2", "高级设置"), element("E3", "恢复测试初始状态"))

        val obs = StepVerifier.observe(before, after, tapSwitch, null, VisualChange(0.6, PixelBounds(0, 100, 1080, 2400)))

        assertEquals(HostVerdict.PAGE_CHANGED, obs.verdict)
    }

    @Test
    fun `expectations are phrased from the action type`() {
        assertTrue(StepVerifier.expectation(tapSwitch, before, null).contains("toggle flips"))
        val input = CandidateAction.Element("E2", ElementOp.INPUT)
        assertEquals("the field shows the text 'travel'", StepVerifier.expectation(input, before, "travel"))
    }
}
