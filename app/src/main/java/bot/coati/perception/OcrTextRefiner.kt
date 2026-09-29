package bot.coati.perception

import bot.coati.coordinates.PixelBounds

/** One recognized text line in raw display pixels, with the recognizer's mean confidence. */
data class OcrLine(val text: String, val bounds: PixelBounds, val confidence: Float = 1f)

/**
 * Repairs a vision-model parse with on-device OCR, conservatively. On real screens the model's own
 * transcription is usually right while OCR misreads small text, icon glyphs and artwork, so OCR
 * never overwrites text the model read. It only adds what the model missed and fixes positions:
 * - an element keeps the model's text; OCR lines inside it that the model did not include (a
 *   list row's channel name, view count, second line) are appended after it;
 * - an element the model left without text gets the OCR text inside it;
 * - text-like elements (labels, text buttons, tabs, links) snap to the exact box of the OCR line
 *   that reads the same;
 * - a text-like element with no matching OCR line inside it moves to the one place on screen
 *   where its text is drawn, when that is unambiguous; otherwise it is marked unconfirmed;
 * - confident text no element accounts for is added as a low-priority text element.
 * Text inside pictures (thumbnails, avatars, icons) is artwork and is never attached to labels.
 * Measured on 14 board screens (lab, Canvas, Flutter, bilibili, NewPipe): no element whose text
 * or tap position the plain parse had right got worse.
 */
object OcrTextRefiner {
    private const val MAX_TEXT = 120
    private const val LINE_BAND = 12
    private const val MIN_OVERLAP = 0.5
    private const val SAME_STRING = 0.5
    private const val MIN_LENGTH_RATIO = 0.7
    private const val MATCH_SIMILARITY = 0.8
    private const val MIN_CONFIDENCE = 0.8f
    private const val MIN_EXTRA_CONFIDENCE = 0.9f
    private const val MIN_CHARS = 2
    private const val SNAP_PADDING = 8
    const val UNCONFIRMED = "not confirmed by OCR"
    const val ADDED_PURPOSE = "text found by OCR"

    private val MOVABLE = setOf("text", "text_btn", "tab", "link")
    private val PICTURES = setOf("icon", "image", "avatar")

