package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResponseTest {

    @Test
    fun `ModelResponse data class holds correct values`() {
        val response = ModelResponse(
            reasoning = "I see a button labeled Settings, tapping it",
            action = "tap",
            params = mapOf("x" to 540, "y" to 1200),
            safetyLevel = "medium",
            isTaskComplete = false,
            statusUpdate = "Tapping Settings button",
        )

        assertEquals("tap", response.action)
        assertEquals("I see a button labeled Settings, tapping it", response.reasoning)
        assertEquals(540, response.params["x"])
        assertEquals(1200, response.params["y"])
        assertEquals("medium", response.safetyLevel)
        assertFalse(response.isTaskComplete)
        assertEquals("Tapping Settings button", response.statusUpdate)
    }

    @Test
    fun `ModelResponse with openApp action`() {
        val response = ModelResponse(
            reasoning = "Opening Chrome as requested",
            action = "openApp",
            params = mapOf("packageName" to "com.android.chrome"),
            safetyLevel = "low",
            isTaskComplete = true,
            statusUpdate = "Chrome opened",
        )

        assertEquals("openApp", response.action)
        assertEquals("com.android.chrome", response.params["packageName"])
        assertTrue(response.isTaskComplete)
    }

    @Test
    fun `ModelResponse with swipe params`() {
        val response = ModelResponse(
            reasoning = "Scrolling down to see more content",
            action = "swipe",
            params = mapOf("x1" to 540, "y1" to 1500, "x2" to 540, "y2" to 500, "durationMs" to 400),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "Scrolling down",
        )

        assertEquals("swipe", response.action)
        assertEquals(540, response.params["x1"])
        assertEquals(1500, response.params["y1"])
        assertEquals(540, response.params["x2"])
        assertEquals(500, response.params["y2"])
    }

    @Test
    fun `ModelResponse with high safety level`() {
        val response = ModelResponse(
            reasoning = "About to delete a file",
            action = "tap",
            params = mapOf("x" to 300, "y" to 800),
            safetyLevel = "high",
            isTaskComplete = false,
            statusUpdate = "Confirming deletion",
        )

        assertEquals("high", response.safetyLevel)
    }

    @Test
    fun `Message equality with image bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val m1 = Message("user", "hello", bytes)
        val m2 = Message("user", "hello", bytes)
        assertEquals(m1, m2)
    }

    @Test
    fun `Message equality without image bytes`() {
        val m1 = Message("user", "hello")
        val m2 = Message("user", "hello")
        assertEquals(m1, m2)
    }

    @Test
    fun `Message hashCode consistency`() {
        val m1 = Message("user", "hello")
        val m2 = Message("user", "hello")
        assertEquals(m1.hashCode(), m2.hashCode())
    }

    @Test
    fun `ModelResponse copy works`() {
        val original = ModelResponse(
            reasoning = "test",
            action = "tap",
            params = mapOf("x" to 1, "y" to 2),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "testing",
        )
        val copy = original.copy(isTaskComplete = true)
        assertTrue(copy.isTaskComplete)
        assertEquals("tap", copy.action)
    }
}
