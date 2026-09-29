package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import kotlin.math.abs

/**
 * Same-screen hybrid of the accessibility tree and a vision parse. Many real screens mix native
 * controls with painted content (Canvas, games, maps, Flutter/Unity views without semantics): the
 * tree alone misses the painted controls, a vision parse alone is slow and loses exact native
 * bounds. On-device OCR tells the two apart cheaply: rendered text inside a node the tree gives no
 * text for is evidence of painted content, and only then is a vision parse merged in.
 *
 * Text in no node at all is not painted content: it is a logo, background, or a layer the app has
 * taken out of the tree while covered (Flutter hides the page behind its settings panel). Text in
 * image nodes is artwork (video thumbnails). On 11 board screens with a tree (lab, Flutter Gallery,
 * bilibili, NewPipe), this rule marked only the Canvas-in-native order page as mixed.
 */
object HybridPerception {
    /** At least this many painted OCR lines mark a screen as mixed; one stray line is noise. */
    const val MIN_PAINTED_LINES = 2

    /** A tree node's box, whether the tree gives it text, and whether it is an image. */
    data class TreeBox(val bounds: PixelBounds, val labelled: Boolean, val image: Boolean)
    private const val MIN_LINE_CONFIDENCE = 0.8f
    private const val MIN_LINE_CHARS = 2

    /**
     * OCR lines drawn inside a tree node that has no text of its own and is not an image, with no
     * labelled node over them, below the status bar and outside [excluded] regions (the keyboard).
     */
    fun paintedLines(
        lines: List<OcrLine>,
        hosts: List<TreeBox>,
        statusBarBottom: Int,
        excluded: List<PixelBounds> = emptyList(),
    ): List<OcrLine> = lines.filter { line ->
        val cx = (line.bounds.left + line.bounds.right) / 2
        val cy = (line.bounds.top + line.bounds.bottom) / 2
        val containing = hosts.filter { it.bounds.contains(cx, cy) }
        val innermost = containing.minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top) }
        cy > statusBarBottom &&
            line.confidence >= MIN_LINE_CONFIDENCE &&
            line.text.count { it.isLetterOrDigit() } >= MIN_LINE_CHARS &&
            excluded.none { it.contains(cx, cy) } &&
            innermost != null && !innermost.image &&
            containing.none { it.labelled }
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
            pageKeyElements = tree.elements.size,
        )
    }

    /**
     * Whether the painted lines OCR reads now are the ones it read before, in the same places: then
     * the painted part of the page is unchanged and an earlier vision parse still describes it.
     * A counter that moved (2 -> 3) or a scrolled page reads differently.
     */
    fun samePainted(before: List<OcrLine>, now: List<OcrLine>, tolerancePx: Int = PAINTED_TOLERANCE_PX): Boolean =
        before.size == now.size && before.zip(now).all { (a, b) ->
            a.text == b.text && abs(a.bounds.left - b.bounds.left) <= tolerancePx &&
                abs(a.bounds.top - b.bounds.top) <= tolerancePx
        }

    private fun PixelBounds.contains(x: Int, y: Int) = x in left until right && y in top until bottom

    private const val MAX_SUMMARY = 600
    private const val PAINTED_TOLERANCE_PX = 12
}
