package bot.coati.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** End-to-end request budget, also shared by agent decision and verification stages. */
const val MODEL_REQUEST_TIMEOUT_MS = 300_000L

/**
 * A model call with no response after this long gets one hedged duplicate. Reasoning and
 * verification calls finish in about 5-12 s; on a phone network a request occasionally stalled for
 * 70-140 s, which alone could exceed a task's time budget.
 */
const val MODEL_HEDGE_AFTER_MS = 20_000L

object GatewayFactory {

    /** Shared transport settings for runtime gateways and debug-only protocol validation. */
    fun createRuntimeClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(MODEL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(MODEL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(MODEL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(MODEL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
