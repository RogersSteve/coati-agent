package bot.coati.service

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeNodeTest {

    @Test
    fun `flatCount returns correct count for single node`() {
        val node = createNode("Button", text = "OK")
        assertEquals(1, node.flatCount())
    }

    @Test
    fun `flatCount returns correct count for tree`() {
        val tree = createNode(
            "LinearLayout",
            children = listOf(
                createNode("Button", text = "OK"),
                createNode("TextView", text = "Hello"),
                createNode(
                    "FrameLayout",
                    children = listOf(createNode("ImageView")),
                ),
            ),
        )
        assertEquals(5, tree.flatCount())
    }

    @Test
    fun `nodesWithText counts text nodes`() {
        val tree = createNode(
            "LinearLayout",
            children = listOf(
                createNode("Button", text = "OK"),
                createNode("ImageView"),
                createNode("TextView", text = "Hello"),
            ),
        )
        assertEquals(2, tree.nodesWithText())
    }

    @Test
    fun `toSimpleString includes clickable marker`() {
        val node = createNode("Button", text = "OK", isClickable = true)
        val result = node.toSimpleString()
        assertTrue(result.contains("[clickable]"))
        assertTrue(result.contains("text=\"OK\""))
    }

    @Test
    fun `toSimpleString includes scrollable marker`() {
        val node = createNode("RecyclerView", isScrollable = true)
        val result = node.toSimpleString()
        assertTrue(result.contains("[scrollable]"))
    }

    @Test
    fun `toSimpleString indents children`() {
        val tree = createNode(
            "Root",
            children = listOf(createNode("Child")),
        )
        val result = tree.toSimpleString()
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("  ")) // child indented
    }

    private fun createNode(
        className: String,
        text: String? = null,
        contentDescription: String? = null,
        isClickable: Boolean = false,
        isScrollable: Boolean = false,
        children: List<UiTreeNode> = emptyList(),
    ) = UiTreeNode(
        className = className,
        text = text,
        contentDescription = contentDescription,
        bounds = Rect(0, 0, 100, 100),
        isClickable = isClickable,
        isScrollable = isScrollable,
        viewIdResourceName = null,
        children = children,
    )
}
