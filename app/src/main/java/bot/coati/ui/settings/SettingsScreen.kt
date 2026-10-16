package bot.coati.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import bot.coati.R
import bot.coati.jev.JevProviders
import bot.coati.data.RoutingMode
import bot.coati.data.SafetyMode
import bot.coati.experience.ManualMode
import bot.coati.model.ModelProviders

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onStartCoati: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenAccessibilityPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var initialIssueFocused by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.startupIssues) {
        if (!initialIssueFocused && state.startupIssues.isNotEmpty()) {
            initialIssueFocused = true
            val hasPermissionIssue = state.startupIssues.any {
                it == StartupIssue.OVERLAY_PERMISSION ||
                    it == StartupIssue.ACCESSIBILITY_PERMISSION ||
                    it == StartupIssue.ACCESSIBILITY_CONNECTION
            }
            if (!hasPermissionIssue) listState.scrollToItem(4)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StartupStatusCard(
                    issues = state.startupIssues,
                    onStartCoati = onStartCoati,
                )
            }
            item {
                ReadinessCard(
                    overlayGranted = state.overlayGranted,
                    accessibilityGranted = state.accessibilityGranted,
                    accessibilityConnected = state.accessibilityConnected,
                    onOpenOverlayPermission = onOpenOverlayPermission,
                    onOpenAccessibilityPermission = onOpenAccessibilityPermission,
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_agent_behavior))
                Spacer(Modifier.height(8.dp))
                SafetyModeCard(
                    selected = state.safetyMode,
                    onSelected = viewModel::setSafetyMode,
                )
                Spacer(Modifier.height(10.dp))
                RoutingModeCard(
                    selected = state.routingMode,
                    onSelected = viewModel::setRoutingMode,
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_app_knowledge))
                Spacer(Modifier.height(8.dp))
                AppKnowledgeCard(
                    selected = state.manualMode,
                    clearingData = state.clearingManualData,
                    hasStoredData = state.hasStoredManualData,
                    statusMessage = state.manualDataMessage,
                    statusError = state.manualDataError,
                    onSelected = viewModel::setManualMode,
                    onClearData = viewModel::clearAppKnowledge,
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_ai_models))
                Spacer(Modifier.height(8.dp))
                ModelSettingsCard(state = state, viewModel = viewModel)
            }
            item {
                SectionTitle(stringResource(R.string.settings_safety_support))
                Spacer(Modifier.height(8.dp))
                InformationCard(
                    title = stringResource(R.string.settings_emergency_stop_title),
                    body = stringResource(R.string.settings_emergency_stop_body),
                )
                Spacer(Modifier.height(10.dp))
                InformationCard(
                    title = stringResource(R.string.settings_about_title),
                    body = stringResource(
                        R.string.settings_about_body,
                        state.versionName.ifBlank { stringResource(R.string.settings_unknown) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun AppKnowledgeCard(
    selected: ManualMode,
    clearingData: Boolean,
    hasStoredData: Boolean?,
    statusMessage: String?,
    statusError: Boolean,
    onSelected: (ManualMode) -> Unit,
    onClearData: () -> Unit,
) {
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ManualModeOption(
                title = stringResource(R.string.settings_knowledge_off_title),
                description = stringResource(R.string.settings_knowledge_off_description),
                mode = ManualMode.OFF,
                selected = selected,
                onSelected = onSelected,
            )
            ManualModeOption(
                title = stringResource(R.string.settings_knowledge_guides_title),
                description = stringResource(R.string.settings_knowledge_guides_description),
                mode = ManualMode.READ_ONLY,
                selected = selected,
                onSelected = onSelected,
            )
            ManualModeOption(
                title = stringResource(R.string.settings_knowledge_learn_title),
                description = stringResource(R.string.settings_knowledge_learn_description),
                mode = ManualMode.LEARNING,
                selected = selected,
                onSelected = onSelected,
            )
            if (selected == ManualMode.SHADOW) {
                Text(
                    text = stringResource(R.string.settings_shadow_notice),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.settings_learning_disclosure),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showClearConfirmation = true },
                enabled = hasStoredData == true && !clearingData,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    text = when {
                        clearingData -> stringResource(R.string.settings_knowledge_clearing)
                        hasStoredData == null -> stringResource(R.string.settings_knowledge_checking)
                        hasStoredData -> stringResource(R.string.settings_knowledge_clear)
                        else -> stringResource(R.string.settings_knowledge_empty)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (hasStoredData == false && !clearingData) {
                Text(
                    text = stringResource(R.string.settings_knowledge_builtin_available),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            statusMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.settings_knowledge_clear_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_knowledge_clear_confirm_body),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearData()
                    },
                ) {
                    Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun ManualModeOption(
    title: String,
    description: String,
    mode: ManualMode,
    selected: ManualMode,
    onSelected: (ManualMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(mode) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelected(mode) })
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartupStatusCard(
    issues: List<StartupIssue>,
    onStartCoati: () -> Unit,
) {
    val ready = issues.isEmpty()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
        border = if (ready) null else BorderStroke(2.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = if (ready) stringResource(R.string.settings_ready)
                else pluralStringResource(R.plurals.settings_required_items, issues.size, issues.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            if (ready) {
                Text(
                    stringResource(R.string.settings_ready_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onStartCoati, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_start))
                }
            } else {
                Text(
                    stringResource(R.string.settings_blocked_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                issues.forEach { issue ->
                    Text(
                        text = stringResource(
                            R.string.settings_issue_bullet,
                            stringResource(startupIssueMessageRes(issue)),
                        ),
                        modifier = Modifier.padding(vertical = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private fun startupIssueMessageRes(issue: StartupIssue): Int = when (issue) {
    StartupIssue.OVERLAY_PERMISSION -> R.string.settings_issue_overlay
    StartupIssue.ACCESSIBILITY_PERMISSION -> R.string.settings_issue_accessibility
    StartupIssue.ACCESSIBILITY_CONNECTION -> R.string.settings_issue_accessibility_disconnected
    StartupIssue.REASONING_MODEL -> R.string.settings_issue_reasoning
    StartupIssue.FAST_MODEL -> R.string.settings_issue_fast
}

@Composable
private fun ReadinessCard(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    accessibilityConnected: Boolean,
    onOpenOverlayPermission: () -> Unit,
    onOpenAccessibilityPermission: () -> Unit,
) {
    val ready = overlayGranted && accessibilityGranted && accessibilityConnected
    Card(
        shape = RoundedCornerShape(20.dp),
        border = if (ready) null else BorderStroke(2.dp, MaterialTheme.colorScheme.error),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.settings_permissions_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ready) {
                    stringResource(R.string.settings_permissions_ready)
                } else {
                    stringResource(R.string.settings_permissions_fix)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            PermissionRow(
                label = stringResource(R.string.settings_permission_overlay),
                available = overlayGranted,
                status = if (overlayGranted) stringResource(R.string.settings_available)
                else stringResource(R.string.settings_permission_required),
                actionLabel = stringResource(R.string.settings_open),
                onAction = onOpenOverlayPermission,
            )
            PermissionRow(
                label = stringResource(R.string.settings_permission_accessibility),
                available = accessibilityGranted && accessibilityConnected,
                status = when {
                    !accessibilityGranted -> stringResource(R.string.settings_permission_required)
                    !accessibilityConnected -> stringResource(R.string.settings_enabled_not_connected)
                    else -> stringResource(R.string.settings_available)
                },
                actionLabel = if (accessibilityGranted) stringResource(R.string.settings_reconnect)
                else stringResource(R.string.settings_open),
                onAction = onOpenAccessibilityPermission,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    available: Boolean,
    status: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!available) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun RoutingModeCard(
    selected: RoutingMode,
    onSelected: (RoutingMode) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_routing_title),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            listOf(
                Triple(RoutingMode.FAST, R.string.settings_routing_fast_title, R.string.settings_routing_fast_description),
                Triple(RoutingMode.BALANCED, R.string.settings_routing_balanced_title, R.string.settings_routing_balanced_description),
                Triple(RoutingMode.ACCURATE, R.string.settings_routing_accurate_title, R.string.settings_routing_accurate_description),
            ).forEach { (mode, title, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(mode) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == selected, onClick = { onSelected(mode) })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_routing_note),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SafetyModeCard(
    selected: SafetyMode,
    onSelected: (SafetyMode) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SafetyModeOption(
                title = stringResource(R.string.settings_safety_cautious_title),
                description = stringResource(R.string.settings_safety_cautious_description),
                mode = SafetyMode.CAUTIOUS,
                selected = selected,
                onSelected = onSelected,
            )
            SafetyModeOption(
                title = stringResource(R.string.settings_safety_standard_title),
                description = stringResource(R.string.settings_safety_standard_description),
                mode = SafetyMode.STANDARD,
                selected = selected,
                onSelected = onSelected,
            )
            SafetyModeOption(
                title = stringResource(R.string.settings_safety_trust_title),
                description = stringResource(R.string.settings_safety_trust_description),
                mode = SafetyMode.TRUST,
                selected = selected,
                onSelected = onSelected,
            )
            Text(
                text = stringResource(R.string.settings_safety_boundary),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SafetyModeOption(
    title: String,
    description: String,
    mode: SafetyMode,
    selected: SafetyMode,
    onSelected: (SafetyMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(mode) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelected(mode) })
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelSettingsCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val reasoningMissing = StartupIssue.REASONING_MODEL in state.startupIssues
    val fastMissing = StartupIssue.FAST_MODEL in state.startupIssues
    val hasModelIssue = reasoningMissing || fastMissing
    var selectedTab by rememberSaveable {
        mutableIntStateOf(if (!reasoningMissing && fastMissing) 1 else 0)
    }
    val role = if (selectedTab == 0) ModelRole.REASONING else ModelRole.FAST
    val editor = if (role == ModelRole.REASONING) state.reasoning else state.fast
    val selectedRoleMissing = if (role == ModelRole.REASONING) reasoningMissing else fastMissing

    Card(
        shape = RoundedCornerShape(20.dp),
        border = if (hasModelIssue) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (hasModelIssue) {
                Text(
                    text = stringResource(R.string.settings_model_setup_required),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                if (reasoningMissing) {
                    Text(
                        stringResource(R.string.settings_reasoning_setup_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (fastMissing) {
                    Text(
                        stringResource(R.string.settings_fast_setup_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            stringResource(
                                if (reasoningMissing) R.string.settings_reasoning_attention else R.string.tab_reasoning,
                            ),
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            stringResource(if (fastMissing) R.string.settings_fast_attention else R.string.tab_fast),
                        )
                    },
                )
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.tab_jev_optional)) })
            }
            Spacer(Modifier.height(16.dp))
            if (selectedTab == 2) {
                JevConfigurationContent(state.jev, viewModel::selectJevProvider, viewModel::updateJevApiKey,
                    viewModel::verifyAndApplyJev, viewModel::removeJevKey)
            } else {
                Text(
                    text = if (role == ModelRole.REASONING) {
                        stringResource(R.string.settings_reasoning_description)
                    } else {
                        stringResource(R.string.settings_fast_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (role == ModelRole.FAST) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reuse_reasoning_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_reuse_reasoning_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.fastSameAsReasoning,
                            onCheckedChange = viewModel::setFastSameAsReasoning,
                            enabled = !state.reasoning.verifying && !state.fast.verifying,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (!(role == ModelRole.FAST && state.fastSameAsReasoning)) {
                    ProviderPicker(
                        selectedProviderId = editor.providerId,
                        onSelected = { viewModel.selectProvider(role, it) },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                val providerId = editor.providerId
                val provider = ModelProviders.findById(providerId)

                if (!(role == ModelRole.FAST && state.fastSameAsReasoning) && provider?.isCustom == true) {
                    OutlinedTextField(
                        value = editor.endpoint,
                        onValueChange = { viewModel.updateEndpoint(role, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_api_endpoint)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (selectedRoleMissing && editor.endpoint.isBlank()) R.string.settings_api_endpoint_required
                                    else R.string.settings_api_endpoint_hint,
                                ),
                            )
                        },
                        isError = selectedRoleMissing && editor.endpoint.isBlank(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                ModelPicker(
                    providerId = providerId,
                    modelName = editor.modelName,
                    onSelected = { viewModel.updateModelName(role, it) },
                )

                if (!(role == ModelRole.FAST && state.fastSameAsReasoning)) {
                    Spacer(Modifier.height(12.dp))
                    ApiKeyField(
                        value = editor.apiKeyInput,
                        maskedKey = editor.maskedApiKey,
                        hasSavedKey = editor.hasSavedKey,
                        isError = selectedRoleMissing && !editor.hasSavedKey && editor.apiKeyInput.isBlank(),
                        onValueChange = { viewModel.updateApiKey(role, it) },
                    )
                }

                editor.errorMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                editor.successMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.verifyAndApply(role) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !editor.verifying,
                ) {
                    if (editor.verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.settings_verify_apply))
                    }
                }
                Text(
                    text = stringResource(R.string.settings_verification_disclosure),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun JevConfigurationContent(
    state: JevEditorState,
    onProvider: (String) -> Unit,
    onKey: (String) -> Unit,
    onVerify: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Text(stringResource(R.string.settings_jev_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.settings_jev_description),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var providerExpanded by remember { mutableStateOf(false) }
        var modelExpanded by remember { mutableStateOf(false) }
        val provider = JevProviders.find(state.providerId)
        Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { providerExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(provider.displayName)
            }
            DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                JevProviders.all.forEach { option ->
                    DropdownMenuItem(text = { Text(option.displayName) }, onClick = {
                        onProvider(option.id)
                        providerExpanded = false
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.jev_model_label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Jev 1.13")
            }
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                DropdownMenuItem(text = { Text("Jev 1.13") }, onClick = { modelExpanded = false })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.dirty) Text(stringResource(R.string.jev_unsaved), style = MaterialTheme.typography.bodyMedium)
        ApiKeyField(
            value = state.apiKeyInput,
            maskedKey = state.maskedApiKey,
            hasSavedKey = state.hasSavedKey,
            isError = false,
            onValueChange = onKey,
            label = stringResource(R.string.settings_jev_api_key),
        )
        state.errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.successMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.verifying,
        ) {
            if (state.verifying) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.settings_verify_apply))
            }
        }
        if (state.configured) {
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_jev_remove))
            }
        }
        Text(
            text = stringResource(R.string.settings_jev_disclosure),
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderPicker(
    selectedProviderId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val provider = ModelProviders.findById(selectedProviderId)
    Column {
        Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(provider?.displayName ?: selectedProviderId)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ModelProviders.all.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            onSelected(option.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(
    providerId: String,
    modelName: String,
    onSelected: (String) -> Unit,
) {
    val provider = ModelProviders.findById(providerId)
    if (provider != null && provider.models.isNotEmpty()) {
        var expanded by remember(providerId) { mutableStateOf(false) }
        Column {
            Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(modelName.ifBlank { stringResource(R.string.settings_select_model) })
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    provider.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onSelected(model)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    } else {
        OutlinedTextField(
            value = modelName,
            onValueChange = onSelected,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
        )
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    maskedKey: String,
    hasSavedKey: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    label: String = stringResource(R.string.settings_api_key),
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = {
            Text(
                if (hasSavedKey) stringResource(R.string.settings_saved_key_hint, maskedKey)
                else stringResource(R.string.settings_enter_api_key),
            )
        },
        supportingText = {
            Text(
                stringResource(
                    if (isError) R.string.settings_api_key_required else R.string.settings_saved_key_disclosure,
                ),
            )
        },
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.settings_hide else R.string.settings_show))
            }
        },
        singleLine = true,
    )
}

@Composable
private fun InformationCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
