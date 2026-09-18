package bot.coati.model

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorMessageTest {

    @Test
    fun `maps authentication errors without exposing response bodies`() {
        val message = userFacingConnectionError(
            Exception("API error 401: {\"message\":\"secret provider detail\"}"),
        )

        assertTrue(message.contains("API key was rejected"))
        assertTrue(message.contains("HTTP 401"))
        assertFalse(message.contains("secret provider detail"))
        assertEquals(
            "The API key was rejected. Check the key and try again. (HTTP 401)",
            userFacingConnectionError(ApiHttpException(401)),
        )
    }

    @Test
    fun `maps quota and server errors to actionable messages`() {
        assertTrue(userFacingConnectionError(Exception("API error 429: quota")).contains("quota"))
        assertEquals(
            "The provider is temporarily unavailable. Try again later. (HTTP 503)",
            userFacingConnectionError(Exception("HTTP 503")),
        )
    }

    @Test
    fun `maps network failures without raw exception text`() {
        assertTrue(userFacingConnectionError(SocketTimeoutException()).contains("timed out"))
        assertTrue(userFacingConnectionError(UnknownHostException()).contains("address could not be reached"))
        assertTrue(userFacingConnectionError(ConnectException()).contains("Could not connect"))
        assertFalse(userFacingConnectionError(Exception("internal-secret")).contains("internal-secret"))
    }
}
