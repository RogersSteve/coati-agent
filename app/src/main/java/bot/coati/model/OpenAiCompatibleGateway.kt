package bot.coati.model

import android.util.Base64
import android.util.Log
import bot.coati.agent.PromptBuilder
import kotlinx.coroutines.CancellationException
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
                Log.e(TAG, "API error: ${response.code}, responseLength=${body.length}")
                return@withContext Result.failure(ApiHttpException(response.code))
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

    override suspend fun quickCheck(
        screenshot: ByteArray,
        question: String,
        manualObservationInstruction: String?,
    ): Result<QuickCheckResult> = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(screenshot, Base64.NO_WRAP)
            val messages = JSONArray()

            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", PromptBuilder.buildFastSystemPrompt(manualObservationInstruction != null))
            })

            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                })
            })
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", PromptBuilder.buildQuickCheckPrompt(question, manualObservationInstruction))
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", if (manualObservationInstruction == null) 128 else 512)
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(GatewayUtils.parseQuickCheckResponse(text))
        } catch (e: Exception) {
            Log.e(TAG, "quickCheck failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun verifyAction(
        beforeScreenshot: ByteArray,
        afterScreenshot: ByteArray,
        actionDescription: String,
        expectedOutcome: String,
        manualObservationInstruction: String?,
    ): Result<VerifyResult> = withContext(Dispatchers.IO) {
        try {
            val base64Before = Base64.encodeToString(beforeScreenshot, Base64.NO_WRAP)
            val base64After = Base64.encodeToString(afterScreenshot, Base64.NO_WRAP)
            val messages = JSONArray()

            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", PromptBuilder.buildVerifySystemPrompt())
            })

            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Before")
                })
            })
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64After")
                })
            })
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", PromptBuilder.buildVerifyUserPrompt(
                    actionDescription,
                    expectedOutcome,
                    manualObservationInstruction,
                ))
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", if (manualObservationInstruction == null) 256 else 512)
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(GatewayUtils.parseVerifyResponse(text))
        } catch (e: Exception) {
            Log.e(TAG, "verifyAction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun summarizeText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You summarize Android agent history. Return concise plain text only.")
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.2)
                put("max_tokens", 1024)
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "summarizeText failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
        return try {
            val messages = JSONArray()
                .put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemPrompt)
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    put("content", request.userPrompt)
                })
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", request.temperature)
                put("max_tokens", request.maxOutputTokens)
            }.toString()
            val httpRequest = Request.Builder()
                .url("$endpoint/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(httpRequest).awaitCancellable().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return Result.failure(ApiHttpException(response.code))
                Result.success(StructuredTextResponseParser.openAi(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateStructuredText failed: ${e.javaClass.simpleName}")
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
                return@withContext Result.failure(ApiHttpException(response.code))
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
            put("content", PromptBuilder.buildReasoningSystemPrompt())
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
            put("text", PromptBuilder.buildReasoningUserPrompt(uiTreeHint, taskDescription, 0, 0))
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        return JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 4096)
        }.toString()
    }

    companion object {
        private const val TAG = "CoatiModel"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