    fun refine(screen: ParsedScreen, ocrLines: List<OcrLine>, statusBarBottom: Int = 0): ParsedScreen {
        val lines = ocrLines.filter { it.confidence >= MIN_CONFIDENCE && (isText(it.text) || isCount(it)) }
        if (lines.isEmpty()) return screen
        val elements = screen.elements
        val used = BooleanArray(lines.size)
        val refined = elements.map { element ->
            val inside = lines.indices.filter { belongs(lines[it].bounds, element.bounds) }
            if (inside.isNotEmpty()) {
                inside.forEach { used[it] = true }
                if (element.type in PICTURES) return@map element
                val ordered = inside.map(lines::get).sortedWith(compareBy({ it.bounds.top / LINE_BAND }, { it.bounds.left }))
                val model = normalize(element.text)
                val text: String
                val matching: List<OcrLine>
                if (model.isEmpty()) {
                    text = ordered.joinToString("\n") { it.text }
                    matching = ordered
                } else {
                    matching = ordered.filter { sameString(element.text, it.text) || normalize(it.text) in model }
                    val extra = ordered.filter { line ->
                        line !in matching && similarity(normalize(line.text), model) < MATCH_SIMILARITY &&
                            line.confidence >= MIN_EXTRA_CONFIDENCE && !inPicture(line.bounds, elements)
                    }
                    text = (listOf(element.text) + extra.map { it.text }).joinToString("\n")
                }
                val bounds = if (element.type in MOVABLE && matching.isNotEmpty()) {
                    union(matching.map { it.bounds }).padded(SNAP_PADDING)
                } else {
                    element.bounds
                }
                return@map element.copy(text = text.take(MAX_TEXT), bounds = bounds)
            }
            if (normalize(element.text).isEmpty() || element.type !in MOVABLE) return@map element
            // The model read the text but placed it where no such text is drawn: move it there when
            // there is exactly one such element and the line belongs to no other element.
            val target = lines.indices.filter { !used[it] }
                .maxByOrNull { similarity(normalize(lines[it].text), normalize(element.text)) }
                ?.takeIf { similarity(normalize(lines[it].text), normalize(element.text)) >= MATCH_SIMILARITY }
            val unique = elements.count { similarity(normalize(it.text), normalize(element.text)) >= MATCH_SIMILARITY } == 1
            val taken = target != null && elements.any { it !== element && belongs(lines[target].bounds, it.bounds) }
            if (target != null && unique && !taken) {
                used[target] = true
                return@map element.copy(bounds = lines[target].bounds.padded(SNAP_PADDING))
            }
            element.copy(purpose = listOf(element.purpose, UNCONFIRMED).filter(String::isNotBlank).joinToString("; "))
        }
        val added = lines.indices.filter { !used[it] }.map(lines::get)
            .filter { (it.bounds.top + it.bounds.bottom) / 2 > statusBarBottom }
            .mapIndexed { i, line ->
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

    /** Words: at least two letters or digits (single glyphs such as "+", "□" or "F" are icon noise). */
    private fun isText(s: String) = s.count(Char::isLetterOrDigit) >= MIN_CHARS

    /** A confident bare number (a counter, a badge, a quantity) carries state even as one digit. */
    private fun isCount(line: OcrLine) = line.confidence >= MIN_EXTRA_CONFIDENCE && line.text.trim().let { t ->
        t.isNotEmpty() && t.all(Char::isDigit)
    }

    /**
     * Share of the parse's texts that OCR also reads somewhere on the screen, or null when either
     * side has too little text to judge. A parse of a different image (seen from relays under load)
     * scores near zero; a normal parse of a text-bearing screen scores well above half.
     */
    fun agreement(screen: ParsedScreen, ocrLines: List<OcrLine>): Double? {
        val read = ocrLines.filter { it.confidence >= MIN_CONFIDENCE }.map { normalize(it.text) }.filter { it.length >= MIN_CHARS }
        val texts = screen.elements.map { normalize(it.text.lineSequence().first()) }.filter { it.length >= AGREEMENT_MIN_CHARS }
        if (read.size < AGREEMENT_MIN_LINES || texts.size < AGREEMENT_MIN_TEXTS) return null
        val seen = texts.count { t -> read.any { r -> r in t || t in r || similarity(r, t) >= AGREEMENT_SIMILARITY } }
        return seen.toDouble() / texts.size
    }

    private const val AGREEMENT_MIN_CHARS = 3
    private const val AGREEMENT_MIN_LINES = 3
    private const val AGREEMENT_MIN_TEXTS = 4
    private const val AGREEMENT_SIMILARITY = 0.6

    /** OCR reads the same string as the model (typo-level differences), not a fragment of it. */
    private fun sameString(model: String, ocr: String): Boolean {
        val m = normalize(model)
        val o = normalize(ocr)
        if (m.isEmpty()) return true
        return similarity(m, o) >= SAME_STRING && o.length >= m.length * MIN_LENGTH_RATIO
    }

    private fun inPicture(line: PixelBounds, elements: List<ScreenElement>) =
        elements.any { it.type in PICTURES && it.bounds.contains(line.centerX(), line.centerY()) }

    private fun belongs(line: PixelBounds, box: PixelBounds) =
        overlapOfLine(line, box) >= MIN_OVERLAP || box.contains(line.centerX(), line.centerY())

    /** Share of the line's area inside [box]. */
    private fun overlapOfLine(line: PixelBounds, box: PixelBounds): Double {
        val w = minOf(line.right, box.right) - maxOf(line.left, box.left)
        val h = minOf(line.bottom, box.bottom) - maxOf(line.top, box.top)
        if (w <= 0 || h <= 0) return 0.0
        val area = (line.right - line.left).toDouble() * (line.bottom - line.top)
        return if (area <= 0) 0.0 else w.toDouble() * h / area
    }

    private fun PixelBounds.centerX() = (left + right) / 2
    private fun PixelBounds.centerY() = (top + bottom) / 2
    private fun PixelBounds.contains(x: Int, y: Int) = x in left until right && y in top until bottom

    private fun union(boxes: List<PixelBounds>) = PixelBounds(
        boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
    )

    private fun PixelBounds.padded(p: Int) = PixelBounds(maxOf(0, left - p), maxOf(0, top - p), right + p, bottom + p)

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
