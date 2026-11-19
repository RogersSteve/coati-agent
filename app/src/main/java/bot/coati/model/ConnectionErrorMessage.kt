package bot.coati.model

import android.content.res.Resources
import bot.coati.R
import bot.coati.model.openai.OpenAiPlanException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val HTTP_STATUS_PATTERN = Regex("(?:API error|HTTP)\\s*(\\d{3})", RegexOption.IGNORE_CASE)

class ApiHttpException(val statusCode: Int) : Exception("API error $statusCode")

fun userFacingConnectionError(error: Throwable?, resources: Resources? = null): String {
    if (error is OpenAiPlanException) {
        val message = error.message ?: return text(resources, R.string.connection_error_openai_signin)
        val known = knownPlanMessage(message)
        return if (known != null && resources != null) {
            resources.getString(known.resId, *known.args.toTypedArray())
        } else {
            message
        }
    }
    val message = error?.message.orEmpty()
    val status = (error as? ApiHttpException)?.statusCode
        ?: HTTP_STATUS_PATTERN.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when (status) {
        400 -> text(resources, R.string.connection_error_http_400)
        401 -> text(resources, R.string.connection_error_http_401)
        403 -> text(resources, R.string.connection_error_http_403)
        404 -> text(resources, R.string.connection_error_http_404)
        408 -> text(resources, R.string.connection_error_http_408)
        409 -> text(resources, R.string.connection_error_http_409)
        429 -> text(resources, R.string.connection_error_http_429)
        in 500..599 -> text(resources, R.string.connection_error_http_server, status ?: 500)
        else -> when {
            error is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                text(resources, R.string.connection_error_timeout)
            error is UnknownHostException -> text(resources, R.string.connection_error_unknown_host)
            error is ConnectException -> text(resources, R.string.connection_error_connect)
            error is SSLException -> text(resources, R.string.connection_error_ssl)
            error is IOException -> text(resources, R.string.connection_error_io)
            else -> text(resources, R.string.connection_error_generic)
        }
    }
}

private class KnownPlanMessage(val resId: Int, val args: List<Any>)

private val PLAN_HTTP = listOf(
    Regex("Could not refresh the OpenAI login \\(HTTP (\\d+)\\)\\.") to R.string.openai_error_refresh_http,
    Regex("OpenAI sign-in was rejected \\(HTTP (\\d+)\\)\\.") to R.string.openai_error_signin_http,
    Regex("OpenAI did not return account details \\(HTTP (\\d+)\\)\\.") to R.string.openai_error_account_http,
    Regex("Could not contact OpenAI \\(HTTP (\\d+)\\)\\.") to R.string.openai_error_contact_http,
)

private fun knownPlanMessage(message: String): KnownPlanMessage? {
    PLAN_MESSAGES[message]?.let { return KnownPlanMessage(it, emptyList()) }
    for ((pattern, resId) in PLAN_HTTP) {
        val code = pattern.matchEntire(message)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
        return KnownPlanMessage(resId, listOf(code))
    }
    return null
}

