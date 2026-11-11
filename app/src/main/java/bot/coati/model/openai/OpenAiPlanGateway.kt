package bot.coati.model.openai

import android.util.Log
import bot.coati.agent.PromptBuilder
import bot.coati.model.ApiHttpException
import bot.coati.model.CloudModelGateway
import bot.coati.model.EmptyModelResponseException
import bot.coati.model.Message
import bot.coati.model.ModelResponse
import bot.coati.model.QuickCheckResult
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextRequest
import bot.coati.model.StructuredTextResult
import bot.coati.model.VerifyResult
import bot.coati.model.GatewayUtils
import bot.coati.model.awaitResponseBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Calls use the public Responses API with a Sign in with ChatGPT OAuth token.
 * Requests are not hedged: a duplicate would spend the subscription quota twice.
 * `store` is false so the request is not written into ChatGPT history.
 */
class OpenAiPlanGateway(
    private val client: OkHttpClient,
    private val credential: OpenAiPlanCredential,
    private val modelName: String,
    responsesUrl: String = OpenAiPlanProtocol.responsesUrl(OpenAiPlanProtocol.API_ROOT),
) : CloudModelGateway {
    private val responsesUrl = requireResponsesUrl(responsesUrl)

    override suspend fun analyzeScreen(
        screenshot: ByteArray,
        uiTreeHint: String,
        taskDescription: String,
        conversationHistory: List<Message>,
    ): Result<ModelResponse> = runText(
        instructions = instructions(PromptBuilder.buildReasoningSystemPrompt(), conversationHistory),
        input = historyInput(conversationHistory).put(userItem(listOf(screenshot), PromptBuilder.buildReasoningUserPrompt(uiTreeHint, taskDescription, 0, 0))),
    ).mapCatching { GatewayUtils.parseModelResponse(it.text) }

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
    ): Result<QuickCheckResult> = runText(
        instructions = PromptBuilder.buildFastSystemPrompt(manualObservationInstruction != null),
        input = JSONArray().put(userItem(screenshots, PromptBuilder.buildQuickCheckPrompt(question, manualObservationInstruction))),
    ).mapCatching { GatewayUtils.parseQuickCheckResponse(it.text) }

    override suspend fun verifyAction(
        beforeScreenshot: ByteArray,
        afterScreenshot: ByteArray,
        actionDescription: String,
        expectedOutcome: String,
        manualObservationInstruction: String?,
    ): Result<VerifyResult> = runText(
        instructions = PromptBuilder.buildVerifySystemPrompt(),
        input = JSONArray().put(
            userItem(
                listOf(beforeScreenshot, afterScreenshot),
                PromptBuilder.buildVerifyUserPrompt(actionDescription, expectedOutcome, manualObservationInstruction),
            ),
        ),
    ).mapCatching { GatewayUtils.parseVerifyResponse(it.text) }

    override suspend fun summarizeText(prompt: String): Result<String> = runText(
        instructions = "You summarize Android agent history. Return concise plain text only.",
        input = JSONArray().put(userItem(emptyList(), prompt)),
    ).map { it.text }

    override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
        val images = listOfNotNull(request.imageJpeg)
        return runText(
            instructions = request.systemPrompt,
            input = JSONArray().put(userItem(images, request.promptText)),
        ).map { output ->
            StructuredTextResult(
                text = output.text,
                finishReason = if (output.incomplete) StructuredTextFinishReason.TRUNCATED else StructuredTextFinishReason.COMPLETE,
            )
        }
    }

    override suspend fun testConnection(): Result<String> = runText(
        instructions = "Reply with a short greeting.",
        input = JSONArray().put(userItem(emptyList(), "Say hello in one short sentence.")),
    ).map { it.text }

    private suspend fun runText(instructions: String, input: JSONArray): Result<PlanModelOutput> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(complete(instructions, input))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "OpenAI plan request failed: ${error.javaClass.simpleName}")
                Result.failure(error)
            }
        }

    private suspend fun complete(instructions: String, input: JSONArray): PlanModelOutput {
        val authorization = credential.authorization()
        val accessToken = OpenAiPlanProtocol.validatedAccessToken(authorization.accessToken)
            ?: throw OpenAiPlanException("The OpenAI login is invalid. Sign in again.")
        val payload = OpenAiPlanProtocol.responsesBody(modelName, instructions, input)
        val request = Request.Builder()
            .url(responsesUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", OpenAiPlanProtocol.USER_AGENT)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val response = client.newCall(request).awaitResponseBody()
        Log.i(TAG, "OpenAI plan response status=${response.code} bodyChars=${response.body.length}")
        when (response.code) {
            401 -> throw OpenAiPlanException("The OpenAI login was rejected. Sign in again.")
            403 -> throw OpenAiPlanException("This OpenAI account cannot use that model. Choose another model.")
        }
        if (!response.isSuccessful) throw ApiHttpException(response.code)
        val output = OpenAiPlanProtocol.parseResponseStream(response.body)
        if (!output.terminal) throw OpenAiPlanException("The OpenAI response stream ended before completion.")
        if (output.failedDetail != null) throw OpenAiPlanException(output.failedDetail)
        if (output.text.isBlank()) throw EmptyModelResponseException("The model returned an empty response.")
        return output
    }

    companion object {
        private const val TAG = "CoatiPlan"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun requireResponsesUrl(raw: String): String {
            val url = raw.toHttpUrl()
            val localhost = url.host == "127.0.0.1" || url.host == "localhost"
            val allowedHost = url.host == "api.openai.com" || localhost
            val allowedPath = url.encodedPath == "/v1/responses" || (localhost && url.encodedPath == "/responses")
            val allowedScheme = url.scheme == "https" || (url.scheme == "http" && localhost)
            if (!allowedHost || !allowedPath || !allowedScheme) {
                throw IllegalArgumentException("Refusing OpenAI plan endpoint")
            }
            return url.toString()
        }

        private fun instructions(system: String, history: List<Message>): String {
            val extra = history.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()
            return if (extra.isBlank()) system else "$system\n\n$extra"
        }

        private fun historyInput(history: List<Message>): JSONArray {
            val input = JSONArray()
            history.filter { it.role != "system" }.forEach { message ->
                input.put(historyItem(message))
            }
            return input
        }

        private fun historyItem(message: Message): JSONObject {
            val role = if (message.role == "assistant") "assistant" else "user"
            val image = message.imageBytes
            if (image == null) return JSONObject().put("role", role).put("content", message.content)
            return userItem(role, listOf(image), message.content)
        }

        private fun userItem(images: List<ByteArray>, text: String): JSONObject =
            userItem("user", images, text)

        private fun userItem(role: String, images: List<ByteArray>, text: String): JSONObject {
            val parts = JSONArray()
            images.forEach { image ->
                parts.put(OpenAiPlanProtocol.imagePart(Base64.getEncoder().encodeToString(image)))
            }
            parts.put(OpenAiPlanProtocol.textPart(text))
            return JSONObject().put("role", role).put("content", parts)
        }
    }
}
