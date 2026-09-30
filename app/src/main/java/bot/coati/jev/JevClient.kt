package bot.coati.jev

import android.util.Log
import bot.coati.model.ApiHttpException
import bot.coati.model.HttpResponseBody
import bot.coati.model.awaitResponseBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One JEV Choice question: the model returns exactly one of [criteria]'s keys. */
data class JevChoiceQuestion(
    val instructions: String,
    val criteria: Map<String, String>,
)

data class JevChoiceAnswer(
    val choice: String,
    val confidence: Double,
    val probabilities: Map<String, Double>,
)

data class JevResponse(
    val model: String,
    val answers: Map<String, JevChoiceAnswer>,
    val inputTokens: Int,
)

/** Pure request/response mapping for the TypeSafe System One API, kept separate for unit tests. */
object JevProtocol {
    const val MAX_OPTIONS = 255

    fun request(model: String, state: JSONObject, questions: Map<String, JevChoiceQuestion>): JSONObject {
        require(questions.isNotEmpty()) { "jev_no_questions" }
        val qs = JSONObject()
        questions.forEach { (name, q) ->
            require(q.criteria.isNotEmpty() && q.criteria.size <= MAX_OPTIONS) { "jev_invalid_option_count:$name" }
            val criteria = JSONObject()
            q.criteria.forEach { (key, description) -> criteria.put(key, description) }
            qs.put(name, JSONObject().put("type", "choice").put("instructions", q.instructions).put("criteria", criteria))
        }
        return JSONObject().put("model", model).put("state", state).put("questions", qs)
    }

    /** Rejects answers that are not one of the offered options; such an answer must never become an action. */
    fun parse(body: String, questions: Map<String, JevChoiceQuestion>): JevResponse {
        val json = JSONObject(body)
        val answersJson = json.getJSONObject("answers")
        val answers = buildMap {
            questions.forEach { (name, q) ->
                val a = answersJson.optJSONObject(name) ?: return@forEach
                val choice = a.getString("choice")
                require(choice in q.criteria) { "jev_choice_not_offered:$name" }
                val probs = buildMap {
                    a.optJSONObject("probabilities")?.let { p -> p.keys().forEach { k -> put(k, p.optDouble(k, 0.0)) } }
                }
                put(name, JevChoiceAnswer(choice, a.optDouble("confidence", 0.0), probs))
            }
        }
        return JevResponse(
            model = json.optString("model", ""),
            answers = answers,
            inputTokens = json.optJSONObject("usage")?.optInt("input_tokens", 0) ?: 0,
        )
    }
}

class JevClient(
    baseClient: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL,
    private val hedgeAfterMs: Long = DEFAULT_HEDGE_AFTER_MS,
) {
    // JEV is a sub-second model; a long shared model budget would stall the executor loop.
    private val client = baseClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun choose(state: JSONObject, questions: Map<String, JevChoiceQuestion>): Result<JevResponse> =
        withContext(Dispatchers.IO) {
            val payload = JevProtocol.request(model, state, questions).toString()
            var lastError: Exception = IllegalStateException("jev_not_attempted")
            for (attempt in 0..MAX_RETRIES) {
                try {
                    val response = hedged(payload)
                    if (response.isSuccessful) {
                        return@withContext Result.success(JevProtocol.parse(response.body, questions))
                    }
                    lastError = ApiHttpException(response.code)
                    if (response.code !in RETRYABLE_CODES) break
                    Log.w(TAG, "JEV HTTP ${response.code}, retry ${attempt + 1}/$MAX_RETRIES")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "JEV request failed: ${e.message}")
                }
                if (attempt < MAX_RETRIES) delay(BACKOFF_MS shl attempt)
            }
            Result.failure(lastError)
        }

    /**
     * Hedged request: a duplicate is sent when the first has not answered within [hedgeAfterMs],
     * and the first usable response wins; the loser is cancelled. JEV answers are deterministic
     * and nearly free, while its observed tail latency (p90 ~12 s vs ~1 s median) dominated steps.
     */
    private suspend fun hedged(payload: String): HttpResponseBody = coroutineScope {
        val results = Channel<Result<HttpResponseBody>>(capacity = 2)
        val attempts = mutableListOf<Job>()
        fun launchAttempt() {
            attempts += launch { results.send(runCatching { post(payload) }) }
        }
        launchAttempt()
        var received = 0
        var fallback: Result<HttpResponseBody>? = null
        try {
            while (true) {
                val result = if (attempts.size == 1) {
                    withTimeoutOrNull(hedgeAfterMs) { results.receive() }
                } else {
                    results.receive()
                }
                if (result == null) {
                    Log.i(TAG, "JEV slow (>${hedgeAfterMs}ms); sending hedged duplicate")
                    launchAttempt()
                    continue
                }
                received++
                val body = result.getOrNull()
                if (body != null && body.code !in RETRYABLE_CODES) return@coroutineScope body
                fallback = result
                // A fast failure hedges immediately instead of waiting out the timer.
                if (attempts.size == 1) {
                    launchAttempt()
                    continue
                }
                if (received >= attempts.size) return@coroutineScope fallback.getOrThrow()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            attempts.forEach { it.cancel() }
        }
    }

    private suspend fun post(payload: String): HttpResponseBody {
        val request = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/systemone")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).awaitResponseBody()
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val questions = mapOf(
                "ping" to JevChoiceQuestion("Is the sky above clear on a sunny day?", mapOf("yes" to "yes", "no" to "no")),
            )
            choose(JSONObject().put("weather", "sunny"), questions).map { "${it.model} ok" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "CoatiJev"
        const val DEFAULT_ENDPOINT = "https://api.typesafe.ai"
        const val DEFAULT_MODEL = "jev-1.13.0"
        private const val CALL_TIMEOUT_MS = 15_000L
        const val DEFAULT_HEDGE_AFTER_MS = 2_000L
        private const val MAX_RETRIES = 2
        private const val BACKOFF_MS = 400L
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504, 529)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
