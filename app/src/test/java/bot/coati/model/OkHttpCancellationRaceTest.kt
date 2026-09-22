package bot.coati.model

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpCancellationRaceTest {
    @Test fun `late response is closed without delivering to cancelled caller`() = race(cancelBeforeResponse = true)

    @Test fun `response read before dispatch is closed even if cancellation wins delivery`() = race(cancelBeforeResponse = false)

    private fun race(cancelBeforeResponse: Boolean) = runTest {
        val callback = slot<Callback>()
        val call = mockk<Call>(relaxed = true)
        every { call.enqueue(capture(callback)) } returns Unit
        var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("body")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = 4
            override fun source(): BufferedSource = source
        }
        val response = Response.Builder()
            .request(Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        var delivered = false
        val pending = async {
            call.awaitResponseBody()
            delivered = true
        }
        runCurrent()
        assertTrue(callback.isCaptured)
        if (cancelBeforeResponse) pending.cancel()
        callback.captured.onResponse(call, response)
        if (!cancelBeforeResponse) pending.cancel()
        runCurrent()
        assertTrue(closed)
        assertTrue(pending.isCancelled)
        assertFalse(delivered)
        verify(exactly = 1) { call.cancel() }
    }
}
