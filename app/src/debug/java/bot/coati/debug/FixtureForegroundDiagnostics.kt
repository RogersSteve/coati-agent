package bot.coati.debug

/** Debug-only primitive state; no window titles, UI text or exception messages. */
internal data class FixtureForegroundState(
    val resumed: Boolean, val focused: Boolean, val productServiceRunning: Boolean,
    val accessibilityPresent: Boolean, val accessibilitySame: Boolean
) {
    fun failure(): String? = when {
        !resumed -> "fixture_not_resumed"
        !focused -> "fixture_window_unfocused"
        productServiceRunning -> "stop_coati_overlay_service_first"
        !accessibilityPresent -> "accessibility_not_ready"
        !accessibilitySame -> "accessibility_instance_changed"
        else -> null
    }
}

internal class FixtureFirstFailure {
    var code: String? = null; private set
    var step: String? = null; private set
    fun record(code: String, step: String) {
        if (this.code == null) { this.code = code; this.step = step }
    }
}
