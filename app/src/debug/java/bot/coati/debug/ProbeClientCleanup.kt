package bot.coati.debug

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal data class ProbeCleanupFailure(val step: String, val errorType: String, val frames: List<String>)

/** Socket close/eviction may perform network I/O; cleanup must also run after Activity cancellation. */
internal suspend fun closeProbeClient(
    client: OkHttpClient,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): List<ProbeCleanupFailure> = withContext(NonCancellable) {
    // Keep the return hop in the uncancelled scope too; otherwise a cancelled Activity
    // can lose the cleanup result while dispatching back from IO, before writing its report.
    withContext(dispatcher) { closeProbeClientOnWorker(client) }
}

private fun closeProbeClientOnWorker(client: OkHttpClient): List<ProbeCleanupFailure> {
    val failures = mutableListOf<ProbeCleanupFailure>()
    val steps = listOf<Pair<String, () -> Unit>>(
        "cancel_calls" to { client.dispatcher.cancelAll() },
        "evict_connections" to { client.connectionPool.evictAll() },
        "shutdown_executor" to { client.dispatcher.executorService.shutdown() },
    )
    for ((name, action) in steps) {
        try { action() }
        catch (error: Exception) {
            // Keep stack locations but not exception messages (which may contain connection details).
            failures += ProbeCleanupFailure(name, error.javaClass.simpleName, error.stackTrace.take(12).map { it.toString() })
        }
    }
    return failures
}
