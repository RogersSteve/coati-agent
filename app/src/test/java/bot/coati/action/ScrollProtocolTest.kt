package bot.coati.action

import bot.coati.coordinates.*
import bot.coati.agent.PromptBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScrollProtocolTest {
    private fun space(w: Int=1080, h: Int=2400): ScreenshotCoordinates {
        val d=DisplaySpace(w,h,0,0,PixelBounds(0,0,w,h),PixelBounds(0,0,w,h),PixelBounds(0,0,0,0),420,1,"test",1f)
        return ScreenshotCoordinates(d,w,h,w,h)
    }
    @Test fun `four browsing directions are opposite finger travel`() {
        val expected=mapOf("DOWN" to (0 to -500),"UP" to (0 to 500),"RIGHT" to (-500 to 0),"LEFT" to (500 to 0))
        for ((direction, delta) in expected) {
            val g=ModelCoordinateAction.scrollGesture(AgentAction.Scroll(direction,500),space())
            assertEquals(delta,g.x2-g.x1 to g.y2-g.y1); assertEquals(400L,g.durationMs)
        }
    }
    @Test fun `a default scroll travels most of the scrollable container through its centre`() {
        // A list from y=300 to y=2300 on a 1080x2400 display: 75% of its 2000 px.
        val list=PixelBounds(0,300,1080,2300)
        val down=ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN"),space(),list)
        assertEquals(0 to -1500,down.x2-down.x1 to down.y2-down.y1)
        assertEquals(540,down.x1)
        assertTrue(down.y1<2300 && down.y2>=300)
        assertEquals(1200L,down.durationMs) // the old default's finger speed
        val up=ModelCoordinateAction.scrollGesture(AgentAction.Scroll("UP"),space(),list)
        assertEquals(1500,up.y2-up.y1)
        // No container: 60% of the display.
        val screen=ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN"),space())
        assertEquals(-1440,screen.y2-screen.y1)
        val side=ModelCoordinateAction.scrollGesture(AgentAction.Scroll("RIGHT"),space())
        assertEquals(-648,side.x2-side.x1)
        // A tiny or off-screen container is ignored; an explicit amount ignores the container.
        assertEquals(screen,ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN"),space(),PixelBounds(0,0,1080,100)))
        assertEquals(ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN",500),space()),
            ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN",500),space(),list))
    }
    @Test fun `odd distances and smallest and largest amounts remain exact and in bounds`() {
        for ((w,h) in listOf(1080 to 2400,1272 to 2772,2400 to 1080,3 to 5,2 to 2)) {
            for (direction in listOf("UP","DOWN","LEFT","RIGHT")) {
                val axis=if(direction in listOf("UP","DOWN"))h else w
                for(amount in listOf(1,axis-1,minOf(501,axis-1))) {
                    val g=ModelCoordinateAction.scrollGesture(AgentAction.Scroll(direction,amount),space(w,h))
                    assertEquals(amount,kotlin.math.abs(g.x2-g.x1)+kotlin.math.abs(g.y2-g.y1))
                    assertTrue(g.x1 in 0 until w && g.x2 in 0 until w && g.y1 in 0 until h && g.y2 in 0 until h)
                }
                assertThrows(IllegalArgumentException::class.java) {ModelCoordinateAction.scrollGesture(AgentAction.Scroll(direction,axis),space(w,h))}
            }
        }
    }
    @Test fun `legacy map and model paths share defaults and case insensitive contract`() {
        for (direction in listOf("up","Down","LEFT","right")) {
            val params=mapOf<String,Any>("direction" to direction)
            val old=AgentAction.fromMap("scroll",params) as AgentAction.Scroll
            assertNull(old.amount)
            val model=ModelCoordinateAction.resolve("scroll",params,null,null,space()) as AgentAction.Scroll
            assertEquals(old,model)
            assertEquals(ScrollProtocol.gesture(old,1080,2400),ModelCoordinateAction.scrollGesture(model,space()))
        }
    }
    @Test fun `invalid input never defaults truncates clamps or overflows`() {
        for (value in listOf<Any>("500",1.5,Double.NaN,Double.POSITIVE_INFINITY,0,-1,Long.MAX_VALUE)) {
            val params=mapOf("direction" to "DOWN","amount" to value)
            assertThrows(IllegalArgumentException::class.java) {AgentAction.fromMap("scroll",params)}
            assertThrows(IllegalArgumentException::class.java) {ModelCoordinateAction.resolve("scroll",params,null,null,space())}
        }
        for (direction in listOf("","SIDEWAYS"," DOWN "))
            assertThrows(IllegalArgumentException::class.java) {AgentAction.Scroll(direction)}
        assertThrows(IllegalArgumentException::class.java) {AgentAction.fromMap("scroll",emptyMap())}
        assertThrows(IllegalArgumentException::class.java) {ModelCoordinateAction.scrollGesture(AgentAction.Scroll("DOWN",Int.MAX_VALUE),space())}
        assertThrows(IllegalArgumentException::class.java) {ModelCoordinateAction.resolve("scroll",mapOf("direction" to "UP"),null,null,null)}
    }
    @Test fun `explicit swipe endpoints are never reversed`() {
        val params=mapOf("x1" to 500,"y1" to 800,"x2" to 500,"y2" to 200)
        assertEquals(AgentAction.Swipe(540,1920,540,480),ModelCoordinateAction.resolve("swipe",params,null,null,space()))
        assertEquals(AgentAction.Swipe(500,800,500,200),AgentAction.fromMap("swipe",params))
    }
    @Test fun `prompt publishes browsing and physical distance semantics`() {
        val prompt=PromptBuilder.buildReasoningUserPrompt("", "test",1080,2400)
        for (term in listOf("DOWN reveals lower content (finger moves up)",
            "UP reveals upper content (finger moves down)", "RIGHT reveals content to the right (finger moves left)",
            "LEFT reveals content to the left (finger moves right)", "physical-pixel", "Omit amount for normal browsing", "not clamped",
            "explicit finger start/end positions")) assertTrue(term,prompt.contains(term))
    }
    @Test fun `stale scroll binding rejects before sink`() = runBlocking {
        val s=space(); var dispatched=false
        try {s.dispatch({s.display.copy(rotation=1)}) {dispatched=true}; fail()}
        catch (_:IllegalArgumentException) {assertFalse(dispatched)}
    }
}
