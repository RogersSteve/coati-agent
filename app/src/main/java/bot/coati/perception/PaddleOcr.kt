package bot.coati.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import bot.coati.coordinates.PixelBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * On-device OCR with PaddleOCR PP-OCRv4 mobile (Chinese + English) on ONNX Runtime, CPU only.
 * Detection (DB) finds text boxes, recognition (SVTR + CTC) reads each box. The angle classifier is
 * skipped: UI text is upright. Sessions are created lazily from assets on first use; one instance
 * can be shared across coroutines.
 */
class PaddleOcr(
    context: Context,
    private val detMaxSide: Int = OcrPostprocess.DET_MAX_SIDE,
    private val threads: Int = DEFAULT_THREADS,
    /** Use ONNX Runtime's XNNPACK execution provider (ARM-optimized kernels) when available. */
    private val xnnpack: Boolean = true,
) : OcrEngine {
    private val assets = context.applicationContext.assets
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val detLazy = lazy { createSession(DET_MODEL) }
    private val recLazy = lazy { createSession(REC_MODEL) }
    private val detSession: OrtSession by detLazy
    private val recSession: OrtSession by recLazy
    private val charset: List<String> by lazy {
        OcrPostprocess.buildCharset(assets.open(DICT).bufferedReader(Charsets.UTF_8).readLines())
    }

    /**
     * Recognizes text lines in [jpeg] (any format BitmapFactory decodes). [scale] multiplies image
     * pixels into display pixels. Lines are in reading order. Returns an empty list on failure.
     */
    override suspend fun recognize(jpeg: ByteArray, scale: Float): List<OcrLine> = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext emptyList()
        try {
            val t0 = System.nanoTime()
            val boxes = detect(bitmap)
            val t1 = System.nanoTime()
            val lines = recognizeBoxes(bitmap, boxes).map { line ->
                line.copy(
                    bounds = PixelBounds(
                        (line.bounds.left * scale).toInt(), (line.bounds.top * scale).toInt(),
                        (line.bounds.right * scale).toInt(), (line.bounds.bottom * scale).toInt(),
                    ),
                )
            }
            Log.d(TAG, "OCR ${bitmap.width}x${bitmap.height}: det ${(t1 - t0) / 1_000_000}ms (${boxes.size} boxes), " +
                "rec ${(System.nanoTime() - t1) / 1_000_000}ms")
            OcrPostprocess.sortLines(lines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed: ${e.message}", e)
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun warmUp() {
        val blank = Bitmap.createBitmap(WARM_UP_WIDTH, WARM_UP_HEIGHT, Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().use { out ->
            blank.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        blank.recycle()
        recognize(jpeg, 1f)
    }

    /** Text boxes in image pixels. */
    private fun detect(bitmap: Bitmap): List<Pair<PixelBounds, Float>> {
        val (w, h) = OcrPostprocess.detInputSize(bitmap.width, bitmap.height, detMaxSide)
        val started = System.nanoTime()
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        if (resized !== bitmap) resized.recycle()
        val input = OcrPostprocess.detTensor(pixels, w, h)
        val prepared = System.nanoTime()
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            detSession.run(mapOf(detSession.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val mapH = shape[2].toInt()
                val mapW = shape[3].toInt()
                check(mapH == h && mapW == w) { "unexpected det output ${shape.contentToString()} for ${w}x$h" }
                FloatArray(mapW * mapH).also { out.floatBuffer.get(it) }
            }
        }
        val inferred = System.nanoTime()
        val boxes = OcrPostprocess.extractBoxes(prob, w, h).map { box ->
            OcrPostprocess.mapToImage(box, w, h, bitmap.width, bitmap.height) to box.score
        }.filter { (b, _) -> b.right - b.left >= MIN_CROP && b.bottom - b.top >= MIN_CROP }
        Log.d(TAG, "det ${w}x$h: prepare ${(prepared - started) / 1_000_000}ms, infer " +
            "${(inferred - prepared) / 1_000_000}ms, boxes ${(System.nanoTime() - inferred) / 1_000_000}ms")
        return boxes
    }

    /** Reads each box; lines stay in image pixels. Crops are batched by similar aspect ratio. */
    private fun recognizeBoxes(bitmap: Bitmap, boxes: List<Pair<PixelBounds, Float>>): List<OcrLine> {
        if (boxes.isEmpty()) return emptyList()
        val widths = boxes.map { (b, _) -> OcrPostprocess.recInputWidth(b.right - b.left, b.bottom - b.top) }
        val order = boxes.indices.sortedBy { widths[it] }
        val lines = mutableListOf<OcrLine>()
        val height = OcrPostprocess.REC_HEIGHT
        for (batch in order.chunked(REC_BATCH)) {
            val padded = batch.maxOf { widths[it] }
            val stride = 3 * height * padded
            val input = FloatArray(batch.size * stride)
            batch.forEachIndexed { slot, index ->
                val b = boxes[index].first
                val crop = Bitmap.createBitmap(bitmap, b.left, b.top, b.right - b.left, b.bottom - b.top)
                val w = widths[index]
                val scaled = Bitmap.createScaledBitmap(crop, w, height, true)
                val pixels = IntArray(w * height)
                scaled.getPixels(pixels, 0, w, 0, 0, w, height)
                if (scaled !== crop) scaled.recycle()
                if (crop !== bitmap) crop.recycle()
                OcrPostprocess.recTensorInto(pixels, w, height, padded, input, slot * stride)
            }
            val shape = longArrayOf(batch.size.toLong(), 3, height.toLong(), padded.toLong())
            val batchStarted = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                recSession.run(mapOf(recSession.inputNames.first() to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val outShape = out.info.shape
                    val steps = outShape[1].toInt()
                    val classes = outShape[2].toInt()
                    if (classes != charset.size + 1) {
                        Log.w(TAG, "Recognizer has $classes classes but charset has ${charset.size} (+blank)")
                    }
                    val probs = FloatArray(batch.size * steps * classes).also { out.floatBuffer.get(it) }
                    batch.forEachIndexed { slot, index ->
                        val (text, conf) = OcrPostprocess.ctcDecode(probs, slot * steps * classes, steps, classes, charset)
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty() && conf >= MIN_REC_CONFIDENCE) {
                            lines += OcrLine(trimmed, boxes[index].first, conf)
                        }
                    }
                }
            }
            Log.d(TAG, "rec batch n=${batch.size} width=$padded: ${(System.nanoTime() - batchStarted) / 1_000_000}ms")
        }
        return lines
    }

    private fun createSession(asset: String): OrtSession {
        val bytes = assets.open(asset).use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Some screens drive activations into subnormal floats, which made the same-size
            // detection run 10x slower depending on image content; flush them to zero.
            options.addConfigEntry("session.set_denormal_as_zero", "1")
            if (xnnpack) {
                runCatching { options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
                    .onFailure { Log.w(TAG, "XNNPACK unavailable: ${it.message}") }
            }
            env.createSession(bytes, options)
        }
    }

    override fun close() {
        if (detLazy.isInitialized()) runCatching { detLazy.value.close() }
        if (recLazy.isInitialized()) runCatching { recLazy.value.close() }
    }

    companion object {
        private const val TAG = "CoatiOcr"
        private const val DET_MODEL = "ocr/ch_PP-OCRv4_det_infer.onnx"
        private const val REC_MODEL = "ocr/ch_PP-OCRv4_rec_infer.onnx"
        private const val DICT = "ocr/ppocr_keys_v1.txt"
        private const val DEFAULT_THREADS = 4
        private const val REC_BATCH = 6
        private const val MIN_CROP = 4
        private const val MIN_REC_CONFIDENCE = 0.5f
        private const val WARM_UP_WIDTH = 720
        private const val WARM_UP_HEIGHT = 1280
    }
}
