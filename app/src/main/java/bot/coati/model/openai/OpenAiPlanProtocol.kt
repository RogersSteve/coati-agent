package bot.coati.model.openai

import org.json.JSONArray
import org.json.JSONObject

/** OpenAI's public Sign in with ChatGPT flow for locally hosted open-source apps. */
internal object OpenAiPlanProtocol {
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val AUTH_BASE = "https://auth.openai.com"
    const val API_ROOT = "https://api.openai.com/v1"
    const val RESOURCE = API_ROOT
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    const val REQUESTED_SCOPES = "openid profile email offline_access resource.invoke $REQUIRED_SCOPE"
    const val USER_AGENT = "Coati/0.1.0 (Android)"
    const val LOGIN_TIMEOUT_MS = 15 * 60 * 1000L

    val FALLBACK_MODELS = listOf(
        OpenAiPlanModel("gpt-6.1-sol", "gpt-6.1-sol"),
    )

    fun authorizeUrl(authBase: String) = "${authBase.trimEnd('/')}/api/accounts/authorize"
    fun oauthTokenUrl(authBase: String) = "${authBase.trimEnd('/')}/api/accounts/oauth/token"
    fun openIdConfigurationUrl(authBase: String) = "${authBase.trimEnd('/')}/.well-known/openid-configuration"
    fun responsesUrl(apiRoot: String) = "${apiRoot.trimEnd('/')}/responses"
    fun modelsUrl(apiRoot: String) = "${apiRoot.trimEnd('/')}/models"

    fun authorizationForm(clientId: String, authorizationCode: String, codeVerifier: String, redirectUri: String): String =
        form(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to authorizationCode,
            "code_verifier" to codeVerifier,
            "redirect_uri" to redirectUri,
            "resource" to RESOURCE,
        )

    fun refreshForm(clientId: String, refreshToken: String): String =
        form(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to RESOURCE,
        )

    fun revokeForm(clientId: String, refreshToken: String): String =
        form(
            "token" to refreshToken,
            "token_type_hint" to "refresh_token",
            "client_id" to clientId,
        )

    fun parseOAuthTokens(body: String): OAuthTokens {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw OpenAiPlanException("OpenAI did not return a login. Try again.")
        }
        if (json.has("error")) {
            val detail = publicErrorDetail(json.optString("error_description").ifBlank { json.optString("error") })
            throw OpenAiPlanException(detail ?: "OpenAI sign-in was rejected. Try again.")
        }
        val access = validatedAccessToken(json.optString("access_token"))
            ?: throw OpenAiPlanException("OpenAI did not return a login. Try again.")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val idToken = json.optString("id_token").takeIf { it.isNotBlank() }
        val scope = json.optString("scope").takeIf { it.isNotBlank() }
        val expiresIn = when (val raw = json.opt("expires_in")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
        return OAuthTokens(access, refresh, idToken, expiresIn, scope)
    }

