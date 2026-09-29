package bot.coati.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JevProtocolTest {

    private val questions = mapOf(
        "action" to JevChoiceQuestion("pick", mapOf("E1.TAP" to "tap search", "BACK" to "back")),
    )

    @Test
    fun `request serializes choice questions with criteria`() {
        val json = JevProtocol.request("jev-1.13.0", JSONObject().put("k", "v"), questions)

        assertEquals("jev-1.13.0", json.getString("model"))
        val action = json.getJSONObject("questions").getJSONObject("action")
        assertEquals("choice", action.getString("type"))
        assertEquals("tap search", action.getJSONObject("criteria").getString("E1.TAP"))
        assertEquals("v", json.getJSONObject("state").getString("k"))
    }

    @Test
    fun `request rejects option counts beyond the API limit`() {
        val tooMany = (0..JevProtocol.MAX_OPTIONS).associate { "o$it" to "d" }
        assertThrows(IllegalArgumentException::class.java) {
            JevProtocol.request("m", JSONObject(), mapOf("q" to JevChoiceQuestion("i", tooMany)))
        }
    }

    @Test
    fun `parse reads choice confidence and probabilities`() {
        val body = """
            {"model":"jev-1.13.0","answers":{"action":{"type":"choice","choice":"E1.TAP","confidence":0.82,
            "probabilities":{"E1.TAP":0.88,"BACK":0.12}}},"usage":{"input_tokens":416,"output_tokens":45}}
        """.trimIndent()

        val response = JevProtocol.parse(body, questions)

        val answer = response.answers.getValue("action")
        assertEquals("E1.TAP", answer.choice)
        assertEquals(0.82, answer.confidence, 1e-9)
        assertEquals(0.88, answer.probabilities.getValue("E1.TAP"), 1e-9)
        assertEquals(416, response.inputTokens)
    }

    @Test
    fun `parse rejects a choice that was never offered`() {
        val body = """{"model":"m","answers":{"action":{"type":"choice","choice":"E9.TAP","confidence":0.9}}}"""
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.parse(body, questions) }
    }
}
