package bot.coati.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import bot.coati.model.ModelProviders

data class ProviderConfig(
    val providerId: String,
    val apiKey: String,
    val endpoint: String,
    val modelName: String,
)

class ConfigStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        migrateCustomProvider()
    }

    fun saveApiKey(provider: String, key: String) {
        require(provider in SUPPORTED_PROVIDERS) { "Unsupported provider: $provider" }
        prefs.edit().putString(apiKeyPrefKey(provider), key).apply()
        Log.i(TAG, "API key saved for provider: $provider (${maskedKey(provider)})")
    }

    fun getApiKey(provider: String): String? {
        return prefs.getString(apiKeyPrefKey(provider), null)
    }

    fun getDefaultProvider(): String {
        return prefs.getString(KEY_DEFAULT_PROVIDER, "gemini") ?: "gemini"
    }

    fun setDefaultProvider(provider: String) {
        require(provider in SUPPORTED_PROVIDERS) { "Unsupported provider: $provider" }
        prefs.edit().putString(KEY_DEFAULT_PROVIDER, provider).apply()
    }

    fun saveEndpoint(provider: String, endpoint: String) {
        prefs.edit().putString(endpointPrefKey(provider), endpoint).apply()
    }

    fun getEndpoint(provider: String): String? {
        return prefs.getString(endpointPrefKey(provider), null)
    }

    fun saveModelName(provider: String, modelName: String) {
        prefs.edit().putString(modelNamePrefKey(provider), modelName).apply()
    }

    fun getModelName(provider: String): String? {
        return prefs.getString(modelNamePrefKey(provider), null)
    }

    fun getActiveProviderConfig(): ProviderConfig? {
        val providerId = getDefaultProvider()
        val apiKey = getApiKey(providerId) ?: return null
        val provider = ModelProviders.findById(providerId) ?: return null
        val endpoint = getEndpoint(providerId) ?: provider.defaultEndpoint
        val modelName = getModelName(providerId) ?: provider.models.firstOrNull() ?: return null
        return ProviderConfig(providerId, apiKey, endpoint, modelName)
    }

    fun maskedKey(provider: String): String {
        val key = getApiKey(provider) ?: return ""
        if (key.length <= 4) return "****"
        return "****${key.takeLast(4)}"
    }

    private fun migrateCustomProvider() {
        val currentDefault = prefs.getString(KEY_DEFAULT_PROVIDER, null)
        if (currentDefault == "custom") {
            prefs.edit().putString(KEY_DEFAULT_PROVIDER, "openai_compatible").apply()
            // Migrate stored API key
            val customKey = prefs.getString(apiKeyPrefKey("custom"), null)
            if (customKey != null) {
                prefs.edit().putString(apiKeyPrefKey("openai_compatible"), customKey).apply()
            }
            Log.i(TAG, "Migrated 'custom' provider to 'openai_compatible'")
        }
    }

    companion object {
        private const val TAG = "CoatiConfig"
        private const val PREFS_NAME = "coati_secure_prefs"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"

        val SUPPORTED_PROVIDERS = listOf(
            "gemini", "openai", "claude", "deepseek", "openai_compatible", "claude_compatible",
        )

        private fun apiKeyPrefKey(provider: String) = "api_key_$provider"
        private fun endpointPrefKey(provider: String) = "endpoint_$provider"
        private fun modelNamePrefKey(provider: String) = "model_name_$provider"
    }
}
