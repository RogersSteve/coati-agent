package bot.coati.perception

import bot.coati.coordinates.PixelBounds

/** One recognized text line in raw display pixels, with the recognizer's mean confidence. */
data class OcrLine(val text: String, val bounds: PixelBounds, val confidence: Float = 1f)

/**
 * Corrects vision-model transcriptions with OCR evidence. Vision models occasionally misread or
 * invent characters; rendered UI text is the ground truth an agent matches task words against.
 */
object OcrTextRefiner {
    private const val MAX_TEXT = 80
    private const val LINE_BAND = 12

    /**
     * Replaces each element's text with the OCR lines whose centers fall inside it (reading order).
     * Elements without OCR evidence, such as glyph-only icons, keep the model text.
     */
    fun refine(screen: ParsedScreen, lines: List<OcrLine>): ParsedScreen {
        if (lines.isEmpty()) return screen
        val refined = screen.elements.map { element ->
            val inside = lines.filter { line ->
                val cx = (line.bounds.left + line.bounds.right) / 2
                val cy = (line.bounds.top + line.bounds.bottom) / 2
                cx in element.bounds.left..element.bounds.right && cy in element.bounds.top..element.bounds.bottom
            }.sortedWith(compareBy({ it.bounds.top / LINE_BAND }, { it.bounds.left }))
            if (inside.isEmpty()) element
            else element.copy(text = inside.joinToString("\n") { it.text }.take(MAX_TEXT))
        }
        return screen.copy(elements = refined)
    }
}
