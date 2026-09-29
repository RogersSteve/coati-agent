package bot.coati.model

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Retries transient model failures for the agent runtime, so one bad response does not count as
 * an agent failure (which triggers re-planning and, after a few, aborts the task).
 *
 * Transient: HTTP 408/429/5xx, connection errors, and for [analyzeScreen] a malformed response:
 * cut off mid-JSON, not JSON at all, or outside the schema (OpenAI-compatible relays occasionally
 * return such bodies with finish_reason "stop"). Not retried:
 * timeouts (the request budget is already spent), other HTTP errors such as auth or bad requests,
 * and cancellation. quickCheck/verifyAction parse leniently, so only their transport is retried.
 * Connection tests are passed through unchanged so configuration errors surface immediately.
 */
class ResilientModelGateway(
    private val delegate: CloudModelGateway,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
) : CloudModelGateway {

    override suspend fun analyzeScreen(
        screenshot: ByteArray,
        uiTreeHint: String,
        taskDescription: String,
        conversationHistory: List<Message>,
    ): Result<ModelResponse> = withRetry("analyzeScreen", retryMalformed = true) {
        delegate.analyzeScreen(screenshot, uiTreeHint, taskDescription, conversationHistory)
    }

    override suspend fun quickCheck(
        screenshot: ByteArray,
        question: String,
        manualObservationInstruction: String?,
    ): Result<QuickCheckResult> = withRetry("quickCheck", retryMalformed = false) {
        delegate.quickCheck(screenshot, question, manualObservationInstruction)
    }

    override suspend fun verifyAction(
        beforeScreenshot: ByteArray,
        afterScreenshot: ByteArray,
        actionDescription: String,
        expectedOutcome: String,
        manualObservationInstruction: String?,
    ): Result<VerifyResult> = withRetry("verifyAction", retryMalformed = false) {
        delegate.verifyAction(beforeScreenshot, afterScreenshot, actionDescription, expectedOutcome, manualObservationInstruction)
    }

    override suspend fun summarizeText(prompt: String): Result<String> =
        withRetry("summarizeText", retryMalformed = false) { delegate.summarizeText(prompt) }

    override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> =
        withRetry("generateStructuredText", retryMalformed = false) { delegate.generateStructuredText(request) }

    override suspend fun testConnection(): Result<String> = delegate.testConnection()

    private suspend fun <T> withRetry(
        operation: String,
        retryMalformed: Boolean,
        call: suspend () -> Result<T>,
    ): Result<T> {
        var attempt = 1
        while (true) {
            val result = call()
            val error = result.exceptionOrNull() ?: return result
            if (attempt >= maxAttempts || !isTransient(error, retryMalformed)) return result
            Log.w(TAG, "$operation attempt $attempt/$maxAttempts failed transiently, retrying: ${error.message}")
            delay(backoffMs * attempt)
            attempt++
        }
    }

    companion object {
        private const val TAG = "CoatiModel"
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BACKOFF_MS = 500L

        internal fun isTransient(error: Throwable, retryMalformed: Boolean): Boolean = when (error) {
            is ApiHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
            // Covers SocketTimeoutException and call timeouts: the request budget is already spent.
            is InterruptedIOException -> false
            is IOException -> true
            is MalformedModelResponseException, is JSONException -> retryMalformed
            else -> false
        }
    }
}
