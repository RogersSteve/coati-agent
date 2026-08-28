package bot.coati.action

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.DirectTextEditMode
import bot.coati.service.DirectTextEditResult
import bot.coati.service.DirectTextEditStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEngineDirectInputTest {

    @Test
    fun `type uses valid input connection without requiring an editable accessibility node`() = runTest {
        withMockedLog {
            val text = "message"
            val root = activeRoot("com.example.customeditor")
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { rootInActiveWindow } returns root
                coEvery {
                    commitTextThroughInputConnection(
                        text,
                        DirectTextEditMode.CARET,
                        "com.example.customeditor",
                    )
                } returns DirectTextEditResult(
                    DirectTextEditStatus.DISPATCHED,
                    "Text commit dispatched",
                )
            }
            val engine = ActionEngine(a11y, mockk(relaxed = true))

            val result = engine.execute(AgentAction.InputText(text, InputTextMode.TYPE))

            assertTrue(result.success)
            coVerify(exactly = 1) {
                a11y.commitTextThroughInputConnection(
                    text,
                    DirectTextEditMode.CARET,
                    "com.example.customeditor",
                )
            }
            verify(exactly = 0) { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        }
    }

    @Test
    fun `append also uses input connection without accessibility editability`() = runTest {
        withMockedLog {
            val text = "\nnext"
            val root = activeRoot("com.example.customeditor")
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { rootInActiveWindow } returns root
                coEvery {
                    commitTextThroughInputConnection(
                        text,
                        DirectTextEditMode.APPEND,
                        "com.example.customeditor",
                    )
                } returns DirectTextEditResult(
                    DirectTextEditStatus.DISPATCHED,
                    "Text commit dispatched",
                )
            }
            val engine = ActionEngine(a11y, mockk(relaxed = true))

            val result = engine.execute(AgentAction.InputText(text, InputTextMode.APPEND))

            assertTrue(result.success)
            verify(exactly = 0) { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        }
    }

    @Test
    fun `unconfirmed direct caret never falls back to accessibility replacement`() = runTest {
        withMockedLog {
            val root = activeRoot("com.example.customeditor")
            val a11y = mockk<CoatiAccessibilityService>(relaxed = true) {
                every { rootInActiveWindow } returns root
                coEvery {
                    commitTextThroughInputConnection(
                        "message",
                        DirectTextEditMode.CARET,
                        "com.example.customeditor",
                    )
                } returns DirectTextEditResult(
                    DirectTextEditStatus.SELECTION_NOT_CONFIRMED,
                    "Current collapsed caret could not be verified",
                )
            }
            val engine = ActionEngine(a11y, mockk(relaxed = true))

            val result = engine.execute(AgentAction.InputText("message", InputTextMode.TYPE))

            assertFalse(result.success)
            assertEquals(ActionFailureCode.TEXT_CARET_NOT_CONFIRMED, result.failureCode)
            verify(exactly = 0) { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        }
    }

    private fun activeRoot(packageName: String): AccessibilityNodeInfo =
        mockk(relaxed = true) {
            every { this@mockk.packageName } returns packageName
        }

    private suspend fun withMockedLog(block: suspend () -> Unit) {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        try {
            block()
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
