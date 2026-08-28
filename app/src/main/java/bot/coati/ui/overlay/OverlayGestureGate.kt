package bot.coati.ui.overlay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps task-control buttons inert while an injected gesture is crossing the overlay window.
 *
 * WindowManager flag updates are asynchronous. The depth counter also makes nested callers safe:
 * controls are enabled again only after the outermost passthrough scope has finished restoring the
 * touchable overlay window.
 */
internal class OverlayGestureGate {
    private val activeScopes = AtomicInteger(0)

    val blocksTaskControls: Boolean
        get() = activeScopes.get() > 0

    fun enter() {
        activeScopes.incrementAndGet()
    }

    fun exit() {
        activeScopes.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }
}
