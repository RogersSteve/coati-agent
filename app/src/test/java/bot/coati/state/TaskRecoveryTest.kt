package bot.coati.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TaskRecoveryTest {

    private lateinit var tempDir: File
    private lateinit var stateManager: StateManager
    private lateinit var recovery: TaskRecovery

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "coati_recovery_test_${System.nanoTime()}")
        tempDir.mkdirs()
        stateManager = StateManager(tempDir)
        recovery = TaskRecovery(stateManager)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `hasIncompleteTask false when no task file`() {
        assertFalse(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask true for executing task`() {
        val task = TaskState.newTask("Test goal")
        stateManager.writeJson("task", task.toJson())
        assertTrue(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask true for paused task`() {
        val task = TaskState.newTask("Test goal").copy(status = "paused")
        stateManager.writeJson("task", task.toJson())
        assertTrue(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask true for waiting_retry task`() {
        val task = TaskState.newTask("Test goal").copy(status = "waiting_retry")
        stateManager.writeJson("task", task.toJson())
        assertTrue(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask false for completed task`() {
        val task = TaskState.newTask("Test goal").copy(status = "completed")
        stateManager.writeJson("task", task.toJson())
        assertFalse(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask false for cancelled task`() {
        val task = TaskState.newTask("Test goal").copy(status = "cancelled")
        stateManager.writeJson("task", task.toJson())
        assertFalse(recovery.hasIncompleteTask())
    }

    @Test
    fun `hasIncompleteTask false for failed task`() {
        val task = TaskState.newTask("Test goal").copy(status = "failed")
        stateManager.writeJson("task", task.toJson())
        assertFalse(recovery.hasIncompleteTask())
    }

    @Test
    fun `getIncompleteTask returns task`() {
        val task = TaskState.newTask("Find settings")
        stateManager.writeJson("task", task.toJson())
        val result = recovery.getIncompleteTask()
        assertNotNull(result)
        assertEquals("Find settings", result!!.goal)
    }

    @Test
    fun `getIncompleteTask returns null for complete task`() {
        val task = TaskState.newTask("goal").copy(status = "completed")
        stateManager.writeJson("task", task.toJson())
        assertNull(recovery.getIncompleteTask())
    }

    @Test
    fun `markCancelled updates status`() {
        val task = TaskState.newTask("goal")
        stateManager.writeJson("task", task.toJson())
        recovery.markCancelled()
        val updated = TaskState.fromJson(stateManager.readJson("task")!!)
        assertEquals("cancelled", updated.status)
    }

    @Test
    fun `getInterruptedAgo returns minutes format`() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val fiveMinAgo = java.util.Date(System.currentTimeMillis() - 5 * 60_000)
        val task = TaskState.newTask("goal").copy(lastUpdated = sdf.format(fiveMinAgo))
        val result = recovery.getInterruptedAgo(task)
        assertTrue(result.contains("minutes ago"))
    }

    @Test
    fun `getInterruptedAgo returns just now for recent`() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val now = java.util.Date()
        val task = TaskState.newTask("goal").copy(lastUpdated = sdf.format(now))
        val result = recovery.getInterruptedAgo(task)
        assertEquals("just now", result)
    }
}
