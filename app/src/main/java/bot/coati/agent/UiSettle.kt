package bot.coati.agent

import kotlinx.coroutines.delay

/**
 * Waits for the app screen to stop changing before the post-action screenshot. A fixed random
 * 300-1500 ms delay captured screens mid-transition (a half-drawn list, a fading dialog) that verify
 * judged FAILED or UNKNOWN, and waited needlessly after actions that settle at once.
 */
internal object UiSettle {
    const val MIN_WAIT_MS = 250L
    const val POLL_MS = 250L
    const val CAP_MS = 2_500L

    /**
     * Polls [snapshot] (what the app windows show) until two consecutive readings match, at most
     * [capMs] after the call. Returns true when the screen settled, false when the cap was reached.
     */
    suspend fun await(
        snapshot: () -> String,
        now: () -> Long,
        minWaitMs: Long = MIN_WAIT_MS,
        pollMs: Long = POLL_MS,
        capMs: Long = CAP_MS,
    ): Boolean {
        val deadline = now() + capMs
        delay(minWaitMs)
        var previous = snapshot()
        while (now() + pollMs <= deadline) {
            delay(pollMs)
            val current = snapshot()
            if (current == previous) return true
            previous = current
        }
        return false
    }

    private val LOADING_WORDS = Regex(
        "\\b(blank|black screen|white screen|splash|loading|spinner|progress indicator|still launching|still opening|not yet rendered|not rendered)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a verify reason says the after-screen was caught blank, on a splash screen or loading. */
    fun describesLoading(reason: String?, observation: String? = null): Boolean =
        listOfNotNull(reason, observation).any { LOADING_WORDS.containsMatchIn(it) }
}
