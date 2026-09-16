package bot.coati.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StateManagerTest {

    private lateinit var tempDir: File
    private lateinit var stateManager: StateManager

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "coati_test_${System.nanoTime()}")
        tempDir.mkdirs()
        stateManager = StateManager(tempDir)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `writeJson and readJson roundtrip`() {
        stateManager.writeJson("test", """{"key":"value"}""")
        val result = stateManager.readJson("test")
        assertEquals("""{"key":"value"}""", result)
    }

    @Test
    fun `readJson returns null for missing file`() {
        assertNull(stateManager.readJson("nonexistent"))
    }

    @Test
    fun `deleteJson removes file`() {
        stateManager.writeJson("test", "content")
        stateManager.deleteJson("test")
        assertNull(stateManager.readJson("test"))
    }

    @Test
    fun `writeMarkdown and readMarkdown roundtrip`() {
        stateManager.writeMarkdown("notes", "# Hello\n- item 1")
        val result = stateManager.readMarkdown("notes")
        assertEquals("# Hello\n- item 1", result)
    }

    @Test
    fun `readMarkdown returns null for missing file`() {
        assertNull(stateManager.readMarkdown("nonexistent"))
    }

    @Test
    fun `atomic write creates file in correct location`() {
        stateManager.writeJson("atomic_test", "data")
        val file = File(tempDir, "atomic_test.json")
        assertTrue(file.exists())
        assertEquals("data", file.readText())
    }

    @Test
    fun `writeJson overwrites existing content`() {
        stateManager.writeJson("overwrite", "old")
        stateManager.writeJson("overwrite", "new")
        assertEquals("new", stateManager.readJson("overwrite"))
    }

    @Test
    fun `legacy apps directory is not recreated`() {
        val appsDir = File(tempDir, "apps")
        assertFalse(appsDir.exists())
    }
}
