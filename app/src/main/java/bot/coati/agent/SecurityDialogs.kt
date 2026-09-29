package bot.coati.agent

import bot.coati.data.SafetyMode

/**
 * System authorization dialogs: runtime-permission requests and package installs. Granting them is
 * the user's decision, like a first-use permission prompt in a coding agent. While one is showing,
 * Android also hides every non-system overlay (tapjacking protection), so Coati cannot show its
 * own confirmation on top of it.
 */
object SecurityDialogs {
    /** Maximum time to wait for the user to answer a system dialog before failing the task. */
    const val USER_RESPONSE_TIMEOUT_MS = 300_000L

    private val PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    fun isSecurityDialog(packageName: String?): Boolean = packageName in PACKAGES

    /**
     * Only a fully trusting user (TRUST) lets the agent answer these dialogs itself; every other
     * mode hands them to the user.
     */
    fun agentMayAnswer(mode: SafetyMode): Boolean = mode == SafetyMode.TRUST

    const val USER_HANDLED_FEEDBACK =
        "The user answered a system permission or install dialog themselves. Re-observe the screen; " +
            "if access was denied, continue without it or report that the task needs it."
}
