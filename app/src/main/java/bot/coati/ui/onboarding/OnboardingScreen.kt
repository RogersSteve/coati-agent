package bot.coati.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bot.coati.R
import bot.coati.model.ApiFormat
import bot.coati.model.ModelProviders
import bot.coati.util.RegionDetector

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinish: () -> Unit,
) {
    val currentStep by viewModel.currentStep.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        StepIndicator(currentStep = currentStep, totalSteps = 5)

        Spacer(modifier = Modifier.height(48.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (currentStep) {
                0 -> WelcomeStep(onNext = { viewModel.nextStep() })
                1 -> OverlayPermissionStep(viewModel = viewModel)
                2 -> AccessibilityPermissionStep(viewModel = viewModel)
                3 -> RegionSelectionStep(viewModel = viewModel)
                4 -> ApiKeyStep(viewModel = viewModel, onFinish = onFinish)
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentStep) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= currentStep) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

@Composable
private fun OverlayPermissionStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val granted by viewModel.overlayGranted.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_overlay_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_overlay_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(48.dp))
        if (granted) {
            Text(
                text = stringResource(R.string.onboarding_overlay_granted),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.nextStep() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.next))
            }
        } else {
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.onboarding_overlay_grant))
            }
        }
    }
}

@Composable
private fun AccessibilityPermissionStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val granted by viewModel.accessibilityGranted.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_accessibility_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_accessibility_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_accessibility_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(48.dp))
        if (granted) {
            Text(
                text = stringResource(R.string.onboarding_accessibility_granted),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.nextStep() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.next))
            }
        } else {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.onboarding_accessibility_grant))
            }
        }
    }
}

