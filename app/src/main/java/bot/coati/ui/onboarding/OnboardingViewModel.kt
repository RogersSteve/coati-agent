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
import bot.coati.util.RegionDetector
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

    private val _apiKeyVerified = MutableStateFlow(false)
    val apiKeyVerified: StateFlow<Boolean> = _apiKeyVerified

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying

    private val _verifyError = MutableStateFlow<String?>(null)
    val verifyError: StateFlow<String?> = _verifyError

    private var verifyJob: Job? = null
    private var fastVerifyJob: Job? = null

    // Tab selection (0 = Reasoning, 1 = Fast)
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    // Fast model state
    private val _fastProvider = MutableStateFlow("gemini")
    val fastProvider: StateFlow<String> = _fastProvider

    private val _fastEndpoint = MutableStateFlow("")
    val fastEndpoint: StateFlow<String> = _fastEndpoint

    private val _fastModelName = MutableStateFlow("")
    val fastModelName: StateFlow<String> = _fastModelName

    private val _fastApiKeyVerified = MutableStateFlow(false)
    val fastApiKeyVerified: StateFlow<Boolean> = _fastApiKeyVerified

    private val _fastVerifying = MutableStateFlow(false)
    val fastVerifying: StateFlow<Boolean> = _fastVerifying

    private val _fastVerifyError = MutableStateFlow<String?>(null)
    val fastVerifyError: StateFlow<String?> = _fastVerifyError

    private val _fastSameAsReasoning = MutableStateFlow(true)
    val fastSameAsReasoning: StateFlow<Boolean> = _fastSameAsReasoning

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
        // Default fast provider to same as reasoning
        _fastProvider.value = defaultProvider
        if (provider != null) {
            _fastEndpoint.value = provider.defaultEndpoint
            _fastModelName.value = provider.models.getOrNull(1) ?: provider.models.firstOrNull() ?: ""
        }
    }

    fun nextStep() {
        _currentStep.value = (_currentStep.value + 1).coerceAtMost(4)
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
        resetVerification()
        _selectedProvider.value = providerId
        val provider = ModelProviders.findById(providerId)
        if (provider != null) {
            _selectedEndpoint.value = provider.defaultEndpoint
            _selectedModelName.value = provider.models.firstOrNull() ?: ""
        } else {
            _selectedEndpoint.value = ""
            _selectedModelName.value = ""
        }
    }

    fun updateEndpoint(endpoint: String) {
        resetVerification()
        _selectedEndpoint.value = endpoint
    }

    fun updateModelName(modelName: String) {
        resetVerification()
        _selectedModelName.value = modelName
    }

    fun resetVerification() {
        verifyJob?.cancel()
        verifyJob = null
        _verifying.value = false
        _apiKeyVerified.value = false
        _verifyError.value = null
    }

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setFastSameAsReasoning(same: Boolean) {
        _fastSameAsReasoning.value = same
    }

    fun selectFastProvider(providerId: String) {
        resetFastVerification()
        _fastProvider.value = providerId
        val provider = ModelProviders.findById(providerId)
        if (provider != null) {
            _fastEndpoint.value = provider.defaultEndpoint
            _fastModelName.value = provider.models.firstOrNull() ?: ""
        } else {
            _fastEndpoint.value = ""
            _fastModelName.value = ""
        }
    }

    fun updateFastEndpoint(endpoint: String) {
        resetFastVerification()
        _fastEndpoint.value = endpoint
    }

    fun updateFastModelName(modelName: String) {
        resetFastVerification()
        _fastModelName.value = modelName
    }

    fun resetFastVerification() {
        fastVerifyJob?.cancel()
        fastVerifyJob = null
        _fastVerifying.value = false
        _fastApiKeyVerified.value = false
        _fastVerifyError.value = null
    }

    fun verifyFastApiKey(provider: String, key: String) {
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
                    configStore.saveRoleConfig("fast", provider, key, endpoint, modelName)
                    _fastApiKeyVerified.value = true
                    _fastVerifyError.value = null
                } else {
                    _fastVerifyError.value = result.exceptionOrNull()?.message ?: "Unknown error"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) _fastVerifyError.value = e.message ?: "Connection failed"
            } finally {
                if (isActive) _fastVerifying.value = false
            }
        }
    }

    fun verifyApiKey(provider: String, key: String) {
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
                    configStore.saveRoleConfig("reasoning", provider, key, endpoint, modelName)
                    _apiKeyVerified.value = true
                    _verifyError.value = null
                } else {
                    _verifyError.value = result.exceptionOrNull()?.message ?: "Unknown error"
                }
            } catch (e: CancellationException) {
                throw e  // must re-throw so coroutine machinery cleans up correctly
            } catch (e: Exception) {
                if (isActive) _verifyError.value = e.message ?: "Connection failed"
            } finally {
                if (isActive) _verifying.value = false
            }
        }
    }

    fun finishOnboarding(onComplete: () -> Unit) {
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
            val pkg = context.packageName
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.contains("$pkg/$pkg.service.CoatiAccessibilityService")
                    || enabled.contains("$pkg/.service.CoatiAccessibilityService")
        }
    }
}
