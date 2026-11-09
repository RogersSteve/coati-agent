package bot.coati.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.coati.R
import bot.coati.data.SafetyMode
import bot.coati.experience.ManualMode
import bot.coati.ui.theme.BallError
import bot.coati.ui.theme.BallExecuting
import bot.coati.ui.theme.BallIdle
import bot.coati.ui.theme.BallWaiting

// Semi-transparent bar colors
private val FrameBarBackground = Color.Black.copy(alpha = 0.6f)
private val FrameBarBackgroundDark = Color.Black.copy(alpha = 0.8f)
private val FrameBarText = Color.White
private val DialogScrim = Color.Black.copy(alpha = 0.5f)

@Composable
fun OverlayContent(
    state: OverlayState,
    ballState: OverlayState = state,
    safetyMode: SafetyMode = SafetyMode.STANDARD,
    manualMode: ManualMode = ManualMode.OFF,
    onExecute: (String) -> Unit = {},
    onSafetyModeChanged: (SafetyMode) -> Unit = {},
    onStop: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onSettings: () -> Unit = {},
    onExit: () -> Unit = {},
    onRecoveryResume: () -> Unit = {},
    onRecoveryAbandon: () -> Unit = {},
) {
    when (state) {
        is OverlayState.CollapsedIdle -> CollapsedBall(ballState = ballState)
        is OverlayState.ExpandedIdle -> ExpandedInputPanel(
            safetyMode = safetyMode,
            manualMode = manualMode,
            onExecute = onExecute,
            onSafetyModeChanged = onSafetyModeChanged,
            onCollapse = onCollapse,
            onSettings = onSettings,
            onExit = onExit,
        )
        is OverlayState.FrameExecuting -> FrameExecutingOverlay(
            taskName = state.taskName,
            status = state.status,
            reasoning = state.reasoning,
            step = state.step,
            totalSteps = state.totalSteps,
            onPause = onPause,
            onStop = onStop,
        )
        is OverlayState.Paused -> PausedBar(
            taskName = state.taskName,
            step = state.step,
            totalSteps = state.totalSteps,
            onResume = onResume,
        )
        is OverlayState.SafetyConfirm -> SafetyConfirmOverlay(
            title = state.title,
            detail = state.detail,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
        is OverlayState.TaskComplete -> TaskCompleteOverlay(
            taskName = state.taskName,
            summary = state.summary,
            steps = state.steps,
            durationMs = state.durationMs,
            onDismiss = onDismiss,
        )
        is OverlayState.TaskFailed -> TaskFailedOverlay(
            taskName = state.taskName,
            reason = state.reason,
            stepsCompleted = state.stepsCompleted,
            totalSteps = state.totalSteps,
            onRetry = onRetry,
            onDismiss = onDismiss,
        )
        is OverlayState.TaskRecovery -> TaskRecoveryOverlay(
            taskName = state.taskName,
            step = state.step,
            totalSteps = state.totalSteps,
            interruptedAgo = state.interruptedAgo,
            onResume = onRecoveryResume,
            onAbandon = onRecoveryAbandon,
        )
    }
}

// ─── COLLAPSED BALL ───

@Composable
fun CollapsedBall(ballState: OverlayState) {
    val ballColor by animateColorAsState(
        targetValue = when (ballState) {
            is OverlayState.FrameExecuting -> BallExecuting
            is OverlayState.SafetyConfirm -> BallWaiting
            is OverlayState.TaskFailed -> BallError
            is OverlayState.Paused -> BallWaiting
            else -> BallIdle
        },
        animationSpec = tween(300),
        label = "ballColor",
    )

    val pulseAlpha = if (ballState is OverlayState.FrameExecuting) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(pulseAlpha)
            .clip(CircleShape)
            .background(ballColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "C",
            color = Color.White,
            fontSize = 20.sp,
        )
    }
}

// ─── EXPANDED INPUT PANEL ───

@Composable
private fun ExpandedInputPanel(
    safetyMode: SafetyMode,
    manualMode: ManualMode,
    onExecute: (String) -> Unit,
    onSafetyModeChanged: (SafetyMode) -> Unit,
    onCollapse: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    var taskInput by remember { mutableStateOf("") }

    PanelContainer(onCollapse = onCollapse) {
        // Title row with settings and exit buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Coati",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.overlay_settings))
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.overlay_exit), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = taskInput,
            onValueChange = { taskInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.overlay_task_hint)) },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        SafetyModeSelector(
            selectedMode = safetyMode,
            onModeSelected = onSafetyModeChanged,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (manualMode == ManualMode.LEARNING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.overlay_learning_on))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Button(
            onClick = {
                if (taskInput.isNotBlank()) onExecute(taskInput)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = taskInput.isNotBlank(),
        ) {
            Text(stringResource(R.string.overlay_execute))
        }
    }
}

