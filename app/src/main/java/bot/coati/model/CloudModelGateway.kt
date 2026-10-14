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
        manualObservationInstruction: String? = null,
    ): Result<QuickCheckResult>

    /**
     * [quickCheck] over several screenshots, oldest first; the last one is the current screen.
     * Gateways without multi-image support check the current screen only.
     */
    suspend fun quickCheckScreens(screenshots: List<ByteArray>, question: String): Result<QuickCheckResult> =
        quickCheck(screenshots.last(), question)

    suspend fun verifyAction(
        beforeScreenshot: ByteArray,
        afterScreenshot: ByteArray,
        actionDescription: String,
        expectedOutcome: String,
        manualObservationInstruction: String? = null,
    ): Result<VerifyResult>

    suspend fun summarizeText(prompt: String): Result<String>

    suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
        return Result.failure(UnsupportedOperationException("Structured text generation is not supported"))
    }

    suspend fun testConnection(): Result<String>
}
