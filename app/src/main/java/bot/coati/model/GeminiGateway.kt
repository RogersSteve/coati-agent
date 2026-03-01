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

class GeminiGateway(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val modelName: String = DEFAULT_MODEL,
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
                .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            Log.d(TAG, "Response status: ${response.code}, body length: ${body.length}")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code} - $body")
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val text = extractResponseText(body)
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
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "Say hello in one short sentence.")
                    }))
                }))
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val text = extractResponseText(body)
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

        val contents = JSONArray()

        // System instruction
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().apply {
                put("text", GatewayUtils.SYSTEM_PROMPT)
            }))
        })
        contents.put(JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().put(JSONObject().apply {
                put("text", "Understood. I will analyze screens and return actions in JSON format.")
            }))
        })

        // Conversation history
        for (msg in conversationHistory) {
            contents.put(JSONObject().apply {
                put("role", if (msg.role == "assistant") "model" else "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", msg.content)
                }))
            })
        }

        // Current request with screenshot
        val userParts = JSONArray()
        userParts.put(JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", base64Image)
            })
        })
        userParts.put(JSONObject().apply {
            put("text", GatewayUtils.buildUserPrompt(uiTreeHint, taskDescription))
        })

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", userParts)
        })

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 1024)
            })
        }.toString()
    }

    private fun extractResponseText(body: String): String {
        return JSONObject(body)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    companion object {
        private const val TAG = "CoatiModel"
        private const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com"
        private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
