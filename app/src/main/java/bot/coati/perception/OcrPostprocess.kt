package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An axis-aligned text box in some pixel space, with its detection score. */
internal data class OcrBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Pure pre/post-processing for the PaddleOCR (PP-OCRv4) pipeline, kept free of Android and ONNX
 * Runtime types so it is JVM-testable. Pixels are packed ARGB ints as returned by Bitmap.getPixels.
 */
internal object OcrPostprocess {
    const val DET_MAX_SIDE = 960
    const val DET_THRESH = 0.3f
    const val DET_BOX_THRESH = 0.6f
    const val DET_UNCLIP_RATIO = 1.5f
    const val DET_MIN_SIZE = 3
    const val REC_HEIGHT = 48
    const val REC_MAX_WIDTH = 1280

    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Detection input size: the long side is capped at [maxSide] (never upscaled) and both sides
     * are rounded to multiples of 32, as the DB backbone downsamples by 32.
     */
    fun detInputSize(width: Int, height: Int, maxSide: Int = DET_MAX_SIDE): Pair<Int, Int> {
        val ratio = min(1f, maxSide.toFloat() / max(width, height))
        fun round32(v: Float) = max(32, (v / 32f).roundToInt() * 32)
        return round32(width * ratio) to round32(height * ratio)
    }

    /** RGB, CHW, ImageNet mean/std normalization for the detector. */
    fun detTensor(pixels: IntArray, width: Int, height: Int): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0]
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2]
        }
        return out
    }

    /**
     * Writes one recognizer input (RGB, CHW, (x/255-0.5)/0.5) of [width]x[height] into [out] at
     * [offset], zero-padded on the right up to [paddedWidth].
     */
    fun recTensorInto(pixels: IntArray, width: Int, height: Int, paddedWidth: Int, out: FloatArray, offset: Int) {
        val plane = paddedWidth * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val i = offset + y * paddedWidth + x
                out[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
            }
        }
    }

    /** Recognizer input width for a crop: height fixed at [REC_HEIGHT], aspect ratio kept. */
    fun recInputWidth(cropWidth: Int, cropHeight: Int, maxWidth: Int = REC_MAX_WIDTH): Int {
        if (cropHeight <= 0) return REC_HEIGHT
        return ceil(REC_HEIGHT * cropWidth.toFloat() / cropHeight).toInt().coerceIn(REC_HEIGHT / 4, maxWidth)
    }

    /** DB unclip offset for a box: area * ratio / perimeter. */
    fun unclipDistance(width: Float, height: Float, ratio: Float = DET_UNCLIP_RATIO): Float {
        val perimeter = 2f * (width + height)
        return if (perimeter <= 0f) 0f else width * height * ratio / perimeter
    }

    /**
     * DB postprocess on a [width]x[height] probability map: binarize at [thresh], 8-connected
     * components, per component an axis-aligned box scored by the mean probability inside the
     * component, filtered by [boxThresh] and [minSize], then unclipped. Returned boxes are in map
     * pixels, clamped to the map.
     */
    fun extractBoxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float = DET_THRESH,
        boxThresh: Float = DET_BOX_THRESH,
        unclipRatio: Float = DET_UNCLIP_RATIO,
        minSize: Int = DET_MIN_SIZE,
    ): List<OcrBox> {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val boxes = mutableListOf<OcrBox>()
        for (start in 0 until width * height) {
            if (visited[start] || prob[start] <= thresh) continue
            visited[start] = true
            var sp = 0
            stack[sp++] = start
            var minX = width; var minY = height; var maxX = -1; var maxY = -1
            var sum = 0.0
            var count = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sum += prob[idx]
                count++
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val n = ny * width + nx
                        if (!visited[n] && prob[n] > thresh) {
                            visited[n] = true
                            stack[sp++] = n
                        }
                    }
                }
            }
            val w = (maxX - minX + 1).toFloat()
            val h = (maxY - minY + 1).toFloat()
            if (min(w, h) < minSize) continue
            val score = (sum / count).toFloat()
            if (score < boxThresh) continue
            val d = unclipDistance(w, h, unclipRatio)
            val box = OcrBox(
                left = (minX - d).coerceAtLeast(0f),
                top = (minY - d).coerceAtLeast(0f),
                right = (maxX + 1 + d).coerceAtMost(width.toFloat()),
                bottom = (maxY + 1 + d).coerceAtMost(height.toFloat()),
                score = score,
            )
            if (min(box.width, box.height) < minSize + 2) continue
            boxes += box
        }
        return boxes
    }

    /** Maps a box from a [mapWidth]x[mapHeight] map into integer pixels of a [imageWidth]x[imageHeight] image. */
    fun mapToImage(box: OcrBox, mapWidth: Int, mapHeight: Int, imageWidth: Int, imageHeight: Int): PixelBounds {
        val sx = imageWidth.toFloat() / mapWidth
        val sy = imageHeight.toFloat() / mapHeight
        return PixelBounds(
            (box.left * sx).toInt().coerceIn(0, imageWidth),
            (box.top * sy).toInt().coerceIn(0, imageHeight),
            ceil(box.right * sx).toInt().coerceIn(0, imageWidth),
            ceil(box.bottom * sy).toInt().coerceIn(0, imageHeight),
        )
    }

    /** PaddleOCR dictionary: one char per line, plus the trailing space class the recognizer emits. */
    fun buildCharset(dictLines: List<String>): List<String> = dictLines.map { it.trimEnd('\r', '\n') } + " "

    /**
     * CTC greedy decode of one sequence stored at [offset] in [probs] as [steps] x [classes].
     * Class 0 is blank, class i maps to charset[i - 1]; repeats collapse unless separated by a
     * blank. Confidence is the mean max probability of the emitted characters (0 when empty).
     */
    fun ctcDecode(probs: FloatArray, offset: Int, steps: Int, classes: Int, charset: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var confSum = 0f
        var emitted = 0
        var prev = -1
        for (t in 0 until steps) {
            val base = offset + t * classes
            var best = 0
            var bestP = probs[base]
            for (c in 1 until classes) {
                val p = probs[base + c]
                if (p > bestP) { best = c; bestP = p }
            }
            if (best != 0 && best != prev && best - 1 < charset.size) {
                text.append(charset[best - 1])
                confSum += bestP
                emitted++
            }
            prev = best
        }
        return text.toString() to (if (emitted == 0) 0f else confSum / emitted)
    }

    /**
     * Reading order: lines whose vertical centers fall within half a line height of a row's first
     * line share that row; rows go top-to-bottom, lines within a row left-to-right.
     */
    fun sortLines(lines: List<OcrLine>): List<OcrLine> {
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
