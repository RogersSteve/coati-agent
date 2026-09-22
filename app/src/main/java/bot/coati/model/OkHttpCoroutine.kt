package bot.coati.model

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
