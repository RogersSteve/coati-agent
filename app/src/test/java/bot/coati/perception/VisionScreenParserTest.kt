package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionScreenParserTest {

    // 1080x2400 display, normalized 0..1000 per axis.
    private val toPixels: (Int, Int) -> Pair<Int, Int> = { x, y -> x * 1080 / 1000 to y * 2400 / 1000 }

    @Test
    fun `positional arrays become elements and regions in display pixels`() {
        val text = """
            ```json
            {"app":"WeChat","page":"chat","summary":"chat with a friend","kb":true,"overlay":null,
             "regions":[["R1","content","V",0,108,1000,896]],
             "els":[["E1","R1","icon","","back chevron","TAP","-",35,65,71,96],
                    ["E2","R1","input","","message field","TAP|INPUT","focused",45,900,780,950],
                    ["E3","R1","text","2022年11月30日","timestamp","-","-",300,119,699,139]]}
            ```
        """.trimIndent()

        val screen = VisionScreenParser.parseResponse(text, "com.tencent.mm", toPixels)

        assertEquals(ScreenSource.VISION, screen.source)
        assertTrue(screen.keyboardVisible)
        assertEquals(setOf(ScrollAxis.VERTICAL), screen.region("R1")!!.axes)
        val input = screen.element("E2")!!
        assertEquals(listOf(ElementOp.TAP, ElementOp.INPUT), input.ops)
        assertEquals("focused", input.state)
        assertEquals(PixelBounds(48, 2160, 842, 2280), input.bounds)
        assertTrue(screen.element("E3")!!.ops.isEmpty())
    }

    @Test
    fun `malformed rows are skipped instead of failing the whole parse`() {
        val text = """{"summary":"s","regions":[["R1"]],"els":[["E1","R1","icon"],["E2","R1","button","OK","ok","TAP","-",10,10,5,20],["E3","R1","button","Go","go","TAP","-",10,10,50,20]]}"""

        val screen = VisionScreenParser.parseResponse(text, "p", toPixels)

        assertEquals(listOf("E3"), screen.elements.map { it.id })
        assertTrue(screen.regions.isEmpty())
    }
}
