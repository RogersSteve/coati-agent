package bot.coati.ui.onboarding

import android.app.Application
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bot.coati.data.ConfigStore
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.model.userFacingConnectionError
import bot.coati.util.RegionDetector
import bot.coati.ui.settings.JevConfigurationController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<Preferences>,
    private val configStore: ConfigStore,
    private val okHttpClient: OkHttpClient,
) : AndroidViewModel(application) {

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep

    private val _recoveryMode = MutableStateFlow(false)
    val recoveryMode: StateFlow<Boolean> = _recoveryMode

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _accessibilityGranted = MutableStateFlow(false)
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted

    private val _selectedRegion = MutableStateFlow("global")
    val selectedRegion: StateFlow<String> = _selectedRegion

    private val _selectedProvider = MutableStateFlow("gemini")
    val selectedProvider: StateFlow<String> = _selectedProvider

    private val _selectedEndpoint = MutableStateFlow("")
    val selectedEndpoint: StateFlow<String> = _selectedEndpoint

    private val _selectedModelName = MutableStateFlow("")
    val selectedModelName: StateFlow<String> = _selectedModelName

    private val _reasoningApiKeyInput = MutableStateFlow("")
    val reasoningApiKeyInput: StateFlow<String> = _reasoningApiKeyInput

    private val _apiKeyVerified = MutableStateFlow(false)
    val apiKeyVerified: StateFlow<Boolean> = _apiKeyVerified

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying

    private val _verifyError = MutableStateFlow<String?>(null)
    val verifyError: StateFlow<String?> = _verifyError

    private var verifyJob: Job? = null
    private var fastVerifyJob: Job? = null

    val jevEditor = JevConfigurationController(application, configStore, okHttpClient, viewModelScope)

    // Tab selection (0 = Reasoning, 1 = Fast, 2 = optional Jev)
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    // Fast model state
    private val _fastProvider = MutableStateFlow("gemini")
    val fastProvider: StateFlow<String> = _fastProvider

    private val _fastEndpoint = MutableStateFlow("")
    val fastEndpoint: StateFlow<String> = _fastEndpoint

    private val _fastModelName = MutableStateFlow("")
    val fastModelName: StateFlow<String> = _fastModelName

    private val _fastApiKeyInput = MutableStateFlow("")
    val fastApiKeyInput: StateFlow<String> = _fastApiKeyInput

    private val _fastApiKeyVerified = MutableStateFlow(false)
    val fastApiKeyVerified: StateFlow<Boolean> = _fastApiKeyVerified

    private val _fastVerifying = MutableStateFlow(false)
    val fastVerifying: StateFlow<Boolean> = _fastVerifying

    private val _fastVerifyError = MutableStateFlow<String?>(null)
    val fastVerifyError: StateFlow<String?> = _fastVerifyError

    private val _fastSameAsReasoning = MutableStateFlow(true)
    val fastSameAsReasoning: StateFlow<Boolean> = _fastSameAsReasoning

    private var independentFastProvider = "gemini"
    private var independentFastEndpoint = ""
    private var independentFastModelName = ""

    init {
        _selectedRegion.value = RegionDetector.detectRegion(application)
        val defaultProvider = configStore.getDefaultProvider()
        _selectedProvider.value = defaultProvider
        val provider = ModelProviders.findById(defaultProvider)
        if (provider != null) {
            _selectedEndpoint.value = configStore.getEndpoint(defaultProvider) ?: provider.defaultEndpoint
            _selectedModelName.value = configStore.getModelName(defaultProvider) ?: provider.models.firstOrNull() ?: ""
        }
        _fastSameAsReasoning.value = configStore.isFastSameAsReasoning()
        val savedIndependentFast = configStore.getRoleConfig(ConfigStore.ROLE_FAST)
        independentFastProvider = savedIndependentFast?.providerId ?: defaultProvider
        val independentProvider = ModelProviders.findById(independentFastProvider)
        independentFastEndpoint = savedIndependentFast?.endpoint
            ?: independentProvider?.defaultEndpoint.orEmpty()
        independentFastModelName = savedIndependentFast?.modelName
            ?: independentProvider?.models?.getOrNull(1)
            ?: independentProvider?.models?.firstOrNull().orEmpty()
        if (_fastSameAsReasoning.value) {
            syncSharedFastModel(defaultProvider)
        } else {
            restoreIndependentFastDraft()
        }
    }

    fun nextStep() {
        _currentStep.value = (_currentStep.value + 1).coerceAtMost(5)
    }

    /** Start directly at the A11y step, skipping all other steps. */
    fun initA11yRecovery() {
        _recoveryMode.value = true
        _currentStep.value = 2
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _overlayGranted.value = Settings.canDrawOverlays(context)
        _accessibilityGranted.value = hasAccessibilityPermission(context)
    }

    fun selectRegion(region: String) {
        _selectedRegion.value = region
    }

    fun selectProvider(providerId: String) {
        if (_selectedProvider.value == providerId) return
        resetVerification()
        _reasoningApiKeyInput.value = ""
        _selectedProvider.value = providerId
        val provider = ModelProviders.findById(providerId)
        if (provider != null) {
            _selectedEndpoint.value = provider.defaultEndpoint
            _selectedModelName.value = provider.models.firstOrNull() ?: ""
        } else {
            _selectedEndpoint.value = ""
            _selectedModelName.value = ""
        }
        if (_fastSameAsReasoning.value) syncSharedFastModel(providerId)
    }

    fun updateEndpoint(endpoint: String) {
        resetVerification()
        _selectedEndpoint.value = endpoint
    }

    fun updateModelName(modelName: String) {
        resetVerification()
        _selectedModelName.value = modelName
    }

    fun updateReasoningApiKey(key: String) {
        if (_reasoningApiKeyInput.value == key) return
        _reasoningApiKeyInput.value = key
        resetVerification()
    }

    fun resetVerification() {
        verifyJob?.cancel()
        verifyJob = null
        _verifying.value = false
        _apiKeyVerified.value = false
        _verifyError.value = null
        if (_fastSameAsReasoning.value) resetFastVerification()
    }

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setFastSameAsReasoning(same: Boolean) {
        if (_fastSameAsReasoning.value == same) return
        if (same) rememberIndependentFastDraft()
        _fastSameAsReasoning.value = same
        resetFastVerification()
        if (same) {
            syncSharedFastModel(_selectedProvider.value)
        } else {
            restoreIndependentFastDraft()
        }
    }

    fun selectFastProvider(providerId: String) {
        if (_fastProvider.value == providerId) return
        resetFastVerification()
        _fastApiKeyInput.value = ""
        _fastProvider.value = providerId
        val provider = ModelProviders.findById(providerId)
        if (provider != null) {
            _fastEndpoint.value = provider.defaultEndpoint
            _fastModelName.value = provider.models.firstOrNull() ?: ""
        } else {
            _fastEndpoint.value = ""
            _fastModelName.value = ""
        }
        rememberIndependentFastDraft()
    }

    fun updateFastEndpoint(endpoint: String) {
        resetFastVerification()
        _fastEndpoint.value = endpoint
        if (!_fastSameAsReasoning.value) rememberIndependentFastDraft()
    }

    fun updateFastModelName(modelName: String) {
        resetFastVerification()
        _fastModelName.value = modelName
        if (!_fastSameAsReasoning.value) rememberIndependentFastDraft()
    }

    fun updateFastApiKey(key: String) {
        if (_fastApiKeyInput.value == key) return
        _fastApiKeyInput.value = key
        resetFastVerification()
    }

    fun resetFastVerification() {
        fastVerifyJob?.cancel()
        fastVerifyJob = null
        _fastVerifying.value = false
        _fastApiKeyVerified.value = false
        _fastVerifyError.value = null
    }

    private fun syncSharedFastModel(providerId: String) {
        val provider = ModelProviders.findById(providerId)
        val compatibleModel = _fastModelName.value.takeIf { it in (provider?.models ?: emptyList()) }
            ?: provider?.models?.getOrNull(1)
            ?: provider?.models?.firstOrNull()
            ?: _selectedModelName.value
        _fastProvider.value = providerId
        _fastEndpoint.value = _selectedEndpoint.value
        _fastModelName.value = compatibleModel
        resetFastVerification()
    }

    private fun rememberIndependentFastDraft() {
        independentFastProvider = _fastProvider.value
        independentFastEndpoint = _fastEndpoint.value
        independentFastModelName = _fastModelName.value
    }

    private fun restoreIndependentFastDraft() {
        _fastProvider.value = independentFastProvider
        _fastEndpoint.value = independentFastEndpoint
        _fastModelName.value = independentFastModelName
    }

    fun verifySharedFastModel() {
        if (!_fastSameAsReasoning.value) return
        if (!_apiKeyVerified.value) {
            _fastVerifyError.value = "Verify the Reasoning model first"
            return
        }
        val reasoning = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
        if (reasoning == null) {
            _fastVerifyError.value = "Configure the Reasoning model first"
            return
        }
        val provider = ModelProviders.findById(reasoning.providerId)
        if (provider == null) {
            _fastVerifyError.value = "Unknown provider: ${reasoning.providerId}"
            return
        }
        val modelName = _fastModelName.value.trim()
        if (modelName.isBlank()) {
            _fastVerifyError.value = "Model name is required"
            return
        }

        _fastVerifying.value = true
        _fastVerifyError.value = null
        fastVerifyJob = viewModelScope.launch {
            try {
                val result = GatewayFactory.create(
                    provider,
                    okHttpClient,
                    reasoning.apiKey,
                    reasoning.endpoint,
                    modelName,
                ).testConnection()
                if (!isActive) return@launch
                if (result.isSuccess) {
                    configStore.saveFastSameAsReasoning(true)
                    configStore.saveFastModelName(modelName)
                    _fastApiKeyVerified.value = true
                    _fastVerifyError.value = null
                } else {
                    _fastVerifyError.value = userFacingConnectionError(result.exceptionOrNull())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) _fastVerifyError.value = userFacingConnectionError(e)
            } finally {
                if (isActive) _fastVerifying.value = false
            }
        }
    }

    fun verifyFastApiKey() {
        if (_fastSameAsReasoning.value) {
            verifySharedFastModel()
            return
        }
        val provider = _fastProvider.value
        val key = _fastApiKeyInput.value.trim()
        if (key.isBlank()) return
        val endpoint = _fastEndpoint.value
        val modelName = _fastModelName.value
        if (endpoint.isBlank() || modelName.isBlank()) {
            _fastVerifyError.value = "Endpoint and model name are required"
            return
        }
        _fastVerifying.value = true
        _fastVerifyError.value = null

        fastVerifyJob = viewModelScope.launch {
            try {
                val modelProvider = ModelProviders.findById(provider)
                if (modelProvider == null) {
                    _fastVerifyError.value = "Unknown provider: $provider"
                    _fastVerifying.value = false
                    return@launch
                }
                val gateway = GatewayFactory.create(modelProvider, okHttpClient, key, endpoint, modelName)
                val result = gateway.testConnection()
                if (!isActive) return@launch  // cancelled while network was in-flight
                if (result.isSuccess) {
                    configStore.saveRoleConfig(ConfigStore.ROLE_FAST, provider, key, endpoint, modelName)
                    configStore.saveFastSameAsReasoning(false)
                    _fastApiKeyVerified.value = true
                    _fastVerifyError.value = null
                    _fastApiKeyInput.value = ""
                } else {
                    _fastVerifyError.value = userFacingConnectionError(result.exceptionOrNull())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) _fastVerifyError.value = userFacingConnectionError(e)
            } finally {
                if (isActive) _fastVerifying.value = false
            }
        }
    }

    fun verifyApiKey() {
        val provider = _selectedProvider.value
        val key = _reasoningApiKeyInput.value.trim()
        if (key.isBlank()) return
        val endpoint = _selectedEndpoint.value
        val modelName = _selectedModelName.value
        if (endpoint.isBlank() || modelName.isBlank()) {
            _verifyError.value = "Endpoint and model name are required"
            return
        }
        _verifying.value = true
        _verifyError.value = null

        verifyJob = viewModelScope.launch {
            try {
                val modelProvider = ModelProviders.findById(provider)
                if (modelProvider == null) {
                    _verifyError.value = "Unknown provider: $provider"
                    _verifying.value = false
                    return@launch
                }
                val gateway = GatewayFactory.create(modelProvider, okHttpClient, key, endpoint, modelName)
                val result = gateway.testConnection()
                if (!isActive) return@launch  // cancelled while network was in-flight
                if (result.isSuccess) {
                    configStore.saveApiKey(provider, key)
                    configStore.saveEndpoint(provider, endpoint)
                    configStore.saveModelName(provider, modelName)
                    configStore.setDefaultProvider(provider)
                    // Also save as reasoning role config
                    configStore.saveRoleConfig(ConfigStore.ROLE_REASONING, provider, key, endpoint, modelName)
                    _apiKeyVerified.value = true
                    _verifyError.value = null
                    _reasoningApiKeyInput.value = ""
                    if (_fastSameAsReasoning.value) syncSharedFastModel(provider)
                } else {
                    _verifyError.value = userFacingConnectionError(result.exceptionOrNull())
                }
            } catch (e: CancellationException) {
                throw e  // must re-throw so coroutine machinery cleans up correctly
            } catch (e: Exception) {
                if (isActive) _verifyError.value = userFacingConnectionError(e)
            } finally {
                if (isActive) _verifying.value = false
            }
        }
    }

    fun finishOnboarding(onComplete: () -> Unit) {
        if (!_apiKeyVerified.value || !_fastApiKeyVerified.value) {
            _currentStep.value = 4
            _selectedTab.value = if (_apiKeyVerified.value) 1 else 0
            return
        }
        viewModelScope.launch {
            // Save fast model settings
            configStore.saveFastSameAsReasoning(_fastSameAsReasoning.value)
            if (_fastSameAsReasoning.value) {
                // Save fast model name so getFastConfig() can substitute it
                configStore.saveFastModelName(_fastModelName.value)
            }

            dataStore.edit { prefs ->
                prefs[KEY_ONBOARDING_COMPLETE] = true
                prefs[KEY_REGION] = _selectedRegion.value
            }
            onComplete()
        }
    }

    companion object {
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_REGION = stringPreferencesKey("region")

        fun hasAccessibilityPermission(context: android.content.Context): Boolean {
            val component = android.content.ComponentName(context, bot.coati.service.CoatiAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.contains(component.flattenToString()) || enabled.contains(component.flattenToShortString())
        }
    }
}
