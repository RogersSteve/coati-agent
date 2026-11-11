package bot.coati.model

enum class ApiFormat {
    GEMINI,
    OPENAI_COMPATIBLE,
    CLAUDE_COMPATIBLE,
}

enum class AuthStyle {
    QUERY_PARAM,
    BEARER,
    ANTHROPIC_HEADER,
}

data class ModelProvider(
    val id: String,
    val displayName: String,
    val apiFormat: ApiFormat,
    val authStyle: AuthStyle,
    val defaultEndpoint: String,
    val models: List<String>,
    val isCustom: Boolean = false,
)

object ModelProviders {

    val all: List<ModelProvider> = listOf(
        ModelProvider(
            id = "gemini",
            displayName = "Gemini (Recommended)",
            apiFormat = ApiFormat.GEMINI,
            authStyle = AuthStyle.QUERY_PARAM,
            defaultEndpoint = "https://generativelanguage.googleapis.com",
            models = listOf("gemini-4-carbon", "gemini-4-argon", "gemini-3.8-flash"),
        ),
        ModelProvider(
            id = "openai",
            displayName = "OpenAI",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            authStyle = AuthStyle.BEARER,
            defaultEndpoint = "https://api.openai.com",
            models = listOf(
                "gpt-6-astra",
                "gpt-6.1-sol",
                "gpt-6.1-terra",
                "gpt-6-sol",
                "gpt-6-luna",
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
            ),
        ),
        ModelProvider(
            id = "claude",
            displayName = "Claude",
            apiFormat = ApiFormat.CLAUDE_COMPATIBLE,
            authStyle = AuthStyle.ANTHROPIC_HEADER,
            defaultEndpoint = "https://api.anthropic.com",
            models = listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5"),
        ),
        ModelProvider(
            id = "deepseek",
            displayName = "DeepSeek",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            authStyle = AuthStyle.BEARER,
            defaultEndpoint = "https://api.deepseek.com",
            models = listOf("deepseek-chat"),
        ),
        ModelProvider(
            id = "openai_compatible",
            displayName = "OpenAI Compatible",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            authStyle = AuthStyle.BEARER,
            defaultEndpoint = "",
            models = emptyList(),
            isCustom = true,
        ),
        ModelProvider(
            id = "claude_compatible",
            displayName = "Claude Compatible",
            apiFormat = ApiFormat.CLAUDE_COMPATIBLE,
            authStyle = AuthStyle.ANTHROPIC_HEADER,
            defaultEndpoint = "",
            models = emptyList(),
            isCustom = true,
        ),
    )

    fun findById(id: String): ModelProvider? = all.find { it.id == id }
}
