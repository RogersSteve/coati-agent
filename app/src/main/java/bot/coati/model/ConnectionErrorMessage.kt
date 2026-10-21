package bot.coati.model

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val HTTP_STATUS_PATTERN = Regex("(?:API error|HTTP)\\s*(\\d{3})", RegexOption.IGNORE_CASE)

class ApiHttpException(val statusCode: Int) : Exception("API error $statusCode")

fun userFacingConnectionError(error: Throwable?): String {
    val message = error?.message.orEmpty()
    val status = (error as? ApiHttpException)?.statusCode
        ?: HTTP_STATUS_PATTERN.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when (status) {
        400 -> "The provider rejected this configuration. Check the endpoint and model name. (HTTP 400)"
        401 -> "The API key was rejected. Check the key and try again. (HTTP 401)"
        403 -> "This account cannot access the selected model. Check its permissions or choose another model. (HTTP 403)"
        404 -> "The endpoint or model could not be found. Check both values. (HTTP 404)"
        408 -> "The provider timed out. Check the network and try again. (HTTP 408)"
        409 -> "The provider reported a configuration conflict. Check the account and model. (HTTP 409)"
        429 -> "The account is rate-limited or out of quota. Check provider billing and try again later. (HTTP 429)"
        in 500..599 -> "The provider is temporarily unavailable. Try again later. (HTTP $status)"
        else -> when {
            error is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                "The connection timed out. Check the network and try again."
            error is UnknownHostException ->
                "The provider address could not be reached. Check the network and API endpoint."
            error is ConnectException ->
                "Could not connect to the provider. Check the network and API endpoint."
            error is SSLException ->
                "A secure connection could not be established. Check the endpoint and device time."
            error is IOException ->
                "The network request failed. Check the connection and try again."
            else ->
                "Connection verification failed. Check the network, endpoint, model, and API key, then try again."
        }
    }
}

/** The model answered without any content (a blocked or empty response); [message] says why. */
class EmptyModelResponseException(message: String) : Exception(message)
