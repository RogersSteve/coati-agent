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

            val response = client.newCall(request).awaitModelResponseBody()
            val body = response.body
            Log.d(TAG, "Response status: ${response.code}, body length: ${body.length}")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code}, responseLength=${body.length}")
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = extractResponseText(body)
            val modelResponse = GatewayUtils.parseModelResponse(text)
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
    ): Result<QuickCheckResult> = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(screenshot, Base64.NO_WRAP)
            val contents = JSONArray()

            // System instruction
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", PromptBuilder.buildFastSystemPrompt(manualObservationInstruction != null))
                }))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Understood. I will answer with JSON.")
                }))
            })

            // Screenshot + question
            val userParts = JSONArray()
            userParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Image)
                })
            })
            userParts.put(JSONObject().apply {
                put("text", PromptBuilder.buildQuickCheckPrompt(question, manualObservationInstruction))
            })
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            })

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", withThinking(compactJsonMaxTokens(manualObservationInstruction != null)))
                })
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).awaitModelResponseBody()
            val body = response.body

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = extractResponseText(body)
            Result.success(GatewayUtils.parseQuickCheckResponse(text))
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
            val contents = JSONArray()

            // System instruction
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", PromptBuilder.buildVerifySystemPrompt())
                }))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Understood. I will compare the screenshots and respond with JSON.")
                }))
            })

            // Two screenshots + prompt
            val userParts = JSONArray()
            userParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Before)
                })
            })
            userParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64After)
                })
            })
            userParts.put(JSONObject().apply {
                put("text", PromptBuilder.buildVerifyUserPrompt(
                    actionDescription,
                    expectedOutcome,
                    manualObservationInstruction,
                ))
            })
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            })

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", withThinking(compactJsonMaxTokens(manualObservationInstruction != null)))
                })
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).awaitModelResponseBody()
            val body = response.body

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = extractResponseText(body)
            Result.success(GatewayUtils.parseVerifyResponse(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "verifyAction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun summarizeText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val contents = JSONArray()

            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "You summarize Android agent history. Return concise plain text only.")
                }))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Understood. I will return a concise plain-text summary.")
                }))
            })
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            })

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", withThinking(1024))
                })
            }.toString()

            val request = Request.Builder()
                .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).awaitModelResponseBody()
            val body = response.body

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            Result.success(extractResponseText(body).trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "summarizeText failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
        return try {
            val contents = JSONArray()
                .put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply { put("text", request.systemPrompt) }))
                })
                .put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "Understood. I will return exactly one JSON object matching the requested schema.")
                    }))
                })
                .put(JSONObject().apply {
                    put("role", "user")
                    val parts = JSONArray()
                    request.imageJpeg?.let { image ->
                        parts.put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
                    }
                    put("parts", parts.put(JSONObject().apply { put("text", request.promptText) }))
                })
            fun body(lowLatency: Boolean) = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", withThinking(request.maxOutputTokens))
                    if (lowLatency) put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                })
            }.toString()
            suspend fun send(lowLatency: Boolean) = client.newCall(
                Request.Builder()
                    .url("$endpoint/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .post(body(lowLatency).toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).awaitModelResponseBody()
            var response = send(request.lowLatency)
            // Models without thinking levels reject the hint; it is optional.
            if (request.lowLatency && response.code == 400) response = send(false)
            if (!response.isSuccessful) return Result.failure(ApiHttpException(response.code))
            Result.success(StructuredTextResponseParser.gemini(response.body))
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

            val response = client.newCall(request).awaitModelResponseBody()
            val body = response.body

            if (!response.isSuccessful) {
                return@withContext Result.failure(ApiHttpException(response.code))
            }

            val text = extractResponseText(body)
            Log.i(TAG, "Connection test OK: $text")
            Result.success(text)
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

        val contents = JSONArray()

        // System instruction
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().apply {
                put("text", PromptBuilder.buildReasoningSystemPrompt())
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
            put("text", PromptBuilder.buildReasoningUserPrompt(uiTreeHint, taskDescription, 0, 0))
        })

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", userParts)
        })

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", withThinking(4096))
            })
        }.toString()
    }

    private fun extractResponseText(body: String): String {
        val candidate = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
        candidate.optString("finishReason").takeIf { it.isNotEmpty() && it != "STOP" }?.let {
            Log.w(TAG, "Response ended with finishReason=$it; the answer may be cut off")
        }
        return extractAnswerText(candidate)
    }

    companion object {
        private const val TAG = "CoatiModel"
        private const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com"
        private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Gemini counts thinking tokens in maxOutputTokens: a Reasoning turn that thought for ~3.5k
         * tokens had ~500 characters of its 4096-token budget left, and its JSON was cut off (board:
         * "Unterminated object at character 493", three turns in a row). The cap only bounds output;
         * unused headroom costs nothing.
         */
        internal const val THINKING_HEADROOM_TOKENS = 12_288

        internal fun withThinking(answerTokens: Int): Int = answerTokens + THINKING_HEADROOM_TOKENS

        /** The answer is every non-thought text part in order, not just the first part. */
        internal fun extractAnswerText(candidate: JSONObject): String {
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            return buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (!part.optBoolean("thought", false)) append(part.optString("text", ""))
                }
            }
        }
    }
}
