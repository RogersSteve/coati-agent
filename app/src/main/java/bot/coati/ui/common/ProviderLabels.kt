package bot.coati.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import bot.coati.R
import bot.coati.model.ModelProvider

@Composable
fun modelProviderName(provider: ModelProvider): String {
    val resId = providerNameRes(provider.id)
    return if (resId != 0) stringResource(resId) else provider.displayName
}

@StringRes
private fun providerNameRes(id: String): Int = when (id) {
    "gemini" -> R.string.provider_gemini
    "openai" -> R.string.provider_openai
    "claude" -> R.string.provider_claude
    "deepseek" -> R.string.provider_deepseek
    "openai_compatible" -> R.string.provider_openai_compatible
    "claude_compatible" -> R.string.provider_claude_compatible
    else -> 0
}
