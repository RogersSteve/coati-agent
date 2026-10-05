package bot.coati.jev

import bot.coati.model.CloudModelGateway
import bot.coati.model.Message
import bot.coati.model.ModelResponse
import bot.coati.model.QuickCheckResult
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextRequest
import bot.coati.model.StructuredTextResult
import bot.coati.model.VerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundAdvisorTest {

    @Test
    fun `plan output yields subgoals, text values and apps`() {
        val guidance = BackgroundAdvisor.parse(
            """{"subgoals":["打开资料库","搜索"],"text_values":{"query":"meeting","empty":""},"apps":["WeChat"]}""",
            step = 0,
        )

        assertEquals(listOf("打开资料库", "搜索"), guidance.subgoals)
        assertEquals(mapOf("query" to "meeting"), guidance.textValues)
        assertEquals(listOf("WeChat"), guidance.apps)
        assertNull(guidance.hint)
        assertNull(guidance.manualObservation)
    }

    @Test
    fun `advice may carry a manual observation`() {
        val guidance = BackgroundAdvisor.parse(
            """
            {"subgoals":[],"hint":"try 设置","manualObservation":{"status":"NEW_OR_CHANGED","pageName":"我的",
             "visualSignature":"two cards","reusableFacts":["设置 opens preferences"],"navigationFacts":[]}}
            """.trimIndent(),
            step = 3,
        )

        assertEquals("try 设置", guidance.hint)
        assertEquals("我的", guidance.manualObservation?.pageName)
        assertEquals(3, guidance.forStep)
    }

    @Test
    fun `waiting for the plan leaves it for the executor to absorb`() = runBlocking {
        val gateway = object : CloudModelGateway {
            override suspend fun analyzeScreen(screenshot: ByteArray, uiTreeHint: String, taskDescription: String, conversationHistory: List<Message>) =
                Result.failure<ModelResponse>(UnsupportedOperationException())
            override suspend fun quickCheck(screenshot: ByteArray, question: String, manualObservationInstruction: String?) =
                Result.failure<QuickCheckResult>(UnsupportedOperationException())
            override suspend fun verifyAction(beforeScreenshot: ByteArray, afterScreenshot: ByteArray, actionDescription: String, expectedOutcome: String, manualObservationInstruction: String?) =
                Result.failure<VerifyResult>(UnsupportedOperationException())
            override suspend fun summarizeText(prompt: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun testConnection() = Result.success("ok")
            override suspend fun generateStructuredText(request: StructuredTextRequest) = Result.success(
                StructuredTextResult("""{"subgoals":["open Joplin"],"text_values":{"q":"Projects"},"apps":["Joplin"]}""", StructuredTextFinishReason.COMPLETE),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val advisor = BackgroundAdvisor(gateway, scope)
        advisor.plan("count to-dos", ByteArray(0))
        advisor.await(5_000)

        val plan = advisor.poll()
        assertEquals(listOf("open Joplin"), plan?.subgoals)
        assertEquals(mapOf("q" to "Projects"), plan?.textValues)
        scope.cancel()
    }
}
