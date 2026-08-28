package bot.coati.action

import android.util.Log
import bot.coati.service.CoatiAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEngineCancellationTest {

    @Test
    fun `execute propagates coroutine cancellation instead of returning action failure`() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        val engine = ActionEngine(
            a11y = mockk<CoatiAccessibilityService>(relaxed = true),
            appResolver = mockk<AppResolver>(relaxed = true),
        )

        try {
            val outcome = runCatching {
                withTimeout(1) {
                    engine.execute(AgentAction.Wait(10_000))
                }
            }

            assertTrue(outcome.exceptionOrNull() is TimeoutCancellationException)
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
