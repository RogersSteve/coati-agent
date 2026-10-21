package bot.coati.action

import bot.coati.coordinates.PixelBounds

/** Content browsing direction, opposite to finger travel. Amount is physical pixels, not dp. */
object ScrollProtocol {
    /**
     * Share of the scrollable container's length a scroll without an explicit amount travels. The
     * former fixed 500 px moved a 2400 px screen by about a fifth, so reading a list took many
     * scroll steps; three quarters keeps a little overlap between pages for orientation.
     */
    const val DEFAULT_CONTAINER_FRACTION = 0.75f

    /**
     * Share of the display used when no scrollable container is known: the full screen includes
     * toolbars and system bars, where a drag that starts does not scroll the content.
     */
    const val DEFAULT_SCREEN_FRACTION = 0.6f

    private const val EXPLICIT_DURATION_MS = 400L
    private const val LEGACY_DEFAULT_PX = 500L

    /** A scrollable node shorter than this along the scroll axis is not used as the container. */
    const val MIN_CONTAINER_PX = 200

    fun validate(direction: String, amount: Int?) {
        require(direction.uppercase() in setOf("UP", "DOWN", "LEFT", "RIGHT")) { "invalid_scroll_direction" }
        require(amount == null || amount > 0) { "invalid_scroll_amount" }
    }
    fun parse(params: Map<String, Any>): AgentAction.Scroll {
        val direction = params["direction"] as? String ?: throw IllegalArgumentException("invalid_scroll_direction")
        val amount = if (!params.containsKey("amount")) null else {
            val value = params["amount"]
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() == value.toInt().toDouble()) { "invalid_scroll_amount" }
            value.toInt()
        }
        return AgentAction.Scroll(direction, amount)
    }

    fun isVertical(direction: String) = direction.uppercase() in setOf("UP", "DOWN")

    /**
     * The swipe for [action] on a [width] x [height] display. An explicit amount is centred on the
     * display, as before. Without one the swipe travels [DEFAULT_CONTAINER_FRACTION] of [container]
     * (the scrollable view being browsed) through its centre, or [DEFAULT_SCREEN_FRACTION] of the
     * display when no usable container is given.
     */
    fun gesture(action: AgentAction.Scroll, width: Int, height: Int, container: PixelBounds? = null): AgentAction.Swipe {
        validate(action.direction, action.amount)
        require(width > 0 && height > 0) { "invalid_scroll_display" }
        val vertical = isVertical(action.direction)
        val axis = if (vertical) height else width
        val region = container?.let { clampToDisplay(it, width, height) }
            ?.takeIf { (if (vertical) it.bottom - it.top else it.right - it.left) >= MIN_CONTAINER_PX }
            ?.takeIf { action.amount == null }
        val (axisStart, axisEnd) = when {
            region == null -> 0 to axis
            vertical -> region.top to region.bottom
            else -> region.left to region.right
        }
        val span = axisEnd - axisStart
        val amount = action.amount ?: run {
            val fraction = if (region != null) DEFAULT_CONTAINER_FRACTION else DEFAULT_SCREEN_FRACTION
            (span * fraction).toInt().coerceIn(1, axis - 1)
        }
        require(amount < axis) { "scroll_amount_exceeds_display" }
        // Centre the complete segment, preserving odd distances and both in-bounds endpoints.
        val low = axisStart + (span - 1 - amount) / 2
        val high = low + amount
        // A longer default travel keeps the old default's finger speed, so its fling is no stronger.
        val durationMs = if (action.amount == null) maxOf(EXPLICIT_DURATION_MS, amount * EXPLICIT_DURATION_MS / LEGACY_DEFAULT_PX)
        else EXPLICIT_DURATION_MS
        val crossX = region?.let { (it.left + it.right) / 2 } ?: (width / 2)
        val crossY = region?.let { (it.top + it.bottom) / 2 } ?: (height / 2)
        return when (action.direction.uppercase()) {
            "DOWN" -> AgentAction.Swipe(crossX, high, crossX, low, durationMs)
            "UP" -> AgentAction.Swipe(crossX, low, crossX, high, durationMs)
            "RIGHT" -> AgentAction.Swipe(high, crossY, low, crossY, durationMs)
            else -> AgentAction.Swipe(low, crossY, high, crossY, durationMs)
        }
    }

    private fun clampToDisplay(bounds: PixelBounds, width: Int, height: Int): PixelBounds? {
        val clamped = PixelBounds(
            bounds.left.coerceIn(0, width), bounds.top.coerceIn(0, height),
            bounds.right.coerceIn(0, width), bounds.bottom.coerceIn(0, height),
        )
        return clamped.takeIf { it.right > it.left && it.bottom > it.top }
    }
}
