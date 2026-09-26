package bot.coati.coordinates

import bot.coati.action.AgentAction
import bot.coati.model.GatewayUtils
import org.junit.Assert.*
import org.junit.Test

class ModelCoordinateActionTest {
    private val d=DisplaySpace(1080,2400,0,0,PixelBounds(0,0,1080,2400),PixelBounds(0,0,1080,2400),PixelBounds(0,128,0,63),420,1,"app",1f)
    private val space=ScreenshotCoordinates(d,1080,2400,720,1600)
    @Test fun `top level tap longpress and focus use screenshot pixels`() {
        assertEquals(AgentAction.Tap(756,2232),ModelCoordinateAction.resolve("tap",emptyMap(),700,930,space))
        assertEquals(AgentAction.LongPress(756,2232,1500),ModelCoordinateAction.resolve("longpress",mapOf("durationMs" to 1500),700,930,space))
        assertEquals(AgentAction.FocusAndInputText(756,2232,"text"),ModelCoordinateAction.resolve("focusAndInputText",mapOf("text" to "text"),700,930,space))
    }
    @Test fun `tree point is already absolute and never scaled twice`() {
        assertEquals(AgentAction.Tap(756,2232),ModelCoordinateAction.resolve("tap",emptyMap(),700,930,space,756 to 2232))
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("tap",emptyMap(),700,930,space,1080 to 2400) }
    }
    @Test fun `documented params tap fallback is normalized but low level pixel API remains unchanged`() {
        val params=mapOf("x" to 700,"y" to 930)
        assertEquals(AgentAction.Tap(756,2232),ModelCoordinateAction.resolve("tap",params,null,null,space))
        assertEquals(AgentAction.Tap(700,930),AgentAction.fromMap("tap",params))
        assertEquals(AgentAction.Tap(540,1200),ModelCoordinateAction.resolve("tap",params,500,500,space))
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("tap",params,500,null,space) }
    }
    @Test fun `swipe endpoints independent of irrelevant top level coordinates`() {
        val params=mapOf("x1" to 100,"y1" to 900,"x2" to 700,"y2" to 930)
        val expected=AgentAction.Swipe(108,2160,756,2232,400)
        assertEquals(expected,ModelCoordinateAction.resolve("swipe",params,null,null,space))
        assertEquals(expected,ModelCoordinateAction.resolve("swipe",params,500,500,space))
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("swipe",mapOf("x1" to 100),null,null,space) }
    }
    @Test fun `focus requires documented top level and malformed params never fall back to pixels`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("focusAndInputText",mapOf("x" to 700,"y" to 930,"text" to "text"),null,null,space) }
        for (value in listOf<Any>("700",700.5,Double.NaN,1200,-1))
            assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("tap",mapOf("x" to value,"y" to 930),null,null,space) }
    }
    @Test fun `scroll keeps pixel amount and validates full display endpoints`() {
        val action=ModelCoordinateAction.resolve("scroll",mapOf("direction" to "UP"),null,null,space)
        assertEquals(AgentAction.Scroll("UP",500),action)
        assertEquals(AgentAction.Swipe(540,1450,540,950,400),ModelCoordinateAction.scrollGesture(action as AgentAction.Scroll,space))
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("scroll",mapOf("direction" to "UP","amount" to 5000),null,null,space) }
    }
    @Test fun `spatial action requires evidence but nonspatial actions unchanged`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCoordinateAction.resolve("tap",emptyMap(),500,500,null) }
        assertEquals(AgentAction.PressKey("BACK"),ModelCoordinateAction.resolve("pressKey",mapOf("keyCode" to "BACK"),null,null,null))
    }
    @Test fun `gateway cannot silently turn malformed top level coordinates into zero`() {
        for (value in listOf("\"bad\"","500.5","-1","1001")) {
            assertThrows(IllegalArgumentException::class.java) { GatewayUtils.parseModelResponse("{\"action\":\"tap\",\"x\":$value,\"y\":930}") }
        }
        val response=GatewayUtils.parseModelResponse("{\"action\":\"tap\",\"params\":{\"x\":700,\"y\":930}}")
        assertEquals(AgentAction.Tap(756,2232),ModelCoordinateAction.resolve(response.action,response.params,response.x,response.y,space))
    }
}
