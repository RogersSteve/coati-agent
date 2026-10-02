package bot.coati.jev

data class JevProvider(val id: String, val displayName: String, val endpoint: String, val model: String)

/** Both providers use the TypeSafe-compatible System One protocol. */
object JevProviders {
    val typeSafe = JevProvider("typesafe", "TypeSafe", "https://api.typesafe.ai", "jev-1.13.0")
    val openRouter = JevProvider("openrouter", "OpenRouter", "https://openrouter.ai/api", "jev-1.13")
    val all = listOf(typeSafe, openRouter)

    fun find(id: String): JevProvider = all.first { it.id == id }
    fun forEndpoint(endpoint: String): JevProvider? =
        all.firstOrNull { it.endpoint == endpoint.trimEnd('/') }
}
