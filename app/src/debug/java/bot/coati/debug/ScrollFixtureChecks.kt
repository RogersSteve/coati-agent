package bot.coati.debug

import bot.coati.action.AgentAction

object ScrollFixtureChecks {
    val directions = listOf("DOWN", "UP", "RIGHT", "LEFT")
    fun movement(direction: String, before: Int, after: Int, firstBefore: Int, firstAfter: Int): Boolean =
        when(direction) {
            "DOWN", "RIGHT" -> after > before && firstAfter > firstBefore
            "UP", "LEFT" -> after < before && firstAfter < firstBefore
            else -> false
        }
    fun endpoints(touches: List<FixtureTouch>, gesture: AgentAction.Swipe): Boolean =
        touches.size >= 2 && touches.first().kind == 0 && touches.last().kind == 1 &&
            touches.count { it.kind == 0 } == 1 && touches.count { it.kind == 1 } == 1 &&
            touches.drop(1).dropLast(1).all { it.kind == 2 } && touches.any { it.kind == 2 } &&
            kotlin.math.abs(touches.first().x - gesture.x1) <= 1f &&
            kotlin.math.abs(touches.first().y - gesture.y1) <= 1f &&
            kotlin.math.abs(touches.last().x - gesture.x2) <= 1f &&
            kotlin.math.abs(touches.last().y - gesture.y2) <= 1f
}

class ScrollFixtureSession {
    var phase = "new"; private set
    var dispatches = 0; private set
    fun start() { check(phase == "new"); phase = "running" }
    fun claim(direction: String) {
        check(phase == "running" && dispatches < 4 && ScrollFixtureChecks.directions[dispatches] == direction)
        dispatches++
    }
    fun arm() { check(phase == "running" && dispatches == 4); phase = "stale_armed" }
    fun validate() { check(phase == "stale_armed"); phase = "validating" }
    fun finish() { check(phase == "validating"); phase = "passed" }
    fun fail() { phase = "failed" }
}
