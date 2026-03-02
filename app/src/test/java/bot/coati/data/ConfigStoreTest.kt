package bot.coati.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStoreTest {

    @Test
    fun `maskedKey masks all but last 4 chars`() {
        val key = "sk-abcdefghijklmnop1234"
        val masked = maskKey(key)
        assertEquals("****1234", masked)
    }

    @Test
    fun `maskedKey handles short key`() {
        val masked = maskKey("abc")
        assertEquals("****", masked)
    }

    @Test
    fun `maskedKey handles exactly 4 char key`() {
        val masked = maskKey("abcd")
        assertEquals("****", masked)
    }

    @Test
    fun `maskedKey handles empty string`() {
        val masked = maskKey("")
        assertEquals("****", masked)
    }

    @Test
    fun `maskedKey handles 5 char key`() {
        val masked = maskKey("abcde")
        assertEquals("****bcde", masked)
    }

    @Test
    fun `supported providers list has 6 entries`() {
        val providers = ConfigStore.SUPPORTED_PROVIDERS
        assertEquals(6, providers.size)
    }

    @Test
    fun `supported providers contains all expected providers`() {
        val providers = ConfigStore.SUPPORTED_PROVIDERS
        assertTrue(providers.contains("gemini"))
        assertTrue(providers.contains("claude"))
        assertTrue(providers.contains("openai"))
        assertTrue(providers.contains("deepseek"))
        assertTrue(providers.contains("openai_compatible"))
        assertTrue(providers.contains("claude_compatible"))
    }

    @Test
    fun `supported providers does not contain legacy custom`() {
        val providers = ConfigStore.SUPPORTED_PROVIDERS
        assertFalse(providers.contains("custom"))
    }

    // ─── Role-based config key tests ───

    @Test
    fun `role key generation produces correct prefixed keys`() {
        assertEquals("reasoning_api_key", ConfigStore.roleApiKeyPrefKey("reasoning"))
        assertEquals("reasoning_provider", ConfigStore.roleProviderPrefKey("reasoning"))
        assertEquals("reasoning_endpoint", ConfigStore.roleEndpointPrefKey("reasoning"))
        assertEquals("reasoning_model_name", ConfigStore.roleModelNamePrefKey("reasoning"))

        assertEquals("fast_api_key", ConfigStore.roleApiKeyPrefKey("fast"))
        assertEquals("fast_provider", ConfigStore.roleProviderPrefKey("fast"))
        assertEquals("fast_endpoint", ConfigStore.roleEndpointPrefKey("fast"))
        assertEquals("fast_model_name", ConfigStore.roleModelNamePrefKey("fast"))
    }

    @Test
    fun `supported roles contains reasoning and fast`() {
        val roles = ConfigStore.SUPPORTED_ROLES
        assertEquals(2, roles.size)
        assertTrue(roles.contains(ConfigStore.ROLE_REASONING))
        assertTrue(roles.contains(ConfigStore.ROLE_FAST))
    }

    @Test
    fun `ROLE constants have expected values`() {
        assertEquals("reasoning", ConfigStore.ROLE_REASONING)
        assertEquals("fast", ConfigStore.ROLE_FAST)
    }

    // Replicates the masking logic from ConfigStore for unit testing
    // without needing Android context
    private fun maskKey(key: String): String {
        if (key.length <= 4) return "****"
        return "****${key.takeLast(4)}"
    }
}
