package bot.coati.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.coati.ui.theme.BallError
import bot.coati.ui.theme.BallExecuting
import bot.coati.ui.theme.BallIdle
import bot.coati.ui.theme.BallWaiting

@Composable
fun OverlayContent(
    state: OverlayState,
    onExecute: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onCollapse: () -> Unit = {},
) {
    when (state) {
        is OverlayState.Collapsed -> CollapsedBall(agentState = state)
        is OverlayState.Expanded -> ExpandedInputPanel(onExecute = onExecute, onCollapse = onCollapse)
        is OverlayState.Hidden -> {}
        is OverlayState.Executing -> ExecutingPanel(status = state.status, progress = state.progress, onStop = onStop, onCollapse = onCollapse)
        is OverlayState.Confirmation -> ConfirmationPanel(title = state.title, detail = state.detail, onConfirm = onConfirm, onCancel = onCancel, onCollapse = onCollapse)
        is OverlayState.Error -> ErrorPanel(message = state.message, onRetry = onRetry, onDismiss = onDismiss, onCollapse = onCollapse)
    }
}

@Composable
fun CollapsedBall(agentState: OverlayState) {
    val ballColor by animateColorAsState(
        targetValue = when (agentState) {
            is OverlayState.Executing -> BallExecuting
            is OverlayState.Confirmation -> BallWaiting
            is OverlayState.Error -> BallError
            else -> BallIdle
        },
        animationSpec = tween(300),
        label = "ballColor",
    )

    // Pulsing animation for executing state
    val pulseAlpha = if (agentState is OverlayState.Executing) {
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

@Composable
private fun ExpandedInputPanel(
    onExecute: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    var taskInput by remember { mutableStateOf("") }

    PanelContainer(onCollapse = onCollapse) {
        OutlinedTextField(
            value = taskInput,
            onValueChange = { taskInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Describe a task...") },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
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
            Text("Execute")
        }
    }
}

@Composable
private fun ExecutingPanel(
    status: String,
    progress: Float?,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
) {
    PanelContainer(onCollapse = onCollapse) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = BallError,
            ),
        ) {
            Text("Stop")
        }
    }
}

@Composable
private fun ConfirmationPanel(
    title: String,
    detail: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onCollapse: () -> Unit,
) {
    PanelContainer(onCollapse = onCollapse) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Confirm")
            }
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCollapse: () -> Unit,
) {
    PanelContainer(onCollapse = onCollapse) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Dismiss")
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

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
            Text("Collapse")
        }
    }
}
