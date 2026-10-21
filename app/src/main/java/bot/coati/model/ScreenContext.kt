package bot.coati.model

import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.ScreenshotCoordinates
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.EditorInputProfile
import bot.coati.service.UiTreeNode
import java.io.ByteArrayOutputStream

data class ScreenContext(
    val screenshotJpeg: ByteArray,
    val uiTreeJson: String,
    val appPackage: String,
    val coordinates: ScreenshotCoordinates? = null,
    /** The tree behind [uiTreeJson], read while Coati's overlay did not cover the app. */
    val uiTree: UiTreeNode? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenContext) return false
        return screenshotJpeg.contentEquals(other.screenshotJpeg) &&
            uiTreeJson == other.uiTreeJson && appPackage == other.appPackage && coordinates == other.coordinates
    }

    override fun hashCode(): Int {
        var result = screenshotJpeg.contentHashCode()
        result = 31 * result + uiTreeJson.hashCode()
        result = 31 * result + appPackage.hashCode()
        result = 31 * result + (coordinates?.hashCode() ?: 0)
        return result
    }

    companion object {
        private const val TAG = "CoatiScreen"
        private const val TARGET_WIDTH = 720

        suspend fun capture(a11y: CoatiAccessibilityService): ScreenContext? {
            val displayBefore = DisplaySpaceReader.read(a11y)
            // Take screenshot
            val bitmap = a11y.takeScreenshot()
            if (bitmap == null) {
                Log.w(TAG, "Failed to capture screenshot")
                return null
            }

            val displayAfter = DisplaySpaceReader.read(a11y)
            // Compress the complete display frame, without cropping or removing system bars.
            val scaled = scaleToWidth(bitmap, TARGET_WIDTH)
            val rawWidth = bitmap.width
            val rawHeight = bitmap.height
            val coordinates = ScreenshotCoordinates.bind(displayBefore, displayAfter,
                bitmap.width, bitmap.height, scaled.width, scaled.height)
            if (coordinates == null) Log.w(TAG, "Screenshot coordinate mapping unavailable; spatial actions will fail closed")
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            val screenshotBytes = stream.toByteArray()
            Log.i(TAG, "Screenshot: ${screenshotBytes.size} bytes")

            // Get UI tree
            val uiTree = a11y.getUiTree()
            // Bounds in the same 0..1000 space as the requested x/y: with pixel bounds the model
            // copied pixel values (y=1890 on a 1920 px screen) and every such answer was rejected.
            // Without a mapping the raw frame size still defines the 0..1000 space of this image; pixel
            // bounds here made the model answer in pixels (y=1343), which the next step rejected.
            val toNormalized = coordinates?.let { it::toNormalized }
                ?: ScreenshotCoordinates.frameNormalizer(rawWidth, rawHeight)
            val uiTreeStr = uiTree?.toSimpleString(toNormalized = toNormalized) ?: ""
            Log.i(TAG, "UI tree: ${uiTree?.flatCount() ?: 0} nodes")

            // Get current app package and input focus state
            val rootNode = a11y.rootInActiveWindow
            val appPackage = rootNode?.packageName?.toString() ?: "unknown"
            val focusedInput = rootNode?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusHint = if (focusedInput != null) {
                val cls = focusedInput.className?.toString()?.substringAfterLast('.') ?: "View"
                val label = focusedInput.hintText?.toString()?.takeIf { it.isNotBlank() }
                    ?: focusedInput.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: focusedInput.viewIdResourceName?.substringAfterLast('/')
                    ?: "field"
                val nodeId = focusedInput.viewIdResourceName?.substringAfterLast('/') ?: "unknown"
                val supportsSetText = focusedInput.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                }
                val textLength = focusedInput.text?.length ?: 0
                val selection = "${focusedInput.textSelectionStart}..${focusedInput.textSelectionEnd}"
                val editorProfile = EditorInputProfile(
                    isEditable = focusedInput.isEditable,
                    isMultiLine = focusedInput.isMultiLine,
                    className = focusedInput.className?.toString(),
                    hasWebViewAncestor = hasWebViewAncestor(focusedInput),
                    supportsSetText = supportsSetText,
                    hasDirectInput = a11y.hasActiveInputConnection(),
                )
                val inputSelection = a11y.currentInputSelectionSummary()
                val focusMetadata = "$cls id=$nodeId editable=${focusedInput.isEditable} " +
                    "multiline=${focusedInput.isMultiLine} showingHint=${focusedInput.isShowingHintText} " +
                    "directInput=${a11y.hasActiveInputConnection()} setText=$supportsSetText " +
                    "textLength=$textLength nodeSelection=$selection inputSelection=$inputSelection " +
                    "editorKind=${editorProfile.kind}"
                Log.i(TAG, "Input focus metadata: $focusMetadata")
                "[Input focus: $cls \"$label\" is focused; id=$nodeId; " +
                    "editable=${focusedInput.isEditable}; multiline=${focusedInput.isMultiLine}; " +
                    "showingHint=${focusedInput.isShowingHintText}; " +
                    "directInput=${a11y.hasActiveInputConnection()}; setText=$supportsSetText; " +
                    "textLength=$textLength; nodeSelection=$selection; inputSelection=$inputSelection; " +
                    "editorKind=${editorProfile.kind}]\n"
            } else {
                "[Input focus: none]\n"
            }
            focusedInput?.recycle()
            rootNode?.recycle()

            return ScreenContext(
                screenshotJpeg = screenshotBytes,
                uiTreeJson = focusHint + uiTreeStr,
                appPackage = appPackage,
                coordinates = coordinates,
                uiTree = uiTree,
            )
        }

        private fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
            if (bitmap.width <= targetWidth) return bitmap
            val ratio = targetWidth.toFloat() / bitmap.width
            val targetHeight = (bitmap.height * ratio).toInt()
            return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }

        private fun hasWebViewAncestor(node: AccessibilityNodeInfo): Boolean {
            var ancestor = node.parent
            while (ancestor != null) {
                val isWebView = ancestor.className?.toString() == "android.webkit.WebView"
                val next = if (isWebView) null else ancestor.parent
                ancestor.recycle()
                if (isWebView) return true
                ancestor = next
            }
            return false
        }
    }
}
