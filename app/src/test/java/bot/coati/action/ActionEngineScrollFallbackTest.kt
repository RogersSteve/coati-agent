package bot.coati.action

import android.util.Log
import bot.coati.coordinates.PixelBounds
import bot.coati.service.CoatiAccessibilityService
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActionEngineScrollFallbackTest {
    private val swipe = AgentAction.Swipe(800, 500, 200, 500)
    private val box = PixelBounds(0, 0, 1000, 1000)
    private val candidate = ScrollCandidate(listOf(0), box, setOf(SemanticScroll.FORWARD), ScrollAxis.HORIZONTAL)
    private val session = ScrollSession("fixture", 7, box, candidate, SemanticScroll.FORWARD)
    private val before = ScrollEvidence("before", IntArray(4096) { 0xff000000.toInt() }, 1000, 1000)
    private val after = before.copy(signature = "after", pixels = IntArray(4096) { -1 })
    private val dispatched = ActionResult(true, "Swiped")
    private lateinit var service: CoatiAccessibilityService
    private lateinit var engine: ActionEngine

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        service = mockk(relaxed = true)
        every { service.prepareScroll(swipe) } returns session
        coEvery { service.scrollEvidence(any()) } returns before
        every { service.performSemanticScroll(any()) } returns true
        engine = ActionEngine(service, mockk(relaxed = true))
    }
    @After fun cleanup() { unmockkStatic(Log::class) }

    @Test fun `original movement prevents any semantic action`() = runTest {
        coEvery { service.scrollEvidence(session) } returnsMany listOf(before, after)
        assertEquals(dispatched, engine.withScrollFallback(swipe) { dispatched })
        verify(exactly = 0) { service.performSemanticScroll(any()) }
    }
    @Test fun `structural change without visual proof also prevents double scroll`() = runTest {
        coEvery { service.scrollEvidence(session) } returnsMany listOf(before, before.copy(signature = "changed"))
        assertEquals(dispatched, engine.withScrollFallback(swipe) { dispatched })
        verify(exactly = 0) { service.performSemanticScroll(any()) }
    }
    @Test fun `one fallback is accepted only with visual and node movement`() = runTest {
        coEvery { service.scrollEvidence(session) } returnsMany listOf(before, before, before, before, after)
        assertTrue(engine.withScrollFallback(swipe) { dispatched }.success)
        verify(exactly = 1) { service.performSemanticScroll(session) }
    }
    @Test fun `performAction true alone is not success and never retries`() = runTest {
        assertEquals(ActionFailureCode.SCROLL_NO_EFFECT, engine.withScrollFallback(swipe) { dispatched }.failureCode)
        verify(exactly = 1) { service.performSemanticScroll(session) }
    }
    @Test fun `animation alone cannot verify semantic movement`() = runTest {
        coEvery { service.scrollEvidence(session) } returnsMany listOf(before, before, before, before, before.copy(pixels = after.pixels))
        assertEquals(ActionFailureCode.SCROLL_NO_EFFECT, engine.withScrollFallback(swipe) { dispatched }.failureCode)
    }
    @Test fun `no action boundary and rejected action explicitly fail`() = runTest {
        every { service.prepareScroll(swipe) } returns session.copy(actionId = null)
        assertEquals(ActionFailureCode.SCROLL_NO_ACTION, engine.withScrollFallback(swipe) { dispatched }.failureCode)
        verify(exactly = 0) { service.performSemanticScroll(any()) }
        every { service.prepareScroll(swipe) } returns session
        every { service.performSemanticScroll(session) } returns false
        assertEquals(ActionFailureCode.SCROLL_REJECTED, engine.withScrollFallback(swipe) { dispatched }.failureCode)
    }
    @Test fun `changed target and unavailable evidence do not perform an unsafe action`() = runTest {
        every { service.performSemanticScroll(session) } returns null
        assertEquals(ActionFailureCode.SCROLL_TARGET_CHANGED, engine.withScrollFallback(swipe) { dispatched }.failureCode)
        clearMocks(service, answers = false, recordedCalls = true)
        coEvery { service.scrollEvidence(session) } returns null
        assertEquals(ActionFailureCode.SCROLL_UNVERIFIED, engine.withScrollFallback(swipe) { dispatched }.failureCode)
        verify(exactly = 0) { service.performSemanticScroll(any()) }
    }
    @Test fun `excluded gestures and dispatch failures retain original behavior`() = runTest {
        every { service.prepareScroll(swipe) } returns null
        assertEquals(dispatched, engine.withScrollFallback(swipe) { dispatched })
        every { service.prepareScroll(swipe) } returns session
        val failed = ActionResult(false, "Cancelled dispatch")
        assertEquals(failed, engine.withScrollFallback(swipe) { failed })
        verify(exactly = 0) { service.performSemanticScroll(any()) }
    }
    @Test fun `cancellation is propagated without a semantic action`() = runTest {
        try {
            engine.withScrollFallback(swipe) { throw CancellationException("stop") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        verify(exactly = 0) { service.performSemanticScroll(any()) }
    }
}
