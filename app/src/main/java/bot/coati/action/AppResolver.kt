package bot.coati.action

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

class AppResolver(private val packageManager: PackageManager) {

    data class LaunchableApp(
        val label: String,
        val packageName: String,
        val activityName: String,
    )

    sealed class Result {
        data class Resolved(val app: LaunchableApp) : Result()
        data class Ambiguous(val query: String, val candidates: List<LaunchableApp>) : Result()
        data class NotFound(val query: String) : Result()
    }

    fun resolve(appName: String): Result {
        return resolve(appName, listLaunchableApps())
    }

    fun listLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        return resolveInfos
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val packageName = activityInfo.packageName?.trim().orEmpty()
                val activityName = activityInfo.name?.trim().orEmpty()
                if (label.isBlank() || packageName.isBlank() || activityName.isBlank()) {
                    null
                } else {
                    LaunchableApp(label, packageName, activityName)
                }
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    companion object {
        private val whitespaceRegex = Regex("\\s+")

        fun resolve(query: String, apps: List<LaunchableApp>): Result {
            val normalizedQuery = normalize(query)
            if (normalizedQuery.isBlank()) return Result.NotFound(query)

            return uniqueOrAmbiguous(query, apps.filter { normalize(it.label) == normalizedQuery })
                ?: uniqueOrAmbiguous(query, apps.filter { it.packageName.equals(query.trim(), ignoreCase = true) })
                ?: uniqueOrAmbiguous(query, apps.filter { app ->
                    val label = normalize(app.label)
                    label.isNotBlank() && label.contains(normalizedQuery)
                })
                ?: uniqueOrAmbiguous(query, apps.filter { app ->
                    val label = normalize(app.label)
                    label.isNotBlank() && normalizedQuery.contains(label)
                })
                ?: uniqueOrAmbiguous(
                    query,
                    apps.filter { app ->
                        app.packageName.split('.').any { normalize(it) == normalizedQuery }
                    },
                )
                ?: Result.NotFound(query)
        }

        fun looksLikePackageName(value: String): Boolean {
            val trimmed = value.trim()
            return trimmed.contains('.') &&
                trimmed.any { it.isLetter() } &&
                trimmed.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        }

        private fun uniqueOrAmbiguous(query: String, matches: List<LaunchableApp>): Result? {
            return when (matches.size) {
                0 -> null
                1 -> Result.Resolved(matches.first())
                else -> Result.Ambiguous(query, matches)
            }
        }

        private fun normalize(value: String): String {
            return value
                .trim()
                .lowercase(Locale.ROOT)
                .replace(whitespaceRegex, "")
                .removeSuffix("app")
                .removeSuffix("application")
        }
    }
}
