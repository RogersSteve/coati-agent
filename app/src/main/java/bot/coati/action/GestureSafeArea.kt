package bot.coati.action

import bot.coati.coordinates.PixelBounds

/**
 * Keeps injected swipes out of the system navigation gesture areas. A swipe that starts or runs
 * along the bottom gesture bar switches apps or goes home, and one that starts at a side edge goes
 * back: on a ColorOS phone, a horizontal scroll of a calendar strip at y=2734 of 2800 switched to the
 * previously used app twice in one task. The top edge stays reachable; pulling down the notification
 * shade or quick settings is a legitimate action.
 */
internal object GestureSafeArea {
    /** Swipes keep at least this far from the bottom edge even if the system reports a smaller area. */
    const val MIN_BOTTOM_DP = 48

    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    fun clamp(swipe: Swipe, width: Int, height: Int, gestureInsets: PixelBounds, density: Float): Swipe {
        val bottomInset = maxOf(gestureInsets.bottom, (MIN_BOTTOM_DP * density).toInt())
        val minX = gestureInsets.left
        val maxX = width - 1 - gestureInsets.right
        val maxY = height - 1 - bottomInset
        if (minX > maxX || maxY < 0) return swipe
        fun x(v: Int) = v.coerceIn(minX, maxX)
        fun y(v: Int) = minOf(v, maxY)
        return Swipe(x(swipe.x1), y(swipe.y1), x(swipe.x2), y(swipe.y2))
    }
}
