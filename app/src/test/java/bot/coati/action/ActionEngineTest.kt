package bot.coati.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEngineTest {

    @Test
    fun `fromMap parses tap action`() {
        val action = AgentAction.fromMap("tap", mapOf("x" to 100, "y" to 200))
        assertTrue(action is AgentAction.Tap)
        val tap = action as AgentAction.Tap
        assertEquals(100, tap.x)
        assertEquals(200, tap.y)
    }

    @Test
    fun `fromMap parses swipe action`() {
        val action = AgentAction.fromMap("swipe", mapOf(
            "x1" to 100, "y1" to 200,
            "x2" to 300, "y2" to 400,
            "durationMs" to 500,
        ))
        assertTrue(action is AgentAction.Swipe)
        val swipe = action as AgentAction.Swipe
        assertEquals(100, swipe.x1)
        assertEquals(200, swipe.y1)
        assertEquals(300, swipe.x2)
        assertEquals(400, swipe.y2)
        assertEquals(500L, swipe.durationMs)
    }

    @Test
    fun `fromMap parses inputText action`() {
        val action = AgentAction.fromMap("inputText", mapOf("text" to "Hello"))
        assertTrue(action is AgentAction.InputText)
        assertEquals("Hello", (action as AgentAction.InputText).text)
    }

    @Test
    fun `fromMap parses pressKey action`() {
        val action = AgentAction.fromMap("pressKey", mapOf("keyCode" to "BACK"))
        assertTrue(action is AgentAction.PressKey)
        assertEquals("BACK", (action as AgentAction.PressKey).keyCode)
    }

    @Test
    fun `fromMap parses scroll action with defaults`() {
        val action = AgentAction.fromMap("scroll", mapOf("direction" to "UP"))
        assertTrue(action is AgentAction.Scroll)
        val scroll = action as AgentAction.Scroll
        assertEquals("UP", scroll.direction)
        assertEquals(500, scroll.amount)
    }

    @Test
    fun `fromMap parses wait action`() {
        val action = AgentAction.fromMap("wait", mapOf("ms" to 2000))
        assertTrue(action is AgentAction.Wait)
        assertEquals(2000L, (action as AgentAction.Wait).ms)
    }

    @Test
    fun `fromMap parses screenshot action`() {
        val action = AgentAction.fromMap("screenshot", emptyMap())
        assertTrue(action is AgentAction.Screenshot)
    }

    @Test
    fun `fromMap parses openApp action`() {
        val action = AgentAction.fromMap("openApp", mapOf("packageName" to "com.android.settings"))
        assertTrue(action is AgentAction.OpenApp)
        assertEquals("com.android.settings", (action as AgentAction.OpenApp).packageName)
    }

    @Test
    fun `fromMap parses longpress action`() {
        val action = AgentAction.fromMap("longpress", mapOf("x" to 50, "y" to 60, "durationMs" to 2000))
        assertTrue(action is AgentAction.LongPress)
        val lp = action as AgentAction.LongPress
        assertEquals(50, lp.x)
        assertEquals(60, lp.y)
        assertEquals(2000L, lp.durationMs)
    }

    @Test
    fun `fromMap is case insensitive`() {
        val action = AgentAction.fromMap("TAP", mapOf("x" to 10, "y" to 20))
        assertTrue(action is AgentAction.Tap)
    }

    @Test
    fun `fromMap handles underscore variants`() {
        val action1 = AgentAction.fromMap("open_app", mapOf("packageName" to "com.test"))
        assertTrue(action1 is AgentAction.OpenApp)

        val action2 = AgentAction.fromMap("input_text", mapOf("text" to "hi"))
        assertTrue(action2 is AgentAction.InputText)

        val action3 = AgentAction.fromMap("long_press", mapOf("x" to 10, "y" to 20))
        assertTrue(action3 is AgentAction.LongPress)

        val action4 = AgentAction.fromMap("press_key", mapOf("keyCode" to "HOME"))
        assertTrue(action4 is AgentAction.PressKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromMap throws for unknown action`() {
        AgentAction.fromMap("unknown", emptyMap())
    }

    @Test
    fun `fromMap handles Number types from JSON`() {
        // JSON parsers may return Double instead of Int
        val action = AgentAction.fromMap("tap", mapOf("x" to 100.0, "y" to 200.0))
        assertTrue(action is AgentAction.Tap)
        val tap = action as AgentAction.Tap
        assertEquals(100, tap.x)
        assertEquals(200, tap.y)
    }

    @Test
    fun `ActionResult equality`() {
        val r1 = ActionResult(success = true, message = "ok")
        val r2 = ActionResult(success = true, message = "ok")
        assertEquals(r1, r2)
    }

    @Test
    fun `ActionResult with screenshot bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val r1 = ActionResult(success = true, message = "ok", screenshotBytes = bytes)
        val r2 = ActionResult(success = true, message = "ok", screenshotBytes = bytes)
        assertEquals(r1, r2)
    }
}
