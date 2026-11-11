package bot.coati.model

import bot.coati.data.ModelAuthMode
import bot.coati.model.openai.OpenAiPlanCredential
import bot.coati.model.openai.OpenAiPlanGateway
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
        authMode: ModelAuthMode = ModelAuthMode.API_KEY,
        planAuth: OpenAiPlanCredential? = null,
    ): CloudModelGateway {
        if (authMode == ModelAuthMode.OPENAI_PLAN) {
            return OpenAiPlanGateway(client, checkNotNull(planAuth) { "OpenAI plan login is missing" }, modelName)
        }
        return when (provider.apiFormat) {
            ApiFormat.GEMINI -> GeminiGateway(client, apiKey, endpoint, modelName)
            ApiFormat.OPENAI_COMPATIBLE -> OpenAiCompatibleGateway(client, apiKey, compatibleBaseUrl(endpoint), modelName)
            ApiFormat.CLAUDE_COMPATIBLE -> ClaudeCompatibleGateway(client, apiKey, compatibleBaseUrl(endpoint), modelName)
        }
    }
}

/** The gateways append /v1/...; providers document their URL with /v1 included, and pasting that gave /v1/v1. */
internal fun compatibleBaseUrl(endpoint: String): String = endpoint.trim().trimEnd('/').removeSuffix("/v1")
