package bot.coati.model

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUsageTest {
    @Test
    fun `gemini usage keeps thinking separate from output`() {
        val usage = ModelUsageParser.parse(
            """{"candidates":[],"usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":80,"thoughtsTokenCount":300,"totalTokenCount":1580}}""",
        )
        assertEquals(ModelUsage(input = 1200, output = 80, thinking = 300), usage)
    }

    @Test
    fun `openai usage subtracts reasoning tokens from completion tokens`() {
        val usage = ModelUsageParser.parse(
            """{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":250,"completion_tokens_details":{"reasoning_tokens":200}}}""",
        )
        assertEquals(ModelUsage(input = 900, output = 50, thinking = 200), usage)
    }

    @Test
    fun `claude usage counts cached input`() {
        val usage = ModelUsageParser.parse(
            """{"content":[],"usage":{"input_tokens":100,"cache_read_input_tokens":400,"cache_creation_input_tokens":20,"output_tokens":60}}""",
        )
        assertEquals(ModelUsage(input = 520, output = 60, thinking = 0), usage)
    }

    @Test
    fun `jev usage reports input tokens only`() {
        val usage = ModelUsageParser.parse("""{"model":"jev-1.13.0","answers":{},"usage":{"input_tokens":733}}""")
        assertEquals(ModelUsage(input = 733), usage)
    }

    @Test
    fun `bodies without usage give null`() {
        assertNull(ModelUsageParser.parse("""{"candidates":[]}"""))
        assertNull(ModelUsageParser.parse("not json"))
    }

    @Test
    fun `meter splits critical and background usage per task`() {
        ModelUsageMeter.beginTask()
        ModelUsageMeter.recordCall(ModelCallPurpose.REASONING, ok = true, durationMs = 4000)
        ModelUsageMeter.recordRequest(ModelCallPurpose.REASONING, ModelUsage(1000, 100, 50))
        ModelUsageMeter.recordRequest(ModelCallPurpose.REASONING, ModelUsage(1000, 100, 0))
        ModelUsageMeter.recordCall(ModelCallPurpose.SELECTOR, ok = false, durationMs = 900)
        ModelUsageMeter.recordCall(ModelCallPurpose.ADVISOR, ok = true, durationMs = 6000)
        ModelUsageMeter.recordRequest(ModelCallPurpose.ADVISOR, ModelUsage(2000, 300, 0))

        val summary = ModelUsageMeter.finishTask()!!
        val critical = summary.getJSONObject("critical")
        assertEquals(2, critical.getInt("calls"))
        assertEquals(1, critical.getInt("failed_calls"))
        assertEquals(2, critical.getInt("requests"))
        assertEquals(2250, critical.getLong("total_tokens"))
        val background = summary.getJSONObject("background")
        assertEquals(1, background.getInt("calls"))
        assertEquals(2300, background.getLong("total_tokens"))
        val reasoning = summary.getJSONObject("purposes").getJSONObject("reasoning")
        assertEquals(50, reasoning.getLong("thinking_tokens"))
        assertFalse(reasoning.getBoolean("background"))
        assertTrue(summary.getJSONObject("purposes").getJSONObject("advisor").getBoolean("background"))
    }

    @Test
    fun `usage after a task ends is reported once as outside the next task`() {
        ModelUsageMeter.beginTask()
        ModelUsageMeter.finishTask()
        assertNull(ModelUsageMeter.finishTask())
        ModelUsageMeter.recordCall(ModelCallPurpose.MANUAL_CURATION, ok = true, durationMs = 10)
        ModelUsageMeter.beginTask()
        val next = ModelUsageMeter.finishTask()!!
        assertEquals(1, next.getJSONObject("outside_task").getInt("calls"))
        assertEquals(0, next.getJSONObject("critical").getInt("calls"))
        ModelUsageMeter.beginTask()
        assertEquals(0, ModelUsageMeter.finishTask()!!.getJSONObject("outside_task").getInt("calls"))
    }

    @Test
    fun `purpose defaults to the gateway operation and callers can override it`() = runBlocking {
        val delegate = object : CloudModelGateway {
            override suspend fun analyzeScreen(screenshot: ByteArray, uiTreeHint: String, taskDescription: String, conversationHistory: List<Message>) =
                Result.failure<ModelResponse>(UnsupportedOperationException())
            override suspend fun quickCheck(screenshot: ByteArray, question: String, manualObservationInstruction: String?) =
                Result.success(QuickCheckResult(answer = true))
            override suspend fun verifyAction(beforeScreenshot: ByteArray, afterScreenshot: ByteArray, actionDescription: String, expectedOutcome: String, manualObservationInstruction: String?) =
                Result.success(VerifyResult(VerifyOutcome.SUCCESS, "ok"))
            override suspend fun summarizeText(prompt: String) = Result.success("")
            override suspend fun testConnection() = Result.success("ok")
        }
        val gateway = ResilientModelGateway(delegate, maxAttempts = 1)
        ModelUsageMeter.beginTask()
        gateway.verifyAction(ByteArray(0), ByteArray(0), "tap", "opens")
        withContext(ModelCallPurpose.COMPLETION_AUDIT) { gateway.quickCheck(ByteArray(0), "done?") }
        val purposes = ModelUsageMeter.finishTask()!!.getJSONObject("purposes")
        assertEquals(1, purposes.getJSONObject("verify").getInt("calls"))
        assertEquals(1, purposes.getJSONObject("completion_audit").getInt("calls"))
        assertFalse(purposes.has("quickCheck"))
    }
}
