package bot.coati.perception

import android.graphics.Rect
import bot.coati.service.UiTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yScreenParserTest {

    // The JVM android.jar stubs Rect's constructor; set the public fields directly.
    private fun rect(l: Int, t: Int, r: Int, b: Int) = Rect().apply { left = l; top = t; right = r; bottom = b }

    private fun node(
        cls: String = "View",
        text: String? = null,
        desc: String? = null,
        bounds: Rect = rect(0, 0, 100, 100),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        checked: Boolean = false,
        children: List<UiTreeNode> = emptyList(),
    ) = UiTreeNode(
        className = cls,
        text = text,
        contentDescription = desc,
        bounds = bounds,
        isClickable = clickable,
        isScrollable = scrollable,
        viewIdResourceName = null,
        children = children,
        isChecked = checked,
    )

    private val tree = node(
        bounds = rect(0, 0, 1080, 2400),
        children = listOf(
            node("ScrollView", desc = "页面：我的", bounds = rect(0, 168, 1080, 2180), scrollable = true, children = listOf(
                node(bounds = rect(47, 430, 1033, 670), clickable = true, children = listOf(
                    node("TextView", "设置", bounds = rect(92, 475, 193, 546)),
                    node("TextView", "调整提示方式", bounds = rect(92, 562, 699, 625)),
                )),
                node("android.widget.Switch", "紧凑资料卡片", bounds = rect(47, 700, 1033, 800), clickable = true, checked = true),
            )),
            node(desc = "首页标签", bounds = rect(21, 2196, 273, 2322), clickable = true),
        ),
    )

    @Test
    fun `clickable containers borrow descendant labels and keep display bounds`() {
        assertTrue(A11yScreenParser.isUsable(tree))

        val screen = A11yScreenParser.parse(tree, "bot.coati.fixture.manual", 1080, 2400)

        val settings = screen.elements.first { it.text.startsWith("设置") }
        assertEquals("设置 / 调整提示方式", settings.text)
        assertEquals(540, settings.centerX)
        assertEquals("on", screen.elements.first { it.type == "switch" }.state)
        assertEquals(1, screen.regions.size)
        assertTrue(screen.summary.contains("页面：我的"))
    }

    @Test
    fun `a tree of unlabelled clickables is not usable`() {
        val opaque = node(children = List(5) { node(clickable = true, bounds = rect(0, it * 100, 100, it * 100 + 90)) })
        assertFalse(A11yScreenParser.isUsable(opaque))
    }

    @Test
    fun `labelled bounds skip page-sized containers`() {
        val screen = node(
            bounds = rect(0, 0, 1080, 2400),
            children = listOf(
                node("ScrollView", desc = "页面：Mixed Order", bounds = rect(0, 168, 1080, 2180), children = listOf(
                    node("TextView", text = "Submit order", bounds = rect(40, 1500, 1040, 1580)),
                )),
            ),
        )
        assertEquals(
            listOf(rect(40, 1500, 1040, 1580)).map { bot.coati.coordinates.PixelBounds(it.left, it.top, it.right, it.bottom) },
            A11yScreenParser.labelledBounds(screen, 1080, 2400),
        )
    }
}
