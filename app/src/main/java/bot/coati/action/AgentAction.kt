package bot.coati.action

sealed class AgentAction {
    data class Tap(val x: Int, val y: Int) : AgentAction()
    data class LongPress(val x: Int, val y: Int, val durationMs: Long = 1000) : AgentAction()
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = 400,
    ) : AgentAction()
    data class InputText(val text: String) : AgentAction()
    data class PressKey(val keyCode: String) : AgentAction()
    data class Scroll(val direction: String, val amount: Int = 500) : AgentAction()
    data class Wait(val ms: Long) : AgentAction()
    data object Screenshot : AgentAction()
    data class OpenApp(val packageName: String? = null, val appName: String? = null) : AgentAction()

    companion object {
        fun fromMap(action: String, params: Map<String, Any>): AgentAction {
            return when (action.lowercase()) {
                "tap" -> Tap(
                    x = (params["x"] as Number).toInt(),
                    y = (params["y"] as Number).toInt(),
                )
                "longpress", "long_press" -> LongPress(
                    x = (params["x"] as Number).toInt(),
                    y = (params["y"] as Number).toInt(),
                    durationMs = (params["durationMs"] as? Number)?.toLong() ?: 1000,
                )
                "swipe" -> Swipe(
                    x1 = (params["x1"] as Number).toInt(),
                    y1 = (params["y1"] as Number).toInt(),
                    x2 = (params["x2"] as Number).toInt(),
                    y2 = (params["y2"] as Number).toInt(),
                    durationMs = (params["durationMs"] as? Number)?.toLong() ?: 400,
                )
                "inputtext", "input_text" -> InputText(
                    text = params["text"] as String,
                )
                "presskey", "press_key" -> PressKey(
                    keyCode = params["keyCode"] as String,
                )
                "scroll" -> Scroll(
                    direction = params["direction"] as String,
                    amount = (params["amount"] as? Number)?.toInt() ?: 500,
                )
                "wait" -> Wait(
                    ms = (params["ms"] as Number).toLong(),
                )
                "screenshot" -> Screenshot
                "openapp", "open_app" -> {
                    val packageName = (params["packageName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    val appName = (params["appName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    if (packageName == null && appName == null) {
                        throw IllegalArgumentException("openApp requires packageName or appName")
                    }
                    OpenApp(packageName = packageName, appName = appName)
                }
                else -> throw IllegalArgumentException("Unknown action: $action")
            }
        }
    }
}

data class ActionResult(
    val success: Boolean,
    val message: String = "",
    val screenshotBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ActionResult) return false
        return success == other.success && message == other.message &&
            screenshotBytes.contentEquals(other.screenshotBytes)
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (screenshotBytes?.contentHashCode() ?: 0)
        return result
    }
}
