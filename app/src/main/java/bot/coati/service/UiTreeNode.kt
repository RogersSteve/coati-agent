package bot.coati.service

import android.graphics.Rect

data class UiTreeNode(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String? = null,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean = false,
    val isFocused: Boolean = false,
    val isMultiLine: Boolean = false,
    val isShowingHintText: Boolean = false,
    val viewIdResourceName: String?,
    val actions: List<String> = emptyList(),
    val children: List<UiTreeNode>,
    val isLongClickable: Boolean = false,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isVisibleToUser: Boolean = true,
) {
    fun toSimpleString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val label = buildString {
            className?.substringAfterLast('.')?.let { append(it) }
            text?.let { append(" text=\"$it\"") }
            contentDescription?.let { append(" desc=\"$it\"") }
            hintText?.let { append(" hint=\"$it\"") }
            if (isClickable) append(" [clickable]")
            if (isScrollable) append(" [scrollable]")
            if (isEditable) append(" [editable]")
            if (isFocused) append(" [focused]")
            if (isMultiLine) append(" [multiline]")
            if (isShowingHintText) append(" [showing-hint]")
            viewIdResourceName?.let { append(" id=$it") }
            if (actions.isNotEmpty()) append(" actions=${actions.joinToString("|")}")
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
