package bot.coati.state

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppPage(val name: String, var description: String, var outdated: Boolean = false)

data class CommonPath(val name: String, val steps: List<String>, var lastUsed: String, var successCount: Int)

data class PopupPattern(
    val signature: String,
    val title: String,
    val keywords: List<String>,
    val buttonText: String,
    var hitCount: Int,
)

data class AppProfile(
    val packageName: String,
    var appName: String = "",
    var uiTreeQuality: String = "",
    var lastUpdated: String = "",
    val pages: MutableList<AppPage> = mutableListOf(),
    val commonPaths: MutableList<CommonPath> = mutableListOf(),
    val knownIssues: MutableList<String> = mutableListOf(),
    val popupPatterns: MutableList<PopupPattern> = mutableListOf(),
) {
    fun upsertPage(name: String, description: String) {
        val existing = pages.find { it.name == name }
        if (existing != null) {
            existing.description = description
            existing.outdated = false
        } else {
            pages.add(AppPage(name, description))
        }
        lastUpdated = timestamp()
    }

    fun markOutdated(pageName: String) {
        pages.find { it.name == pageName }?.outdated = true
        lastUpdated = timestamp()
    }

    fun addOrUpdatePath(name: String, steps: List<String>) {
        val existing = commonPaths.find { it.name == name }
        if (existing != null) {
            existing.lastUsed = timestamp()
            existing.successCount++
        } else {
            commonPaths.add(CommonPath(name, steps, timestamp(), 1))
        }
        lastUpdated = timestamp()
    }

    fun upsertPopup(signature: String, title: String, keywords: List<String>, buttonText: String) {
        val existing = popupPatterns.find { it.signature == signature }
        if (existing != null) {
            existing.hitCount++
        } else {
            popupPatterns.add(PopupPattern(signature, title, keywords, buttonText, 1))
        }
        lastUpdated = timestamp()
    }

    fun toJson(): String {
        val pagesJson = pages.joinToString(",") { page ->
            """{"name":"${escapeJson(page.name)}","description":"${escapeJson(page.description)}","outdated":${page.outdated}}"""
        }
        val pathsJson = commonPaths.joinToString(",") { path ->
            val stepsJson = path.steps.joinToString(",") { "\"${escapeJson(it)}\"" }
            """{"name":"${escapeJson(path.name)}","steps":[$stepsJson],"lastUsed":"${escapeJson(path.lastUsed)}","successCount":${path.successCount}}"""
        }
        val issuesJson = knownIssues.joinToString(",") { "\"${escapeJson(it)}\"" }
        val popupsJson = popupPatterns.joinToString(",") { popup ->
            val kw = popup.keywords.joinToString(",") { "\"${escapeJson(it)}\"" }
            """{"signature":"${escapeJson(popup.signature)}","title":"${escapeJson(popup.title)}","keywords":[$kw],"buttonText":"${escapeJson(popup.buttonText)}","hitCount":${popup.hitCount}}"""
        }
        return """{
  "packageName": "${escapeJson(packageName)}",
  "appName": "${escapeJson(appName)}",
  "uiTreeQuality": "${escapeJson(uiTreeQuality)}",
  "lastUpdated": "${escapeJson(lastUpdated)}",
  "pages": [$pagesJson],
  "commonPaths": [$pathsJson],
  "knownIssues": [$issuesJson],
  "popupPatterns": [$popupsJson]
}"""
    }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# $appName ($packageName)")
        if (uiTreeQuality.isNotBlank()) sb.appendLine("- UI Tree Quality: $uiTreeQuality")
        sb.appendLine("- Last Updated: $lastUpdated")

        if (pages.isNotEmpty()) {
            sb.appendLine("\n## Known Pages")
            pages.forEach { page ->
                val prefix = if (page.outdated) "\u26a0\ufe0f " else ""
                sb.appendLine("- **${prefix}${page.name}**: ${page.description}")
            }
        }

        if (commonPaths.isNotEmpty()) {
            sb.appendLine("\n## Common Paths")
            commonPaths.forEach { path ->
                sb.appendLine("- **${path.name}** (used ${path.successCount}x)")
                path.steps.forEachIndexed { i, step ->
                    sb.appendLine("  ${i + 1}. $step")
                }
            }
        }

        if (knownIssues.isNotEmpty()) {
            sb.appendLine("\n## Known Issues")
            knownIssues.forEach { sb.appendLine("- $it") }
        }

        if (popupPatterns.isNotEmpty()) {
            sb.appendLine("\n## Known Popups")
            popupPatterns.forEach { popup ->
                sb.appendLine("- **${popup.title}**: click \"${popup.buttonText}\" (seen ${popup.hitCount}x)")
            }
        }

        return sb.toString().trimEnd()
    }

    companion object {
        fun fromJson(json: String): AppProfile {
            fun extractString(key: String): String {
                val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                return regex.find(json)?.groupValues?.get(1)?.unescapeJson() ?: ""
            }

            // Parse pages
            val pagesRegex = Regex(""""pages"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            val pagesMatch = pagesRegex.find(json)?.groupValues?.get(1) ?: ""
            val pages = if (pagesMatch.isBlank()) {
                mutableListOf()
            } else {
                val pageRegex = Regex("""\{"name"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"description"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"outdated"\s*:\s*(true|false)\}""")
                pageRegex.findAll(pagesMatch).map { m ->
                    AppPage(m.groupValues[1].unescapeJson(), m.groupValues[2].unescapeJson(), m.groupValues[3] == "true")
                }.toMutableList()
            }

            // Parse commonPaths
            val pathsRegex = Regex(""""commonPaths"\s*:\s*\[(.*?)](?=\s*,\s*"(?:knownIssues|popupPatterns)")""", RegexOption.DOT_MATCHES_ALL)
            val pathsMatch = pathsRegex.find(json)?.groupValues?.get(1) ?: ""
            val paths = if (pathsMatch.isBlank()) {
                mutableListOf()
            } else {
                val pathRegex = Regex("""\{"name"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"steps"\s*:\s*\[(.*?)]\s*,\s*"lastUsed"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"successCount"\s*:\s*(\d+)\}""", RegexOption.DOT_MATCHES_ALL)
                pathRegex.findAll(pathsMatch).map { m ->
                    val stepsStr = m.groupValues[2]
                    val stepRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
                    val steps = stepRegex.findAll(stepsStr).map { it.groupValues[1].unescapeJson() }.toList()
                    CommonPath(m.groupValues[1].unescapeJson(), steps, m.groupValues[3].unescapeJson(), m.groupValues[4].toInt())
                }.toMutableList()
            }

            // Parse knownIssues
            val issuesRegex = Regex(""""knownIssues"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            val issuesMatch = issuesRegex.find(json)?.groupValues?.get(1) ?: ""
            val issues = if (issuesMatch.isBlank()) {
                mutableListOf()
            } else {
                val strRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
                strRegex.findAll(issuesMatch).map { it.groupValues[1].unescapeJson() }.toMutableList()
            }

            // Parse popupPatterns — use greedy match to handle nested arrays in keywords
            val popupsStart = json.indexOf("\"popupPatterns\"")
            val popupsMatch = if (popupsStart >= 0) {
                val bracketStart = json.indexOf('[', popupsStart)
                if (bracketStart >= 0) {
                    // Find matching closing bracket, accounting for nesting
                    var depth = 0
                    var bracketEnd = -1
                    for (i in bracketStart until json.length) {
                        when (json[i]) {
                            '[' -> depth++
                            ']' -> { depth--; if (depth == 0) { bracketEnd = i; break } }
                        }
                    }
                    if (bracketEnd > bracketStart + 1) json.substring(bracketStart + 1, bracketEnd) else ""
                } else ""
            } else ""
            val popups = if (popupsMatch.isBlank()) {
                mutableListOf()
            } else {
                val popupRegex = Regex("""\{"signature"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"title"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"keywords"\s*:\s*\[(.*?)]\s*,\s*"buttonText"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"hitCount"\s*:\s*(\d+)\}""", RegexOption.DOT_MATCHES_ALL)
                popupRegex.findAll(popupsMatch).map { m ->
                    val kwStr = m.groupValues[3]
                    val kwRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
                    val kws = kwRegex.findAll(kwStr).map { it.groupValues[1].unescapeJson() }.toList()
                    PopupPattern(
                        m.groupValues[1].unescapeJson(),
                        m.groupValues[2].unescapeJson(),
                        kws,
                        m.groupValues[4].unescapeJson(),
                        m.groupValues[5].toInt(),
                    )
                }.toMutableList()
            }

            return AppProfile(
                packageName = extractString("packageName"),
                appName = extractString("appName"),
                uiTreeQuality = extractString("uiTreeQuality"),
                lastUpdated = extractString("lastUpdated"),
                pages = pages,
                commonPaths = paths,
                knownIssues = issues,
                popupPatterns = popups,
            )
        }

        private fun timestamp(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            return sdf.format(Date())
        }
    }
}

private fun timestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    return sdf.format(Date())
}
