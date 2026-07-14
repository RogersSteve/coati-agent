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

    suspend fun verifyAction(
        beforeScreenshot: ByteArray,
        afterScreenshot: ByteArray,
        actionDescription: String,
        expectedOutcome: String,
    ): Result<VerifyResult>

    suspend fun summarizeText(prompt: String): Result<String>

    suspend fun testConnection(): Result<String>
}
