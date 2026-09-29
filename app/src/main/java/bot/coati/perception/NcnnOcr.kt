package bot.coati.perception

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import bot.coati.coordinates.PixelBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** JNI entry points of libcoati_ocr.so (src/main/cpp/ocr_jni.cpp). */
internal object NcnnOcrNative {
    /** Loads det/rec models on the default Vulkan GPU; 0 when there is no usable GPU. */
    @JvmStatic external fun nativeCreate(assets: AssetManager, detSize: Int): Long

    /** Name of the Vulkan device in use, or null when none is initialized. */
    @JvmStatic external fun nativeDeviceName(): String?

    /**
     * [Array<String> texts, FloatArray conf,x0,y0,x1,y1 per line] for an ARGB_8888 bitmap, or null on
     * failure. Boxes centered inside [skipRects] (x0,y0,x1,y1 quadruples, image pixels) are not read.
     */
    @JvmStatic external fun nativeRecognize(handle: Long, bitmap: Bitmap, skipRects: IntArray?, onlyRects: IntArray?): Array<Any>?

    @JvmStatic external fun nativeRelease(handle: Long)
}

/**
 * PaddleOCR PP-OCRv5 mobile (det + rec, Chinese + English) on ncnn's Vulkan backend. Inference runs
 * on the GPU only; create instances through [OcrEngines.create], which returns null when the device
 * has no usable Vulkan GPU. One instance can be shared across coroutines: calls are serialized.
 */
class NcnnOcr private constructor(private var handle: Long) : OcrEngine {
    private val mutex = Mutex()

    override suspend fun recognize(
        jpeg: ByteArray,
        scale: Float,
        skip: List<PixelBounds>,
        only: List<PixelBounds>?,
    ): List<OcrLine> = withContext(Dispatchers.Default) {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return@withContext emptyList()
        try {
            run(bitmap, scale, skip, only)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun warmUp() {
        val blank = Bitmap.createBitmap(WARM_UP_WIDTH, WARM_UP_HEIGHT, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        try {
            withContext(Dispatchers.Default) { run(blank, 1f, emptyList(), null) }
        } finally {
            blank.recycle()
        }
    }

    private suspend fun run(bitmap: Bitmap, scale: Float, skip: List<PixelBounds>, only: List<PixelBounds>?): List<OcrLine> = mutex.withLock {
        if (handle == 0L) return emptyList()
        try {
            val started = System.nanoTime()
            // Skip regions arrive in display pixels; the bitmap is the (possibly downscaled) capture.
            fun toImage(boxes: List<PixelBounds>) = boxes.flatMap { b ->
                listOf((b.left / scale).toInt(), (b.top / scale).toInt(), (b.right / scale).toInt(), (b.bottom / scale).toInt())
            }.toIntArray()
            // An empty "only" list means nothing is wanted; skip the inference altogether.
            if (only != null && only.isEmpty()) return emptyList()
            val result = NcnnOcrNative.nativeRecognize(
                handle, bitmap, skip.takeIf { it.isNotEmpty() }?.let(::toImage), only?.let(::toImage),
            ) ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val lines = OcrReadingOrder.toLines(result[0] as Array<String>, result[1] as FloatArray, scale)
            Log.d(TAG, "OCR ${bitmap.width}x${bitmap.height}: ${(System.nanoTime() - started) / 1_000_000}ms, ${lines.size} lines")
            lines
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun close() {
        // Blocks only while an in-flight inference finishes; never called on the main thread in practice.
        runBlocking {
            mutex.withLock {
                if (handle != 0L) {
                    NcnnOcrNative.nativeRelease(handle)
                    handle = 0L
                }
            }
        }
    }

    companion object {
        private const val TAG = "NcnnOcr"
        private const val WARM_UP_WIDTH = 720
        private const val WARM_UP_HEIGHT = 1280

        /** Detection input long side (multiple of 32). */
        const val DET_SIZE = 960

        /** Loads the models on the GPU; null when there is no usable Vulkan GPU. Blocking. */
        internal fun open(assets: AssetManager): NcnnOcr? {
            val handle = NcnnOcrNative.nativeCreate(assets, DET_SIZE)
            if (handle == 0L) return null
            Log.i(TAG, "PP-OCRv5 on Vulkan GPU: ${NcnnOcrNative.nativeDeviceName()}")
            return NcnnOcr(handle)
        }
    }
}
