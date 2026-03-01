package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayUtilsTest {

    @Test
    fun `extractJsonFromText extracts from markdown code block`() {
        val input = """
            Some text before
            ```json
            {"action": "tap", "params": {"x": 100}}
            ```
            Some text after
        """.trimIndent()
        val result = GatewayUtils.extractJsonFromText(input)
        assertTrue(result.startsWith("{"))
        assertTrue(result.contains("\"action\""))
    }

    @Test
    fun `extractJsonFromText extracts from code block without json label`() {
        val input = """
            ```
            {"action": "scroll"}
            ```
        """.trimIndent()
        val result = GatewayUtils.extractJsonFromText(input)
        assertTrue(result.contains("\"action\""))
    }

    @Test
    fun `extractJsonFromText handles raw JSON`() {
        val input = """{"action": "wait", "params": {"ms": 1000}}"""
        val result = GatewayUtils.extractJsonFromText(input)
        assertEquals(input, result)
    }

    @Test
    fun `extractJsonFromText returns original text when no JSON found`() {
        val input = "no json here"
        val result = GatewayUtils.extractJsonFromText(input)
        assertEquals(input, result)
    }

    @Test
    fun `buildUserPrompt contains task description`() {
        val result = GatewayUtils.buildUserPrompt("UI tree here", "Open settings")
        assertTrue(result.contains("Open settings"))
        assertTrue(result.contains("UI tree here"))
    }

    @Test
    fun `buildUserPrompt contains action format instructions`() {
        val result = GatewayUtils.buildUserPrompt("", "test")
        assertTrue(result.contains("tap"))
        assertTrue(result.contains("swipe"))
        assertTrue(result.contains("safetyLevel"))
    }

    @Test
    fun `SYSTEM_PROMPT contains key rules`() {
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("Coati"))
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("Android"))
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("JSON"))
    }

    // parseModelResponse uses org.json.JSONObject (Android framework),
    // so it cannot be tested in JVM unit tests per project conventions.
}
