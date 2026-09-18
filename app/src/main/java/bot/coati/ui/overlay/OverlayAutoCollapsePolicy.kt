package bot.coati.ui.overlay

internal object OverlayAutoCollapsePolicy {

    fun shouldCollapse(
        packageName: String?,
        className: String?,
        homePackages: Set<String>,
    ): Boolean {
        val normalizedPackage = packageName.orEmpty().lowercase()
        val normalizedClass = className.orEmpty().lowercase()

        if (normalizedPackage in homePackages.map { it.lowercase() }) return true

        val isSystemUi = normalizedPackage == "com.android.systemui"
        return "recents" in normalizedClass ||
            "overview" in normalizedClass ||
            (isSystemUi && ("keyguard" in normalizedClass || "lockscreen" in normalizedClass))
    }
}
