package bot.coati.agent

internal class CapabilityFailureLedger(
    private val replanThreshold: Int = 2,
    private val abortThreshold: Int = 3,
) {
    private val counts = mutableMapOf<String, Int>()
    private val constraints = linkedMapOf<String, String>()
    private var activeScope: String? = null

    fun activateScope(scope: String) {
        if (scope == activeScope) return
        counts.clear()
        constraints.clear()
        activeScope = scope
    }

    fun recordFailure(key: String, constraint: String): CapabilityFailureDecision {
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        if (count >= replanThreshold) {
            constraints[key] = constraint
        }
        return when {
            count >= abortThreshold -> CapabilityFailureDecision.ABORT
            count >= replanThreshold -> CapabilityFailureDecision.REPLAN
            else -> CapabilityFailureDecision.RETRY
        }
    }

    fun persistentConstraints(): List<String> = constraints.values.toList()

    fun count(key: String): Int = counts[key] ?: 0

    fun reset() {
        counts.clear()
        constraints.clear()
        activeScope = null
    }
}

internal enum class CapabilityFailureDecision {
    RETRY,
    REPLAN,
    ABORT,
}
