package bot.coati.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DurableFilesTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "coati_durable_${System.nanoTime()}").apply { mkdirs() }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun `replaces the file completely and leaves no temporary file`() {
        val file = File(dir, "task.json")
        DurableFiles.writeAtomically(file, """{"status":"executing","goal":"很长的任务"}""")
        DurableFiles.writeAtomically(file, """{"status":"completed"}""")

        assertEquals("""{"status":"completed"}""", file.readText())
        assertFalse(File(dir, "task.json.tmp").exists())
    }
}
