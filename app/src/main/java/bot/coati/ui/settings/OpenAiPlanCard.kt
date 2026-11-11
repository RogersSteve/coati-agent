package bot.coati.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.coati.R

@Composable
fun OpenAiPlanCard(controller: OpenAiPlanController) {
    val state by controller.state.collectAsState()
    OpenAiPlanCard(
        state = state,
        onSignIn = controller::signIn,
        onOpenLogin = controller::reopenLoginPage,
        onCancel = controller::cancelSignIn,
        onSelectAccount = controller::selectAccount,
        onSelectModel = controller::selectModel,
        onUse = controller::useSelected,
        onSignOut = controller::signOut,
    )
}

@Composable
private fun OpenAiPlanCard(
    state: OpenAiPlanUiState,
    onSignIn: () -> Unit,
    onOpenLogin: () -> Unit,
    onCancel: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onUse: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.openai_plan_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (state.phase) {
                OpenAiPlanPhase.SIGNED_OUT -> {
                    Text(
                        stringResource(R.string.openai_plan_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSignIn,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator()
                        } else {
                            Text(stringResource(R.string.openai_plan_sign_in))
                        }
                    }
                }
                OpenAiPlanPhase.WAITING -> {
                    Text(
                        stringResource(R.string.openai_plan_code_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.busy) {
                        CircularProgressIndicator()
                    }
                    Button(
                        onClick = onOpenLogin,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.openai_plan_open_page))
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
                OpenAiPlanPhase.SIGNED_IN -> {
                    val who = listOfNotNull(state.email, state.planLabel).joinToString(" · ")
                    if (who.isNotBlank()) {
                        Text(stringResource(R.string.openai_plan_signed_in_as, who), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (state.accounts.size > 1) {
                        Text(stringResource(R.string.openai_plan_account), style = MaterialTheme.typography.labelLarge)
                        state.accounts.forEach { account ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = account.id == state.selectedAccountId,
                                    onClick = { onSelectAccount(account.id) },
                                    enabled = !state.busy,
                                )
                                Text(account.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    ModelMenu(
                        models = state.models,
                        selected = state.selectedModel,
                        enabled = !state.busy,
                        onSelected = onSelectModel,
                    )
                    if (state.catalogFallback) {
                        Text(
                            stringResource(R.string.openai_plan_model_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.inUse) {
                        Text(
                            stringResource(R.string.openai_plan_in_use),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = onUse,
                        enabled = !state.busy && !state.selectedModel.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) CircularProgressIndicator() else Text(stringResource(R.string.openai_plan_use))
                    }
                    TextButton(onClick = onSignOut, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.openai_plan_sign_out))
                    }
                }
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ModelMenu(
    models: List<OpenAiPlanChoice>,
    selected: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selected }?.label ?: selected ?: stringResource(R.string.settings_select_model)
    Column {
        Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.label) },
                        onClick = {
                            onSelected(model.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