private val PLAN_MESSAGES = mapOf(
    "OpenAI did not return a login. Try again." to R.string.openai_error_no_login,
    "OpenAI sign-in was rejected. Try again." to R.string.openai_error_rejected,
    "The OpenAI login is invalid. Sign in again." to R.string.openai_error_invalid_login,
    "The OpenAI login was rejected. Sign in again." to R.string.openai_error_login_rejected,
    "This OpenAI account cannot use that model. Choose another model." to R.string.openai_error_model_forbidden,
    "The OpenAI response stream ended before completion." to R.string.openai_error_stream_incomplete,
    "This phone could not save the OpenAI login." to R.string.openai_error_save_failed,
    "This phone could not clear the OpenAI login." to R.string.openai_error_clear_failed,
    "This phone could not save the OpenAI login identity." to R.string.openai_error_save_identity_failed,
    "OpenAI returned an invalid sign-in. Start again." to R.string.openai_error_invalid_signin,
    "OpenAI returned a different account. Sign in again and choose the expected account." to R.string.openai_error_different_account,
    "OpenAI sign-in was cancelled." to R.string.openai_error_cancelled,
    "OpenAI sign-in failed." to R.string.openai_error_failed,
    "OpenAI did not finish sign-in. Try again." to R.string.openai_error_incomplete,
    "OpenAI did not return a renewable login. Try again." to R.string.openai_error_no_refresh,
    "OpenAI did not return an identity. Try again." to R.string.openai_error_no_identity,
    "Sign in with ChatGPT first." to R.string.openai_error_sign_in_first,
    "Choose the signed-in ChatGPT account." to R.string.openai_error_choose_account,
    "Choose a model." to R.string.openai_error_choose_model,
    "The OpenAI login expired. Sign in again." to R.string.openai_error_expired,
    "OpenAI did not provide a sign-out endpoint." to R.string.openai_error_no_revoke,
    "OpenAI returned an invalid response." to R.string.openai_error_invalid_response,
    "Refusing to contact an unknown OpenAI address." to R.string.openai_error_unknown_address,
    "OpenAI did not register this app. Try again." to R.string.openai_error_not_registered,
    "OpenAI returned a different app registration. Start again." to R.string.openai_error_registration_mismatch,
    "The saved OpenAI registration is invalid." to R.string.openai_error_registration_invalid,
    "ChatGPT plan access was not approved. Sign in again and allow plan usage." to R.string.openai_error_plan_not_approved,
    "This phone could not open secure storage for the OpenAI login." to R.string.openai_error_storage,
    "OpenAI is not available." to R.string.openai_error_unavailable,
    "Could not reach OpenAI." to R.string.openai_error_unreachable,
)

private fun text(resources: Resources?, resId: Int, vararg args: Any): String =
    resources?.getString(resId, *args) ?: english(resId, args)

private fun english(resId: Int, args: Array<out Any>): String {
    val template = when (resId) {
        R.string.connection_error_openai_signin -> "OpenAI sign-in failed. Try again."
        R.string.connection_error_http_400 ->
            "The provider rejected this configuration. Check the endpoint and model name. (HTTP 400)"
        R.string.connection_error_http_401 ->
            "The API key was rejected. Check the key and try again. (HTTP 401)"
        R.string.connection_error_http_403 ->
            "This account cannot access the selected model. Check its permissions or choose another model. (HTTP 403)"
        R.string.connection_error_http_404 ->
            "The endpoint or model could not be found. Check both values. (HTTP 404)"
        R.string.connection_error_http_408 ->
            "The provider timed out. Check the network and try again. (HTTP 408)"
        R.string.connection_error_http_409 ->
            "The provider reported a configuration conflict. Check the account and model. (HTTP 409)"
        R.string.connection_error_http_429 ->
            "The account is rate-limited or out of quota. Check provider billing and try again later. (HTTP 429)"
        R.string.connection_error_http_server ->
            "The provider is temporarily unavailable. Try again later. (HTTP %1\$d)"
        R.string.connection_error_timeout ->
            "The connection timed out. Check the network and try again."
        R.string.connection_error_unknown_host ->
            "The provider address could not be reached. Check the network and API endpoint."
        R.string.connection_error_connect ->
            "Could not connect to the provider. Check the network and API endpoint."
        R.string.connection_error_ssl ->
            "A secure connection could not be established. Check the endpoint and device time."
        R.string.connection_error_io ->
            "The network request failed. Check the connection and try again."
        else ->
            "Connection verification failed. Check the network, endpoint, model, and API key, then try again."
    }
    return if (args.isEmpty()) template else String.format(template, *args)
}

/** The model answered without any content (a blocked or empty response); [message] says why. */
class EmptyModelResponseException(message: String) : Exception(message)
