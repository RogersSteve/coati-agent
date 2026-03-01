package bot.coati.model

data class ModelResponse(
    val reasoning: String,
    val action: String,
    val params: Map<String, Any>,
    val safetyLevel: String,
    val isTaskComplete: Boolean,
    val statusUpdate: String,
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
