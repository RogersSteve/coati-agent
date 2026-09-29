package bot.coati.perception

import bot.coati.coordinates.PixelBounds

/**
 * Same-screen hybrid of the accessibility tree and a vision parse. Many real screens mix native
 * controls with painted content (Canvas, games, maps, Flutter/Unity views without semantics): the
 * tree alone misses the painted controls, a vision parse alone is slow and loses exact native
 * bounds. On-device OCR tells the two apart cheaply: rendered text that no tree node accounts for is
 * evidence of painted content, and only then is a vision parse merged in.
 */
object HybridPerception {
    /** At least this many uncovered OCR lines mark a screen as mixed; one stray line is noise. */
    const val MIN_UNCOVERED_LINES = 1
    private const val MIN_LINE_CONFIDENCE = 0.8f
    private const val MIN_LINE_CHARS = 2

    /**
     * OCR lines whose center lies outside every labelled tree node and below the status bar.
     * [labelled] are bounds of nodes that carry text or a description (the tree "explains" them).
     */
    fun uncoveredLines(lines: List<OcrLine>, labelled: List<PixelBounds>, statusBarBottom: Int): List<OcrLine> =
        lines.filter { line ->
            val cx = (line.bounds.left + line.bounds.right) / 2
            val cy = (line.bounds.top + line.bounds.bottom) / 2
            cy > statusBarBottom &&
                line.confidence >= MIN_LINE_CONFIDENCE &&
                line.text.count { it.isLetterOrDigit() } >= MIN_LINE_CHARS &&
                labelled.none { it.contains(cx, cy) }
        }

    /**
     * The tree screen plus the vision elements and regions the tree does not already cover (their
     * center lies outside every tree element). Tree elements keep their exact bounds and states;
     * vision elements are renumbered after them.
     */
    fun merge(tree: ParsedScreen, vision: ParsedScreen, statusBarBottom: Int): ParsedScreen {
        val added = vision.elements.filter { v ->
            v.centerY > statusBarBottom && tree.elements.none { it.bounds.contains(v.centerX, v.centerY) }
        }
        val elements = tree.elements + added.mapIndexed { i, e -> e.copy(id = "E${tree.elements.size + i + 1}") }
        val regions = tree.regions + vision.regions
            .filter { v -> tree.regions.none { it.bounds.contains((v.bounds.left + v.bounds.right) / 2, (v.bounds.top + v.bounds.bottom) / 2) } }
            .mapIndexed { i, r -> r.copy(id = "R${tree.regions.size + i + 1}") }
        return tree.copy(
            source = ScreenSource.HYBRID,
            summary = listOf(tree.summary, vision.summary).filter(String::isNotBlank).joinToString(" | ").take(MAX_SUMMARY),
            overlay = tree.overlay ?: vision.overlay,
            elements = elements,
            regions = regions,
        )
    }

    private fun PixelBounds.contains(x: Int, y: Int) = x in left until right && y in top until bottom

    private const val MAX_SUMMARY = 600
}
