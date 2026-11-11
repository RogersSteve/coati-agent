package bot.coati.model.openai

import bot.coati.model.awaitResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal interface OpenAiIdTokenVerifier {
    suspend fun verify(
        idToken: String,
        clientId: String,
        nonce: String?,
        expectedSubject: String? = null,
    ): PlanIdentity
}

internal class JwksOpenAiIdTokenVerifier(
    private val client: OkHttpClient,
    private val authBaseUrl: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OpenAiIdTokenVerifier {
    override suspend fun verify(
        idToken: String,
        clientId: String,
        nonce: String?,
        expectedSubject: String?,
    ): PlanIdentity {
        val parts = idToken.split('.')
        if (parts.size != 3) throw invalidLogin()
        val header = decodeObject(parts[0])
        val payload = decodeObject(parts[1])
        if (header.optString("alg") != "RS256") throw invalidLogin()
        val keyId = header.optString("kid").takeIf { it.isNotBlank() } ?: throw invalidLogin()

        val configuration = getJson(OpenAiPlanProtocol.openIdConfigurationUrl(authBaseUrl))
        val issuer = configuration.optString("issuer").takeIf { it.isNotBlank() } ?: throw invalidLogin()
        if (issuer != authBaseUrl.trimEnd('/')) throw invalidLogin()
        val jwksUrl = configuration.optString("jwks_uri").takeIf { it.isNotBlank() } ?: throw invalidLogin()
        val keys = getJson(jwksUrl).optJSONArray("keys") ?: throw invalidLogin()
        val key = findKey(keys, keyId) ?: throw invalidLogin()
        verifySignature(parts[0], parts[1], parts[2], key)

        if (payload.optString("iss") != issuer) throw invalidLogin()
        if (!audienceContains(payload.opt("aud"), clientId)) throw invalidLogin()
        val nowSeconds = nowMs() / 1000L
        if (!payload.has("exp") || !payload.has("iat")) throw invalidLogin()
        val expiry = payload.optLong("exp", 0L)
        if (expiry <= nowSeconds - CLOCK_SKEW_SECONDS) throw OpenAiPlanException("The OpenAI login expired. Sign in again.")
        val issuedAt = payload.optLong("iat", 0L)
        if (issuedAt > nowSeconds + CLOCK_SKEW_SECONDS) throw invalidLogin()
        if (nonce != null && !constantTimeEquals(payload.optString("nonce"), nonce)) throw invalidLogin()

        val subject = payload.optString("sub").takeIf { it.isNotBlank() && it.length <= 256 } ?: throw invalidLogin()
        if (payload.has("sid") && payload.optString("sid").isBlank()) throw invalidLogin()
        if (expectedSubject != null && !constantTimeEquals(subject, expectedSubject)) {
            throw OpenAiPlanException("OpenAI returned a different account. Sign in again and choose the expected account.")
        }
        val profile = payload.optJSONObject("https://api.openai.com/profile")
        val auth = payload.optJSONObject("https://api.openai.com/auth")
        val email = payload.optString("email").ifBlank { null }
            ?: profile?.optString("email")?.ifBlank { null }
        val plan = auth?.optString("chatgpt_plan_type")?.ifBlank { null }
        return PlanIdentity(subject, email, plan, expiry)
    }

    private suspend fun getJson(rawUrl: String): JSONObject {
        val url = rawUrl.toHttpUrl()
        val auth = authBaseUrl.toHttpUrl()
        val localhost = url.host == "127.0.0.1" || url.host == "localhost"
        if (url.host != auth.host || (url.scheme != "https" && !(url.scheme == "http" && localhost))) {
            throw OpenAiPlanException("OpenAI returned an unsafe login address.")
        }
        val response = client.newCall(Request.Builder().url(url).get().build()).awaitResponseBody()
        if (!response.isSuccessful) throw OpenAiPlanException("Could not verify the OpenAI login (HTTP ${response.code}).")
        return runCatching { JSONObject(response.body) }.getOrElse { throw invalidLogin() }
    }

    private fun findKey(keys: JSONArray, keyId: String): JSONObject? {
        for (index in 0 until keys.length()) {
            val key = keys.optJSONObject(index) ?: continue
            if (key.optString("kid") == keyId && key.optString("kty") == "RSA" && key.optString("use").let { it.isBlank() || it == "sig" }) {
                return key
            }
        }
        return null
    }

    private fun verifySignature(header: String, payload: String, encodedSignature: String, jwk: JSONObject) {
        val valid = runCatching {
            val modulus = decodeUnsigned(jwk.optString("n"))
            val exponent = decodeUnsigned(jwk.optString("e"))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update("$header.$payload".toByteArray(Charsets.US_ASCII))
            verifier.verify(decode(encodedSignature))
        }.getOrDefault(false)
        if (!valid) throw invalidLogin()
    }

    private fun decodeObject(encoded: String): JSONObject =
        runCatching { JSONObject(String(decode(encoded), Charsets.UTF_8)) }.getOrElse { throw invalidLogin() }

    private fun decodeUnsigned(encoded: String): BigInteger = BigInteger(1, decode(encoded))

    private fun decode(encoded: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrElse { throw invalidLogin() }

    private fun audienceContains(raw: Any?, clientId: String): Boolean = when (raw) {
        is String -> constantTimeEquals(raw, clientId)
        is JSONArray -> (0 until raw.length()).any { constantTimeEquals(raw.optString(it), clientId) }
        else -> false
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun invalidLogin() = OpenAiPlanException("OpenAI returned an invalid login. Try again.")

    companion object {
        private const val CLOCK_SKEW_SECONDS = 60L
    }
}
