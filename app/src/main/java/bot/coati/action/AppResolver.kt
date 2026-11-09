package bot.coati.action

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

class AppResolver(
    private val packageManager: PackageManager,
    private val selfPackage: String? = null,
) {

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
        return resolve(appName, listLaunchableApps(), selfPackage)
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
        private val nonWordRegex = Regex("[^\\p{L}\\p{N}]+")

        /**
         * [selfPackage] (Coati itself) matches only by its exact label or package: "Coati Manual Lab"
         * contains the label "Coati", and the agent opened itself for a whole task instead of the
         * fixture app labelled "Coati 手册实验室".
         */
        fun resolve(query: String, apps: List<LaunchableApp>, selfPackage: String? = null): Result {
            val normalizedQuery = normalize(query)
            if (normalizedQuery.isBlank()) return Result.NotFound(query)
            val others = apps.filter { it.packageName != selfPackage }

            return uniqueOrAmbiguous(query, apps.filter { normalize(it.label) == normalizedQuery })
                ?: uniqueOrAmbiguous(query, apps.filter { it.packageName.equals(query.trim(), ignoreCase = true) })
                ?: uniqueOrAmbiguous(query, others.filter { app ->
                    val label = normalize(app.label)
                    label.isNotBlank() && label.contains(normalizedQuery)
                })
                ?: uniqueOrAmbiguous(query, others.filter { app ->
                    val label = normalize(app.label)
                    label.isNotBlank() && normalizedQuery.contains(label)
                })
                ?: uniqueOrAmbiguous(
                    query,
                    others.filter { app ->
                        app.packageName.split('.').any { normalize(it) == normalizedQuery }
                    },
                )
                ?: tieBreak(query, others)?.let { Result.Resolved(it) }
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
                else -> tieBreak(query, matches)?.let { Result.Resolved(it) } ?: Result.Ambiguous(query, matches)
            }
        }

        /**
         * Among several matches, the one whose package name or label covers the most query words its
         * label leaves out, when exactly one covers the most. "Simple Calendar Pro" matched both apps
         * labelled "Calendar" through the shared word; only com.simplemobiletools.calendar.pro also
         * covers "simple" and "pro", and the ambiguity cost a step in every Simple Calendar episode.
         */
        internal fun tieBreak(query: String, matches: List<LaunchableApp>): LaunchableApp? {
            val words = words(query)
            if (words.isEmpty()) return null
            val scored = matches.map { app ->
                val labelWords = words(app.label).toSet()
                val segments = app.packageName.lowercase(Locale.ROOT).split('.', '_')
                app to words.count { word ->
                    word !in labelWords && word.length >= 2 && segments.any { it.startsWith(word) }
                }
            }
            val best = scored.maxOf { it.second }
            if (best == 0) return null
            return scored.singleOrNull { it.second == best }?.first
        }

        private fun words(value: String): List<String> =
            value.lowercase(Locale.ROOT).split(nonWordRegex).filter { it.isNotBlank() }

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
