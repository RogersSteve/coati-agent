package bot.coati.service

import android.content.Intent
import android.content.pm.PackageManager

/** Packages of the device's home screens (launchers). Needs the HOME `<queries>` entry. */
object HomeScreens {
    fun packages(packageManager: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return realHomes(packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
            info.activityInfo?.packageName?.let { it to info.priority }
        })
    }

    /**
     * Settings declares a fallback home shown only during boot (priority -1000); counting it would
     * treat the Settings app as a home screen. Real launchers declare a non-negative priority.
     */
    internal fun realHomes(candidates: List<Pair<String, Int>>): Set<String> =
        candidates.filter { (_, priority) -> priority >= 0 }.map { it.first }.toSet()
}
