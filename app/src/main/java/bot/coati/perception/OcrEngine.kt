package bot.coati.perception

import java.io.Closeable

/** On-device text recognition over a screenshot. Implementations must be safe to share across coroutines. */
interface OcrEngine : Closeable {
    /**
     * Recognizes text lines in [jpeg]. [scale] multiplies image pixels into display pixels.
     * Lines are in reading order; returns an empty list on failure.
     */
    suspend fun recognize(jpeg: ByteArray, scale: Float): List<OcrLine>

    /** Loads models and runs one throwaway inference so the first real screen is not slow. */
    suspend fun warmUp()
}
