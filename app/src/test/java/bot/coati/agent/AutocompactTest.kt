package bot.coati.agent

import bot.coati.model.Message
import bot.coati.model.CloudModelGateway
import bot.coati.model.ModelResponse
import bot.coati.model.QuickCheckResult
import bot.coati.model.VerifyResult
import bot.coati.state.StateManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AutocompactTest {

    private val autocompact = Autocompact(compactInterval = 15)

    @Test
    fun `shouldCompact at interval`() {
        assertTrue(autocompact.shouldCompact(15))
        assertTrue(autocompact.shouldCompact(30))
        assertTrue(autocompact.shouldCompact(45))
    }

    @Test
    fun `shouldCompact false between intervals`() {
        assertFalse(autocompact.shouldCompact(1))
        assertFalse(autocompact.shouldCompact(14))
        assertFalse(autocompact.shouldCompact(16))
    }

    @Test
    fun `shouldCompact false at zero`() {
        assertFalse(autocompact.shouldCompact(0))
    }

    @Test
    fun `shouldCompact with custom interval`() {
        val custom = Autocompact(compactInterval = 5)
        assertTrue(custom.shouldCompact(5))
        assertTrue(custom.shouldCompact(10))
        assertFalse(custom.shouldCompact(3))
    }

    @Test
    fun `pruneScreenshotCache keeps last N`() {
        val cache = mutableListOf(
            byteArrayOf(1), byteArrayOf(2), byteArrayOf(3),
            byteArrayOf(4), byteArrayOf(5),
        )
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(3, cache.size)
        assertEquals(3.toByte(), cache[0][0])
        assertEquals(4.toByte(), cache[1][0])
        assertEquals(5.toByte(), cache[2][0])
    }

    @Test
    fun `pruneScreenshotCache does nothing when under limit`() {
        val cache = mutableListOf(byteArrayOf(1), byteArrayOf(2))
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(2, cache.size)
    }

    @Test
    fun `pruneScreenshotCache with empty list`() {
        val cache = mutableListOf<ByteArray>()
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(0, cache.size)
    }

    @Test
    fun `pruneScreenshotCache with exact limit`() {
        val cache = mutableListOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(3, cache.size)
    }

    @Test
    fun `compact uses text-only summary and keeps recent entries`() = runTest {
        val gateway = FakeGateway("Opened search. Last action tapped the search box.")
        val stateManager = StateManager(Files.createTempDirectory("coati-autocompact-test").toFile())
        val history = mutableListOf(
            Message("user", "Task: search for tea"),
            Message("assistant", "Plan: open app"),
            Message("user", "Task: search for tea"),
            Message("assistant", "Opened app"),
            Message("user", "Task: search for tea"),
            Message("assistant", "Tapped search"),
        )

        autocompact.compact(gateway, history, stateManager, "task123")

        assertTrue(gateway.prompt!!.contains("Tapped search"))
        assertEquals(4, history.size)
        assertEquals("user", history[0].role)
        assertTrue(history[0].content.contains("[History Summary]"))
        assertTrue(history[0].content.contains("Opened search"))
        assertEquals("assistant", history[1].role)
        assertEquals("Opened app", history[1].content)
        assertEquals("user", history[2].role)
        assertEquals("Task: search for tea", history[2].content)
        assertEquals("assistant", history[3].role)
        assertEquals("Tapped search", history[3].content)
        assertEquals("Opened search. Last action tapped the search box.", stateManager.readMarkdown("history_task123"))
        assertFalse(gateway.analyzeScreenCalled)
    }

    private class FakeGateway(private val summary: String) : CloudModelGateway {
        var prompt: String? = null
        var analyzeScreenCalled = false

        override suspend fun analyzeScreen(
            screenshot: ByteArray,
            uiTreeHint: String,
            taskDescription: String,
            conversationHistory: List<Message>,
        ): Result<ModelResponse> {
            analyzeScreenCalled = true
            return Result.failure(AssertionError("Autocompact should use summarizeText"))
        }

        override suspend fun quickCheck(
            screenshot: ByteArray,
            question: String,
        ): Result<QuickCheckResult> = Result.failure(UnsupportedOperationException())

        override suspend fun verifyAction(
            beforeScreenshot: ByteArray,
            afterScreenshot: ByteArray,
            actionDescription: String,
            expectedOutcome: String,
        ): Result<VerifyResult> = Result.failure(UnsupportedOperationException())

        override suspend fun summarizeText(prompt: String): Result<String> {
            this.prompt = prompt
            return Result.success(summary)
        }

        override suspend fun testConnection(): Result<String> = Result.failure(UnsupportedOperationException())
    }
}
