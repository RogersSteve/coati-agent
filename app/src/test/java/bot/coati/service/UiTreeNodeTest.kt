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
    fun `toSimpleString includes editable input metadata`() {
        val node = createNode(
            "EditText",
            hintText = "Body",
            isEditable = true,
            isFocused = true,
            isMultiLine = true,
            isShowingHintText = true,
            actions = listOf("SET_TEXT"),
        )
        val result = node.toSimpleString()

        assertTrue(result.contains("hint=\"Body\""))
        assertTrue(result.contains("[editable]"))
        assertTrue(result.contains("[focused]"))
        assertTrue(result.contains("[multiline]"))
        assertTrue(result.contains("[showing-hint]"))
        assertTrue(result.contains("actions=SET_TEXT"))
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
        hintText: String? = null,
        isClickable: Boolean = false,
        isScrollable: Boolean = false,
        isEditable: Boolean = false,
        isFocused: Boolean = false,
        isMultiLine: Boolean = false,
        isShowingHintText: Boolean = false,
        actions: List<String> = emptyList(),
        children: List<UiTreeNode> = emptyList(),
    ) = UiTreeNode(
        className = className,
        text = text,
        contentDescription = contentDescription,
        hintText = hintText,
        bounds = Rect(0, 0, 100, 100),
        isClickable = isClickable,
        isScrollable = isScrollable,
        isEditable = isEditable,
        isFocused = isFocused,
        isMultiLine = isMultiLine,
        isShowingHintText = isShowingHintText,
        viewIdResourceName = null,
        actions = actions,
        children = children,
    )

    @Test
    fun `pruned keeps actionable and text nodes and lifts them out of plain layouts`() {
        val tree = createNode(
            "FrameLayout",
            children = listOf(
                createNode(
                    "LinearLayout",
                    children = listOf(
                        createNode("ImageView"),
                        createNode("Button", text = "Save", isClickable = true),
                        createNode("RecyclerView", isScrollable = true, children = listOf(
                            createNode("LinearLayout", children = listOf(createNode("TextView", text = "Buy milk"))),
                        )),
                    ),
                ),
            ),
        )
        val pruned = tree.pruned()
        assertEquals("FrameLayout", pruned.className)
        assertEquals(listOf("Button", "RecyclerView"), pruned.children.map { it.className })
        assertEquals(listOf("TextView"), pruned.children[1].children.map { it.className })
        assertEquals(4, pruned.flatCount())
    }
}
