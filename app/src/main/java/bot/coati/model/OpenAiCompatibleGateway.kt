package bot.coati.model

import android.util.Base64
import android.util.Log
import bot.coati.agent.PromptBuilder
import bot.coati.model.openai.OpenAiPlanException
import bot.coati.model.openai.OpenAiPlanProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            val reply = complete(requestBody)
            val modelResponse = try {
                GatewayUtils.parseModelResponse(reply.text)
            } catch (e: MalformedModelResponseException) {
                Log.w(TAG, "Malformed response, finish_reason=${reply.finishReason}")
                throw e
            }
            Log.i(TAG, "Parsed response: action=${modelResponse.action}, safety=${modelResponse.safetyLevel}")
            Result.success(modelResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreen failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun quickCheck(
        screenshot: ByteArray,
        question: String,
        manualObservationInstruction: String?,
    ): Result<QuickCheckResult> = checkScreens(listOf(screenshot), question, manualObservationInstruction)

    override suspend fun quickCheckScreens(screenshots: List<ByteArray>, question: String): Result<QuickCheckResult> =
        checkScreens(screenshots, question, null)

    private suspend fun checkScreens(
        screenshots: List<ByteArray>,
        question: String,
        manualObservationInstruction: String?,
    ): Result<QuickCheckResult> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()

            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", PromptBuilder.buildFastSystemPrompt(manualObservationInstruction != null))
            })

            val userContent = JSONArray()
            for (screenshot in screenshots) {
                userContent.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${Base64.encodeToString(screenshot, Base64.NO_WRAP)}")
                    })
                })
            }
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
                put("max_tokens", withThinking(compactJsonMaxTokens(manualObservationInstruction != null)))
            }.toString()

            val reply = complete(requestBody)
            Result.success(GatewayUtils.parseQuickCheckResponse(reply.text))
        } catch (e: CancellationException) {
            throw e
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
                put("max_tokens", withThinking(compactJsonMaxTokens(manualObservationInstruction != null)))
            }.toString()

            val reply = complete(requestBody)
            Result.success(GatewayUtils.parseVerifyResponse(reply.text))
        } catch (e: CancellationException) {
            throw e
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
                put("max_tokens", withThinking(1024))
            }.toString()

            val reply = complete(requestBody)
            Result.success(reply.text.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "summarizeText failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
        return try {
            val userContent: Any = request.imageJpeg?.let { image ->
                JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put(
                            "url", "data:image/jpeg;base64,${Base64.encodeToString(image, Base64.NO_WRAP)}",
                        ))
                    })
                    .put(JSONObject().put("type", "text").put("text", request.promptText))
            } ?: request.promptText
            val messages = JSONArray()
                .put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemPrompt)
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            if (usesResponsesApi()) {
                val reply = complete(JSONObject().apply {
                    put("model", modelName)
                    put("messages", messages)
                }.toString())
                return Result.success(StructuredTextResult(
                    text = reply.text.trim(),
                    finishReason = if (reply.finishReason == "length") StructuredTextFinishReason.TRUNCATED else StructuredTextFinishReason.COMPLETE,
                ))
            }
            fun body(lowLatency: Boolean) = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", request.temperature)
                put("max_tokens", withThinking(request.maxOutputTokens))
                if (lowLatency) put("reasoning_effort", "low")
            }.toString()
            suspend fun send(lowLatency: Boolean) = client.newCall(
                Request.Builder()
                    .url("$endpoint/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body(lowLatency).toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).awaitModelResponseBody()
            var response = send(request.lowLatency)
            // Non-reasoning models may reject reasoning_effort; the hint is optional.
            if (request.lowLatency && response.code == 400) response = send(false)
            if (!response.isSuccessful) return Result.failure(ApiHttpException(response.code))
            Result.success(StructuredTextResponseParser.openAi(response.body))
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

            val reply = complete(requestBody)
            Log.i(TAG, "Connection test OK: ${reply.text}")
            Result.success(reply.text)
        } catch (e: CancellationException) {
            throw e
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
            put("max_tokens", withThinking(4096))
        }.toString()
    }

    private suspend fun complete(chatBody: String): ChatReply {
        val response = if (usesResponsesApi()) {
            val payload = OpenAiPlanProtocol.responsesBodyFromChat(JSONObject(chatBody))
            client.newCall(
                Request.Builder()
                    .url("$endpoint/v1/responses")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "text/event-stream")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).awaitModelResponseBody()
        } else {
            client.newCall(
                Request.Builder()
                    .url("$endpoint/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(chatBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).awaitModelResponseBody()
        }
        Log.d(TAG, "Response status: ${response.code}, body length: ${response.body.length}")
        if (!response.isSuccessful) {
            Log.e(TAG, "API error: ${response.code}, responseLength=${response.body.length}")
            throw ApiHttpException(response.code)
        }
        if (!usesResponsesApi()) {
            val choice = JSONObject(response.body).getJSONArray("choices").getJSONObject(0)
            return ChatReply(choice.getJSONObject("message").getString("content"), choice.optString("finish_reason"))
        }
        val output = OpenAiPlanProtocol.parseResponseStream(response.body)
        if (!output.terminal) throw OpenAiPlanException("The OpenAI response stream ended before completion.")
        if (output.failedDetail != null) throw OpenAiPlanException(output.failedDetail)
        if (output.text.isBlank()) throw EmptyModelResponseException("The model returned an empty response.")
        return ChatReply(output.text, if (output.incomplete) "length" else "stop")
    }

    private fun usesResponsesApi(): Boolean = usesResponsesApi(endpoint)

    private data class ChatReply(val text: String, val finishReason: String)

    companion object {
        private const val TAG = "CoatiModel"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Reasoning models behind this API (Gemini through OpenRouter, GPT-5) count thinking in
         * max_tokens: with 4096 a Reasoning turn's JSON was cut off (AndroidWorld, finish_reason
         * "length"). Headroom is smaller than Gemini's and capped, because some models served here
         * (deepseek-chat) reject max_tokens above 8192. Unused headroom costs nothing.
         */
        internal const val THINKING_HEADROOM_TOKENS = 4_096
        internal const val MAX_OUTPUT_TOKENS = 8_192

        internal fun withThinking(answerTokens: Int): Int =
            minOf(answerTokens + THINKING_HEADROOM_TOKENS, maxOf(answerTokens, MAX_OUTPUT_TOKENS))

        /** Keep third-party OpenAI-compatible endpoints on their established Chat Completions contract. */
        internal fun usesResponsesApi(endpoint: String): Boolean =
            endpoint.toHttpUrlOrNull()?.let { it.scheme == "https" && it.host == "api.openai.com" } == true
    }
}