    fun parseModels(body: String): List<OpenAiPlanModel> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val models = root.optJSONArray("models") ?: return emptyList()
        val parsed = mutableListOf<OpenAiPlanModel>()
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            val slug = item.optString("slug").ifBlank { item.optString("id") }.ifBlank { item.optString("name") }
            if (slug.isBlank() || slug.length > 128 || slug.any { it.isWhitespace() }) continue
            if (item.has("visibility") && item.optString("visibility") != "list") continue
            val display = item.optString("display_name").ifBlank { slug }
            parsed.add(OpenAiPlanModel(slug, display))
        }
        return parsed.distinctBy { it.slug }
    }

    fun parseResponseStream(sse: String): PlanModelOutput {
        var deltas = StringBuilder()
        var doneText: String? = null
        var completedText: String? = null
        var failed: String? = null
        var incomplete = false
        var terminal = false
        val normalized = sse.replace("\r\n", "\n").replace('\r', '\n')
        for (event in normalized.split("\n\n")) {
            val data = event.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
            if (data.isBlank() || data == "[DONE]") continue
            val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (json.optString("type")) {
                "response.output_text.delta" -> deltas.append(json.optString("delta"))
                "response.output_text.done" -> json.optString("text").takeIf { it.isNotBlank() }?.let { doneText = it }
                "response.completed" -> {
                    terminal = true
                    completedText = extractCompletedText(json) ?: completedText
                    val status = json.optJSONObject("response")?.optString("status").orEmpty()
                    if (status == "incomplete") incomplete = true
                    if (status == "failed") failed = failed ?: errorDetail(json) ?: "The model request was rejected."
                }
                "response.failed", "error" -> {
                    terminal = true
                    failed = failed ?: errorDetail(json) ?: "The model request was rejected."
                }
                "response.incomplete" -> {
                    terminal = true
                    incomplete = true
                }
            }
        }
        val text = doneText ?: completedText ?: deltas.toString()
        return PlanModelOutput(text.trim(), failed, incomplete, terminal)
    }

    fun responsesBody(model: String, instructions: String, input: JSONArray): JSONObject =
        JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("stream", true)
            .put("store", false)

    /** Convert the shared Chat Completions-shaped prompt into a public Responses API request. */
    fun responsesBodyFromChat(chat: JSONObject): JSONObject {
        val instructions = StringBuilder()
        val input = JSONArray()
        val messages = chat.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system") {
                appendChatText(instructions, message.opt("content"))
                if (instructions.isNotEmpty()) instructions.append('\n')
                continue
            }
            val content = message.opt("content")
            val mappedRole = if (role == "assistant") "assistant" else "user"
            if (content is JSONArray) {
                val parts = JSONArray()
                for (partIndex in 0 until content.length()) {
                    val part = content.optJSONObject(partIndex) ?: continue
                    when (part.optString("type")) {
                        "text" -> parts.put(textPart(part.optString("text")))
                        "image_url" -> {
                            val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                            if (url.isNotBlank()) {
                                parts.put(JSONObject().put("type", "input_image").put("image_url", url))
                            }
                        }
                    }
                }
                input.put(JSONObject().put("role", mappedRole).put("content", parts))
            } else {
                input.put(JSONObject().put("role", mappedRole).put("content", content?.toString().orEmpty()))
            }
        }
        return responsesBody(chat.optString("model"), instructions.toString().trim(), input)
    }

    fun textPart(text: String): JSONObject = JSONObject().put("type", "input_text").put("text", text)

    fun imagePart(jpegBase64: String): JSONObject =
        JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$jpegBase64")

    fun encodeAccounts(accounts: List<OpenAiPlanAccount>): String =
        JSONArray().apply {
            accounts.forEach { account ->
                put(JSONObject().put("id", account.id).put("label", account.label).put("planType", account.planType))
            }
        }.toString()

    fun decodeAccounts(json: String): List<OpenAiPlanAccount> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = validatedAccountId(item.optString("id")) ?: continue
                add(OpenAiPlanAccount(id, item.optString("label").ifBlank { "ChatGPT" }, item.optString("planType").ifBlank { null }))
            }
        }
    }

    fun encodeModels(models: List<OpenAiPlanModel>): String =
        JSONArray().apply {
            models.forEach { put(JSONObject().put("slug", it.slug).put("displayName", it.displayName)) }
        }.toString()

    fun decodeModels(json: String): List<OpenAiPlanModel> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val slug = item.optString("slug")
                if (slug.isNotBlank()) add(OpenAiPlanModel(slug, item.optString("displayName").ifBlank { slug }))
            }
        }
    }

    fun validatedAccountId(id: String): String? =
        id.takeIf { it.matches(Regex("[A-Za-z0-9_\\-]{1,128}")) }

    fun validatedAccessToken(token: String): String? =
        token.takeIf { it.isNotBlank() && it.length <= 16_384 && '\r' !in it && '\n' !in it }

    fun publicErrorDetail(raw: String?): String? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > 160) return null
        val lower = text.lowercase()
        if ("eyj" in lower || "bearer" in lower || "sk-" in lower || "token" in lower || "refresh" in lower) return null
        if (!text.matches(Regex("[A-Za-z0-9 .,;:'\"()/_+-]{1,160}"))) return null
        return text
    }

    private fun appendChatText(into: StringBuilder, content: Any?) {
        when (content) {
            is String -> into.append(content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") == "text") into.append(part.optString("text"))
                }
            }
        }
    }

    private fun extractCompletedText(json: JSONObject): String? {
        val output = json.optJSONObject("response")?.optJSONArray("output") ?: return null
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text" || part.optString("type") == "text") {
                    text.append(part.optString("text"))
                }
            }
        }
        return text.toString().ifBlank { null }
    }

    private fun errorDetail(json: JSONObject): String? {
        val nested = json.optJSONObject("error")
            ?: json.optJSONObject("response")?.optJSONObject("error")
        val raw = nested?.optString("message")?.ifBlank { null }
            ?: json.optString("message").ifBlank { null }
        return publicErrorDetail(raw)
    }

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long?,
    val scope: String?,
) {
    override fun toString(): String = "OAuthTokens"
}

internal data class PlanIdentity(
    val subject: String,
    val email: String?,
    val planType: String?,
    val expiresAtEpochSeconds: Long?,
)

internal data class PlanModelOutput(
    val text: String,
    val failedDetail: String?,
    val incomplete: Boolean,
    val terminal: Boolean,
)

data class OpenAiPlanAccount(val id: String, val label: String, val planType: String?)

data class OpenAiPlanModel(val slug: String, val displayName: String)

class OpenAiPlanException(message: String) : Exception(message)
