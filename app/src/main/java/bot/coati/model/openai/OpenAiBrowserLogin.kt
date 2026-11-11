package bot.coati.model.openai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal class OpenAiBrowserLogin private constructor(
    val authorizationUrl: String,
    val redirectUri: String,
    val requestedClientId: String,
    val existingSubject: String?,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    private val server: ServerSocket,
) : AutoCloseable {
    suspend fun awaitCallback(deadlineMs: Long, nowMs: () -> Long): OpenAiOAuthCallback {
        try {
            while (nowMs() <= deadlineMs) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val connection = socket
                try {
                    connection.soTimeout = REQUEST_TIMEOUT_MS
                    val requestTarget = try {
                        readRequestTarget(connection.getInputStream().bufferedReader(StandardCharsets.US_ASCII))
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (requestTarget == null) {
                        respond(connection, 400, "Sign-in request was not understood.")
                        continue
                    }
                    val url = runCatching { "http://127.0.0.1$requestTarget".toHttpUrl() }.getOrNull()
                    if (url == null || url.encodedPath != CALLBACK_PATH) {
                        respond(connection, 404, "This sign-in page is no longer available.")
                        continue
                    }
                    val callback = OpenAiOAuthCallback(
                        code = url.queryParameter("code"),
                        state = url.queryParameter("state"),
                        clientId = url.queryParameter("client_id"),
                        error = url.queryParameter("error"),
                        errorDescription = url.queryParameter("error_description"),
                    )
                    respond(
                        connection,
                        if (callback.error == null) 200 else 400,
                        if (callback.error == null) "Sign-in returned to Coati. You can close this page."
                        else "OpenAI sign-in was not completed. You can close this page.",
                    )
                    return callback
                } finally {
                    runCatching { connection.close() }
                }
            }
            throw OpenAiPlanException("Sign-in timed out. Start again.")
        } finally {
            close()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    override fun toString(): String = "OpenAiBrowserLogin"

    companion object {
        const val CALLBACK_PATH = "/auth/callback"
        private const val REQUEST_TIMEOUT_MS = 5_000

        fun create(
            requestedClientId: String,
            existingSubject: String?,
            state: String,
            nonce: String,
            codeVerifier: String,
            authorizationUrl: (redirectUri: String) -> String,
        ): OpenAiBrowserLogin {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 1_000
            }
            val redirectUri = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
            return OpenAiBrowserLogin(
                authorizationUrl = authorizationUrl(redirectUri),
                redirectUri = redirectUri,
                requestedClientId = requestedClientId,
                existingSubject = existingSubject,
                state = state,
                nonce = nonce,
                codeVerifier = codeVerifier,
                server = server,
            )
        }

        private fun readRequestTarget(reader: BufferedReader): String? {
            val line = reader.readLine()?.takeIf { it.length <= 8_192 } ?: return null
            val parts = line.split(' ')
            if (parts.size != 3 || parts[0] != "GET") return null
            return parts[1].takeIf { it.startsWith('/') && it.length <= 8_192 }
        }

        private fun respond(socket: java.net.Socket, status: Int, message: String) {
            val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
            val body = "<!doctype html><meta charset=\"utf-8\"><title>Coati</title><p>${escapeHtml(message)}</p>"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().use { output ->
                output.write(headers)
                output.write(bytes)
                output.flush()
            }
        }

        private fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

internal data class OpenAiOAuthCallback(
    val code: String?,
    val state: String?,
    val clientId: String?,
    val error: String?,
    val errorDescription: String?,
)
