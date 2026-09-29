package bot.coati.jev

import android.util.Log
import bot.coati.perception.ElementOp
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class JevDecisionEngineTest {
    private companion object {
        const val HEDGE_MS = 300L
    }

    private lateinit var server: MockWebServer
    private lateinit var engine: JevDecisionEngine

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        server = MockWebServer()
        server.start()
        engine = JevDecisionEngine(
            JevClient(OkHttpClient(), "k", server.url("/").toString(), "jev-test", hedgeAfterMs = HEDGE_MS),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    private fun answer(vararg pairs: Pair<String, Pair<String, Double>>) = MockResponse().setBody(
        JSONObject().put("model", "jev-test").put("answers", JSONObject().apply {
            pairs.forEach { (question, choice) ->
                put(question, JSONObject().put("type", "choice").put("choice", choice.first).put("confidence", choice.second))
            }
        }).toString(),
    )

    private fun input(memory: ExplorationMemory? = null, previous: PreviousStep? = null): JevStepInput {
        val s = screen(element("E1", "设置"), element("E2", "首页"), element("E3", "资料库"))
        return JevStepInput(
            task = "打开实验档案",
            subgoals = listOf("找到实验档案"),
            advisorHint = null,
            appGuide = null,
            history = emptyList(),
            screen = s,
            candidates = JevCandidates.build(s, memory, emptySet(), emptyList(), canType = false),
            textValues = emptyMap(),
            previous = previous,
        )
    }

    @Test
    fun `a confident choice costs one round trip and carries verification`() = runBlocking {
        server.enqueue(answer("action" to ("E1.TAP" to 0.93), "outcome" to ("SUCCESS" to 0.99)))

        val decision = engine.decide(input(previous = PreviousStep("TAP 我的", "我的 页面"))).getOrThrow()

        assertEquals("E1.TAP", decision.candidate.key)
        assertEquals(StepOutcome.SUCCESS, decision.previousOutcome)
        assertFalse(decision.explored)
        assertEquals(1, server.requestCount)
        val questions = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("questions")
        assertTrue(questions.has("outcome"))
    }

    @Test
    fun `an uncertain choice is re-asked as exploration over untried navigation`() = runBlocking {
        val memory = ExplorationMemory()
        val s = input().screen
        memory.record(s.pageKey, JevCandidates.identity("首页", "TAP"), setOf("欢迎", "精选"), "首页")
        server.enqueue(answer("action" to ("E2.TAP" to 0.21)))
        server.enqueue(answer("explore" to ("E1.TAP" to 0.55)))

        val decision = engine.decide(input(memory)).getOrThrow()

        assertEquals("E1.TAP", decision.candidate.key)
        assertTrue(decision.explored)
        server.takeRequest()
        val explore = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONObject("questions").getJSONObject("explore").getJSONObject("criteria")
        assertFalse("tried page must be left out", explore.has("E2.TAP"))
        assertFalse(explore.has("DONE"))
        assertTrue(explore.has("E1.TAP") && explore.has("E3.TAP"))
    }

    @Test
    fun `a slow response is hedged by a duplicate request`() = runBlocking {
        server.enqueue(answer("action" to ("E1.TAP" to 0.9)).setBodyDelay(3, TimeUnit.SECONDS))
        server.enqueue(answer("action" to ("E3.TAP" to 0.9)))

        val started = System.nanoTime()
        val decision = engine.decide(input()).getOrThrow()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("E3.TAP", decision.candidate.key)
        assertEquals(2, server.requestCount)
        assertTrue("hedged answer must not wait for the slow one: ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `a certain host no-change overrides JEV and re-decides the action`() = runBlocking {
        val previous = PreviousStep(
            actionLabel = "TAP item '设置'",
            screenDigest = "我的",
            expectation = "the page or section for '设置' opens",
            observation = HostObservation(HostVerdict.NO_CHANGE, 0.0, true, "host check: no change"),
        )
        server.enqueue(answer("action" to ("E1.TAP" to 0.9), "outcome" to ("SUCCESS" to 0.8)))
        server.enqueue(answer("action" to ("E3.TAP" to 0.7)))

        val decision = engine.decide(input(previous = previous)).getOrThrow()

        assertEquals(StepOutcome.NO_CHANGE, decision.previousOutcome)
        assertTrue(decision.outcomeFromHost && decision.reconsidered)
        assertEquals("E3.TAP", decision.candidate.key)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(first.getJSONObject("questions").getJSONObject("outcome").getString("instructions")
            .contains("the page or section for '设置' opens"))
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(second.getJSONObject("state").getString("previous_step_verdict").contains("had NO effect"))
    }

    @Test
    fun `overload responses are retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(529))
        server.enqueue(answer("action" to ("E3.TAP" to 0.8)))

        val decision = engine.decide(input()).getOrThrow()

        assertEquals("E3.TAP", decision.candidate.key)
        assertNull(decision.previousOutcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `input text question is only asked when typing is possible`() {
        val s = screen(element("E1", "搜索", ElementOp.TAP, ElementOp.INPUT))
        val withText = JevStepInput("t", emptyList(), null, null, emptyList(), s,
            JevCandidates.build(s, null, emptySet(), emptyList(), canType = true), mapOf("query" to "meeting"), null)

        assertTrue(JevDecisionEngine.buildQuestions(withText).containsKey("text"))
        assertFalse(JevDecisionEngine.buildQuestions(withText.copy(textValues = emptyMap())).containsKey("text"))
    }
}
