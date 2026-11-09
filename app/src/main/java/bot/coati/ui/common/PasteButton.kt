package bot.coati.ui.common

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import bot.coati.R

/**
 * Long-press paste needs a floating toolbar, which an overlay window cannot show, and API keys are
 * too long to type: every field meant for long values offers this instead.
 */
@Composable
fun PasteButton(onPaste: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    TextButton(onClick = { clipboard.getText()?.text?.trim()?.takeIf(String::isNotEmpty)?.let(onPaste) }) {
        Text(stringResource(R.string.action_paste))
    }
}
