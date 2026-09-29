package bot.coati.agent

import bot.coati.data.SafetyMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDialogsTest {
    @Test fun `permission and install dialogs are security dialogs`() {
        for (pkg in listOf(
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )) assertTrue(pkg, SecurityDialogs.isSecurityDialog(pkg))
    }

    @Test fun `ordinary apps and unknown windows are not`() {
        for (pkg in listOf("tv.danmaku.bili", "com.android.settings", "com.android.systemui", "unknown", null)) {
            assertFalse("$pkg", SecurityDialogs.isSecurityDialog(pkg))
        }
    }

    @Test fun `only TRUST lets the agent answer`() {
        assertTrue(SecurityDialogs.agentMayAnswer(SafetyMode.TRUST))
        assertFalse(SecurityDialogs.agentMayAnswer(SafetyMode.STANDARD))
        assertFalse(SecurityDialogs.agentMayAnswer(SafetyMode.CAUTIOUS))
    }
}
