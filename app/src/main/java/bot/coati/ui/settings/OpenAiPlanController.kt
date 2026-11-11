package bot.coati.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import bot.coati.data.ConfigStore
import bot.coati.data.ModelAuthMode
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.model.openai.OpenAiPlanAuth
import bot.coati.model.openai.OpenAiPlanException
import bot.coati.model.openai.OpenAiPlanModel
import bot.coati.model.openai.OpenAiPlanProtocol
import bot.coati.model.userFacingConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class OpenAiPlanPhase { SIGNED_OUT, WAITING, SIGNED_IN }

data class OpenAiPlanUiState(
    val phase: OpenAiPlanPhase = OpenAiPlanPhase.SIGNED_OUT,
    val busy: Boolean = false,
    val loginUrl: String = "",
    val email: String? = null,
    val planLabel: String? = null,
    val accounts: List<OpenAiPlanChoice> = emptyList(),
    val selectedAccountId: String? = null,
    val models: List<OpenAiPlanChoice> = emptyList(),
    val selectedModel: String? = null,
    val inUse: Boolean = false,
    val catalogFallback: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

data class OpenAiPlanChoice(val id: String, val label: String)

class OpenAiPlanController(
    private val application: Application,
    private val configStore: ConfigStore,
    private val planAuth: OpenAiPlanAuth,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onApplied: () -> Unit,
    private val onSignedOut: (wasApplied: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(signedOut())
    val state: StateFlow<OpenAiPlanUiState> = _state
    private var job: Job? = null

    init {
        reload()
    }

    fun signIn() {
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null, phase = OpenAiPlanPhase.SIGNED_OUT)
            try {
                planAuth.beginBrowserLogin().use { login ->
                    if (!isActive) return@launch
                    _state.value = _state.value.copy(
                        phase = OpenAiPlanPhase.WAITING,
                        busy = true,
                        loginUrl = login.authorizationUrl,
                        error = null,
                    )
                    openLoginPage(login.authorizationUrl)
                    planAuth.finishBrowserLogin(login)
                }
                if (!isActive) return@launch
                reload(message = application.getString(bot.coati.R.string.openai_plan_signed_in))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive) fail(error, OpenAiPlanPhase.SIGNED_OUT)
            }
        }
    }

    fun cancelSignIn() {
        job?.cancel()
        reload()
    }

    fun reopenLoginPage() {
        _state.value.loginUrl.takeIf { it.isNotBlank() }?.let(::openLoginPage)
    }

    fun selectAccount(accountId: String) {
        if (_state.value.selectedAccountId == accountId || _state.value.busy) return
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null, selectedAccountId = accountId)
            try {
                val models = planAuth.listModels(accountId)
                if (!isActive) return@launch
                val choices = modelChoices(models)
                val selected = choices.firstOrNull { it.id == _state.value.selectedModel }?.id ?: choices.firstOrNull()?.id
                _state.value = _state.value.copy(
                    busy = false,
                    models = choices,
                    selectedModel = selected,
                    catalogFallback = models == OpenAiPlanProtocol.FALLBACK_MODELS,
                    inUse = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive) fail(error, OpenAiPlanPhase.SIGNED_IN)
            }
        }
    }

    fun selectModel(model: String) {
        _state.value = _state.value.copy(selectedModel = model, message = null, error = null, inUse = appliedModel() == model && appliedAccount() == _state.value.selectedAccountId)
    }

    fun useSelected() {
        val accountId = _state.value.selectedAccountId
        val model = _state.value.selectedModel
        if (accountId.isNullOrBlank() || model.isNullOrBlank()) {
            _state.value = _state.value.copy(error = application.getString(bot.coati.R.string.openai_plan_choose_model))
            return
        }
        val models = _state.value.models.map { OpenAiPlanModel(it.id, it.label) }
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            try {
                val provider = ModelProviders.findById("openai")
                    ?: throw OpenAiPlanException("OpenAI is not available.")
                val result = GatewayFactory.create(
                    provider,
                    client,
                    "",
                    OpenAiPlanProtocol.API_ROOT,
                    model,
                    ModelAuthMode.OPENAI_PLAN,
                    AccountHeader(planAuth, accountId),
                ).testConnection()
                if (result.isFailure) throw result.exceptionOrNull() ?: OpenAiPlanException("Could not reach OpenAI.")
                planAuth.applySelection(accountId, model, models)
                configStore.saveOpenAiPlanConfig(model, OpenAiPlanProtocol.API_ROOT)
                if (!isActive) return@launch
                reload(message = application.getString(bot.coati.R.string.openai_plan_in_use))
                onApplied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive) fail(error, OpenAiPlanPhase.SIGNED_IN)
            }
        }
    }

    fun signOut() {
        job?.cancel()
        job = scope.launch {
            val wasApplied = configStore.getReasoningConfig()?.authMode == ModelAuthMode.OPENAI_PLAN
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            val revoked = try {
                planAuth.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive) fail(error, OpenAiPlanPhase.SIGNED_IN)
                return@launch
            }
            if (!isActive) return@launch
            val message = application.getString(
                if (revoked) bot.coati.R.string.openai_plan_signed_out else bot.coati.R.string.openai_plan_signed_out_local,
            )
            _state.value = signedOut().copy(message = message)
            onSignedOut(wasApplied)
        }
    }

    private fun reload(message: String? = null) {
        val snapshot = planAuth.currentSnapshot()
        if (snapshot == null) {
            _state.value = signedOut().copy(message = message)
            return
        }
        val models = snapshot.models
        val selectedModel = snapshot.selectedModel ?: models.firstOrNull()?.slug
        _state.value = OpenAiPlanUiState(
            phase = OpenAiPlanPhase.SIGNED_IN,
            email = snapshot.email,
            planLabel = displayPlan(snapshot.planType),
            accounts = snapshot.accounts.map { account ->
                OpenAiPlanChoice(account.id, accountLabel(account.label, account.planType))
            },
            selectedAccountId = snapshot.selectedAccountId,
            models = modelChoices(models),
            selectedModel = selectedModel,
            inUse = appliedModel() == selectedModel && appliedAccount() == snapshot.selectedAccountId,
            catalogFallback = models == OpenAiPlanProtocol.FALLBACK_MODELS,
            message = message,
        )
    }

    private fun fail(error: Exception, phase: OpenAiPlanPhase) {
        val signedIn = planAuth.currentSnapshot()
        if (signedIn == null || phase == OpenAiPlanPhase.SIGNED_OUT) {
            _state.value = signedOut().copy(error = userFacingConnectionError(error))
        } else {
            reload()
            _state.value = _state.value.copy(busy = false, error = userFacingConnectionError(error))
        }
    }

    private fun signedOut(): OpenAiPlanUiState {
        val needsLogin = configStore.getReasoningConfig()?.authMode == ModelAuthMode.OPENAI_PLAN && !planAuth.hasSession()
        return OpenAiPlanUiState(
            error = if (needsLogin) application.getString(bot.coati.R.string.openai_plan_sign_in_again) else null,
        )
    }

    private fun appliedModel(): String? =
        configStore.getReasoningConfig()?.takeIf { it.authMode == ModelAuthMode.OPENAI_PLAN }?.modelName

    private fun appliedAccount(): String? =
        planAuth.currentSnapshot()?.selectedAccountId?.takeIf { appliedModel() != null }

    private fun modelChoices(models: List<OpenAiPlanModel>): List<OpenAiPlanChoice> =
        models.filter { it.slug.startsWith("gpt-") }.map { OpenAiPlanChoice(it.slug, it.displayName) }

    private fun displayPlan(plan: String?): String? =
        plan?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    private fun accountLabel(label: String, plan: String?): String =
        displayPlan(plan)?.let { "$label · $it" } ?: label

    private fun openLoginPage(url: String) {
        application.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private class AccountHeader(
    private val auth: OpenAiPlanAuth,
    private val accountId: String,
) : bot.coati.model.openai.OpenAiPlanCredential {
    override suspend fun authorization() = auth.authorization().copy(accountId = accountId)
}
