package bot.coati.jev

import bot.coati.perception.ElementOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevCandidatesTest {

    @Test
    fun `builds element, scroll and control candidates`() {
        val s = screen(
            element("E1", "设置", ElementOp.TAP),
            element("E2", "搜索资料", ElementOp.TAP, ElementOp.INPUT, type = "input"),
            regions = listOf(verticalRegion()),
            keyboard = true,
        )

        val keys = JevCandidates.build(s, ExplorationMemory(), emptySet(), apps = listOf("Maps"), canType = true)
            .map { it.key }

        assertTrue(keys.containsAll(listOf("E1.TAP", "E2.TAP", "E2.INPUT", "R1.SCROLL_DOWN", "R1.SCROLL_UP")))
        assertTrue(keys.containsAll(listOf("OPEN_APP.Maps", "ENTER", "BACK", "WAIT", "DONE", "ESCALATE")))
    }

    @Test
    fun `a region's advertised extent drops directions it cannot move and marks unseen content`() {
        val atTop = verticalRegion().copy(moreForward = true, moreBackward = false)
        val s = screen(element("E1", "资料库", ElementOp.TAP), regions = listOf(atTop))

        val candidates = JevCandidates.build(s, ExplorationMemory(), emptySet(), apps = emptyList(), canType = false)

        val keys = candidates.map { it.key }
        assertFalse("R1.SCROLL_UP" in keys)
        val down = candidates.single { it.key == "R1.SCROLL_DOWN" }
        assertTrue(down.description.contains("continues below"))
    }

    @Test
    fun `input candidates are hidden when typing is impossible and excluded keys are dropped`() {
        val s = screen(element("E1", "搜索", ElementOp.TAP, ElementOp.INPUT))

        val keys = JevCandidates.build(s, null, excluded = setOf("E1.TAP", "DONE"), apps = emptyList(), canType = false)
            .map { it.key }

        assertFalse("E1.INPUT" in keys)
        assertFalse("E1.TAP" in keys)
        assertFalse("DONE" in keys)
    }

    @Test
    fun `element outcomes are remembered globally by text, not by id`() {
        val memory = ExplorationMemory()
        val home = screen(element("E1", "首页"), element("E2", "资料库"), element("E3", "我的"))
        val library = screen(element("E7", "搜索资料"), element("E8", "离线旅行清单"), element("E9", "会议准备清单"))
        memory.visit(home.pageKey)
        memory.record(home.pageKey, JevCandidates.identityOf(CandidateAction.Element("E2", ElementOp.TAP), home),
            library.pageKey, "资料库页面")
        memory.visit(library.pageKey)

        // Same tab, different id, on a different page: the destination is still known.
        val profile = screen(element("E4", "设置"), element("E5", "资料库"), element("E6", "帮助与关于"))
        val candidates = JevCandidates.build(profile, memory, emptySet(), emptyList(), canType = false)
            .associateBy { it.key }

        assertTrue(candidates.getValue("E5.TAP").annotated)
        assertTrue(candidates.getValue("E5.TAP").description.contains("资料库页面"))
        assertFalse(candidates.getValue("E4.TAP").annotated)
    }

    @Test
    fun `bouncing between two pages is a cycle until an intervention breaks it`() {
        val memory = ExplorationMemory()
        val list = setOf("返回", "查看详情", "搜索资料")
        val detail = setOf("离线旅行清单", "检查项目", "返回")
        repeat(3) { memory.visit(list); memory.visit(detail) }
        assertTrue(memory.isCycling())

        memory.breakCycle()
        assertFalse(memory.isCycling())

        val progressing = ExplorationMemory()
        listOf(setOf("a"), setOf("b"), setOf("c"), setOf("a"), setOf("b"), setOf("d")).forEach(progressing::visit)
        assertFalse(progressing.isCycling())
    }

    @Test
    fun `filling a form on one page is neither a cycle nor stagnation until it goes on for long`() {
        val memory = ExplorationMemory()
        val form = setOf("Mixed Order", "Copies", "Priority delivery", "Recipient", "Submit order")
        repeat(8) { memory.visit(form) }
        assertFalse(memory.isCycling())
        assertFalse(memory.isStagnant())

        repeat(6) { memory.visit(form) }
        assertTrue(memory.isStagnant())
    }

    @Test
    fun `an intervention marker is not a page`() {
        val memory = ExplorationMemory()
        val form = setOf("Mixed Order", "Copies", "Priority delivery", "Recipient", "Submit order")
        repeat(3) { memory.visit(form) }
        memory.breakCycle()
        repeat(6) { memory.visit(form) }
        assertFalse(memory.isCycling())
    }

    @Test
    fun `an action that left the page unchanged is removed while alternatives remain`() {
        val memory = ExplorationMemory()
        val page = screen(element("E1", "查看详情"), element("E2", "首页"), element("E3", "收藏"))
        memory.visit(page.pageKey)
        memory.record(page.pageKey, JevCandidates.identity("查看详情", "TAP"), page.pageKey, "same")

        val keys = JevCandidates.build(page, memory, emptySet(), emptyList(), canType = false).map { it.key }
        assertFalse("E1.TAP" in keys)

        // With no other way to move, dead ends come back rather than stranding the executor.
        val only = screen(element("E1", "查看详情"))
        memory.record(only.pageKey, JevCandidates.identity("查看详情", "TAP"), only.pageKey, "same")
        memory.record(only.pageKey, ExplorationMemory.ActionIdentity.PageRelative("BACK"), only.pageKey, "same")
        val restored = JevCandidates.build(only, memory, emptySet(), emptyList(), canType = false)
        assertEquals("TAP item '查看详情' [tried: no effect]", restored.first { it.key == "E1.TAP" }.description)
    }

    @Test
    fun `a destination is a dead end only once every entry it offered was tried`() {
        val memory = ExplorationMemory()
        val home = screen(element("E1", "首页"), element("E2", "资料库"), element("E3", "我的"))
        val profile = screen(element("E4", "设置"), element("E5", "帮助与关于"), element("E6", "首页"))
        memory.visit(home.pageKey)
        memory.record(home.pageKey, JevCandidates.identity("我的", "TAP"), profile.pageKey, "我的")
        memory.visit(profile.pageKey)
        memory.recordOffered(profile.pageKey, setOf(JevCandidates.identity("设置", "TAP"), JevCandidates.identity("帮助与关于", "TAP")))

        fun profileTab() = JevCandidates.build(home, memory, emptySet(), emptyList(), canType = false)
            .firstOrNull { it.key == "E3.TAP" }
        assertTrue(profileTab()!!.description.contains("has untried entries"))

        memory.record(profile.pageKey, JevCandidates.identity("设置", "TAP"), setOf("设置页"), "设置")
        memory.record(profile.pageKey, JevCandidates.identity("帮助与关于", "TAP"), setOf("帮助页"), "帮助")
        assertEquals(null, profileTab())
    }
}
