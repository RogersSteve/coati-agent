package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import java.io.Closeable

/** On-device text recognition over a screenshot. Implementations must be safe to share across coroutines. */
interface OcrEngine : Closeable {
    /**
     * Recognizes text lines in [jpeg]. [scale] multiplies image pixels into display pixels.
     * Text centered inside a [skip] region (display pixels) is detected but not read: recognition is
     * the expensive part, and callers skip regions they already understand. When [only] is given,
     * just text centered inside one of those regions is read (an empty list reads nothing). Lines are
     * in reading order; returns an empty list on failure.
     */
    suspend fun recognize(
        jpeg: ByteArray,
        scale: Float,
        skip: List<PixelBounds> = emptyList(),
        only: List<PixelBounds>? = null,
    ): List<OcrLine>

    /** Loads models and runs one throwaway inference so the first real screen is not slow. */
    suspend fun warmUp()
}