@Composable
private fun SafetyModeSelector(
    selectedMode: SafetyMode,
    onModeSelected: (SafetyMode) -> Unit,
) {
    val options = listOf(
        SafetyMode.TRUST to stringResource(R.string.safety_mode_trust),
        SafetyMode.STANDARD to stringResource(R.string.safety_mode_standard),
        SafetyMode.CAUTIOUS to stringResource(R.string.safety_mode_cautious),
    )

    Text(
        text = stringResource(R.string.overlay_execution_mode),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (mode, label) ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text(
        text = stringResource(when (selectedMode) {
            SafetyMode.TRUST -> R.string.settings_safety_trust_description
            SafetyMode.STANDARD -> R.string.settings_safety_standard_description
            SafetyMode.CAUTIOUS -> R.string.settings_safety_cautious_description
        }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.settings_safety_boundary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─── FRAME EXECUTING OVERLAY ───

@Composable
private fun FrameExecutingOverlay(
    taskName: String,
    status: String,
    reasoning: String,
    step: Int,
    totalSteps: Int,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Consume all touch events so user can't interact with apps below
            .pointerInput(Unit) {
                detectTapGestures { /* consume */ }
            },
    ) {
        // Top bar - semi-transparent
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.TopCenter)
                .background(FrameBarBackground)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    // Pulsing indicator
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = taskName,
                        color = FrameBarText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (totalSteps > 0) {
                    Text(
                        text = "$step/$totalSteps",
                        color = FrameBarText.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // Bottom bar - semi-transparent
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(FrameBarBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    color = FrameBarText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reasoning.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = reasoning,
                    color = FrameBarText.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPause,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(stringResource(R.string.overlay_pause))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = BallError,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(stringResource(R.string.overlay_stop_button))
                }
            }
        }
    }
}

// ─── PAUSED BAR ───

@Composable
private fun PausedBar(
    taskName: String,
    step: Int,
    totalSteps: Int,
    onResume: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "C",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(BallWaiting)
                        .padding(4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.overlay_paused),
                    color = FrameBarText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (totalSteps > 0) {
                    Text(
                        text = " ($step/$totalSteps)",
                        color = FrameBarText.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                }
            }
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BallExecuting,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.overlay_resume_button), fontSize = 12.sp)
            }
        }
    }
}

// ─── SAFETY CONFIRM OVERLAY ───

@Composable
private fun SafetyConfirmOverlay(
    title: String,
    detail: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    // Full screen scrim with centered dialog
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FrameBarBackgroundDark)
            .pointerInput(Unit) { detectTapGestures { /* consume */ } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "⚠ $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_confirm))
                    }
                }
            }
        }
    }
}

// ─── TASK COMPLETE OVERLAY ───

@Composable
private fun TaskCompleteOverlay(
    taskName: String,
    summary: String,
    steps: Int,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    val durationText = formatDuration(durationMs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrim)
            .pointerInput(Unit) { detectTapGestures { /* consume */ } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.overlay_task_complete),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BallExecuting,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = taskName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.overlay_steps_duration, steps, durationText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(stringResource(R.string.overlay_close))
                }
            }
        }
    }
}

// ─── TASK FAILED OVERLAY ───

@Composable
private fun TaskFailedOverlay(
    taskName: String,
    reason: String,
    stepsCompleted: Int,
    totalSteps: Int,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrim)
            .pointerInput(Unit) { detectTapGestures { /* consume */ } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.overlay_task_failed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BallError,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = taskName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (totalSteps > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.overlay_completed_steps, stepsCompleted, totalSteps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_close))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_retry))
                    }
                }
            }
        }
    }
}

// ─── TASK RECOVERY OVERLAY ───

@Composable
private fun TaskRecoveryOverlay(
    taskName: String,
    step: Int,
    totalSteps: Int,
    interruptedAgo: String,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrim)
            .pointerInput(Unit) { detectTapGestures { /* consume */ } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.overlay_unfinished_task),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"$taskName\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.overlay_progress_steps, step, totalSteps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (interruptedAgo.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.overlay_interrupted, interruptedAgo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onAbandon,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_abandon))
                    }
                    Button(
                        onClick = onResume,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.overlay_resume))
                    }
                }
            }
        }
    }
}

// ─── SHARED PANEL CONTAINER ───

@Composable
private fun PanelContainer(
    onCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        content()
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.overlay_collapse))
        }
    }
}

// ─── UTILITY ───

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
