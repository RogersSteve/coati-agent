package bot.coati.action

import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEngineOpenAppTest {

    @Test
    fun `opening the app already in front is a no-op the loop does not charge`() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        try {
            val front = mockk<AccessibilityNodeInfo>(relaxed = true) {
                every { packageName } returns "com.flauschcode.broccoli"
            }
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { packageManager.getLaunchIntentForPackage("com.flauschcode.broccoli") } returns
                    mockk<Intent>(relaxed = true)
                every { rootInActiveWindow } returns front
            }

            val result = ActionEngine(a11y, mockk(relaxed = true))
                .execute(AgentAction.OpenApp(packageName = "com.flauschcode.broccoli"))

            assertTrue(result.success)
            assertTrue(result.noOp)
            verify(exactly = 0) { a11y.performGlobalAction(any()) }
            verify(exactly = 0) { a11y.startActivity(any()) }
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
