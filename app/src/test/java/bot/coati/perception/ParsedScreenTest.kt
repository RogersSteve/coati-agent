package bot.coati.perception

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedScreenTest {

    private val chrome = setOf("返回", "V1", "首页", "资料库", "收藏", "我的", "页面：")

    @Test
    fun `sibling native pages are one page for exploration but distinct for the manual`() {
        // Board: Settings and Advanced settings share their bars and differ in a few labels.
        val settings = chrome + setOf("设置", "显示完成提示", "高级设置")
        val advanced = chrome + setOf("高级设置", "紧凑资料卡片", "实验档案")

        assertTrue(ParsedScreen.samePage(settings, advanced))
        assertFalse(ParsedScreen.nearlySamePage(settings, advanced))
    }

    @Test
    fun `one changed item keeps a page the same for the manual`() {
        val list = chrome + (1..20).map { "条目$it" }
        val scrolledByOne = chrome + (2..21).map { "条目$it" }

        assertTrue(ParsedScreen.nearlySamePage(list, scrolledByOne))
    }
}
