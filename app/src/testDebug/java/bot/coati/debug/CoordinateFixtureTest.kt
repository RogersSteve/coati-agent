package bot.coati.debug

import bot.coati.coordinates.*
import bot.coati.action.AgentAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoordinateFixtureTest {
    private val box=PixelBounds(700,2190,810,2270)
    private fun events(duration: Long=100, endX: Float=756f, endY: Float=2232f) = listOf(
        FixtureTouch(0,756f,2232f,10), FixtureTouch(2,endX,endY,10+duration/2), FixtureTouch(1,endX,endY,10+duration))
    private fun space(): ScreenshotCoordinates {
        val d=DisplaySpace(1080,2400,0,0,PixelBounds(0,0,1080,2400),PixelBounds(0,0,1080,2400),PixelBounds(0,128,0,63),420,1,"bot.coati",1f)
        return ScreenshotCoordinates(d,1080,2400,720,1600)
    }
    @Test fun `production resolver reproduces fixed height hit without fixture forward math`() {
        val s=space()
        val action=ModelCoordinateAction.resolve("tap",emptyMap(),700,930,s) as AgentAction.Tap
        assertEquals(756,action.x); assertEquals(2232,action.y)
        assertTrue(CoordinateFixtureChecks.hit("tap",events(),box))
        val missed=listOf(FixtureTouch(0,756f,2054f,10),FixtureTouch(1,756f,2054f,110))
        assertFalse(CoordinateFixtureChecks.hit("tap",missed,box))
    }
    @Test fun `actual touches must prove hold or swipe and cancellation is failure`() {
        assertFalse(CoordinateFixtureChecks.hit("longpress",events(100),box))
        assertTrue(CoordinateFixtureChecks.hit("longpress",events(1000),box))
        assertTrue(CoordinateFixtureChecks.hit("swipe",events(500,900f,2232f),box,PixelBounds(850,2190,950,2270)))
        assertFalse(CoordinateFixtureChecks.hit("tap",events().dropLast(1)+FixtureTouch(3,756f,2232f,110),box))
        assertFalse(CoordinateFixtureChecks.hit("tap",events()+events(),box))
        assertFalse(CoordinateFixtureChecks.hit("tap",emptyList(),box))
    }
    @Test fun `one bounded sequence never permits repeat or fourth action`() {
        val session=CoordinateFixtureSession();session.start()
        session.claim("tap");session.claim("longpress");session.claim("swipe")
        assertEquals(3,session.dispatches)
        assertThrows(IllegalStateException::class.java) {session.claim("tap")}
        session.arm();session.validateOnce();session.finish()
        assertEquals("passed",session.phase)
        assertThrows(IllegalStateException::class.java) {session.validateOnce()}
        assertThrows(IllegalStateException::class.java) {session.start()}
    }
    @Test fun `failure ends sequence without automatic retry`() {
        val session=CoordinateFixtureSession();session.start();session.claim("tap");session.fail()
        assertThrows(IllegalStateException::class.java) {session.claim("longpress")}
        assertEquals(1,session.dispatches)
    }
    @Test fun `stale gate uses production binding and never reaches execution`() = runBlocking {
        val s=space();var calls=0
        try { s.dispatch({s.display.copy(rotation=1)}) {calls++}; fail() }
        catch(e:IllegalArgumentException) {assertEquals("stale_screenshot_coordinates",e.message)}
        assertEquals(0,calls)
    }
    @Test fun `all three fixture actions use production resolver and dispatch guard`() = runBlocking {
        val s=space();val seen=mutableListOf<AgentAction>()
        for(type in listOf("tap","longpress","swipe")) {
            val params=if(type=="swipe") mapOf("x1" to 200,"y1" to 600,"x2" to 800,"y2" to 600) else emptyMap()
            val action=ModelCoordinateAction.resolve(type,params,if(type=="swipe")null else 700,if(type=="swipe")null else 930,s)
            s.dispatch({s.display}) {seen.add(action)}
        }
        assertEquals(AgentAction.Tap(756,2232),seen[0])
        assertEquals(AgentAction.LongPress(756,2232),seen[1])
        assertEquals(AgentAction.Swipe(216,1440,864,1440),seen[2])
    }
}
