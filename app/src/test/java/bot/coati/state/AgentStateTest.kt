package bot.coati.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateTest {

    @Test
    fun `default creates idle state`() {
        val state = AgentState.default()
        assertFalse(state.isRunning)
        assertNull(state.currentTaskId)
        assertEquals(0, state.stepCount)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(0L, state.lastActionTime)
    }

    @Test
    fun `toJson and fromJson roundtrip`() {
        val original = AgentState(
            isRunning = true,
            currentTaskId = "task-abc-123",
            stepCount = 15,
            consecutiveFailures = 2,
            lastActionTime = 1709290800000L,
        )

        val json = original.toJson()
        val restored = AgentState.fromJson(json)

        assertEquals(original.isRunning, restored.isRunning)
        assertEquals(original.currentTaskId, restored.currentTaskId)
        assertEquals(original.stepCount, restored.stepCount)
        assertEquals(original.consecutiveFailures, restored.consecutiveFailures)
        assertEquals(original.lastActionTime, restored.lastActionTime)
    }

    @Test
    fun `toJson and fromJson with null taskId`() {
        val original = AgentState.default()
        val json = original.toJson()
        val restored = AgentState.fromJson(json)
        assertNull(restored.currentTaskId)
    }

    @Test
    fun `toJson produces valid format`() {
        val state = AgentState(true, "task-1", 5, 1, 12345L)
        val json = state.toJson()
        assertTrue(json.contains(""""isRunning": true"""))
        assertTrue(json.contains(""""currentTaskId": "task-1""""))
        assertTrue(json.contains(""""stepCount": 5"""))
        assertTrue(json.contains(""""consecutiveFailures": 1"""))
        assertTrue(json.contains(""""lastActionTime": 12345"""))
    }

    @Test
    fun `fromJson handles not running state`() {
        val json = """{"isRunning": false, "currentTaskId": null, "stepCount": 0, "consecutiveFailures": 0, "lastActionTime": 0}"""
        val state = AgentState.fromJson(json)
        assertFalse(state.isRunning)
        assertNull(state.currentTaskId)
    }
}
