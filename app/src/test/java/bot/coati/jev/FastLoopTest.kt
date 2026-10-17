package bot.coati.jev

import bot.coati.model.LoopPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastLoopTest {

    @Test
    fun `an item keeps its identity when its control flips state`() {
        val before = FastLoop.itemKey("Favorite / Favorite CM-7472 (Favorite)")
        assertEquals("cm-7472", before)
        assertEquals(before, FastLoop.itemKey("Favorited / Favorite CM-7472 (Favorited)"))
        assertEquals("refresh", FastLoop.itemKey("Refresh"))
    }

    @Test
    fun `an item loop counts distinct items and never offers a handled one again`() {
        val loop = FastLoop(LoopPlan(each = "tap Favorite on the next listing", count = 2))
        assertTrue(loop.itemLoop)
        loop.recordProgress("Favorite / Favorite CM-7472 (Favorite)")
        assertTrue(loop.isHandled("Favorited / Favorite CM-7472 (Favorited)"))
        assertFalse(loop.isHandled("Favorite / Favorite CM-1259 (Favorite)"))
        assertFalse(loop.countReached)
        loop.recordProgress("Favorite / Favorite CM-1259 (Favorite)")
        assertTrue(loop.countReached)
        assertEquals(2, loop.handled.size)
    }

    @Test
    fun `a repeat-until loop acts on the same control again`() {
        val loop = FastLoop(LoopPlan(each = "tap Refresh", until = "CM-9338 shows a price below \$40,000"))
        assertFalse(loop.itemLoop)
        loop.recordProgress("Refresh")
        assertFalse(loop.isHandled("Refresh"))
        loop.recordProgress("Refresh")
        assertEquals(2, loop.iterations)
        assertTrue(loop.guidance().contains("Stop when: CM-9338"))
    }

    @Test
    fun `three attempts without effect stall the loop and progress resets the count`() {
        val loop = FastLoop(LoopPlan(each = "tap Hide", scope = "rows marked SOLD"))
        loop.recordNoProgress(); loop.recordNoProgress()
        loop.recordProgress("Hide / Hide CM-3305 (Hide)")
        loop.recordNoProgress(); loop.recordNoProgress()
        assertFalse(loop.stalled)
        loop.recordNoProgress()
        assertTrue(loop.stalled)
    }

    @Test
    fun `the audit record says when an every-item loop did not reach the end of the list`() {
        val loop = FastLoop(LoopPlan(each = "tap Hide", scope = "rows marked SOLD"))
        loop.recordProgress("Hide / Hide CM-3305 (Hide)")
        loop.finish(FastLoop.Stop.SELECTOR_FINISHED)
        assertTrue(loop.evidence().contains("did NOT reach the end of the list"))
        assertTrue(loop.evidence().contains("Hide / Hide CM-3305"))
        loop.recordEndOfList()
        assertFalse(loop.evidence().contains("did NOT reach"))
        assertTrue(loop.summary().startsWith("[LOOP DONE] 1 items: Hide / Hide CM-3305 (Hide)"))
    }

    @Test
    fun `the first success teaches the control the host repeats`() {
        val loop = FastLoop(LoopPlan(each = "tap Favorite", count = 10))
        assertTrue(loop.hostSelects)
        assertFalse(loop.isControl("Favorite / Favorite CM-1259 (Favorite)"))
        loop.recordProgress("Favorite / Favorite CM-7472 (Favorite)")
        assertEquals("Favorite", loop.control)
        assertTrue(loop.isControl("Favorite / Favorite CM-1259 (Favorite)"))
        assertFalse(loop.isControl("Favorited / Favorite CM-7472 (Favorited)"))
        assertFalse(loop.isControl("Compare / Compare CM-1259 (Compare)"))
    }

    @Test
    fun `scope keywords name badge words and ids, and a negated scope has none`() {
        assertEquals(setOf("sold"), FastLoop.keywordsOf("rows marked SOLD"))
        assertEquals(setOf("cm-3305", "cm-8821"), FastLoop.keywordsOf("listings CM-3305, CM-8821"))
        assertTrue(FastLoop.keywordsOf("cars not marked Sold").isEmpty())
        val loop = FastLoop(LoopPlan(each = "tap Hide", scope = "rows marked SOLD"))
        assertFalse(loop.hostSelects)
        assertFalse(loop.matchesScope("Hide / Hide CM-2048 (Hide)", "Mercedes-Benz C-Class Sedan · CM-2048 · \$39,500"))
        assertFalse(loop.scopeMatched)
        assertTrue(loop.matchesScope("Hide / Hide CM-6610 (Hide)", "Toyota Camry · CM-6610 · \$24,900 · SOLD"))
        assertTrue(loop.scopeMatched)
    }

    @Test
    fun `only a pure repeat-until loop asks a model whether to stop`() {
        assertTrue(FastLoop(LoopPlan(each = "tap Refresh", until = "price below 40k")).checksUntil)
        assertFalse(FastLoop(LoopPlan(each = "tap Favorite", count = 10, until = "10 favorites")).checksUntil)
        assertFalse(FastLoop(LoopPlan(each = "tap Hide", until = "bottom", scope = "rows marked SOLD")).checksUntil)
    }
}
