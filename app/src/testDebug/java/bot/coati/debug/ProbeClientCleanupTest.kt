package bot.coati.debug

import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProbeClientCleanupTest {
    @Test fun `socket and executor cleanup all run off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        val client = mockk<OkHttpClient>()
        val networkDispatcher = mockk<Dispatcher>()
        val pool = mockk<ConnectionPool>()
        val executor = mockk<ExecutorService>()
        every { client.dispatcher } returns networkDispatcher
        every { client.connectionPool } returns pool
        every { networkDispatcher.executorService } returns executor
        every { networkDispatcher.cancelAll() } answers { threads += Thread.currentThread(); Unit }
        every { pool.evictAll() } answers { threads += Thread.currentThread(); Unit }
        every { executor.shutdown() } answers { threads += Thread.currentThread(); Unit }
        assertTrue(closeProbeClient(client).isEmpty())
        assertEquals(3, threads.size)
        assertTrue(threads.all { it != caller })
    }

    @Test fun `eviction failure is reported and does not prevent executor shutdown`() = runBlocking {
        val client = mockk<OkHttpClient>(relaxed = true)
        val dispatcher = client.dispatcher
        val executor = dispatcher.executorService
        every { client.connectionPool.evictAll() } throws IllegalStateException("do not export connection details")
        val failures = closeProbeClient(client)
        assertEquals(listOf("evict_connections"), failures.map { it.step })
        assertEquals("IllegalStateException", failures.single().errorType)
        assertTrue(failures.single().frames.isNotEmpty())
        assertFalse(failures.toString().contains("connection details"))
        verify(exactly = 1) { dispatcher.cancelAll(); executor.shutdown() }
    }

    @Test(timeout = 10_000) fun `cancelled caller still closes the owned client`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { cleanupDispatcher ->
            val client = mockk<OkHttpClient>(relaxed = true)
            val dispatcher = client.dispatcher
            val pool = client.connectionPool
            val executor = dispatcher.executorService
            val started = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Boolean>()
            val job = launch {
                try { started.complete(Unit); awaitCancellation() }
                finally { completed.complete(closeProbeClient(client, cleanupDispatcher).isEmpty()) }
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(completed.await())
            verify(exactly = 1) { dispatcher.cancelAll(); pool.evictAll(); executor.shutdown() }
        }
    }
}
