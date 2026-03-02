package bot.coati.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateTest {

    @Test
    fun `newTask creates task with executing status`() {
        val task = TaskState.newTask("Open Settings")
        assertEquals("executing", task.status)
        assertEquals("Open Settings", task.goal)
        assertEquals(0, task.totalSteps)
        assertEquals(0, task.currentStep)
        assertNotNull(task.id)
        assertNotNull(task.created)
        assertTrue(task.plan.isEmpty())
        assertTrue(task.errors.isEmpty())
        assertEquals("", task.collectedData)
        assertEquals("", task.historySummary)
        assertNull(task.targetPackage)
    }

    @Test
    fun `toJson and fromJson roundtrip`() {
        val original = TaskState(
            id = "abc-123",
            status = "executing",
            created = "2026-03-01T10:00:00",
            lastUpdated = "2026-03-01T10:05:00",
            totalSteps = 10,
            currentStep = 3,
            goal = "Search for cats",
            plan = listOf(
                PlanStep("Open browser", true),
                PlanStep("Type query", false),
            ),
            collectedData = "Found 5 results",
            historySummary = "Opened Chrome, navigated to Google",
            errors = listOf("Timeout at step 2"),
            targetPackage = "com.android.chrome",
        )

        val json = original.toJson()
        val restored = TaskState.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.status, restored.status)
        assertEquals(original.created, restored.created)
        assertEquals(original.lastUpdated, restored.lastUpdated)
        assertEquals(original.totalSteps, restored.totalSteps)
        assertEquals(original.currentStep, restored.currentStep)
        assertEquals(original.goal, restored.goal)
        assertEquals(original.plan.size, restored.plan.size)
        assertEquals(original.plan[0].description, restored.plan[0].description)
        assertTrue(restored.plan[0].completed)
        assertFalse(restored.plan[1].completed)
        assertEquals(original.collectedData, restored.collectedData)
        assertEquals(original.historySummary, restored.historySummary)
        assertEquals(original.errors, restored.errors)
        assertEquals(original.targetPackage, restored.targetPackage)
    }

    @Test
    fun `toJson and fromJson with null targetPackage`() {
        val original = TaskState.newTask("test goal")
        val json = original.toJson()
        val restored = TaskState.fromJson(json)
        assertNull(restored.targetPackage)
    }

    @Test
    fun `toJson and fromJson with empty plan and errors`() {
        val original = TaskState.newTask("empty goal")
        val json = original.toJson()
        val restored = TaskState.fromJson(json)
        assertTrue(restored.plan.isEmpty())
        assertTrue(restored.errors.isEmpty())
    }

    @Test
    fun `toJson escapes special characters`() {
        val task = TaskState.newTask("Search for \"cats\" & dogs")
        val json = task.toJson()
        assertTrue(json.contains("\\\"cats\\\""))
        val restored = TaskState.fromJson(json)
        assertEquals("Search for \"cats\" & dogs", restored.goal)
    }

    @Test
    fun `toMarkdown includes plan with checkmarks`() {
        val task = TaskState(
            id = "test",
            status = "executing",
            created = "2026-03-01T10:00:00",
            lastUpdated = "2026-03-01T10:00:00",
            totalSteps = 5,
            currentStep = 2,
            goal = "My task",
            plan = listOf(
                PlanStep("Step one", true),
                PlanStep("Step two", false),
            ),
            collectedData = "",
            historySummary = "",
            errors = emptyList(),
        )

        val md = task.toMarkdown()
        assertTrue(md.contains("# Task: My task"))
        assertTrue(md.contains("Status: executing"))
        assertTrue(md.contains("2 / 5 steps"))
        assertTrue(md.contains("[x] Step one"))
        assertTrue(md.contains("[ ] Step two"))
    }

    @Test
    fun `toMarkdown includes errors`() {
        val task = TaskState.newTask("goal").copy(
            errors = listOf("Error 1", "Error 2"),
        )
        val md = task.toMarkdown()
        assertTrue(md.contains("## Errors"))
        assertTrue(md.contains("- Error 1"))
        assertTrue(md.contains("- Error 2"))
    }

    @Test
    fun `toMarkdown includes collected data`() {
        val task = TaskState.newTask("goal").copy(
            collectedData = "Some important data",
        )
        val md = task.toMarkdown()
        assertTrue(md.contains("## Collected Data"))
        assertTrue(md.contains("Some important data"))
    }

    @Test
    fun `VALID_STATUSES contains all expected values`() {
        val expected = listOf("executing", "paused", "cancelled", "completed", "failed", "waiting_retry")
        assertEquals(expected, TaskState.VALID_STATUSES)
    }

    @Test
    fun `fromJson handles missing optional fields gracefully`() {
        val minimalJson = """{"id":"test","status":"executing","created":"now","lastUpdated":"now","totalSteps":0,"currentStep":0,"goal":"test","plan":[],"collectedData":"","historySummary":"","errors":[],"targetPackage":null}"""
        val task = TaskState.fromJson(minimalJson)
        assertEquals("test", task.id)
        assertEquals("executing", task.status)
        assertNull(task.targetPackage)
    }
}
