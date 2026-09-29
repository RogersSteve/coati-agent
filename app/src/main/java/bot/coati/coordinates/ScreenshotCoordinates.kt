package bot.coati.coordinates

/** Screen-space pixels, never dp or app-content-local coordinates. */
data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class DisplaySpace(
    val width: Int, val height: Int, val rotation: Int, val displayId: Int,
    val windowBounds: PixelBounds, val appBounds: PixelBounds,
    val insets: PixelBounds, val densityDpi: Int, val windowId: Int,
    val packageName: String, val magnification: Float,
    val visibleInsets: PixelBounds = insets,
) {
    fun validate() {
        require(windowId >= 0 && packageName.isNotBlank()) { "unknown_active_window" }
        require(width > 0 && height > 0 && displayId == 0 && rotation in 0..3 && magnification == 1f) { "unsupported_display_space" }
        require(windowBounds == PixelBounds(0, 0, width, height)) { "unsupported_display_region" }
        // The screenshot always spans the whole display and gestures use display pixels, so the
        // active window may be partial (dialog, popup, bottom sheet, split screen) without changing
        // the mapping. It only has to be a real window on this display.
        require(appBounds.left < appBounds.right && appBounds.top < appBounds.bottom) { "invalid_app_bounds" }
        require(appBounds.left >= 0 && appBounds.top >= 0 && appBounds.right <= width && appBounds.bottom <= height) {
            "app_window_outside_display"
        }
    }
}

/** Bound to ONE captured bitmap and the exact full-frame resize used for its model request.
 * normalized -> encoded image -> raw bitmap -> display: the resize cancels independently on
 * each axis, and raw == display is validated. No crop, translation or bar compensation exists.
 */
data class ScreenshotCoordinates(
    val display: DisplaySpace, val rawWidth: Int, val rawHeight: Int,
    val imageWidth: Int, val imageHeight: Int,
) {
    init {
        display.validate()
        require(rawWidth == display.width && rawHeight == display.height) { "screenshot_display_mismatch" }
        require(imageWidth > 0 && imageHeight > 0 && imageWidth <= rawWidth && imageHeight <= rawHeight) { "invalid_image_size" }
        require(imageHeight == (rawHeight * (imageWidth.toFloat() / rawWidth)).toInt()) { "unsupported_image_transform" }
    }
    companion object {
        fun bind(before: DisplaySpace?, after: DisplaySpace?, rawWidth: Int, rawHeight: Int,
                 imageWidth: Int, imageHeight: Int): ScreenshotCoordinates? {
            if (before == null || before != after) return null
            return try { ScreenshotCoordinates(before, rawWidth, rawHeight, imageWidth, imageHeight) }
            catch (_: IllegalArgumentException) { null }
        }
    }
    fun requireCurrent(current: DisplaySpace?) {
        require(current == display) { "stale_screenshot_coordinates" }
        current.validate()
    }
    suspend fun <T> dispatch(current: () -> DisplaySpace?, action: suspend () -> T): T {
        requireCurrent(current())
        return action()
    }
    fun normalized(x: Int, y: Int): Pair<Int, Int> {
        require(x in 0..1000 && y in 0..1000) { "normalized_coordinate_out_of_range" }
        // 1000 denotes the last edge; dispatchGesture must remain inside the physical pixel extent.
        return minOf((x.toLong() * rawWidth / 1000).toInt(), rawWidth - 1) to
            minOf((y.toLong() * rawHeight / 1000).toInt(), rawHeight - 1)
    }
    fun absolute(x: Int, y: Int): Pair<Int, Int> {
        require(x in 0 until rawWidth && y in 0 until rawHeight) { "screen_coordinate_out_of_range" }
        return x to y
    }
}
