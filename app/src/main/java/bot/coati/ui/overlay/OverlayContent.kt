package bot.coati.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.coati.ui.theme.BallIdle

@Composable
fun OverlayContent(state: OverlayState) {
    when (state) {
        is OverlayState.Collapsed -> CollapsedBall()
        is OverlayState.Expanded -> ExpandedPanel()
        is OverlayState.Hidden -> {}
    }
}

@Composable
private fun CollapsedBall() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(BallIdle),
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
private fun ExpandedPanel() {
    // Placeholder for Phase 2+
    Box(
        modifier = Modifier
            .size(220.dp, 160.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text("Coati")
    }
}
