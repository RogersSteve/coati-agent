package bot.coati.action

import bot.coati.coordinates.PixelBounds
import kotlin.math.abs

internal enum class ScrollAxis { HORIZONTAL, VERTICAL }

/** Immutable identity, re-resolved against the same App window before any semantic action. */
internal data class ScrollCandidate(
    val path: List<Int>, val bounds: PixelBounds, val actions: Set<Int>, val axis: ScrollAxis?,
)

internal data class ScrollSession(
    val packageName: String, val windowId: Int, val viewport: PixelBounds,
    val candidate: ScrollCandidate?, val actionId: Int?,
)

internal data class ScrollEvidence(val signature: String, val pixels: IntArray, val width: Int, val height: Int) {
    fun visualChangeFrom(other: ScrollEvidence): Boolean? {
        if (width != other.width || height != other.height || pixels.size != other.pixels.size) return null
        var valid = 0
        var changed = 0
        for (i in pixels.indices) {
            val a = pixels[i]; val b = other.pixels[i]
            if (a ushr 24 == 0 || b ushr 24 == 0) continue // masked system/overlay region
            valid++
            val delta = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
                abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
            if (delta > 24) changed++
        }
        return if (valid < 128) null else changed.toDouble() / valid >= 0.01
    }
}

internal object SemanticScroll {
    const val FORWARD = 4096
    const val BACKWARD = 8192
    const val UP = 16908344
    const val LEFT = 16908345
    const val DOWN = 16908346
    const val RIGHT = 16908347

    fun axis(swipe: AgentAction.Swipe): ScrollAxis? {
        val x = abs(swipe.x2 - swipe.x1); val y = abs(swipe.y2 - swipe.y1)
        return when {
            x >= 48 && x > y * 1.5 -> ScrollAxis.HORIZONTAL
            y >= 48 && y > x * 1.5 -> ScrollAxis.VERTICAL
            else -> null
        }
    }

    /** Content browsing direction of [swipe] (opposite to finger travel), named as ScrollProtocol does. */
    fun contentDirection(swipe: AgentAction.Swipe): String? = when (axis(swipe)) {
        ScrollAxis.HORIZONTAL -> if (swipe.x2 < swipe.x1) "right" else "left"
        ScrollAxis.VERTICAL -> if (swipe.y2 < swipe.y1) "down" else "up"
        null -> null
    }

    fun directionalAxis(actions: Set<Int>): ScrollAxis? {
        val h = actions.any { it == LEFT || it == RIGHT }
        val v = actions.any { it == UP || it == DOWN }
        return when { h && !v -> ScrollAxis.HORIZONTAL; v && !h -> ScrollAxis.VERTICAL; else -> null }
    }

    /** Infer only clear rows/columns, not an axis from the viewport aspect ratio. */
    fun geometryAxis(children: List<PixelBounds>): ScrollAxis? {
        if (children.size < 2) return null
        fun overlap(a0: Int, a1: Int, b0: Int, b1: Int) =
            (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0).toDouble() / minOf(a1 - a0, b1 - b0).coerceAtLeast(1)
        var horizontal = 0; var vertical = 0
        for ((a, b) in children.zipWithNext()) {
            val ox = overlap(a.left, a.right, b.left, b.right)
            val oy = overlap(a.top, a.bottom, b.top, b.bottom)
            if (oy >= 0.6 && ox < 0.2) horizontal++
            if (ox >= 0.6 && oy < 0.2) vertical++
        }
        return when {
            horizontal > 0 && vertical == 0 -> ScrollAxis.HORIZONTAL
            vertical > 0 && horizontal == 0 -> ScrollAxis.VERTICAL
            else -> null
        }
    }

    fun contains(b: PixelBounds, x: Int, y: Int) = x >= b.left && x < b.right && y >= b.top && y < b.bottom

    fun select(candidates: List<ScrollCandidate>, swipe: AgentAction.Swipe): ScrollCandidate? {
        val axis = axis(swipe) ?: return null
        // Keep the innermost matching container even at its boundary. Do not scroll an ancestor
        // merely because the intended child no longer advertises the requested direction.
        return candidates.filter {
            it.axis == axis && contains(it.bounds, swipe.x1, swipe.y1)
        }.minWithOrNull(compareBy<ScrollCandidate> {
            (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top)
        }.thenByDescending { it.path.size })
    }

    fun action(candidate: ScrollCandidate, swipe: AgentAction.Swipe): Int? {
        val forward = when (axis(swipe)) {
            ScrollAxis.HORIZONTAL -> swipe.x2 < swipe.x1
            ScrollAxis.VERTICAL -> swipe.y2 < swipe.y1
            null -> return null
        }
        val directional = if (axis(swipe) == ScrollAxis.HORIZONTAL) { if (forward) RIGHT else LEFT }
            else { if (forward) DOWN else UP }
        return when {
            directional in candidate.actions -> directional
            // An explicit directional action family must not fall back to the opposite boundary.
            candidate.actions.any { it in setOf(UP, DOWN, LEFT, RIGHT) } -> null
            forward && FORWARD in candidate.actions -> FORWARD
            !forward && BACKWARD in candidate.actions -> BACKWARD
            else -> null
        }
    }
}
