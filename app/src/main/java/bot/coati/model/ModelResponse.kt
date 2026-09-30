package bot.coati.model

data class ModelResponse(
    val reasoning: String,
    val action: String,
    val params: Map<String, Any>,
    val safetyLevel: String,
    val isTaskComplete: Boolean,
    val statusUpdate: String,
    val x: Int? = null,
    val y: Int? = null,
    val expectedOutcome: String = "",
    val usedManualHeadings: List<String> = emptyList(),
    val manualObservation: ManualPageObservation? = null,
    /** The answer when the task asks for information; set on the completing response only. */
    val answer: String? = null,
)

/**
 * A compact observation of the currently visible App page. It is evidence, never an edit
 * command. The living-manual layer validates and sanitizes it again before persistence.
 */
data class ManualPageObservation(
    val status: String,
    val pageName: String? = null,
    val matchedHeadingPath: String? = null,
    val visualSignature: String? = null,
    val reusableFacts: List<String> = emptyList(),
    val navigationFacts: List<String> = emptyList(),
    val popupOrRecoveryFact: String? = null,
)

enum class VerifyOutcome { SUCCESS, FAILED, DRIFTED, UNEXPECTED, UNKNOWN }

data class VerifyResult(
    val outcome: VerifyOutcome,
    val reason: String,
    val observation: String? = null,
    val manualObservation: ManualPageObservation? = null,
)

data class QuickCheckResult(
    val answer: Boolean,
    val category: String? = null,
    val confidence: Float = 1.0f,
    val isValid: Boolean = true,
    val manualObservation: ManualPageObservation? = null,
    /** The completion audit's reading of the task's answer from the final screen, for information tasks. */
    val taskAnswer: String? = null,
)

data class Message(
    val role: String,
    val content: String,
    val imageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return role == other.role && content == other.content &&
            imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        return result
    }
}
