package bot.coati.model

import okhttp3.OkHttpClient

object GatewayFactory {

    fun create(
        provider: ModelProvider,
        client: OkHttpClient,
        apiKey: String,
        endpoint: String,
        modelName: String,
    ): CloudModelGateway {
        return when (provider.apiFormat) {
            ApiFormat.GEMINI -> GeminiGateway(client, apiKey, endpoint, modelName)
            ApiFormat.OPENAI_COMPATIBLE -> OpenAiCompatibleGateway(client, apiKey, endpoint, modelName)
            ApiFormat.CLAUDE_COMPATIBLE -> ClaudeCompatibleGateway(client, apiKey, endpoint, modelName)
        }
    }
}
