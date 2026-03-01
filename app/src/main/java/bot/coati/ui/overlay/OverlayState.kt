package bot.coati.ui.overlay

sealed class OverlayState {
    data object Collapsed : OverlayState()
    data object Expanded : OverlayState()
    data object Hidden : OverlayState()
    data class Executing(val status: String, val progress: Float? = null) : OverlayState()
    data class Confirmation(val title: String, val detail: String) : OverlayState()
    data class Error(val message: String) : OverlayState()
}
