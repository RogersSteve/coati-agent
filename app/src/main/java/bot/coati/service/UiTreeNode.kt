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
) {
    /**
     * @param toNormalized maps a pixel to the model's 0..1000 space. Prompts must pass it: bounds
     * in pixels next to a request for normalized coordinates made models copy pixel values.
     */
    fun toSimpleString(indent: Int = 0, toNormalized: ((Int, Int) -> Pair<Int, Int>)? = null): String {
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
            if (toNormalized == null) {
                append(" bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            } else {
                val (left, top) = toNormalized(bounds.left, bounds.top)
                val (right, bottom) = toNormalized(bounds.right, bounds.bottom)
                append(" bounds=[$left,$top][$right,$bottom]")
            }
        }
        sb.appendLine("$prefix$label")
        children.forEach { sb.append(it.toSimpleString(indent + 1, toNormalized)) }
        return sb.toString()
    }

    fun flatCount(): Int = 1 + children.sumOf { it.flatCount() }

    fun nodesWithText(): Int {
        val self = if (!text.isNullOrBlank()) 1 else 0
        return self + children.sumOf { it.nodesWithText() }
    }
}
