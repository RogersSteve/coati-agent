package bot.coati.action

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEngineClickFallbackTest {

    private fun TestScope.engine(a11y: CoatiAccessibilityService) =
        ActionEngine(a11y, mockk(relaxed = true), uptimeMillis = { currentTime })

    private fun node() = mockk<AccessibilityNodeInfo>(relaxed = true) {
        every { className } returns "android.widget.LinearLayout"
        every { performAction(any()) } returns true
    }

    private val gesture = ActionResult(success = true, message = "Tapped (540, 829)")

    @Test
    fun `a tap that left the screen unchanged is completed with the accessibility click`() = runTest {
        withMockedLog {
            val target = node()
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { actionableNodeAt(540, 829, false) } returns target
                every { appScreenSnapshot() } returns "Files: Alarms, Android, DCIM"
            }

            val result = engine(a11y).withClickFallback(540, 829, longClick = false) { gesture }

            verify(exactly = 1) { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            assertTrue(result.success)
            assertTrue(result.message.contains("accessibility click"))
        }
    }

    @Test
    fun `a tap that changed the screen is never clicked a second time`() = runTest {
        withMockedLog {
            val target = node()
            var screen = "Files: Alarms, Android, DCIM"
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { actionableNodeAt(any(), any(), any()) } returns target
                every { appScreenSnapshot() } answers { screen }
            }

            val result = engine(a11y).withClickFallback(540, 829, longClick = false) {
                screen = "Alarms: (empty)" // the folder opened
                gesture
            }

            verify(exactly = 0) { target.performAction(any()) }
            assertEquals(gesture, result)
        }
    }

    @Test
    fun `a long press that left the screen unchanged falls back to the long click action`() = runTest {
        withMockedLog {
            val target = node()
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { actionableNodeAt(540, 829, true) } returns target
                every { appScreenSnapshot() } returns "Files: Alarms"
            }

            engine(a11y).withClickFallback(540, 829, longClick = true) { gesture }

            verify(exactly = 1) { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
        }
    }

    @Test
    fun `points without a list item and failed gestures keep the gesture result`() = runTest {
        withMockedLog {
            val target = node()
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { actionableNodeAt(10, 10, false) } returns null
                every { actionableNodeAt(540, 829, false) } returns target
                every { appScreenSnapshot() } returns "unchanged"
            }
            val engine = engine(a11y)

            assertEquals(gesture, engine.withClickFallback(10, 10, longClick = false) { gesture })
            val failed = ActionResult(success = false, message = "Tap failed")
            assertEquals(failed, engine.withClickFallback(540, 829, longClick = false) { failed })
            verify(exactly = 0) { target.performAction(any()) }
        }
    }

    private suspend fun withMockedLog(block: suspend () -> Unit) {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        try {
            block()
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
