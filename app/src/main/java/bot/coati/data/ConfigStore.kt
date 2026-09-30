package bot.coati.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import bot.coati.experience.ManualMode
import bot.coati.model.ModelProviders

data class ProviderConfig(
    val providerId: String,
    val apiKey: String,
    val endpoint: String,
    val modelName: String,
)

enum class SafetyMode { CAUTIOUS, STANDARD, TRUST }

/** Optional JEV fast executor. Without it, the Reasoning model decides every step. */
data class JevConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
)

class ConfigStore(context: Context) {

    private val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
        migrateToRoleConfig()
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

    // ─── SAFETY MODE ───

    fun getSafetyMode(): SafetyMode {
        val name = prefs.getString(KEY_SAFETY_MODE, SafetyMode.STANDARD.name) ?: SafetyMode.STANDARD.name
        return try {
            SafetyMode.valueOf(name)
        } catch (_: IllegalArgumentException) {
            SafetyMode.STANDARD
        }
    }

    fun setSafetyMode(mode: SafetyMode) {
        prefs.edit().putString(KEY_SAFETY_MODE, mode.name).apply()
    }

    fun getManualMode(): ManualMode {
        val name = prefs.getString(KEY_MANUAL_MODE, ManualMode.OFF.name) ?: ManualMode.OFF.name
        return resolveManualMode(name, isDebuggable)
    }

    fun setManualMode(mode: ManualMode) {
        prefs.edit().putString(KEY_MANUAL_MODE, mode.name).apply()
        Log.i(TAG, "App manual mode set to $mode")
    }

    // ─── JEV FAST EXECUTOR (optional) ───

    fun saveJevConfig(apiKey: String, endpoint: String, model: String) {
        prefs.edit()
            .putString(KEY_JEV_API_KEY, apiKey)
            .putString(KEY_JEV_ENDPOINT, endpoint)
            .putString(KEY_JEV_MODEL, model)
            .apply()
        Log.i(TAG, "JEV config saved: model=$model")
    }

    fun getJevConfig(): JevConfig? {
        val apiKey = prefs.getString(KEY_JEV_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return JevConfig(
            apiKey = apiKey,
            endpoint = prefs.getString(KEY_JEV_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_JEV_ENDPOINT,
            model = prefs.getString(KEY_JEV_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_JEV_MODEL,
        )
    }

    /** Debug/benchmark switch: GPU OCR in the JEV fast path (on by default where a GPU exists). */
    fun isOcrEnabled(): Boolean = prefs.getBoolean(KEY_OCR_ENABLED, true)

    fun setOcrEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OCR_ENABLED, enabled).apply()
    }

    fun clearJevConfig() {
        prefs.edit().remove(KEY_JEV_API_KEY).apply()
        Log.i(TAG, "JEV config cleared")
    }

    // ─── ROLE-BASED CONFIG (Reasoning / Fast) ───

    fun saveRoleConfig(role: String, providerId: String, apiKey: String, endpoint: String, modelName: String) {
        require(role in SUPPORTED_ROLES) { "Unsupported role: $role" }
        prefs.edit()
            .putString(roleApiKeyPrefKey(role), apiKey)
            .putString(roleProviderPrefKey(role), providerId)
            .putString(roleEndpointPrefKey(role), endpoint)
            .putString(roleModelNamePrefKey(role), modelName)
            .apply()
        Log.i(TAG, "Role config saved: role=$role, provider=$providerId, model=$modelName")
    }

    fun getRoleConfig(role: String): ProviderConfig? {
        require(role in SUPPORTED_ROLES) { "Unsupported role: $role" }
        val providerId = prefs.getString(roleProviderPrefKey(role), null) ?: return null
        val apiKey = prefs.getString(roleApiKeyPrefKey(role), null) ?: return null
        val endpoint = prefs.getString(roleEndpointPrefKey(role), null) ?: ""
        val modelName = prefs.getString(roleModelNamePrefKey(role), null) ?: return null
        return ProviderConfig(providerId, apiKey, endpoint, modelName)
    }

    fun getReasoningConfig(): ProviderConfig? = getRoleConfig(ROLE_REASONING)

    fun getFastConfig(): ProviderConfig? {
        if (isFastSameAsReasoning()) {
            val reasoning = getReasoningConfig() ?: return null
            val fastModelName = prefs.getString(roleModelNamePrefKey(ROLE_FAST), null)
                ?: reasoning.modelName
            return reasoning.copy(modelName = fastModelName)
        }
        return getRoleConfig(ROLE_FAST)
    }

    fun saveFastSameAsReasoning(same: Boolean) {
        prefs.edit().putBoolean(KEY_FAST_SAME_AS_REASONING, same).apply()
    }

    fun isFastSameAsReasoning(): Boolean {
        return prefs.getBoolean(KEY_FAST_SAME_AS_REASONING, true)
    }

    fun saveFastModelName(modelName: String) {
        prefs.edit().putString(roleModelNamePrefKey(ROLE_FAST), modelName).apply()
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

    /** Migrate old single-provider config to reasoning role if reasoning config is absent. */
    private fun migrateToRoleConfig() {
        val hasReasoningConfig = prefs.getString(roleProviderPrefKey(ROLE_REASONING), null) != null
        if (hasReasoningConfig) return

        val oldConfig = getActiveProviderConfig() ?: return
        saveRoleConfig(ROLE_REASONING, oldConfig.providerId, oldConfig.apiKey, oldConfig.endpoint, oldConfig.modelName)
        Log.i(TAG, "Migrated single config to reasoning role: ${oldConfig.providerId}")
    }

    companion object {
        private const val TAG = "CoatiConfig"
        private const val PREFS_NAME = "coati_secure_prefs"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"
        private const val KEY_FAST_SAME_AS_REASONING = "fast_same_as_reasoning"
        private const val KEY_SAFETY_MODE = "safety_mode"
        private const val KEY_MANUAL_MODE = "app_manual_mode"
        private const val KEY_JEV_API_KEY = "jev_api_key"
        private const val KEY_JEV_ENDPOINT = "jev_endpoint"
        private const val KEY_JEV_MODEL = "jev_model"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        const val DEFAULT_JEV_ENDPOINT = "https://api.typesafe.ai"
        const val DEFAULT_JEV_MODEL = "jev-1.13.0"

        const val ROLE_REASONING = "reasoning"
        const val ROLE_FAST = "fast"

        internal fun resolveManualMode(name: String, isDebuggable: Boolean): ManualMode {
            val parsed = runCatching { ManualMode.valueOf(name) }.getOrDefault(ManualMode.OFF)
            return if (parsed == ManualMode.SHADOW && !isDebuggable) ManualMode.OFF else parsed
        }

        val SUPPORTED_ROLES = listOf(ROLE_REASONING, ROLE_FAST)

        val SUPPORTED_PROVIDERS = listOf(
            "gemini", "openai", "claude", "deepseek", "openai_compatible", "claude_compatible",
        )

        private fun apiKeyPrefKey(provider: String) = "api_key_$provider"
        private fun endpointPrefKey(provider: String) = "endpoint_$provider"
        private fun modelNamePrefKey(provider: String) = "model_name_$provider"

        fun roleApiKeyPrefKey(role: String) = "${role}_api_key"
        fun roleProviderPrefKey(role: String) = "${role}_provider"
        fun roleEndpointPrefKey(role: String) = "${role}_endpoint"
        fun roleModelNamePrefKey(role: String) = "${role}_model_name"
    }
}
