package bot.coati.model.openai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class OpenAiIdTokenVerifierTest {
    @Test
    fun `verifies signature issuer audience nonce expiry and subject`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val publicKey = keyPair.public as RSAPublicKey
            val jwk = JSONObject()
                .put("kty", "RSA")
                .put("use", "sig")
                .put("kid", "key-1")
                .put("n", unsigned(publicKey.modulus.toByteArray()))
                .put("e", unsigned(publicKey.publicExponent.toByteArray()))
            server.enqueue(json(JSONObject().put("issuer", base).put("jwks_uri", "$base/jwks").toString()))
            server.enqueue(json(JSONObject().put("keys", org.json.JSONArray().put(jwk)).toString()))

            val nowSeconds = 2_000_000_000L
            val token = jwt(
                keyPair.private,
                JSONObject()
                    .put("iss", base)
                    .put("aud", org.json.JSONArray().put("oaiapp_test"))
                    .put("sub", "subject-1")
                    .put("email", "dev@example.com")
                    .put("nonce", "nonce-1")
                    .put("iat", nowSeconds)
                    .put("exp", nowSeconds + 3_600),
            )
            val verifier = JwksOpenAiIdTokenVerifier(OkHttpClient(), base) { nowSeconds * 1000L }
            val identity = verifier.verify(token, "oaiapp_test", "nonce-1")
            assertEquals("subject-1", identity.subject)
            assertEquals("dev@example.com", identity.email)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects a nonce mismatch after validating the signature`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val publicKey = keyPair.public as RSAPublicKey
            val jwk = JSONObject()
                .put("kty", "RSA")
                .put("use", "sig")
                .put("kid", "key-1")
                .put("n", unsigned(publicKey.modulus.toByteArray()))
                .put("e", unsigned(publicKey.publicExponent.toByteArray()))
            server.enqueue(json(JSONObject().put("issuer", base).put("jwks_uri", "$base/jwks").toString()))
            server.enqueue(json(JSONObject().put("keys", org.json.JSONArray().put(jwk)).toString()))
            val nowSeconds = 2_000_000_000L
            val token = jwt(
                keyPair.private,
                JSONObject()
                    .put("iss", base)
                    .put("aud", "oaiapp_test")
                    .put("sub", "subject-1")
                    .put("nonce", "right-nonce")
                    .put("iat", nowSeconds)
                    .put("exp", nowSeconds + 3_600),
            )
            val verifier = JwksOpenAiIdTokenVerifier(OkHttpClient(), base) { nowSeconds * 1000L }
            val failure = runCatching { verifier.verify(token, "oaiapp_test", "wrong-nonce") }.exceptionOrNull()
            assertTrue(failure is OpenAiPlanException)
        } finally {
            server.shutdown()
        }
    }

    private fun jwt(privateKey: java.security.PrivateKey, payload: JSONObject): String {
        val header = JSONObject().put("alg", "RS256").put("kid", "key-1")
        val signingInput = "${encoded(header.toString().toByteArray())}.${encoded(payload.toString().toByteArray())}"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${encoded(signer.sign())}"
    }

    private fun unsigned(bytes: ByteArray): String = encoded(bytes.dropWhile { it == 0.toByte() }.toByteArray())

    private fun encoded(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
