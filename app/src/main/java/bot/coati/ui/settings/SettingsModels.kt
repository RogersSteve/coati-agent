package bot.coati.ui.settings

import bot.coati.data.ProviderConfig
import bot.coati.data.SafetyMode
import bot.coati.experience.ManualMode
import bot.coati.model.ModelProviders

enum class ModelRole {
    REASONING,
    FAST,
}

internal enum class ModelDraftIssue {
    ENDPOINT_REQUIRED,
    MODEL_REQUIRED,
    API_KEY_REQUIRED,
}

enum class StartupIssue {
    OVERLAY_PERMISSION,
    ACCESSIBILITY_PERMISSION,
    ACCESSIBILITY_CONNECTION,
    REASONING_MODEL,
    FAST_MODEL,
}

data class ModelEditorState(
    val providerId: String,
    val endpoint: String,
    val modelName: String,
    val apiKeyInput: String = "",
    val maskedApiKey: String = "",
    val hasSavedKey: Boolean = false,
    val verifying: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
)

data class SettingsUiState(
    val versionName: String = "",
    val safetyMode: SafetyMode = SafetyMode.STANDARD,
    val manualMode: ManualMode = ManualMode.OFF,
    val clearingManualData: Boolean = false,
    val hasStoredManualData: Boolean? = null,
    val manualDataMessage: String? = null,
    val manualDataError: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val reasoningConfigured: Boolean = false,
    val fastConfigured: Boolean = false,
    val reasoning: ModelEditorState,
    val fast: ModelEditorState,
    val fastSameAsReasoning: Boolean = true,
) {
    val startupIssues: List<StartupIssue>
        get() = startupIssues(
            overlayGranted = overlayGranted,
            accessibilityGranted = accessibilityGranted,
            accessibilityConnected = accessibilityConnected,
            reasoningConfigured = reasoningConfigured,
            fastConfigured = fastConfigured,
            fastSameAsReasoning = fastSameAsReasoning,
        )

    val readyToStart: Boolean
        get() = startupIssues.isEmpty()
}

internal val USER_MANUAL_MODES = listOf(
    ManualMode.OFF,
    ManualMode.READ_ONLY,
    ManualMode.LEARNING,
)

internal fun startupIssues(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    accessibilityConnected: Boolean,
    reasoningConfigured: Boolean,
    fastConfigured: Boolean,
    fastSameAsReasoning: Boolean,
): List<StartupIssue> = buildList {
    if (!overlayGranted) add(StartupIssue.OVERLAY_PERMISSION)
    if (!accessibilityGranted) {
        add(StartupIssue.ACCESSIBILITY_PERMISSION)
    } else if (!accessibilityConnected) {
        add(StartupIssue.ACCESSIBILITY_CONNECTION)
    }
    if (!reasoningConfigured) add(StartupIssue.REASONING_MODEL)
    if (!fastConfigured && (!fastSameAsReasoning || reasoningConfigured)) add(StartupIssue.FAST_MODEL)
}

internal fun isCompleteModelConfig(config: ProviderConfig?): Boolean {
    return config != null &&
        config.providerId.isNotBlank() &&
        ModelProviders.findById(config.providerId) != null &&
        config.endpoint.isNotBlank() &&
        config.modelName.isNotBlank() &&
        config.apiKey.isNotBlank()
}

internal fun shouldStartOverlayDirectly(
    openSettingsRequested: Boolean,
    serviceRunning: Boolean,
    requirementsComplete: Boolean,
): Boolean = !openSettingsRequested && !serviceRunning && requirementsComplete

internal fun maskApiKey(key: String): String {
    if (key.isBlank()) return ""
    if (key.length <= 4) return "••••"
    return "•••• ${key.takeLast(4)}"
}

internal fun resolveApiKey(input: String, savedKey: String?, canReuseSavedKey: Boolean): String? {
    return input.trim().takeIf { it.isNotEmpty() }
        ?: savedKey?.takeIf { canReuseSavedKey && it.isNotBlank() }
}

internal fun validateModelDraft(endpoint: String, modelName: String, apiKey: String?): ModelDraftIssue? {
    if (endpoint.isBlank()) return ModelDraftIssue.ENDPOINT_REQUIRED
    if (modelName.isBlank()) return ModelDraftIssue.MODEL_REQUIRED
    if (apiKey.isNullOrBlank()) return ModelDraftIssue.API_KEY_REQUIRED
    return null
}

internal fun selectCompatibleFastModel(
    providerModels: List<String>,
    currentFastModel: String,
    reasoningModel: String,
): String {
    if (providerModels.isEmpty()) return reasoningModel
    return currentFastModel.takeIf { it in providerModels }
        ?: providerModels.getOrNull(1)
        ?: providerModels.first()
}
