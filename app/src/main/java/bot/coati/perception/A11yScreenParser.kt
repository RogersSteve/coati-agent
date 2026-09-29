package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import bot.coati.service.UiTreeNode

/**
 * Builds executor candidates directly from the accessibility tree, without any model call.
 * Unlabelled clickable containers borrow the texts of their descendants, which is how most
 * Compose/RecyclerView rows expose their meaning.
 */
object A11yScreenParser {

    /** The tree is trusted only when most actionable nodes can be named. */
    fun isUsable(root: UiTreeNode?): Boolean {
        if (root == null) return false
        val actionable = flatten(root).filter { it.isActionable() }
        if (actionable.size < MIN_ACTIONABLE) return false
        val labelled = actionable.count { label(it).isNotBlank() }
        return labelled >= actionable.size * MIN_LABELLED_RATIO
    }

    fun parse(root: UiTreeNode, packageName: String, displayWidth: Int, displayHeight: Int): ParsedScreen {
        val nodes = flatten(root).filter {
            it.isVisibleToUser && it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top
        }
        val elements = mutableListOf<ScreenElement>()
        val regions = mutableListOf<ScreenRegion>()
        for (node in nodes) {
            val bounds = node.bounds.clampTo(displayWidth, displayHeight) ?: continue
            if (node.isScrollable) {
                val axis = if (node.actions.any { it == "SCROLL_LEFT" || it == "SCROLL_RIGHT" } ||
                    (bounds.width() > bounds.height() * 1.5 && bounds.height() < displayHeight / 4)
                ) ScrollAxis.HORIZONTAL else ScrollAxis.VERTICAL
                regions += ScreenRegion("R${regions.size + 1}", "content", setOf(axis), bounds)
            }
            if (!node.isActionable()) continue
            val ops = buildList {
                if (node.isClickable) add(ElementOp.TAP)
                if (node.isEditable) add(ElementOp.INPUT)
                if (node.isLongClickable) add(ElementOp.LONG)
            }
            elements += ScreenElement(
                id = "E${elements.size + 1}",
                type = typeOf(node),
                text = label(node).take(MAX_TEXT),
                purpose = node.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                ops = ops,
                state = stateOf(node),
                bounds = bounds,
            )
        }
        val staticTexts = nodes.filter { !it.isActionable() }
            .mapNotNull { (it.text ?: it.contentDescription)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(MAX_SUMMARY_TEXTS)
        return ParsedScreen(
            source = ScreenSource.ACCESSIBILITY,
            packageName = packageName,
            summary = staticTexts.joinToString(" | ").take(MAX_SUMMARY),
            keyboardVisible = nodes.any { it.isEditable && it.isFocused },
            overlay = null,
            elements = elements,
            regions = regions,
        )
    }

    /**
     * Bounds of visible nodes whose own text or description explains what is drawn there. Large
     * containers are left out: a page-level description on a full-screen root explains nothing
     * about the text painted inside it.
     */
    fun labelledBounds(root: UiTreeNode, displayWidth: Int, displayHeight: Int): List<PixelBounds> {
        val maxArea = displayWidth.toLong() * displayHeight * MAX_LABELLED_AREA
        return flatten(root).filter { node ->
            node.isVisibleToUser &&
                listOfNotNull(node.text, node.contentDescription, node.hintText).any { it.isNotBlank() }
        }.mapNotNull { it.bounds.clampTo(displayWidth, displayHeight) }
            .filter { b -> (b.right - b.left).toLong() * (b.bottom - b.top) <= maxArea }
    }

    private fun UiTreeNode.isActionable() = isClickable || isEditable || isLongClickable

    private fun label(node: UiTreeNode): String {
        val own = listOfNotNull(node.text, node.contentDescription, node.hintText)
            .map(String::trim).filter(String::isNotBlank).distinct()
        if (own.isNotEmpty()) return own.joinToString(" / ")
        return flatten(node).drop(1)
            .mapNotNull { (it.text ?: it.contentDescription)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" / ")
    }

    private fun typeOf(node: UiTreeNode): String {
        val cls = node.className?.substringAfterLast('.').orEmpty()
        return when {
            node.isEditable -> "input"
            cls.contains("Switch") || cls.contains("CheckBox") -> "switch"
            cls.contains("Tab") -> "tab"
            cls.contains("Image") -> "icon"
            cls.contains("Button") -> "button"
            else -> "item"
        }
    }

    private fun stateOf(node: UiTreeNode): String? = when {
        node.isFocused && node.isEditable -> "focused"
        node.isChecked -> "on"
        node.isSelected -> "selected"
        node.className?.contains("Switch") == true -> "off"
        else -> null
    }

    private fun flatten(root: UiTreeNode): List<UiTreeNode> {
        val out = mutableListOf<UiTreeNode>()
        val stack = ArrayDeque<UiTreeNode>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            out += node
            node.children.asReversed().forEach(stack::addFirst)
        }
        return out
    }

    private fun android.graphics.Rect.clampTo(width: Int, height: Int): PixelBounds? {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        return if (r > l && b > t) PixelBounds(l, t, r, b) else null
    }

    private fun PixelBounds.width() = right - left
    private fun PixelBounds.height() = bottom - top

    private const val MAX_LABELLED_AREA = 0.25
    private const val MIN_ACTIONABLE = 3
    private const val MIN_LABELLED_RATIO = 0.8
    private const val MAX_TEXT = 80
    private const val MAX_SUMMARY_TEXTS = 25
    private const val MAX_SUMMARY = 500
}
