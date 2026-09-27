package bot.coati.action

/** Content browsing direction, opposite to finger travel. Amount is physical pixels, not dp. */
object ScrollProtocol {
    const val DEFAULT_AMOUNT = 500
    fun validate(direction: String, amount: Int) {
        require(direction.uppercase() in setOf("UP", "DOWN", "LEFT", "RIGHT")) { "invalid_scroll_direction" }
        require(amount > 0) { "invalid_scroll_amount" }
    }
    fun parse(params: Map<String, Any>): AgentAction.Scroll {
        val direction = params["direction"] as? String ?: throw IllegalArgumentException("invalid_scroll_direction")
        val amount = if (!params.containsKey("amount")) DEFAULT_AMOUNT else {
            val value = params["amount"]
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() == value.toInt().toDouble()) { "invalid_scroll_amount" }
            value.toInt()
        }
        return AgentAction.Scroll(direction, amount)
    }
    fun gesture(action: AgentAction.Scroll, width: Int, height: Int): AgentAction.Swipe {
        validate(action.direction, action.amount)
        require(width > 0 && height > 0) { "invalid_scroll_display" }
        val vertical = action.direction.uppercase() in setOf("UP", "DOWN")
        val axis = if (vertical) height else width
        require(action.amount < axis) { "scroll_amount_exceeds_display" }
        // Centre the complete segment, preserving odd distances and both in-bounds endpoints.
        val low = (axis - 1 - action.amount) / 2
        val high = low + action.amount
        return when (action.direction.uppercase()) {
            "DOWN" -> AgentAction.Swipe(width / 2, high, width / 2, low, 400)
            "UP" -> AgentAction.Swipe(width / 2, low, width / 2, high, 400)
            "RIGHT" -> AgentAction.Swipe(high, height / 2, low, height / 2, 400)
            else -> AgentAction.Swipe(low, height / 2, high, height / 2, 400)
        }
    }
}
