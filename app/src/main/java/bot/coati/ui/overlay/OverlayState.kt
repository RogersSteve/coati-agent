package bot.coati.ui.overlay

sealed class OverlayState {
    data object Collapsed : OverlayState()
    data object Expanded : OverlayState()
    data object Hidden : OverlayState()
}
