package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `ModelResponse with normalized coordinates`() {
        val response = ModelResponse(
            reasoning = "Tapping search button",
            action = "tap",
            params = mapOf("x" to 500, "y" to 750),
            safetyLevel = "medium",
            isTaskComplete = false,
            statusUpdate = "Tapping search",
            x = 500,
            y = 750,
        )
        assertEquals(500, response.x)
        assertEquals(750, response.y)
    }

    @Test
    fun `ModelResponse without coordinates defaults to null`() {
        val response = ModelResponse(
            reasoning = "Scrolling down",
            action = "scroll",
            params = mapOf("direction" to "DOWN"),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "Scrolling",
        )
        assertNull(response.x)
        assertNull(response.y)
    }

    @Test
    fun `ExperienceNote data class holds values`() {
        val note = ExperienceNote(page = "Home", observation = "Has a search bar at top")
        assertEquals("Home", note.page)
        assertEquals("Has a search bar at top", note.observation)
    }

    @Test
    fun `ModelResponse with experienceNote`() {
        val response = ModelResponse(
            reasoning = "test",
            action = "tap",
            params = mapOf("x" to 100, "y" to 200),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "Tapping",
            experienceNote = ExperienceNote("Home", "Main feed with posts"),
        )
        assertNotNull(response.experienceNote)
        assertEquals("Home", response.experienceNote!!.page)
        assertEquals("Main feed with posts", response.experienceNote!!.observation)
    }

    @Test
    fun `ModelResponse without experienceNote defaults to null`() {
        val response = ModelResponse(
            reasoning = "test",
            action = "tap",
            params = emptyMap(),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "test",
        )
        assertNull(response.experienceNote)
    }

    @Test
    fun `QuickCheckResult holds correct values`() {
        val result = QuickCheckResult(
            answer = true,
            category = "screen_changed",
            confidence = 0.95f,
        )
        assertTrue(result.answer)
        assertEquals("screen_changed", result.category)
        assertEquals(0.95f, result.confidence)
    }

    @Test
    fun `QuickCheckResult defaults`() {
        val result = QuickCheckResult(answer = false)
        assertFalse(result.answer)
        assertNull(result.category)
        assertEquals(1.0f, result.confidence)
    }

    // ─── VerifyOutcome and VerifyResult ───

    @Test
    fun `VerifyOutcome enum has 4 values`() {
        val values = VerifyOutcome.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(VerifyOutcome.SUCCESS))
        assertTrue(values.contains(VerifyOutcome.FAILED))
        assertTrue(values.contains(VerifyOutcome.DRIFTED))
        assertTrue(values.contains(VerifyOutcome.UNEXPECTED))
    }

    @Test
    fun `VerifyResult holds correct values`() {
        val result = VerifyResult(
            outcome = VerifyOutcome.SUCCESS,
            reason = "Screen changed as expected",
            observation = null,
        )
        assertEquals(VerifyOutcome.SUCCESS, result.outcome)
        assertEquals("Screen changed as expected", result.reason)
        assertNull(result.observation)
    }

    @Test
    fun `VerifyResult with observation for UNEXPECTED`() {
        val result = VerifyResult(
            outcome = VerifyOutcome.UNEXPECTED,
            reason = "Different path taken",
            observation = "A dialog appeared offering a shortcut",
        )
        assertEquals(VerifyOutcome.UNEXPECTED, result.outcome)
        assertNotNull(result.observation)
        assertEquals("A dialog appeared offering a shortcut", result.observation)
    }

    @Test
    fun `VerifyResult DRIFTED without observation`() {
        val result = VerifyResult(
            outcome = VerifyOutcome.DRIFTED,
            reason = "Navigated to wrong page",
        )
        assertEquals(VerifyOutcome.DRIFTED, result.outcome)
        assertNull(result.observation)
    }

    @Test
    fun `ModelResponse with expectedOutcome`() {
        val response = ModelResponse(
            reasoning = "Tapping search",
            action = "tap",
            params = mapOf("x" to 500, "y" to 200),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "Tapping search button",
            expectedOutcome = "Search input field should appear",
        )
        assertEquals("Search input field should appear", response.expectedOutcome)
    }

    @Test
    fun `ModelResponse expectedOutcome defaults to empty string`() {
        val response = ModelResponse(
            reasoning = "test",
            action = "tap",
            params = emptyMap(),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "test",
        )
        assertEquals("", response.expectedOutcome)
    }

    @Test
    fun `ModelResponse isPopupDismissal defaults to false`() {
        val response = ModelResponse(
            reasoning = "test",
            action = "tap",
            params = emptyMap(),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "test",
        )
        assertFalse(response.isPopupDismissal)
    }

    @Test
    fun `ModelResponse isPopupDismissal set to true`() {
        val response = ModelResponse(
            reasoning = "Dismissing permission dialog",
            action = "tap",
            params = mapOf("x" to 500, "y" to 800),
            safetyLevel = "low",
            isTaskComplete = false,
            statusUpdate = "Tapping Allow",
            isPopupDismissal = true,
        )
        assertTrue(response.isPopupDismissal)
    }
}
