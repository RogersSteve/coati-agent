package bot.coati.model

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully consumed response: no closeable resource crosses the coroutine boundary. */
internal data class HttpResponseBody(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Keep cancellation attached until the entire body has been read, not just the headers. */
internal suspend fun Call.awaitResponseBody(): HttpResponseBody {
    currentCoroutineContext().ensureActive()
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (!continuation.isActive) return
                        HttpResponseBody(it.code, it.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }
        try {
            enqueue(callback)
        } catch (e: Exception) {
            cancel()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}

/**
 * [awaitResponseBody] with one hedge: on a phone network a request occasionally stalls for minutes
 * while the median model call takes seconds. When nothing has arrived after [hedgeAfterMs], an
 * identical call is sent and the first complete response wins; the other is cancelled. A fast
 * failure is returned as before (callers retry it); a failure while another attempt is still
 * pending waits for that attempt.
 */
internal suspend fun Call.awaitHedgedResponseBody(hedgeAfterMs: Long, onHedge: () -> Unit = {}): HttpResponseBody =
    coroutineScope {
        val results = Channel<Result<HttpResponseBody>>(capacity = 2)
        val attempts = mutableListOf<Job>()
        fun start(call: Call) {
            attempts += launch { results.send(runCatching { call.awaitResponseBody() }) }
        }
        start(this@awaitHedgedResponseBody)
        var pending = 1
        try {
            while (true) {
                val result = if (attempts.size == 1) withTimeoutOrNull(hedgeAfterMs) { results.receive() } else results.receive()
                if (result == null) {
                    onHedge()
                    start(clone())
                    pending++
                    continue
                }
                pending--
                result.getOrNull()?.let { return@coroutineScope it }
                if (pending == 0) throw result.exceptionOrNull()!!
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            attempts.forEach { it.cancel() }
        }
    }
