package bot.coati.fixture.manual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ManualLabColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF4D5F7D),
    secondaryContainer = Color(0xFFDCE3F2),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE1E5EE),
)

@Composable
internal fun ManualLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ManualLabColors,
        content = content,
    )
}
