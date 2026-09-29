package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import kotlin.math.roundToInt

/** Pure helpers for turning native OCR output into [OcrLine]s; JVM-testable. */
internal object OcrReadingOrder {
    /** Floats per line in the native result: confidence, x0, y0, x1, y1 (image pixels). */
    const val VALUES_PER_LINE = 5

    /**
     * Builds lines from the native result: [texts] and [values] ([VALUES_PER_LINE] floats per line),
     * scaling bounds by [scale] into display pixels, dropping blank text, in reading order.
     */
    fun toLines(texts: Array<String>, values: FloatArray, scale: Float): List<OcrLine> {
        require(values.size == texts.size * VALUES_PER_LINE) { "expected ${texts.size * VALUES_PER_LINE} values, got ${values.size}" }
        val lines = texts.indices.mapNotNull { i ->
            val text = texts[i].trim()
            if (text.isEmpty()) return@mapNotNull null
            val o = i * VALUES_PER_LINE
            OcrLine(
                text = text,
                bounds = PixelBounds(
                    (values[o + 1] * scale).roundToInt(), (values[o + 2] * scale).roundToInt(),
                    (values[o + 3] * scale).roundToInt(), (values[o + 4] * scale).roundToInt(),
                ),
                confidence = values[o],
            )
        }
        return sort(lines)
    }

    /**
     * Reading order: lines whose vertical centers fall within half a line height of a row's first
     * line share that row; rows go top-to-bottom, lines within a row left-to-right.
     */
    fun sort(lines: List<OcrLine>): List<OcrLine> {
        val byCenter = lines.sortedWith(compareBy({ it.bounds.top + it.bounds.bottom }, { it.bounds.left }))
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in byCenter) {
            val row = rows.lastOrNull()
            val anchor = row?.first()
            val cy = (line.bounds.top + line.bounds.bottom) / 2f
            if (anchor != null && cy - (anchor.bounds.top + anchor.bounds.bottom) / 2f <=
                (anchor.bounds.bottom - anchor.bounds.top) / 2f
            ) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.bounds.left } }
    }
}
