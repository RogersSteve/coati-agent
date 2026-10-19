package bot.coati.coordinates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenshotCoordinatesTest {
    private fun display(w: Int = 1080, h: Int = 2400, rotation: Int = 0, top: Int = 128, bottom: Int = 63) =
        DisplaySpace(w, h, rotation, 0, PixelBounds(0,0,w,h), PixelBounds(0,0,w,h),
            PixelBounds(0,top,0,bottom),420,1,"app",1f)
    private fun space(d: DisplaySpace = display(), imageWidth: Int = 720) =
        ScreenshotCoordinates(d,d.width,d.height,imageWidth,(d.height*(imageWidth.toFloat()/d.width)).toInt())
    @Test fun `confirmed old content height misses while bound full frame hits`() {
        val oldY=930*2209/1000
        assertEquals(2054,oldY); assertTrue(oldY < 2127)
        assertEquals(756 to 2232,space().normalized(700,930))
    }
    @Test fun `pixels map back to the normalized space`() {
        val s = space(display(1080, 1920))
        assertEquals(500 to 984, s.toNormalized(540, 1890))
        assertEquals(1000 to 1000, s.toNormalized(1080, 1920))
        val (x, y) = s.toNormalized(540, 1890)
        assertEquals(540 to 1889, s.normalized(x, y))
    }
    @Test fun `resolutions resizing orientation and bars do not add offsets`() {
        for ((w,h) in listOf(1080 to 2400,1440 to 3200,480 to 800,2400 to 1080,800 to 480)) {
            for (top in listOf(0,24,128)) for (bottom in listOf(0,48,63)) {
                val d=display(w,h,if(w>h) 1 else 0,top,bottom)
                for (encoded in listOf(minOf(w,720),w)) {
                    val s=space(d,encoded)
                    assertEquals(w/2 to h/2,s.normalized(500,500))
                    assertEquals(w*7/10 to h*9/10,s.normalized(700,900))
                    assertEquals(w-1 to h-1,s.normalized(1000,1000))
                    assertEquals(0 to 0,s.normalized(0,0))
                }
            }
        }
    }
    @Test fun `any changed geometry prevents dispatch including same sized rotation`() = runBlocking {
        val d=display(); val s=space(d)
        val changed=listOf(null,d.copy(rotation=2),d.copy(width=1200),d.copy(height=2209),d.copy(densityDpi=480),
            d.copy(insets=PixelBounds(0,128,0,0)), d.copy(visibleInsets=PixelBounds(0,0,0,0)), d.copy(windowId=2),d.copy(packageName="other"),
            d.copy(displayId=1),d.copy(magnification=2f),d.copy(appBounds=PixelBounds(0,0,1080,1200)))
        for (current in changed) {
            var calls=0
            try { s.dispatch({current}) { calls++ }; fail("accepted changed space") }
            catch (_:IllegalArgumentException) { assertEquals(0,calls) }
        }
        var calls=0; s.dispatch({d}) { calls++ }; assertEquals(1,calls)
    }
    @Test fun `unknown invalid magnified or mismatched capture rejected`() {
        val d=display()
        val invalid=listOf(d.copy(displayId=1),d.copy(magnification=2f),d.copy(appBounds=PixelBounds(0,0,0,2400)),
            d.copy(appBounds=PixelBounds(-20,600,1100,1800)),d.copy(windowBounds=PixelBounds(0,128,1080,2400)))
        for (bad in invalid) assertThrows(IllegalArgumentException::class.java) { space(bad) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCoordinates(d,1080,2209,720,1472) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCoordinates(d,1080,2400,720,1500) }
    }
    @Test fun `partial windows bind in display pixels without offsets`() {
        // A permission dialog, a bottom sheet and a split-screen half: the full-frame mapping is unchanged.
        for (bounds in listOf(PixelBounds(66,851,1014,1549),PixelBounds(0,1500,1080,2400),PixelBounds(0,0,540,2400))) {
            val d=display().copy(appBounds=bounds)
            assertEquals(756 to 2232,space(d).normalized(700,930))
        }
    }
    @Test fun `fullscreen content bounds permitted but never used as coordinate origin`() {
        val d=display().copy(appBounds=PixelBounds(0,128,1080,2337))
        assertEquals(756 to 2232,space(d).normalized(700,930))
    }
    @Test fun `binding belongs to capture and rejects changes during capture`() {
        val d=display()
        assertEquals(space(d),ScreenshotCoordinates.bind(d,d,1080,2400,720,1600))
        assertNull(ScreenshotCoordinates.bind(d,d.copy(rotation=2),1080,2400,720,1600))
        assertNull(ScreenshotCoordinates.bind(null,d,1080,2400,720,1600))
        assertNull(ScreenshotCoordinates.bind(d,d,1080,2209,720,1472))
        val first=space(d)
        val second=space(display(2400,1080,1),720)
        assertEquals(756 to 2232,first.normalized(700,930))
        assertEquals(1680 to 1004,second.normalized(700,930))
        assertThrows(IllegalArgumentException::class.java) { first.requireCurrent(second.display) }
    }
    @Test fun `invalid points fail instead of guessing or clamping arbitrary input`() {
        for (p in listOf(-1 to 500,1001 to 500,500 to -1,500 to 1001))
            assertThrows(IllegalArgumentException::class.java) { space().normalized(p.first,p.second) }
        assertThrows(IllegalArgumentException::class.java) { space().absolute(1080,0) }
        assertEquals(756 to 2232,space().absolute(756,2232))
    }
    @Test fun `unbound frame still describes its tree in the 0-1000 space`() {
        val normalize=ScreenshotCoordinates.frameNormalizer(1080,2400)!!
        assertEquals(space().toNormalized(964,2220),normalize(964,2220))
        assertEquals(1000 to 1000,normalize(5000,5000))
        assertNull(ScreenshotCoordinates.frameNormalizer(0,2400))
    }
}
