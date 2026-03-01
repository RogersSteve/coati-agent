package bot.coati.model

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ClaudeCompatibleGateway(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String,
    private val modelName: String,
) : CloudModelGateway {

    override suspend fun analyzeScreen(
        screenshot: ByteArray,
        uiTreeHint: String,
        taskDescription: String,
        conversationHistory: List<Message>,
    ): Result<ModelResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(screenshot, uiTreeHint, taskDescription, conversationHistory)
            Log.d(TAG, "Request body size: ${requestBody.length} chars")

            val request = Request.Builder()
                .url("$endpoint/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            Log.d(TAG, "Response status: ${response.code}, body length: ${body.length}")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code} - $body")
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val text = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            val modelResponse = GatewayUtils.parseModelResponse(text)
            Log.i(TAG, "Parsed response: action=${modelResponse.action}, safety=${modelResponse.safetyLevel}")
            Result.success(modelResponse)
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreen failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("max_tokens", 64)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Say hello in one short sentence.")
                }))
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val text = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            Log.i(TAG, "Connection test OK: $text")
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun buildRequestBody(
        screenshot: ByteArray,
        uiTreeHint: String,
        taskDescription: String,
        conversationHistory: List<Message>,
    ): String {
        val base64Image = Base64.encodeToString(screenshot, Base64.NO_WRAP)
        val messages = JSONArray()

        // Conversation history
        for (msg in conversationHistory) {
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        // Current request with screenshot
        val userContent = JSONArray()
        userContent.put(JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", "image/jpeg")
                put("data", base64Image)
            })
        })
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", GatewayUtils.buildUserPrompt(uiTreeHint, taskDescription))
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        return JSONObject().apply {
            put("model", modelName)
            put("max_tokens", 1024)
            put("system", GatewayUtils.SYSTEM_PROMPT)
            put("messages", messages)
        }.toString()
    }

    companion object {
        private const val TAG = "CoatiModel"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
