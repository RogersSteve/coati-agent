package bot.coati.model

interface CloudModelGateway {
    suspend fun analyzeScreen(
        screenshot: ByteArray,
        uiTreeHint: String,
        taskDescription: String,
        conversationHistory: List<Message>,
    ): Result<ModelResponse>

    suspend fun quickCheck(
        screenshot: ByteArray,
        question: String,
    ): Result<QuickCheckResult>

    suspend fun testConnection(): Result<String>
}
