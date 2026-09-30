package bot.coati.perception

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates the on-device OCR engine. OCR runs on the GPU (ncnn Vulkan) or not at all: there is no
 * CPU fallback, so devices without a usable Vulkan GPU get null and run without OCR.
 */
object OcrEngines {
    private const val TAG = "OcrEngines"
    private const val LIBRARY = "coati_ocr"

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "OCR unavailable: cannot load lib$LIBRARY.so: ${e.message}")
            false
        }
    }

    /** The GPU OCR engine, or null (logged) when the native library or a Vulkan GPU is unavailable. */
    suspend fun create(context: Context): OcrEngine? = withContext(Dispatchers.Default) {
        if (!libraryLoaded) return@withContext null
        val engine = try {
            NcnnOcr.open(context.applicationContext.assets)
        } catch (e: Throwable) {
            Log.w(TAG, "OCR unavailable: native init failed: ${e.message}", e)
            null
        }
        if (engine == null) Log.w(TAG, "OCR unavailable: no usable Vulkan GPU or model load failed")
        engine
    }
}
