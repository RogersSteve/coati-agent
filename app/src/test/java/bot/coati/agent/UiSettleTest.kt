package bot.coati.agent

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSettleTest {

    @Test
    fun `a screen that stops changing ends the wait early`() = runTest {
        val readings = ArrayDeque(listOf("list loading", "list half drawn", "list", "list"))
        val settled = UiSettle.await({ readings.removeFirstOrNull() ?: "list" }, { currentTime })
        assertTrue(settled)
        assertEquals(250L + 3 * 250L, currentTime)
    }

    @Test
    fun `an unchanged screen settles after the minimum wait and one poll`() = runTest {
        assertTrue(UiSettle.await({ "same" }, { currentTime }))
        assertEquals(500L, currentTime)
    }

    @Test
    fun `a screen that never settles is captured at the cap`() = runTest {
        var tick = 0
        assertFalse(UiSettle.await({ "frame ${tick++}" }, { currentTime }))
        assertTrue(currentTime <= UiSettle.CAP_MS)
    }

    @Test
    fun `verify reasons about blank splash or loading screens are recognised`() {
        assertTrue(UiSettle.describesLoading("The AFTER screenshot shows a blank white screen"))
        assertTrue(UiSettle.describesLoading("App splash screen still visible"))
        assertTrue(UiSettle.describesLoading(null, "A loading spinner covers the list"))
        assertFalse(UiSettle.describesLoading("The screen remained completely unchanged"))
        assertFalse(UiSettle.describesLoading(null, null))
    }
}
