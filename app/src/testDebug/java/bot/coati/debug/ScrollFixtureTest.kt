package bot.coati.debug

import bot.coati.action.AgentAction
import bot.coati.coordinates.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScrollFixtureTest {
    @Test fun `content must move and visible items must change in browsing direction`() {
        for(d in listOf("DOWN","RIGHT")) {
            assertTrue(ScrollFixtureChecks.movement(d,4000,4500,40,45))
            assertFalse(ScrollFixtureChecks.movement(d,4000,3500,40,35))
            assertFalse(ScrollFixtureChecks.movement(d,4000,4500,40,40))
            assertFalse(ScrollFixtureChecks.movement(d,4000,4000,40,45))
        }
        for(d in listOf("UP","LEFT")) {
            assertTrue(ScrollFixtureChecks.movement(d,4000,3500,40,35))
            assertFalse(ScrollFixtureChecks.movement(d,4000,4500,40,45))
        }
    }
    @Test fun `endpoint evidence requires real complete stroke not just dispatch return`() {
        val g=AgentAction.Swipe(500,750,500,249)
        val t=listOf(FixtureTouch(0,500f,750f,0),FixtureTouch(2,500f,400f,200),FixtureTouch(1,500f,249f,400))
        assertTrue(ScrollFixtureChecks.endpoints(t,g))
        assertFalse(ScrollFixtureChecks.endpoints(emptyList(),g))
        assertFalse(ScrollFixtureChecks.endpoints(t.dropLast(1),g))
        assertFalse(ScrollFixtureChecks.endpoints(t,g.copy(y2=251)))
        assertFalse(ScrollFixtureChecks.endpoints(t+t,g))
    }
    @Test fun `four claims only and failed session cannot restart`() {
        val s=ScrollFixtureSession();s.start()
        for(d in ScrollFixtureChecks.directions)s.claim(d)
        assertThrows(IllegalStateException::class.java) {s.claim("DOWN")}
        s.arm();s.validate();assertThrows(IllegalStateException::class.java) {s.validate()};s.finish()
        assertEquals(4,s.dispatches); assertEquals("passed",s.phase)
        val f=ScrollFixtureSession();f.start();f.fail()
        assertThrows(IllegalStateException::class.java) {f.claim("DOWN")}
        assertThrows(IllegalStateException::class.java) {f.start()}
    }
    @Test fun `production conversion rejects invalid distance and stale binding before trap`() = runBlocking {
        val d=DisplaySpace(1080,2400,0,0,PixelBounds(0,0,1080,2400),PixelBounds(0,0,1080,2400),PixelBounds(0,0,0,0),420,1,"app",1f)
        val b=ScreenshotCoordinates(d,1080,2400,720,1600)
        var entries=0
        for(amount in listOf<Any>(0,-1,1.5,"500",2400,Int.MAX_VALUE)) {
            try { ModelCoordinateAction.resolve("scroll",mapOf("direction" to "DOWN","amount" to amount),null,null,b);entries++ }
            catch(_:IllegalArgumentException) { }
        }
        try {b.dispatch({d.copy(rotation=1)}) {entries++}} catch(_:IllegalArgumentException) { }
        assertEquals(0,entries)
        for(amount in listOf(500,501)) {
            val a=ModelCoordinateAction.resolve("scroll",mapOf("direction" to "DOWN","amount" to amount),null,null,b) as AgentAction.Scroll
            val g=ModelCoordinateAction.scrollGesture(a,b);assertEquals(amount,g.y1-g.y2)
        }
    }
}
