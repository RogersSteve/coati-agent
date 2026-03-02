package bot.coati.agent

enum class FailureAction { RETRY, REPLAN, ABORT }

class ExceptionHandler(
    private val maxConsecutiveFailures: Int = 3,
    private val replanThreshold: Int = 2,
) {
    var consecutiveFailures = 0
        private set

    fun recordFailure(): FailureAction {
        consecutiveFailures++
        return when {
            consecutiveFailures >= maxConsecutiveFailures -> FailureAction.ABORT
            consecutiveFailures >= replanThreshold -> FailureAction.REPLAN
            else -> FailureAction.RETRY
        }
    }

    fun recordSuccess() {
        consecutiveFailures = 0
    }

    fun reset() {
        consecutiveFailures = 0
    }

    fun buildReplanHint(): String {
        return "Previous approach failed $consecutiveFailures times. Reconsider your strategy — try a different element, navigation path, or approach."
    }
}
