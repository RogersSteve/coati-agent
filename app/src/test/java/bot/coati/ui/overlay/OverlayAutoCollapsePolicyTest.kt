package bot.coati.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAutoCollapsePolicyTest {

    private val homePackages = setOf("com.example.launcher")

    @Test
    fun `collapses when the launcher becomes foreground`() {
        assertTrue(
            OverlayAutoCollapsePolicy.shouldCollapse(
                packageName = "com.example.launcher",
                className = "com.example.launcher.HomeActivity",
                homePackages = homePackages,
            ),
        )
    }

    @Test
    fun `collapses for recent apps and keyguard windows`() {
        assertTrue(
            OverlayAutoCollapsePolicy.shouldCollapse(
                packageName = "com.android.systemui",
                className = "com.android.systemui.recents.RecentsActivity",
                homePackages = homePackages,
            ),
        )
        assertTrue(
            OverlayAutoCollapsePolicy.shouldCollapse(
                packageName = "com.android.systemui",
                className = "com.android.keyguard.KeyguardHostView",
                homePackages = homePackages,
            ),
        )
    }

    @Test
    fun `does not collapse for ordinary app or system dialogs`() {
        assertFalse(
            OverlayAutoCollapsePolicy.shouldCollapse(
                packageName = "com.example.browser",
                className = "com.example.browser.MainActivity",
                homePackages = homePackages,
            ),
        )
        assertFalse(
            OverlayAutoCollapsePolicy.shouldCollapse(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                homePackages = homePackages,
            ),
        )
    }
}
