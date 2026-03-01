package bot.coati.service

import android.graphics.Rect

data class UiTreeNode(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val viewIdResourceName: String?,
    val children: List<UiTreeNode>,
) {
    fun toSimpleString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val label = buildString {
            className?.substringAfterLast('.')?.let { append(it) }
            text?.let { append(" text=\"$it\"") }
            contentDescription?.let { append(" desc=\"$it\"") }
            if (isClickable) append(" [clickable]")
            if (isScrollable) append(" [scrollable]")
            viewIdResourceName?.let { append(" id=$it") }
            append(" bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        }
        sb.appendLine("$prefix$label")
        children.forEach { sb.append(it.toSimpleString(indent + 1)) }
        return sb.toString()
    }

    fun flatCount(): Int = 1 + children.sumOf { it.flatCount() }

    fun nodesWithText(): Int {
        val self = if (!text.isNullOrBlank()) 1 else 0
        return self + children.sumOf { it.nodesWithText() }
    }
}
