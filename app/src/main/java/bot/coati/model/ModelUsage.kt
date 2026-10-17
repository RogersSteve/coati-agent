package bot.coati.model

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What a model call is for, carried in the coroutine context so gateways can attribute usage
 * without new parameters. [background] calls (planning ahead, manual curation) never block a step;
 * every other purpose sits on the critical path.
 */
class ModelCallPurpose(val label: String, val background: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelCallPurpose> {
        val REASONING = ModelCallPurpose("reasoning")
        val VERIFY = ModelCallPurpose("verify")
        val COMPLETION_AUDIT = ModelCallPurpose("completion_audit")
        val VISION_PARSE = ModelCallPurpose("vision_parse")
        val PRECOMMIT_GATE = ModelCallPurpose("precommit_gate")
        val AUTOCOMPACT = ModelCallPurpose("autocompact")
        val SELECTOR = ModelCallPurpose("selector")
        val ADVISOR = ModelCallPurpose("advisor", background = true)
        val MANUAL_CURATION = ModelCallPurpose("manual_curation", background = true)
        val UNLABELED = ModelCallPurpose("unlabeled")

        /** Purpose of a gateway operation whose caller did not name one. */
        fun forOperation(operation: String): ModelCallPurpose = when (operation) {
            "analyzeScreen" -> REASONING
            "verifyAction" -> VERIFY
            else -> ModelCallPurpose(operation)
        }
    }

    override fun toString(): String = label
}

/** Tokens of one response. [output] excludes [thinking]; providers that do not split them report thinking as 0. */
data class ModelUsage(val input: Int = 0, val output: Int = 0, val thinking: Int = 0) {
    val total: Int get() = input + output + thinking
}

/** Reads token usage from any supported provider's response body; null when the body has none. */
internal object ModelUsageParser {
    fun parse(body: String): ModelUsage? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        root.optJSONObject("usageMetadata")?.let { gemini ->
            return ModelUsage(
                input = gemini.optInt("promptTokenCount"),
                output = gemini.optInt("candidatesTokenCount"),
                thinking = gemini.optInt("thoughtsTokenCount"),
            )
        }
        val usage = root.optJSONObject("usage") ?: return null
        if (usage.has("prompt_tokens") || usage.has("completion_tokens")) {
            // OpenAI chat completions: completion_tokens already includes reasoning tokens.
            val reasoning = usage.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0
            return ModelUsage(
                input = usage.optInt("prompt_tokens"),
                output = (usage.optInt("completion_tokens") - reasoning).coerceAtLeast(0),
                thinking = reasoning,
            )
        }
        // Claude, OpenAI Responses and JEV: input_tokens/output_tokens; Claude reports cached input separately.
        val reasoning = usage.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens") ?: 0
        return ModelUsage(
            input = usage.optInt("input_tokens") + usage.optInt("cache_read_input_tokens") +
                usage.optInt("cache_creation_input_tokens"),
            output = (usage.optInt("output_tokens") - reasoning).coerceAtLeast(0),
            thinking = reasoning,
        )
    }
}

/**
 * Per-task model usage for benchmarks: logical calls (one per gateway call, however many retries)
 * and HTTP requests with their tokens, grouped by [ModelCallPurpose]. Usage outside a task, such as
 * manual curation finishing after the task ended, goes to the next summary's `outside_task` bucket.
 */
object ModelUsageMeter {
    private class Tally {
        var calls = 0
        var failedCalls = 0
        var callMs = 0L
        var requests = 0
        var input = 0L
        var output = 0L
        var thinking = 0L
    }

    private val lock = Any()
    private var byPurpose = linkedMapOf<String, Tally>()
    private val background = mutableSetOf<String>()
    private var outsideTask = Tally()
    private var active = false

    fun beginTask(): Unit = synchronized(lock) {
        byPurpose = linkedMapOf()
        active = true
    }

    fun recordCall(purpose: ModelCallPurpose, ok: Boolean, durationMs: Long): Unit = synchronized(lock) {
        tally(purpose).apply {
            calls++
            if (!ok) failedCalls++
            callMs += durationMs
        }
    }

    fun recordRequest(purpose: ModelCallPurpose, usage: ModelUsage?): Unit = synchronized(lock) {
        tally(purpose).apply {
            requests++
            if (usage != null) {
                input += usage.input
                output += usage.output
                thinking += usage.thinking
            }
        }
    }

    /**
     * Ends the task and returns its usage as JSON, or null when no task is active (it already ended).
     * Later calls go to `outside_task` until the next task.
     */
    fun finishTask(): JSONObject? = synchronized(lock) {
        if (!active) return null
        active = false
        val purposes = JSONObject()
        val critical = Tally()
        val bg = Tally()
        byPurpose.forEach { (label, t) ->
            purposes.put(label, t.toJson().put("background", label in background))
            (if (label in background) bg else critical).add(t)
        }
        JSONObject()
            .put("critical", critical.toJson())
            .put("background", bg.toJson())
            .put("purposes", purposes)
            .put("outside_task", outsideTask.toJson())
            .also { outsideTask = Tally() }
    }

    private fun tally(purpose: ModelCallPurpose): Tally {
        if (!active) return outsideTask
        if (purpose.background) background += purpose.label
        return byPurpose.getOrPut(purpose.label) { Tally() }
    }

    private fun Tally.add(other: Tally) {
        calls += other.calls
        failedCalls += other.failedCalls
        callMs += other.callMs
        requests += other.requests
        input += other.input
        output += other.output
        thinking += other.thinking
    }

    private fun Tally.toJson() = JSONObject()
        .put("calls", calls)
        .put("failed_calls", failedCalls)
        .put("call_ms", callMs)
        .put("requests", requests)
        .put("input_tokens", input)
        .put("output_tokens", output)
        .put("thinking_tokens", thinking)
        .put("total_tokens", input + output + thinking)
}

/** [awaitResponseBody] for model endpoints: also meters the request under the caller's purpose. */
internal suspend fun Call.awaitModelResponseBody(): HttpResponseBody {
    val purpose = currentCoroutineContext()[ModelCallPurpose] ?: ModelCallPurpose.UNLABELED
    // Audit timing on devices: request size and how each call ended, never its content.
    val audit = purpose == ModelCallPurpose.COMPLETION_AUDIT
    val started = System.nanoTime()
    if (audit) Log.i(TAG, "Completion audit HTTP starting: bodyBytes=${request().body?.contentLength()}")
    val response = try {
        awaitResponseBody()
    } catch (e: Exception) {
        if (audit) Log.w(TAG, "Completion audit HTTP interrupted: elapsedMs=${(System.nanoTime() - started) / 1_000_000} type=${e.javaClass.simpleName}")
        throw e
    }
    if (audit) Log.i(TAG, "Completion audit HTTP completed: status=${response.code} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
    ModelUsageMeter.recordRequest(purpose, if (response.isSuccessful) ModelUsageParser.parse(response.body) else null)
    return response
}

private const val TAG = "CoatiModel"
