package bot.coati.data

/**
 * How much the fast executor (Jev) does on its own; every other step goes to the Reasoning model.
 * A choice is escalated when its confidence is below the minimum for its kind: goal-directed
 * choices and explorations of an unfamiliar screen. Faster modes run more steps on the fast path
 * (about 2 s instead of 10 s each on AndroidWorld) at the price of more wrong moves.
 */
enum class RoutingMode(val minGoalConfidence: Double, val minExploreConfidence: Double) {
    /** Fast path for every goal-directed choice; explorations below 0.4 escalate. */
    FAST(0.0, 0.4),

    /** Fast path for every goal-directed choice; explorations below 0.6 escalate. */
    BALANCED(0.0, 0.6),

    /** Fast path only for goal-directed choices at 0.8 or higher; every exploration escalates. */
    ACCURATE(0.8, 1.01),
}

/** The thresholds in effect, from a [RoutingMode] or set explicitly for an experiment. */
data class RoutingPolicy(val minGoalConfidence: Double, val minExploreConfidence: Double, val label: String) {
    /** Whether a non-final choice should go to the Reasoning model instead of the fast path. */
    fun escalates(explored: Boolean, confidence: Double): Boolean =
        confidence < if (explored) minExploreConfidence else minGoalConfidence

    companion object {
        val DEFAULT_MODE = RoutingMode.BALANCED

        fun of(mode: RoutingMode) = RoutingPolicy(mode.minGoalConfidence, mode.minExploreConfidence, mode.name)

        fun custom(minGoalConfidence: Double, minExploreConfidence: Double) =
            RoutingPolicy(minGoalConfidence, minExploreConfidence, "CUSTOM($minGoalConfidence,$minExploreConfidence)")
    }
}
