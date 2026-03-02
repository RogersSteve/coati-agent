package bot.coati.state

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppExperienceManagerTest {

    private lateinit var tempDir: File
    private lateinit var stateManager: StateManager
    private lateinit var experienceManager: AppExperienceManager

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "coati_exp_test_${System.nanoTime()}")
        tempDir.mkdirs()
        stateManager = StateManager(tempDir)
        experienceManager = AppExperienceManager(stateManager)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `loadForPrompt returns null for unknown app`() {
        assertNull(experienceManager.loadForPrompt("com.unknown.app"))
    }

    @Test
    fun `recordObservation creates new profile`() {
        experienceManager.recordObservation("com.test.app", "Home", "Main feed page")
        val markdown = experienceManager.loadForPrompt("com.test.app")
        assertNotNull(markdown)
        assertTrue(markdown!!.contains("Home"))
        assertTrue(markdown.contains("Main feed page"))
    }

    @Test
    fun `recordObservation updates existing page`() {
        experienceManager.recordObservation("com.test.app", "Home", "Old description")
        experienceManager.recordObservation("com.test.app", "Home", "New description")
        val markdown = experienceManager.loadForPrompt("com.test.app")
        assertNotNull(markdown)
        assertTrue(markdown!!.contains("New description"))
    }

    @Test
    fun `recordSuccessPath saves path`() {
        experienceManager.recordSuccessPath("com.test.app", "Search", listOf("Tap bar", "Type query"))
        val markdown = experienceManager.loadForPrompt("com.test.app")
        assertNotNull(markdown)
        assertTrue(markdown!!.contains("Search"))
        assertTrue(markdown.contains("Tap bar"))
    }

    @Test
    fun `markPageOutdated marks page`() {
        experienceManager.recordObservation("com.test.app", "Home", "desc")
        experienceManager.markPageOutdated("com.test.app", "Home")
        val markdown = experienceManager.loadForPrompt("com.test.app")
        assertNotNull(markdown)
        assertTrue(markdown!!.contains("\u26a0\ufe0f"))
    }

    @Test
    fun `multiple pages stored correctly`() {
        experienceManager.recordObservation("com.test.app", "Home", "Feed page")
        experienceManager.recordObservation("com.test.app", "Settings", "Settings page")
        val markdown = experienceManager.loadForPrompt("com.test.app")
        assertNotNull(markdown)
        assertTrue(markdown!!.contains("Home"))
        assertTrue(markdown.contains("Settings"))
    }
}
