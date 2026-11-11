package bot.coati.model.openai

import android.content.Context
import android.util.Log
import bot.coati.model.awaitResponseBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OpenAiPlanSnapshot(
    val email: String?,
    val planType: String?,
    val accounts: List<OpenAiPlanAccount>,
    val selectedAccountId: String,
    val models: List<OpenAiPlanModel>,
    val selectedModel: String?,
)

data class OpenAiPlanAuthorization(
    val accessToken: String,
    val accountId: String,
) {
    override fun toString(): String = "OpenAiPlanAuthorization"
}

interface OpenAiPlanCredential {
    suspend fun authorization(): OpenAiPlanAuthorization
}

/**
 * Sign in with ChatGPT for an open-source local client. The browser handles the
 * password and consent; Coati receives an OAuth callback only on 127.0.0.1.
 */
class OpenAiPlanAuth internal constructor(
    private val store: OpenAiPlanTokenStore,
    private val client: OkHttpClient,
    private val onClearPlan: () -> Unit,
    private val authBaseUrl: String = OpenAiPlanProtocol.AUTH_BASE,
    private val apiRoot: String = OpenAiPlanProtocol.API_ROOT,
    private val nowMs: () -> Long = System::currentTimeMillis,
    verifier: OpenAiIdTokenVerifier? = null,
) : OpenAiPlanCredential {
    private val http = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", OpenAiPlanProtocol.USER_AGENT).build())
        }
        .build()
    private val idTokenVerifier = verifier ?: JwksOpenAiIdTokenVerifier(http, authBaseUrl, nowMs)

    fun hasSession(): Boolean = runCatching { store.read() != null }.getOrDefault(false)

    fun currentSnapshot(): OpenAiPlanSnapshot? = runCatching { store.read()?.let(::snapshot) }.getOrNull()

    internal suspend fun beginBrowserLogin(): OpenAiBrowserLogin = withContext(Dispatchers.IO) {
        val registration = store.readRegistration()
        val clientId = registration?.clientId ?: OpenAiPlanProtocol.DYNAMIC_CLIENT_ID
        val hostId = store.getOrCreateHostId()
        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        OpenAiBrowserLogin.create(
            requestedClientId = clientId,
            existingSubject = registration?.subject,
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
        ) { redirectUri ->
            OpenAiPlanProtocol.authorizeUrl(authBaseUrl).toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .apply {
                    if (registration == null) addQueryParameter("agent_name_hint", "Coati")
                    registration?.email?.let { addQueryParameter("login_hint", it) }
                }
                .addQueryParameter("ext_agent_host_id", hostId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("scope", OpenAiPlanProtocol.REQUESTED_SCOPES)
                .addQueryParameter("resource", OpenAiPlanProtocol.RESOURCE)
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
                .build()
                .toString()
        }
    }

    internal suspend fun finishBrowserLogin(login: OpenAiBrowserLogin): OpenAiPlanSnapshot = withContext(Dispatchers.IO) {
        val callback = login.awaitCallback(nowMs() + OpenAiPlanProtocol.LOGIN_TIMEOUT_MS, nowMs)
        if (!constantTimeEquals(callback.state.orEmpty(), login.state)) {
            throw OpenAiPlanException("OpenAI returned an invalid sign-in. Start again.")
        }
        callback.error?.let {
            val detail = OpenAiPlanProtocol.publicErrorDetail(callback.errorDescription)
            throw OpenAiPlanException(detail ?: if (it == "access_denied") "OpenAI sign-in was cancelled." else "OpenAI sign-in failed.")
        }
        val code = callback.code?.takeIf { it.isNotBlank() && it.length <= 8_192 && '\r' !in it && '\n' !in it }
            ?: throw OpenAiPlanException("OpenAI did not finish sign-in. Try again.")
        val issuedClientId = resolveIssuedClientId(login.requestedClientId, callback.clientId)
        currentCoroutineContext().ensureActive()
        val tokens = exchange(issuedClientId, code, login.codeVerifier, login.redirectUri)
        val refreshToken = tokens.refreshToken ?: throw OpenAiPlanException("OpenAI did not return a renewable login. Try again.")
        var stored = false
        try {
            val idToken = tokens.idToken ?: throw OpenAiPlanException("OpenAI did not return an identity. Try again.")
            val scopes = tokens.scope.orEmpty()
            requirePlanScope(scopes)
            val identity = idTokenVerifier.verify(idToken, issuedClientId, login.nonce, login.existingSubject)
            val accountId = accountKey(identity.subject)
            val authorization = OpenAiPlanAuthorization(tokens.accessToken, accountId)
            val models = loadModels(authorization)
            val selectedModel = models.firstOrNull()?.slug
            val account = OpenAiPlanAccount(accountId, identity.email ?: "ChatGPT", identity.planType)
            val record = recordFrom(
                tokens = OAuthTokens(tokens.accessToken, refreshToken, idToken, tokens.expiresInSeconds, scopes),
                identity = identity,
                clientId = issuedClientId,
                account = account,
                models = models,
                selectedModel = selectedModel,
                previous = null,
            )
            sessionLock.withLock {
                currentCoroutineContext().ensureActive()
                store.write(record)
                stored = true
                currentCoroutineContext().ensureActive()
            }
            Log.i(TAG, "OpenAI plan login stored")
            snapshot(record)
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (stored) sessionLock.withLock { store.clear() }
                runCatching { revoke(issuedClientId, refreshToken) }
            }
            throw error
        }
    }

    suspend fun listModels(accountId: String): List<OpenAiPlanModel> = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            val current = store.read() ?: throw OpenAiPlanException("Sign in with ChatGPT first.")
            if (accountId != current.accountId) throw OpenAiPlanException("Choose the signed-in ChatGPT account.")
            loadModels(freshAuthorization(current))
        }
    }

    suspend fun applySelection(
        accountId: String,
        model: String,
        models: List<OpenAiPlanModel>,
    ): OpenAiPlanSnapshot = withContext(Dispatchers.IO) {
        if (model.isBlank() || model.length > 128 || model.any { it.isWhitespace() }) throw OpenAiPlanException("Choose a model.")
        sessionLock.withLock {
            val current = store.read() ?: throw OpenAiPlanException("Sign in with ChatGPT first.")
            if (accountId != current.accountId) throw OpenAiPlanException("Choose the signed-in ChatGPT account.")
            val updated = current.copy(selectedModel = model, modelsJson = OpenAiPlanProtocol.encodeModels(models))
            store.write(updated)
            try {
                freshAuthorization(store.read() ?: updated)
            } catch (error: Exception) {
                if (store.read() != null) store.write(current)
                throw error
            }
            snapshot(store.read() ?: updated)
        }
    }

    override suspend fun authorization(): OpenAiPlanAuthorization = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            freshAuthorization(store.read() ?: throw OpenAiPlanException("Sign in with ChatGPT first."))
        }
    }

    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            val current = store.read()
            if (current == null) {
                onClearPlan()
                return@withLock false
            }
            val revoked = try {
                revoke(current.clientId, current.refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            store.clear()
            onClearPlan()
            Log.i(TAG, "OpenAI plan login removed from this device")
            revoked
        }
    }

    private suspend fun freshAuthorization(current: OpenAiPlanRecord): OpenAiPlanAuthorization {
        val access = OpenAiPlanProtocol.validatedAccessToken(current.accessToken)
        if (access != null && current.accessExpiryEpochMs - EXPIRY_SKEW_MS > nowMs()) {
            return OpenAiPlanAuthorization(access, current.accountId)
        }
        val refreshed = refresh(current)
        store.write(refreshed)
        return OpenAiPlanAuthorization(
            OpenAiPlanProtocol.validatedAccessToken(refreshed.accessToken)
                ?: throw OpenAiPlanException("The OpenAI login expired. Sign in again."),
            refreshed.accountId,
        )
    }

    private suspend fun refresh(current: OpenAiPlanRecord): OpenAiPlanRecord {
        val response = postForm(
            OpenAiPlanProtocol.oauthTokenUrl(authBaseUrl),
            OpenAiPlanProtocol.refreshForm(current.clientId, current.refreshToken),
        )
        if (response.code !in 200..299) {
            if (isTerminalRefreshError(response.body)) {
                store.clear()
                onClearPlan()
                throw OpenAiPlanException("The OpenAI login expired. Sign in again.")
            }
            throw OpenAiPlanException("Could not refresh the OpenAI login (HTTP ${response.code}).")
        }
        val tokens = OpenAiPlanProtocol.parseOAuthTokens(response.body)
        val scopes = tokens.scope ?: current.scopes
        requirePlanScope(scopes)
        val identity = if (tokens.idToken != null) {
            idTokenVerifier.verify(tokens.idToken, current.clientId, nonce = null, expectedSubject = current.subject)
        } else {
            PlanIdentity(current.subject, current.email, current.planType, null)
        }
        val account = OpenAiPlanAccount(current.accountId, identity.email ?: current.email ?: "ChatGPT", identity.planType ?: current.planType)
        return recordFrom(
            tokens = OAuthTokens(
                tokens.accessToken,
                tokens.refreshToken ?: current.refreshToken,
                tokens.idToken ?: current.idToken,
                tokens.expiresInSeconds,
                scopes,
            ),
            identity = identity,
            clientId = current.clientId,
            account = account,
            models = OpenAiPlanProtocol.decodeModels(current.modelsJson),
            selectedModel = current.selectedModel,
            previous = current,
        )
    }

    private suspend fun loadModels(authorization: OpenAiPlanAuthorization): List<OpenAiPlanModel> {
        val loaded = try {
            OpenAiPlanProtocol.parseModels(getAuthorized(OpenAiPlanProtocol.modelsUrl(apiRoot), authorization))
                .filter { it.slug.startsWith("gpt-") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        return loaded.ifEmpty { OpenAiPlanProtocol.FALLBACK_MODELS }
    }

    private suspend fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): OAuthTokens {
        val response = postForm(
            OpenAiPlanProtocol.oauthTokenUrl(authBaseUrl),
            OpenAiPlanProtocol.authorizationForm(clientId, code, verifier, redirectUri),
        )
        if (response.code !in 200..299) throw OpenAiPlanException("OpenAI sign-in was rejected (HTTP ${response.code}).")
        return OpenAiPlanProtocol.parseOAuthTokens(response.body)
    }

    private suspend fun revoke(clientId: String, refreshToken: String): Boolean {
        val configuration = getJson(OpenAiPlanProtocol.openIdConfigurationUrl(authBaseUrl))
        val revokeUrl = configuration.optString("revocation_endpoint").takeIf { it.isNotBlank() }
            ?: throw OpenAiPlanException("OpenAI did not provide a sign-out endpoint.")
        val response = postForm(revokeUrl, OpenAiPlanProtocol.revokeForm(clientId, refreshToken))
        Log.i(TAG, "OpenAI plan revoke status=${response.code}")
        return response.code in 200..299
    }

    private fun recordFrom(
        tokens: OAuthTokens,
        identity: PlanIdentity,
        clientId: String,
        account: OpenAiPlanAccount,
        models: List<OpenAiPlanModel>,
        selectedModel: String?,
        previous: OpenAiPlanRecord?,
    ): OpenAiPlanRecord {
        val expiry = tokens.expiresInSeconds?.let { nowMs() + it * 1000L }
            ?: identity.expiresAtEpochSeconds?.times(1000L)
            ?: nowMs() + 60 * 60 * 1000L
        return OpenAiPlanRecord(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: previous?.refreshToken
                ?: throw OpenAiPlanException("OpenAI did not return a renewable login. Try again."),
            idToken = tokens.idToken ?: previous?.idToken,
            accessExpiryEpochMs = expiry,
            clientId = clientId,
            subject = identity.subject,
            scopes = tokens.scope ?: previous?.scopes.orEmpty(),
            accountId = account.id,
            email = identity.email ?: previous?.email,
            planType = identity.planType ?: previous?.planType,
            fedramp = false,
            accountsJson = OpenAiPlanProtocol.encodeAccounts(listOf(account)),
            modelsJson = OpenAiPlanProtocol.encodeModels(models),
            selectedModel = selectedModel,
        )
    }

    private suspend fun getAuthorized(url: String, authorization: OpenAiPlanAuthorization): String {
        val response = send(
            Request.Builder()
                .url(allowed(url))
                .header("Authorization", "Bearer ${authorization.accessToken}")
                .header("Accept", "application/json")
                .get()
                .build(),
        )
        if (response.code !in 200..299) throw OpenAiPlanException("OpenAI did not return account details (HTTP ${response.code}).")
        return response.body
    }

    private suspend fun getJson(url: String): JSONObject {
        val response = send(Request.Builder().url(allowed(url)).get().build())
        if (response.code !in 200..299) throw OpenAiPlanException("Could not contact OpenAI (HTTP ${response.code}).")
        return runCatching { JSONObject(response.body) }.getOrElse { throw OpenAiPlanException("OpenAI returned an invalid response.") }
    }

    private suspend fun postForm(url: String, encodedForm: String) =
        send(Request.Builder().url(allowed(url)).post(encodedForm.toRequestBody(FORM)).build())

    private suspend fun send(request: Request) = http.newCall(request).awaitResponseBody()

    private fun allowed(raw: String): String {
        val url = raw.toHttpUrl()
        val auth = authBaseUrl.toHttpUrl()
        val api = apiRoot.toHttpUrl()
        val localhost = url.host == "127.0.0.1" || url.host == "localhost"
        val knownHost = url.host == auth.host || url.host == api.host
        val secure = url.scheme == "https" || (url.scheme == "http" && localhost)
        if (!knownHost || !secure) throw OpenAiPlanException("Refusing to contact an unknown OpenAI address.")
        return url.toString()
    }

    private fun snapshot(record: OpenAiPlanRecord) = OpenAiPlanSnapshot(
        email = record.email,
        planType = record.planType,
        accounts = OpenAiPlanProtocol.decodeAccounts(record.accountsJson),
        selectedAccountId = record.accountId,
        models = OpenAiPlanProtocol.decodeModels(record.modelsJson).ifEmpty { OpenAiPlanProtocol.FALLBACK_MODELS },
        selectedModel = record.selectedModel,
    )

    private fun resolveIssuedClientId(requested: String, returned: String?): String {
        if (requested == OpenAiPlanProtocol.DYNAMIC_CLIENT_ID) {
            return returned?.takeIf(::validClientId)
                ?: throw OpenAiPlanException("OpenAI did not register this app. Try again.")
        }
        if (returned != null && !constantTimeEquals(requested, returned)) {
            throw OpenAiPlanException("OpenAI returned a different app registration. Start again.")
        }
        return requested.takeIf(::validClientId) ?: throw OpenAiPlanException("The saved OpenAI registration is invalid.")
    }

    private fun requirePlanScope(scopes: String) {
        if (OpenAiPlanProtocol.REQUIRED_SCOPE !in scopes.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            throw OpenAiPlanException("ChatGPT plan access was not approved. Sign in again and allow plan usage.")
        }
    }

    private fun isTerminalRefreshError(body: String): Boolean {
        val code = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }

    private fun accountKey(subject: String): String =
        "sub_" + base64Url(MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))).take(32)

    private fun validClientId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    companion object {
        private const val TAG = "CoatiPlan"
        private const val EXPIRY_SKEW_MS = 60_000L
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
        private val sessionLock = Mutex()

        fun create(context: Context, client: OkHttpClient, configStore: bot.coati.data.ConfigStore): OpenAiPlanAuth {
            val store = try {
                SecureOpenAiPlanTokenStore.get(context)
            } catch (error: Exception) {
                Log.e(TAG, "OpenAI plan storage unavailable: ${error.javaClass.simpleName}")
                UnavailablePlanStore
            }
            return OpenAiPlanAuth(store, client, onClearPlan = { configStore.clearOpenAiPlanUse() })
        }

        fun signedIn(context: Context): Boolean =
            runCatching { SecureOpenAiPlanTokenStore.get(context).read() != null }.getOrDefault(false)
    }
}

private object UnavailablePlanStore : OpenAiPlanTokenStore {
    override fun read(): OpenAiPlanRecord? =
        throw OpenAiPlanException("This phone could not open secure storage for the OpenAI login.")

    override fun write(record: OpenAiPlanRecord): Unit =
        throw OpenAiPlanException("This phone could not open secure storage for the OpenAI login.")

    override fun clear() = Unit

    override fun readRegistration(): OpenAiPlanRegistration? = null

    override fun getOrCreateHostId(): String =
        throw OpenAiPlanException("This phone could not open secure storage for the OpenAI login.")
}
