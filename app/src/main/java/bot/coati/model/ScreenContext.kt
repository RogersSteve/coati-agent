package bot.coati.model

import android.graphics.Bitmap
import android.util.Log
import bot.coati.service.CoatiAccessibilityService
import java.io.ByteArrayOutputStream

data class ScreenContext(
    val screenshotJpeg: ByteArray,
    val uiTreeJson: String,
    val appPackage: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenContext) return false
        return screenshotJpeg.contentEquals(other.screenshotJpeg) &&
            uiTreeJson == other.uiTreeJson && appPackage == other.appPackage
    }

    override fun hashCode(): Int {
        var result = screenshotJpeg.contentHashCode()
        result = 31 * result + uiTreeJson.hashCode()
        result = 31 * result + appPackage.hashCode()
        return result
    }

    companion object {
        private const val TAG = "CoatiScreen"
        private const val TARGET_WIDTH = 720

        suspend fun capture(a11y: CoatiAccessibilityService): ScreenContext? {
            // Take screenshot
            val bitmap = a11y.takeScreenshot()
            if (bitmap == null) {
                Log.w(TAG, "Failed to capture screenshot")
                return null
            }

            // Compress to 720p JPEG
            val scaled = scaleToWidth(bitmap, TARGET_WIDTH)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            val screenshotBytes = stream.toByteArray()
            Log.i(TAG, "Screenshot: ${screenshotBytes.size} bytes")

            // Get UI tree
            val uiTree = a11y.getUiTree()
            val uiTreeStr = uiTree?.toSimpleString() ?: ""
            Log.i(TAG, "UI tree: ${uiTree?.flatCount() ?: 0} nodes")

            // Get current app package
            val rootNode = a11y.rootInActiveWindow
            val appPackage = rootNode?.packageName?.toString() ?: "unknown"
            rootNode?.recycle()

            return ScreenContext(
                screenshotJpeg = screenshotBytes,
                uiTreeJson = uiTreeStr,
                appPackage = appPackage,
            )
        }

        private fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
            if (bitmap.width <= targetWidth) return bitmap
            val ratio = targetWidth.toFloat() / bitmap.width
            val targetHeight = (bitmap.height * ratio).toInt()
            return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }
}
