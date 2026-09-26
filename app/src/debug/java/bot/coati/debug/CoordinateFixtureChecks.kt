package bot.coati.debug

import bot.coati.coordinates.PixelBounds

/** Only validates fixture touch observations. Coordinate conversion lives in main source. */
data class FixtureTouch(val kind: Int, val x: Float, val y: Float, val time: Long)
object CoordinateFixtureChecks {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    fun contains(b: PixelBounds, x: Float, y: Float) = x >= b.left && x < b.right && y >= b.top && y < b.bottom
    fun hit(type: String, events: List<FixtureTouch>, start: PixelBounds, end: PixelBounds = start): Boolean {
        if (events.size < 2 || events.first().kind != DOWN || events.last().kind != UP ||
            events.count { it.kind == DOWN } != 1 || events.count { it.kind == UP } != 1 ||
            events.drop(1).dropLast(1).any { it.kind != MOVE }) return false
        if (!contains(start, events.first().x, events.first().y) || !contains(end, events.last().x, events.last().y)) return false
        val duration = events.last().time - events.first().time
        return when (type) {
            "tap" -> duration in 0..500 && events.all { contains(start, it.x, it.y) }
            "longpress" -> duration >= 800 && events.all { contains(start, it.x, it.y) }
            "swipe" -> duration >= 200 && events.any { it.kind == MOVE }
            else -> false
        }
    }
}

class CoordinateFixtureSession {
    var phase = "new"; private set
    var dispatches = 0; private set
    private var next = 0
    fun start() { check(phase == "new") { "run_already_started" }; phase = "running" }
    fun claim(action: String) {
        check(phase == "running" && next < 3 && action == listOf("tap", "longpress", "swipe")[next]) { "unexpected_action" }
        next++; dispatches++
    }
    fun arm() { check(phase == "running" && dispatches == 3); phase = "stale_armed" }
    fun validateOnce() { check(phase == "stale_armed") { "stale_not_armed" }; phase = "validating" }
    fun finish() { check(phase == "validating"); phase = "passed" }
    fun fail() { phase = "failed" }
}
