package bot.coati.ui.settings

import android.content.Context
import bot.coati.R
import bot.coati.data.ConfigStore
import bot.coati.jev.JevClient
import bot.coati.jev.JevProviders
import bot.coati.model.userFacingConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Shared editor for onboarding and settings. Draft changes never replace the active config. */
class JevConfigurationController(
    private val context: Context,
    private val store: ConfigStore,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onApplied: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(savedState())
    val state: StateFlow<JevEditorState> = mutableState
    private var verification: Job? = null

    private fun savedState(): JevEditorState {
        val saved = store.getJevConfig()
        val provider = saved?.let { JevProviders.forEndpoint(it.endpoint) }
        return JevEditorState(
            providerId = provider?.id ?: JevProviders.typeSafe.id,
            hasSavedKey = saved != null && provider != null,
            maskedApiKey = if (provider != null) saved?.apiKey?.let(::maskApiKey).orEmpty() else "",
            configured = saved != null,
        )
    }

    private fun cancelVerification() {
        verification?.cancel()
        verification = null
    }

    fun selectProvider(id: String) {
        JevProviders.find(id)
        if (id == state.value.providerId) return
        cancelVerification()
        val saved = savedState()
        mutableState.value = JevEditorState(
            providerId = id,
            configured = saved.configured,
            hasSavedKey = saved.hasSavedKey && saved.providerId == id,
            maskedApiKey = saved.maskedApiKey.takeIf { saved.providerId == id }.orEmpty(),
            dirty = !saved.hasSavedKey || saved.providerId != id,
        )
    }

    fun updateKey(key: String) {
        if (key == state.value.apiKeyInput) return
        cancelVerification()
        mutableState.value = state.value.copy(
            apiKeyInput = key, verifying = false, successMessage = null, errorMessage = null,
            dirty = key.isNotBlank() || savedState().providerId != state.value.providerId,
        )
    }

    fun discardDraft() {
        cancelVerification()
        mutableState.value = savedState()
    }

    fun remove() {
        cancelVerification()
        store.clearJevConfig()
        mutableState.value = savedState().copy(successMessage = context.getString(R.string.settings_jev_removed))
        onApplied()
    }

    fun verifyAndApply() {
        val draft = state.value
        val provider = JevProviders.find(draft.providerId)
        val saved = store.getJevConfig()
        val key = resolveApiKey(draft.apiKeyInput, saved?.apiKey,
            canReuseSavedKey = saved != null && JevProviders.forEndpoint(saved.endpoint)?.id == provider.id)
        if (key.isNullOrBlank()) {
            mutableState.value = draft.copy(errorMessage = context.getString(R.string.settings_key_required))
            return
        }
        cancelVerification()
        mutableState.value = draft.copy(verifying = true, errorMessage = null, successMessage = null)
        verification = scope.launch {
            try {
                JevClient(client, key, provider.endpoint, provider.model).testConnection().getOrThrow()
                if (!isActive) return@launch
                store.saveJevConfig(key, provider.endpoint, provider.model)
                mutableState.value = savedState().copy(successMessage = context.getString(R.string.settings_connection_applied))
                onApplied()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) mutableState.value = state.value.copy(errorMessage = userFacingConnectionError(e))
            } finally {
                if (isActive) mutableState.value = state.value.copy(verifying = false)
            }
        }
    }
}