@Composable
private fun RegionSelectionStep(viewModel: OnboardingViewModel) {
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val regions = listOf("CN", "global", "JP")

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_region_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_region_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_region_detected, RegionDetector.regionDisplayName(selectedRegion)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(32.dp))

        regions.forEach { region ->
            val isSelected = region == selectedRegion
            OutlinedButton(
                onClick = { viewModel.selectRegion(region) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (isSelected) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(RegionDetector.regionDisplayName(region))
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = { viewModel.nextStep() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(stringResource(R.string.next))
        }
    }
}

@Composable
private fun ApiKeyStep(
    viewModel: OnboardingViewModel,
    onFinish: () -> Unit,
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val verified by viewModel.apiKeyVerified.collectAsState()
    val fastSameAsReasoning by viewModel.fastSameAsReasoning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_apikey_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_apikey_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Tab Row
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { viewModel.selectTab(0) },
                text = { Text(stringResource(R.string.tab_reasoning)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { viewModel.selectTab(1) },
                text = { Text(stringResource(R.string.tab_fast)) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> ReasoningModelConfig(viewModel)
            1 -> FastModelConfig(viewModel)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Finish button — enabled when reasoning verified
        if (verified) {
            Button(
                onClick = { viewModel.finishOnboarding(onFinish) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.onboarding_finish))
            }
        }
    }
}

@Composable
private fun ReasoningModelConfig(viewModel: OnboardingViewModel) {
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val selectedEndpoint by viewModel.selectedEndpoint.collectAsState()
    val selectedModelName by viewModel.selectedModelName.collectAsState()
    val verified by viewModel.apiKeyVerified.collectAsState()
    val verifying by viewModel.verifying.collectAsState()
    val verifyError by viewModel.verifyError.collectAsState()

    var apiKeyInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val providers = ModelProviders.all
    val currentProvider = ModelProviders.findById(selectedProvider)

    // Provider dropdown
    Box {
        OutlinedButton(
            onClick = { providerDropdownExpanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(currentProvider?.displayName ?: selectedProvider)
        }
        DropdownMenu(
            expanded = providerDropdownExpanded,
            onDismissRequest = { providerDropdownExpanded = false },
        ) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        viewModel.selectProvider(provider.id)
                        apiKeyInput = ""
                        providerDropdownExpanded = false
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Endpoint field — only shown for custom providers
    if (currentProvider?.isCustom == true) {
        OutlinedTextField(
            value = selectedEndpoint,
            onValueChange = { viewModel.updateEndpoint(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_endpoint_hint)) },
            placeholder = {
                Text(
                    if (currentProvider.apiFormat == ApiFormat.CLAUDE_COMPATIBLE)
                        "https://api.anthropic.com"
                    else
                        "https://api.openai.com",
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Model selector
    if (currentProvider != null && currentProvider.models.isNotEmpty()) {
        Box {
            OutlinedButton(
                onClick = { modelDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (selectedModelName.isNotBlank()) selectedModelName
                    else stringResource(R.string.onboarding_model_select),
                )
            }
            DropdownMenu(
                expanded = modelDropdownExpanded,
                onDismissRequest = { modelDropdownExpanded = false },
            ) {
                currentProvider.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            viewModel.updateModelName(model)
                            modelDropdownExpanded = false
                        },
                    )
                }
            }
        }
    } else {
        OutlinedTextField(
            value = selectedModelName,
            onValueChange = { viewModel.updateModelName(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_model_name_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // API key input
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = { apiKeyInput = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.onboarding_apikey_input_hint)) },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Text(if (passwordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
            }
        },
        shape = RoundedCornerShape(12.dp),
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Verify button
    Button(
        onClick = { viewModel.verifyApiKey(selectedProvider, apiKeyInput) },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = apiKeyInput.isNotBlank() && !verifying,
    ) {
        if (verifying) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.onboarding_apikey_verify))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (verified) {
        Text(
            text = stringResource(R.string.onboarding_apikey_verified),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    verifyError?.let { error ->
        Text(
            text = stringResource(R.string.onboarding_apikey_failed, error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FastModelConfig(viewModel: OnboardingViewModel) {
    val fastSameAsReasoning by viewModel.fastSameAsReasoning.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val fastProvider by viewModel.fastProvider.collectAsState()
    val fastEndpoint by viewModel.fastEndpoint.collectAsState()
    val fastModelName by viewModel.fastModelName.collectAsState()
    val fastVerified by viewModel.fastApiKeyVerified.collectAsState()
    val fastVerifying by viewModel.fastVerifying.collectAsState()
    val fastVerifyError by viewModel.fastVerifyError.collectAsState()

    var fastApiKeyInput by remember { mutableStateOf("") }
    var fastPasswordVisible by remember { mutableStateOf(false) }
    var fastProviderDropdownExpanded by remember { mutableStateOf(false) }
    var fastModelDropdownExpanded by remember { mutableStateOf(false) }

    // Checkbox: use same as reasoning
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = fastSameAsReasoning,
            onCheckedChange = { viewModel.setFastSameAsReasoning(it) },
        )
        Text(
            text = stringResource(R.string.fast_same_as_reasoning),
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    if (fastSameAsReasoning) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.fast_same_as_reasoning_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Only show model name selector for fast model
        val reasoningProvider = ModelProviders.findById(selectedProvider)
        if (reasoningProvider != null && reasoningProvider.models.size > 1) {
            Box {
                OutlinedButton(
                    onClick = { fastModelDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (fastModelName.isNotBlank()) fastModelName
                        else stringResource(R.string.onboarding_model_select),
                    )
                }
                DropdownMenu(
                    expanded = fastModelDropdownExpanded,
                    onDismissRequest = { fastModelDropdownExpanded = false },
                ) {
                    reasoningProvider.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.updateFastModelName(model)
                                fastModelDropdownExpanded = false
                            },
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = fastModelName,
                onValueChange = { viewModel.updateFastModelName(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.fast_model_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
    } else {
        // Independent fast model config
        Spacer(modifier = Modifier.height(12.dp))

        val currentFastProvider = ModelProviders.findById(fastProvider)

        // Provider dropdown
        Box {
            OutlinedButton(
                onClick = { fastProviderDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(currentFastProvider?.displayName ?: fastProvider)
            }
            DropdownMenu(
                expanded = fastProviderDropdownExpanded,
                onDismissRequest = { fastProviderDropdownExpanded = false },
            ) {
                ModelProviders.all.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            viewModel.selectFastProvider(provider.id)
                            fastApiKeyInput = ""
                            fastProviderDropdownExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Endpoint for custom
        if (currentFastProvider?.isCustom == true) {
            OutlinedTextField(
                value = fastEndpoint,
                onValueChange = { viewModel.updateFastEndpoint(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_endpoint_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Model selector
        if (currentFastProvider != null && currentFastProvider.models.isNotEmpty()) {
            Box {
                OutlinedButton(
                    onClick = { fastModelDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (fastModelName.isNotBlank()) fastModelName
                        else stringResource(R.string.onboarding_model_select),
                    )
                }
                DropdownMenu(
                    expanded = fastModelDropdownExpanded,
                    onDismissRequest = { fastModelDropdownExpanded = false },
                ) {
                    currentFastProvider.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.updateFastModelName(model)
                                fastModelDropdownExpanded = false
                            },
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = fastModelName,
                onValueChange = { viewModel.updateFastModelName(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.fast_model_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // API key
        OutlinedTextField(
            value = fastApiKeyInput,
            onValueChange = { fastApiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_apikey_input_hint)) },
            singleLine = true,
            visualTransformation = if (fastPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { fastPasswordVisible = !fastPasswordVisible }) {
                    Text(if (fastPasswordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
                }
            },
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Verify button
        Button(
            onClick = { viewModel.verifyFastApiKey(fastProvider, fastApiKeyInput) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = fastApiKeyInput.isNotBlank() && !fastVerifying,
        ) {
            if (fastVerifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.onboarding_apikey_verify))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (fastVerified) {
            Text(
                text = stringResource(R.string.onboarding_apikey_verified),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        fastVerifyError?.let { error ->
            Text(
                text = stringResource(R.string.onboarding_apikey_failed, error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
