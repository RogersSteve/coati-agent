package bot.coati.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManualRendererTest {
    private val renderer = AppManualRenderer()

    @Test
    fun `guide and learn read the same current markdown while off and shadow inject nothing`() {
        val manual = testManual()
        val guide = renderer.render(manual, ManualMode.READ_ONLY, "搜索资料", "搜索框")!!
        val learn = renderer.render(manual, ManualMode.LEARNING, "搜索资料", "搜索框")!!
        assertEquals(guide.text, learn.text)
        assertNull(renderer.render(manual, ManualMode.OFF, "搜索", "搜索框"))
        assertNull(renderer.render(manual, ManualMode.SHADOW, "搜索", "搜索框"))
    }

    @Test
    fun `retrieval always includes index and selects only relevant page details`() {
        val rendered = renderer.render(
            testManual(),
            ManualMode.READ_ONLY,
            task = "在首页输入内容并查看搜索结果",
            uiTreeHint = "EditText hint=搜索 Button text=搜索",
        )!!
        assertTrue("页面索引" in rendered.headingPaths)
        assertTrue("页面详情/搜索结果页" in rendered.headingPaths)
        assertFalse("页面详情/设置页" in rendered.headingPaths)
        assertTrue(rendered.text.contains("[Manual heading: 页面详情/搜索结果页]"))
        assertTrue(rendered.text.length <= AppManualPolicy.MAX_RENDERED_CHARS)
        assertTrue(rendered.estimatedTokens <= AppManualPolicy.MAX_RENDERED_ESTIMATED_TOKENS)
    }

    @Test
    fun `unrelated task gets compact outline without arbitrary detail`() {
        val rendered = renderer.render(testManual(), ManualMode.READ_ONLY, "查看天气", "温度")!!
        assertTrue("页面索引" in rendered.headingPaths)
        assertFalse(rendered.headingPaths.any { it.startsWith("页面详情/") })
    }
}
