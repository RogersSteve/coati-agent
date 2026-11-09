package bot.coati.agent

import bot.coati.data.SafetyMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDialogsOwnAccessTest {
    @Test
    fun `the dialog about the agent's own access is the user's even in trust mode`() {
        assertTrue(SecurityDialogs.userMustAnswer("com.oplus.safecenter", SafetyMode.TRUST))
        assertTrue(SecurityDialogs.userMustAnswer("com.oplus.safecenter", SafetyMode.STANDARD))
    }

    @Test
    fun `ordinary permission dialogs still follow the safety mode`() {
        assertFalse(SecurityDialogs.userMustAnswer("com.android.permissioncontroller", SafetyMode.TRUST))
        assertTrue(SecurityDialogs.userMustAnswer("com.android.permissioncontroller", SafetyMode.STANDARD))
        assertFalse(SecurityDialogs.userMustAnswer("com.example.app", SafetyMode.STANDARD))
    }
}
