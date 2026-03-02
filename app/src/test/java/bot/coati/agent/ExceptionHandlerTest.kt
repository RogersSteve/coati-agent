package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ExceptionHandlerTest {

    @Test
    fun `first failure returns RETRY`() {
        val handler = ExceptionHandler()
        assertEquals(FailureAction.RETRY, handler.recordFailure())
        assertEquals(1, handler.consecutiveFailures)
    }

    @Test
    fun `second failure returns REPLAN`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        assertEquals(FailureAction.REPLAN, handler.recordFailure())
        assertEquals(2, handler.consecutiveFailures)
    }

    @Test
    fun `third failure returns ABORT`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        handler.recordFailure()
        assertEquals(FailureAction.ABORT, handler.recordFailure())
        assertEquals(3, handler.consecutiveFailures)
    }

    @Test
    fun `recordSuccess resets counter`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        handler.recordFailure()
        handler.recordSuccess()
        assertEquals(0, handler.consecutiveFailures)
        assertEquals(FailureAction.RETRY, handler.recordFailure())
    }

    @Test
    fun `reset clears counter`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        handler.recordFailure()
        handler.reset()
        assertEquals(0, handler.consecutiveFailures)
    }

    @Test
    fun `custom thresholds`() {
        val handler = ExceptionHandler(maxConsecutiveFailures = 5, replanThreshold = 3)
        assertEquals(FailureAction.RETRY, handler.recordFailure())  // 1
        assertEquals(FailureAction.RETRY, handler.recordFailure())  // 2
        assertEquals(FailureAction.REPLAN, handler.recordFailure()) // 3
        assertEquals(FailureAction.REPLAN, handler.recordFailure()) // 4
        assertEquals(FailureAction.ABORT, handler.recordFailure())  // 5
    }

    @Test
    fun `buildReplanHint includes failure count`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        handler.recordFailure()
        val hint = handler.buildReplanHint()
        assert(hint.contains("2 times"))
        assert(hint.contains("Reconsider"))
    }

    @Test
    fun `multiple success-failure cycles`() {
        val handler = ExceptionHandler()
        handler.recordFailure()
        handler.recordSuccess()
        handler.recordFailure()
        handler.recordSuccess()
        assertEquals(0, handler.consecutiveFailures)
        assertEquals(FailureAction.RETRY, handler.recordFailure())
    }
}
