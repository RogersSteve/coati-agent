package bot.coati.agent

enum class ScreenType { LIST, DIALOG, FORM, NAVIGATION, MEDIA, UNKNOWN }

enum class PerceptionSource { CLOUD_MODEL, UI_TREE_ONLY, RULE_ENGINE }

data class ScreenState(
    val screenType: ScreenType,
    val appPackage: String,
    val uiTreeQuality: UiTreeQuality,
    val nodeCount: Int,
    val textNodeCount: Int,
    val perceptionSource: PerceptionSource,
    val summary: String = "",
)
