package bot.coati.coordinates

import bot.coati.action.AgentAction
import bot.coati.action.ScrollProtocol

/** Adapter for the existing PromptBuilder contract. AgentAction.fromMap remains a pixel API. */
object ModelCoordinateAction {
    fun isSpatial(type: String) = type.lowercase() in setOf("tap", "longpress", "long_press", "swipe", "focusandinputtext", "focus_and_input_text", "scroll")
    private fun integer(value: Any?): Int {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "invalid_coordinate_number" }
        return value.toInt()
    }
    fun target(type: String, params: Map<String, Any>, x: Int?, y: Int?): Pair<Int, Int>? {
        if (type.lowercase() !in setOf("tap", "longpress", "long_press", "focusandinputtext", "focus_and_input_text")) return null
        require((x == null) == (y == null)) { "incomplete_top_level_coordinates" }
        // Top-level is the established primary source. Never mix half-pairs from two sources.
        val point = if (x != null && y != null) x to y else {
            require(type.lowercase() in setOf("tap", "longpress", "long_press")) { "missing_top_level_coordinates" }
            integer(params["x"]) to integer(params["y"])
        }
        require(point.first in 0..1000 && point.second in 0..1000) { "normalized_coordinate_out_of_range" }
        return point
    }
    fun resolve(type: String, params: Map<String, Any>, x: Int?, y: Int?, space: ScreenshotCoordinates?,
                treeTarget: Pair<Int, Int>? = null): AgentAction {
        if (!isSpatial(type)) return AgentAction.fromMap(type, params)
        requireNotNull(space) { "missing_screenshot_coordinates" }
        val target = target(type, params, x, y)
        val point = if (target != null) {
            if (treeTarget != null && type.lowercase() in setOf("tap", "longpress", "long_press")) space.absolute(treeTarget.first, treeTarget.second)
            else space.normalized(target.first, target.second)
        } else null
        return when (type.lowercase()) {
            "tap" -> AgentAction.Tap(point!!.first, point.second)
            "longpress", "long_press" -> AgentAction.LongPress(point!!.first, point.second, (params["durationMs"] as? Number)?.toLong() ?: 1000)
            "focusandinputtext", "focus_and_input_text" -> AgentAction.FocusAndInputText(point!!.first, point.second, params["text"] as String)
            "swipe" -> {
                val start = space.normalized(integer(params["x1"]), integer(params["y1"]))
                val end = space.normalized(integer(params["x2"]), integer(params["y2"]))
                AgentAction.Swipe(start.first, start.second, end.first, end.second, (params["durationMs"] as? Number)?.toLong() ?: 400)
            }
            "scroll" -> {
                val action = ScrollProtocol.parse(params)
                scrollGesture(action, space) // validate now; preserve the semantic action for Verify/manual evidence
                action
            }
            else -> error("unsupported_spatial_action")
        }
    }
    fun scrollGesture(action: AgentAction.Scroll, space: ScreenshotCoordinates, container: PixelBounds? = null): AgentAction.Swipe {
        val swipe = ScrollProtocol.gesture(action, space.rawWidth, space.rawHeight, container)
        space.absolute(swipe.x1, swipe.y1); space.absolute(swipe.x2, swipe.y2)
        return swipe
    }
}
