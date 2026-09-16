package bot.coati.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object GatewayFactory {

    /** Shared transport settings for runtime gateways and debug-only protocol validation. */
    fun createRuntimeClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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
