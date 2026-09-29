package bot.coati.perception

import bot.coati.coordinates.PixelBounds

/** One recognized text line in raw display pixels, with the recognizer's mean confidence. */
data class OcrLine(val text: String, val bounds: PixelBounds, val confidence: Float = 1f)

/**
 * Repairs a vision-model parse with on-device OCR. The model understands the screen (which things
 * are controls, what they do) but transcribes text imperfectly and places boxes on a coarse
 * 0-1000 grid; OCR reads rendered text exactly and knows its pixel position. So OCR:
 * - replaces the model's text inside each element;
 * - snaps text-like elements (labels, text buttons, tabs, links) to the exact text position;
 * - moves an element whose text the model read correctly but placed where no such text is drawn;
 * - adds confident text the model left out, as low-priority text elements;
 * - marks elements whose text OCR cannot find anywhere as unconfirmed (images can hide text from
 *   OCR, so they are kept).
 */
object OcrTextRefiner {
    private const val MAX_TEXT = 80
    private const val LINE_BAND = 12
    private const val MIN_OVERLAP = 0.5
    private const val MATCH_SIMILARITY = 0.8
    private const val MIN_ADDED_CONFIDENCE = 0.8f
    private const val MIN_ADDED_CHARS = 2
    private const val SNAP_PADDING = 8
    const val UNCONFIRMED = "not confirmed by OCR"
    const val ADDED_PURPOSE = "text found by OCR"

    private val TEXT_LIKE = setOf("text", "text_btn", "tab", "link")

    fun refine(screen: ParsedScreen, lines: List<OcrLine>, statusBarBottom: Int = 0): ParsedScreen {
        if (lines.isEmpty()) return screen
        val used = BooleanArray(lines.size)
        val refined = screen.elements.map { element ->
            val inside = lines.indices.filter { overlapOfLine(lines[it].bounds, element.bounds) >= MIN_OVERLAP }
            if (inside.isNotEmpty()) {
                inside.forEach { used[it] = true }
                val ordered = inside.map(lines::get).sortedWith(compareBy({ it.bounds.top / LINE_BAND }, { it.bounds.left }))
                val text = ordered.joinToString("\n") { it.text }.take(MAX_TEXT)
                val bounds = if (element.type in TEXT_LIKE) union(ordered.map { it.bounds }).padded(SNAP_PADDING) else element.bounds
                return@map element.copy(text = text, bounds = bounds)
            }
            if (normalize(element.text).isEmpty()) return@map element // icons and images: nothing to check
            // The model read the text but placed it where no such text is drawn: move it there.
            val match = lines.indices.filter { !used[it] }
                .maxByOrNull { similarity(normalize(lines[it].text), normalize(element.text)) }
                ?.takeIf { similarity(normalize(lines[it].text), normalize(element.text)) >= MATCH_SIMILARITY }
            if (match != null) {
                used[match] = true
                val line = lines[match]
                val bounds = if (element.type in TEXT_LIKE) line.bounds.padded(SNAP_PADDING) else element.bounds.movedTo(line.bounds)
                return@map element.copy(text = line.text.take(MAX_TEXT), bounds = bounds)
            }
            element.copy(purpose = listOf(element.purpose, UNCONFIRMED).filter(String::isNotBlank).joinToString("; "))
        }
        val added = lines.indices.filter { !used[it] }.map(lines::get).filter { line ->
            (line.bounds.top + line.bounds.bottom) / 2 > statusBarBottom &&
                line.confidence >= MIN_ADDED_CONFIDENCE &&
                line.text.count { it.isLetterOrDigit() } >= MIN_ADDED_CHARS
        }.mapIndexed { i, line ->
            ScreenElement(
                id = "E${refined.size + i + 1}",
                type = "text",
                text = line.text.take(MAX_TEXT),
                purpose = ADDED_PURPOSE,
                ops = listOf(ElementOp.TAP),
                state = null,
                bounds = line.bounds.padded(SNAP_PADDING),
            )
        }
        return screen.copy(elements = refined + added)
    }

    /** Share of the line's area inside [box]. */
    private fun overlapOfLine(line: PixelBounds, box: PixelBounds): Double {
        val w = minOf(line.right, box.right) - maxOf(line.left, box.left)
        val h = minOf(line.bottom, box.bottom) - maxOf(line.top, box.top)
        if (w <= 0 || h <= 0) return 0.0
        val area = (line.right - line.left).toDouble() * (line.bottom - line.top)
        return if (area <= 0) 0.0 else w.toDouble() * h / area
    }

    private fun union(boxes: List<PixelBounds>) = PixelBounds(
        boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
    )

    private fun PixelBounds.padded(p: Int) = PixelBounds(maxOf(0, left - p), maxOf(0, top - p), right + p, bottom + p)

    /** Keeps the element's size but centers it on [target]. */
    private fun PixelBounds.movedTo(target: PixelBounds): PixelBounds {
        val dx = (target.left + target.right) / 2 - (left + right) / 2
        val dy = (target.top + target.bottom) / 2 - (top + bottom) / 2
        return PixelBounds(maxOf(0, left + dx), maxOf(0, top + dy), right + dx, bottom + dy)
    }

    internal fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** 1 - normalized Levenshtein distance. */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
