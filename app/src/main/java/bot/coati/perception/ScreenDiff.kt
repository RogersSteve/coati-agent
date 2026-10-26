package bot.coati.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import bot.coati.coordinates.PixelBounds
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Grayscale thumbnail of one captured frame; [values] are 0..255 row-major luminances. */
class LumaGrid(val width: Int, val height: Int, val values: IntArray) {
    init {
        require(width > 0 && height > 0 && values.size == width * height) { "invalid_luma_grid" }
    }
}

/** Where and how much the screen changed between two frames, in display pixels. */
data class VisualChange(val changedFraction: Double, val bounds: PixelBounds?)

/**
 * Pixel-level before/after comparison for verification. Catches changes an accessibility tree
 * or a text parse cannot express (custom-drawn switches, color-only selection, canvas apps).
 *
 * Frames are decoded at 1/2 scale straight from the JPEG, so the whole comparison takes about
 * 10 ms and never needs an image library. 1/8 scale averaged a short label change ("Visible" ->
 * "Hidden") down to a luminance delta of 9 and missed it; at 1/2 it reaches 77 while two captures
 * of an unchanged screen still differ nowhere.
 */
object ScreenDiff {
    /** Per-cell luminance delta that counts as a real change, above JPEG noise. */
    private const val CELL_THRESHOLD = 24

    /** Fallback top band when the real status-bar inset is unknown; its clock and icons change on their own. */
    private const val STATUS_BAR_FRACTION = 0.035

    fun lumaGrid(jpeg: ByteArray, sampleSize: Int = 2): LumaGrid? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            LumaGrid(bitmap.width, bitmap.height, IntArray(pixels.size) { i -> luma(pixels[i]) })
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * @param statusBarPx the real status-bar (+cutout) height in display pixels; 0 when unknown.
     * @return null when the frames are not comparable (different geometry, e.g. rotation).
     */
    fun compare(
        before: LumaGrid,
        after: LumaGrid,
        displayWidth: Int,
        displayHeight: Int,
        statusBarPx: Int = 0,
    ): VisualChange? {
        if (before.width != after.width || before.height != after.height) return null
        val skipRows = if (statusBarPx > 0) {
            // Round up and add one row so antialiased bar edges never count as content changes.
            ((statusBarPx.toLong() * after.height + displayHeight - 1) / displayHeight).toInt() + 1
        } else {
            (after.height * STATUS_BAR_FRACTION).toInt()
        }.coerceAtMost(after.height - 1)
        var changed = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in skipRows until after.height) {
            val row = y * after.width
            for (x in 0 until after.width) {
                if (abs(after.values[row + x] - before.values[row + x]) > CELL_THRESHOLD) {
                    changed++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val total = (after.height - skipRows) * after.width
        val bounds = if (changed == 0) null else PixelBounds(
            minX * displayWidth / after.width,
            minY * displayHeight / after.height,
            (maxX + 1) * displayWidth / after.width,
            (maxY + 1) * displayHeight / after.height,
        )
        return VisualChange(changed.toDouble() / total, bounds)
    }

    /** Width and height of a JPEG without decoding its pixels. */
    fun imageSize(jpeg: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /**
     * Full-width horizontal band of [jpeg] around [bounds] (image pixels), at least [minHeightFraction]
     * of the frame tall, so a small change (a one-line confirmation) is judged at a closer scale.
     */
    fun band(jpeg: ByteArray, bounds: PixelBounds, minHeightFraction: Double = 0.2): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        try {
            val want = maxOf((bounds.bottom - bounds.top) * 3, (bitmap.height * minHeightFraction).toInt())
                .coerceAtMost(bitmap.height)
            val top = ((bounds.top + bounds.bottom) / 2 - want / 2).coerceIn(0, bitmap.height - want)
            val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, want)
            try {
                return ByteArrayOutputStream().use { out ->
                    crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
