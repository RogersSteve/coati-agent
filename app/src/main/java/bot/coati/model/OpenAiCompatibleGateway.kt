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

class OpenAiCompatibleGateway(
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
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
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
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

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
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Say hello in one short sentence.")
                }))
                put("temperature", 0.2)
                put("max_tokens", 64)
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val text = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

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

        // System message
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", GatewayUtils.SYSTEM_PROMPT)
        })

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
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
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
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 1024)
        }.toString()
    }

    companion object {
        private const val TAG = "CoatiModel"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
