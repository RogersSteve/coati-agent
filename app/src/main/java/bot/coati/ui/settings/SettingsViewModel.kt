package bot.coati.ui.settings

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bot.coati.R
import bot.coati.data.ConfigStore
import bot.coati.data.ProviderConfig
import bot.coati.data.SafetyMode
import bot.coati.experience.ManualMode
import bot.coati.experience.AppManualRepository
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.model.userFacingConnectionError
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import bot.coati.ui.onboarding.OnboardingViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val configStore: ConfigStore,
    private val okHttpClient: OkHttpClient,
) : AndroidViewModel(application) {

    private var reasoningSavedConfig = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
    private var fastSavedConfig = configStore.getRoleConfig(ConfigStore.ROLE_FAST)
    private var reasoningVerifyJob: Job? = null
    private var fastVerifyJob: Job? = null
    private var manualDataScanJob: Job? = null

    private val initialReasoning = editorState(reasoningSavedConfig, fallbackProviderId = configStore.getDefaultProvider())
    private val initialFastSameAsReasoning = configStore.isFastSameAsReasoning()
    private val initialIndependentFast = editorState(
        config = fastSavedConfig ?: reasoningSavedConfig,
        fallbackProviderId = initialReasoning.providerId,
        fallbackModelIndex = 1,
    )
    private val initialFast = if (initialFastSameAsReasoning) {
        editorState(
            config = configStore.getFastConfig() ?: reasoningSavedConfig,
            fallbackProviderId = initialReasoning.providerId,
            fallbackModelIndex = 1,
        )
    } else {
        initialIndependentFast
    }
    private var independentFastDraft = initialIndependentFast

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            versionName = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
            }.getOrDefault(""),
            safetyMode = configStore.getSafetyMode(),
            manualMode = configStore.getManualMode(),
            reasoningConfigured = isCompleteModelConfig(reasoningSavedConfig),
            fastConfigured = isCompleteModelConfig(configStore.getFastConfig()),
            reasoning = initialReasoning,
            fast = initialFast,
            fastSameAsReasoning = initialFastSameAsReasoning,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        reasoningSavedConfig = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
        fastSavedConfig = configStore.getRoleConfig(ConfigStore.ROLE_FAST)
        _uiState.value = _uiState.value.copy(
            safetyMode = configStore.getSafetyMode(),
            manualMode = configStore.getManualMode(),
            overlayGranted = Settings.canDrawOverlays(context),
            accessibilityGranted = OnboardingViewModel.hasAccessibilityPermission(context),
            accessibilityConnected = CoatiAccessibilityService.instance != null,
            reasoningConfigured = isCompleteModelConfig(reasoningSavedConfig),
            fastConfigured = isCompleteModelConfig(configStore.getFastConfig()),
        )
        refreshManualDataPresence()
    }

    fun setSafetyMode(mode: SafetyMode) {
        configStore.setSafetyMode(mode)
        _uiState.value = _uiState.value.copy(safetyMode = mode)
        if (CoatiService.isRunning()) {
            CoatiService.refreshSafetyMode(getApplication())
        }
    }

    fun setManualMode(mode: ManualMode) {
        if (mode !in USER_MANUAL_MODES || _uiState.value.manualMode == mode) return
        configStore.setManualMode(mode)
        _uiState.value = _uiState.value.copy(manualMode = mode, manualDataMessage = null, manualDataError = false)
        if (CoatiService.isRunning()) {
            CoatiService.refreshManualMode(getApplication())
        }
    }

    fun clearAppKnowledge() {
        if (_uiState.value.clearingManualData) return
        _uiState.value = _uiState.value.copy(
            clearingManualData = true,
            manualDataMessage = null,
            manualDataError = false,
        )
        viewModelScope.launch {
            try {
                val cleared = CoatiService.clearAppManualData(getApplication())
                _uiState.value = _uiState.value.copy(
                    clearingManualData = false,
                    hasStoredManualData = if (cleared) false else _uiState.value.hasStoredManualData,
                    manualDataMessage = getApplication<Application>().getString(
                        if (cleared) R.string.settings_knowledge_cleared
                        else R.string.settings_knowledge_clear_partial_failure,
                    ),
                    manualDataError = !cleared,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    clearingManualData = false,
                    manualDataMessage = getApplication<Application>().getString(R.string.settings_knowledge_clear_failure),
                    manualDataError = true,
                )
                refreshManualDataPresence()
            }
        }
    }

    private fun refreshManualDataPresence() {
        manualDataScanJob?.cancel()
        manualDataScanJob = viewModelScope.launch {
            val hasData = withContext(Dispatchers.IO) {
                AppManualRepository(getApplication<Application>().noBackupFilesDir).hasAnyData()
            }
            if (isActive) _uiState.value = _uiState.value.copy(hasStoredManualData = hasData)
        }
    }

    fun setFastSameAsReasoning(same: Boolean) {
        val current = _uiState.value
        if (current.fastSameAsReasoning == same) return
        cancelVerification(ModelRole.REASONING)
        cancelVerification(ModelRole.FAST)
        if (!current.fastSameAsReasoning) {
            independentFastDraft = current.fast.asIdleDraft()
        }
        val idleCurrent = _uiState.value
        val fastState = if (same) {
            val reasoning = reasoningSavedConfig
            val providerId = reasoning?.providerId ?: idleCurrent.reasoning.providerId
            val provider = ModelProviders.findById(providerId)
            val selectedModel = idleCurrent.fast.modelName.takeIf { it in (provider?.models ?: emptyList()) }
                ?: provider?.models?.getOrNull(1)
                ?: provider?.models?.firstOrNull()
                ?: reasoning?.modelName
                ?: idleCurrent.fast.modelName
            idleCurrent.fast.copy(
                providerId = providerId,
                endpoint = reasoning?.endpoint ?: idleCurrent.reasoning.endpoint,
                modelName = selectedModel,
                apiKeyInput = "",
                maskedApiKey = reasoning?.apiKey?.let(::maskApiKey).orEmpty(),
                hasSavedKey = !reasoning?.apiKey.isNullOrBlank(),
                successMessage = null,
                errorMessage = null,
            )
        } else {
            independentFastDraft.asIdleDraft()
        }
        _uiState.value = idleCurrent.copy(fastSameAsReasoning = same, fast = fastState)
    }

    fun selectProvider(role: ModelRole, providerId: String) {
        val provider = ModelProviders.findById(providerId) ?: return
        if (editor(role).providerId == providerId) return
        cancelVerification(role)
        updateEditor(role) { current ->
            val saved = savedConfig(role)
            val canReuse = saved?.providerId == providerId
            current.copy(
                providerId = providerId,
                endpoint = saved?.endpoint?.takeIf { canReuse } ?: provider.defaultEndpoint,
                modelName = saved?.modelName?.takeIf { canReuse }
                    ?: provider.models.firstOrNull().orEmpty(),
                apiKeyInput = "",
                maskedApiKey = saved?.apiKey?.takeIf { canReuse }?.let(::maskApiKey).orEmpty(),
                hasSavedKey = canReuse && !saved?.apiKey.isNullOrBlank(),
                successMessage = null,
                errorMessage = null,
            )
        }
    }

    fun updateEndpoint(role: ModelRole, endpoint: String) {
        cancelVerification(role)
        updateEditor(role) { it.copy(endpoint = endpoint, successMessage = null, errorMessage = null) }
    }

    fun updateModelName(role: ModelRole, modelName: String) {
        cancelVerification(role)
        updateEditor(role) { it.copy(modelName = modelName, successMessage = null, errorMessage = null) }
    }

    fun updateApiKey(role: ModelRole, apiKey: String) {
        cancelVerification(role)
        updateEditor(role) { it.copy(apiKeyInput = apiKey, successMessage = null, errorMessage = null) }
    }

    fun verifyAndApply(role: ModelRole) {
        if (role == ModelRole.FAST && _uiState.value.fastSameAsReasoning) {
            verifySharedFastModel()
            return
        }

        val draft = editor(role)
        val provider = ModelProviders.findById(draft.providerId)
        if (provider == null) {
            setEditorError(role, getApplication<Application>().getString(R.string.settings_unknown_provider, draft.providerId))
            return
        }
        val saved = savedConfig(role)
        val key = resolveApiKey(
            input = draft.apiKeyInput,
            savedKey = saved?.apiKey,
            canReuseSavedKey = saved?.providerId == draft.providerId,
        )
        val validationError = validateModelDraft(draft.endpoint, draft.modelName, key)
        if (validationError != null) {
            setEditorError(role, modelDraftIssueMessage(validationError))
            return
        }
        val fastSameAtStart = _uiState.value.fastSameAsReasoning
        val sharedFastModelAtStart = if (role == ModelRole.REASONING && fastSameAtStart) {
            selectSharedFastModel(draft.providerId, draft.modelName.trim())
        } else {
            null
        }

        launchVerification(role) {
            val result = GatewayFactory.create(provider, okHttpClient, key!!, draft.endpoint.trim(), draft.modelName.trim())
                .testConnection()
            if (result.isFailure) throw result.exceptionOrNull() ?: IllegalStateException("Connection failed")

            val sharedFastModel = sharedFastModelAtStart?.also { fastModel ->
                    val fastResult = GatewayFactory.create(
                        provider,
                        okHttpClient,
                        key!!,
                        draft.endpoint.trim(),
                        fastModel,
                    ).testConnection()
                    if (fastResult.isFailure) {
                        throw fastResult.exceptionOrNull() ?: IllegalStateException("Fast model connection failed")
                    }
            }

            val savedConfig = ProviderConfig(
                providerId = draft.providerId,
                apiKey = key,
                endpoint = draft.endpoint.trim(),
                modelName = draft.modelName.trim(),
            )
            when (role) {
                ModelRole.REASONING -> {
                    configStore.saveApiKey(savedConfig.providerId, savedConfig.apiKey)
                    configStore.saveEndpoint(savedConfig.providerId, savedConfig.endpoint)
                    configStore.saveModelName(savedConfig.providerId, savedConfig.modelName)
                    configStore.setDefaultProvider(savedConfig.providerId)
                    configStore.saveRoleConfig(
                        ConfigStore.ROLE_REASONING,
                        savedConfig.providerId,
                        savedConfig.apiKey,
                        savedConfig.endpoint,
                        savedConfig.modelName,
                    )
                    reasoningSavedConfig = savedConfig
                    if (sharedFastModel != null) {
                        configStore.saveFastSameAsReasoning(true)
                        configStore.saveFastModelName(sharedFastModel)
                    }
                }
                ModelRole.FAST -> {
                    configStore.saveFastSameAsReasoning(false)
                    configStore.saveRoleConfig(
                        ConfigStore.ROLE_FAST,
                        savedConfig.providerId,
                        savedConfig.apiKey,
                        savedConfig.endpoint,
                        savedConfig.modelName,
                    )
                    fastSavedConfig = savedConfig
                }
            }
            completeVerification(role, savedConfig, sharedFastModelName = sharedFastModel)
            reloadModelsIfAvailable()
        }
    }

    private fun verifySharedFastModel() {
        val state = _uiState.value
        val reasoning = reasoningSavedConfig
        if (reasoning == null) {
            setEditorError(ModelRole.FAST, getApplication<Application>().getString(R.string.settings_reasoning_first))
            return
        }
        val provider = ModelProviders.findById(reasoning.providerId)
        if (provider == null) {
            setEditorError(
                ModelRole.FAST,
                getApplication<Application>().getString(R.string.settings_unknown_provider, reasoning.providerId),
            )
            return
        }
        val modelName = state.fast.modelName.trim()
        val validationError = validateModelDraft(reasoning.endpoint, modelName, reasoning.apiKey)
        if (validationError != null) {
            setEditorError(ModelRole.FAST, modelDraftIssueMessage(validationError))
            return
        }
        launchVerification(ModelRole.FAST) {
            val result = GatewayFactory.create(provider, okHttpClient, reasoning.apiKey, reasoning.endpoint, modelName)
                .testConnection()
            if (result.isFailure) throw result.exceptionOrNull() ?: IllegalStateException("Connection failed")
            configStore.saveFastSameAsReasoning(true)
            configStore.saveFastModelName(modelName)
            completeVerification(
                ModelRole.FAST,
                reasoning.copy(modelName = modelName),
                persistAsIndependent = false,
            )
            reloadModelsIfAvailable()
        }
    }

    private fun launchVerification(role: ModelRole, block: suspend () -> Unit) {
        when (role) {
            ModelRole.REASONING -> reasoningVerifyJob?.cancel()
            ModelRole.FAST -> fastVerifyJob?.cancel()
        }
        updateEditor(role) { it.copy(verifying = true, successMessage = null, errorMessage = null) }
        val job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) setEditorError(role, userFacingConnectionError(e))
            } finally {
                if (isActive) updateEditor(role) { it.copy(verifying = false) }
            }
        }
        when (role) {
            ModelRole.REASONING -> reasoningVerifyJob = job
            ModelRole.FAST -> fastVerifyJob = job
        }
    }

    private fun completeVerification(
        role: ModelRole,
        config: ProviderConfig,
        persistAsIndependent: Boolean = true,
        sharedFastModelName: String? = null,
    ) {
        if (role == ModelRole.FAST && persistAsIndependent) fastSavedConfig = config
        updateEditor(role) {
            it.copy(
                providerId = config.providerId,
                endpoint = config.endpoint,
                modelName = config.modelName,
                apiKeyInput = "",
                maskedApiKey = maskApiKey(config.apiKey),
                hasSavedKey = true,
                successMessage = getApplication<Application>().getString(R.string.settings_connection_applied),
                errorMessage = null,
            )
        }
        if (role == ModelRole.REASONING && _uiState.value.fastSameAsReasoning) {
            val current = _uiState.value
            val fastModel = sharedFastModelName ?: selectSharedFastModel(config.providerId, config.modelName)
            _uiState.value = current.copy(
                fast = current.fast.copy(
                    providerId = config.providerId,
                    endpoint = config.endpoint,
                    modelName = fastModel,
                    apiKeyInput = "",
                    maskedApiKey = maskApiKey(config.apiKey),
                    hasSavedKey = true,
                    successMessage = null,
                    errorMessage = null,
                ),
            )
        }
        refreshPermissions()
    }

    private fun selectSharedFastModel(providerId: String, reasoningModelName: String): String {
        val provider = ModelProviders.findById(providerId) ?: return reasoningModelName
        return selectCompatibleFastModel(
            providerModels = provider.models,
            currentFastModel = _uiState.value.fast.modelName,
            reasoningModel = reasoningModelName,
        )
    }

    private fun reloadModelsIfAvailable() {
        if (CoatiService.isRunning()) {
            CoatiService.reloadModelConfiguration(getApplication())
        }
    }

    private fun editor(role: ModelRole): ModelEditorState = when (role) {
        ModelRole.REASONING -> _uiState.value.reasoning
        ModelRole.FAST -> _uiState.value.fast
    }

    private fun savedConfig(role: ModelRole): ProviderConfig? = when (role) {
        ModelRole.REASONING -> reasoningSavedConfig
        ModelRole.FAST -> fastSavedConfig
    }

    private fun updateEditor(role: ModelRole, transform: (ModelEditorState) -> ModelEditorState) {
        val current = _uiState.value
        val updated = when (role) {
            ModelRole.REASONING -> current.copy(reasoning = transform(current.reasoning))
            ModelRole.FAST -> current.copy(fast = transform(current.fast))
        }
        _uiState.value = updated
        if (role == ModelRole.FAST && !updated.fastSameAsReasoning) {
            independentFastDraft = updated.fast
        }
    }

    private fun cancelVerification(role: ModelRole) {
        when (role) {
            ModelRole.REASONING -> {
                reasoningVerifyJob?.cancel()
                reasoningVerifyJob = null
            }
            ModelRole.FAST -> {
                fastVerifyJob?.cancel()
                fastVerifyJob = null
            }
        }
        val current = _uiState.value
        _uiState.value = when (role) {
            ModelRole.REASONING -> current.copy(
                reasoning = current.reasoning.copy(verifying = false, successMessage = null, errorMessage = null),
            )
            ModelRole.FAST -> current.copy(
                fast = current.fast.copy(verifying = false, successMessage = null, errorMessage = null),
            )
        }
    }

    private fun setEditorError(role: ModelRole, message: String) {
        updateEditor(role) { it.copy(verifying = false, successMessage = null, errorMessage = message) }
    }

    private fun modelDraftIssueMessage(issue: ModelDraftIssue): String = getApplication<Application>().getString(
        when (issue) {
            ModelDraftIssue.ENDPOINT_REQUIRED -> R.string.settings_endpoint_required
            ModelDraftIssue.MODEL_REQUIRED -> R.string.settings_model_required
            ModelDraftIssue.API_KEY_REQUIRED -> R.string.settings_key_required
        },
    )

    private fun editorState(
        config: ProviderConfig?,
        fallbackProviderId: String,
        fallbackModelIndex: Int = 0,
    ): ModelEditorState {
        val provider = ModelProviders.findById(config?.providerId ?: fallbackProviderId)
            ?: ModelProviders.all.first()
        val modelName = config?.modelName
            ?: provider.models.getOrNull(fallbackModelIndex)
            ?: provider.models.firstOrNull().orEmpty()
        return ModelEditorState(
            providerId = provider.id,
            endpoint = config?.endpoint ?: provider.defaultEndpoint,
            modelName = modelName,
            maskedApiKey = config?.apiKey?.let(::maskApiKey).orEmpty(),
            hasSavedKey = !config?.apiKey.isNullOrBlank(),
        )
    }

    private fun ModelEditorState.asIdleDraft(): ModelEditorState = copy(
        verifying = false,
        successMessage = null,
        errorMessage = null,
    )
}
