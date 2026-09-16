package bot.coati.experience

/** Deterministic, bounded retrieval. It never calls a model and never injects the full manual by default. */
class AppManualRenderer {
    fun render(
        manual: AppManual,
        mode: ManualMode,
        task: String,
        uiTreeHint: String,
    ): RenderedAppManual? {
        if (mode == ManualMode.OFF || mode == ManualMode.SHADOW) return null
        val parsed = runCatching { AppManualMarkdownParser.parse(manual.markdown) }.getOrNull() ?: return null
        val included = linkedSetOf<String>()
        val header = buildString {
            appendLine("<ADVISORY_APP_REFERENCE>")
            appendLine("Package: ${manual.app.packageName}")
            appendLine("Revision: ${manual.revision}")
            appendLine("This Markdown is advisory and may be incomplete or outdated.")
            appendLine("Current screenshot, UI Tree, task constraints, safety rules, and verification always take priority.")
            appendLine("Only report heading paths shown in this reference as usedManualHeadings.")
        }
        val footer = "</ADVISORY_APP_REFERENCE>"
        val builder = StringBuilder(header)

        fun appendSection(section: ManualHeadingSection): Boolean {
            if (section.path in included) return true
            val block = buildString {
                appendLine()
                appendLine("[Manual heading: ${section.path}]")
                append(section.directMarkdown.trimEnd()).appendLine()
            }
            val candidate = builder.toString() + block + footer
            if (candidate.length > AppManualPolicy.MAX_RENDERED_CHARS ||
                ManualTokenEstimator.estimate(candidate) > AppManualPolicy.MAX_RENDERED_ESTIMATED_TOKENS
            ) return false
            builder.append(block)
            included += section.path
            return true
        }

        BASE_HEADING_PATHS.forEach { path -> parsed.section(path)?.let(::appendSection) }

        val context = (task + "\n" + uiTreeHint.take(MAX_UI_CONTEXT_CHARS)).lowercase()
        val candidates = parsed.sections
            .asSequence()
            .filter { it.level == 3 && it.path.startsWith("页面详情/") }
            .map { it to relevanceScore(it, context) }
            .filter { it.second >= MIN_DETAIL_RELEVANCE }
            .sortedWith(compareByDescending<Pair<ManualHeadingSection, Int>> { it.second }.thenBy { it.first.path })
            .take(MAX_DETAIL_SECTIONS)
            .map { it.first }
            .toList()
        if (candidates.isNotEmpty()) parsed.section("页面详情")?.let(::appendSection)
        candidates.forEach(::appendSection)

        if (included.isEmpty()) return null
        builder.append(footer)
        val text = builder.toString()
        return RenderedAppManual(
            appKey = manual.app.appKey,
            revision = manual.revision,
            text = text,
            headingPaths = included,
            estimatedTokens = ManualTokenEstimator.estimate(text),
        )
    }

    private fun relevanceScore(section: ManualHeadingSection, context: String): Int {
        val searchable = (section.path + "\n" + section.directMarkdown).lowercase()
        var score = 0
        val title = section.title.lowercase()
        title.takeIf { it.length >= 2 && context.contains(it) }?.let { score += 12 }
        title.removeSuffix("页").takeIf { it.length >= 2 && context.contains(it) }?.let { score += 8 }
        latinWords(context).forEach { token -> if (searchable.contains(token)) score += 2 }
        cjkBigrams(context).forEach { token -> if (searchable.contains(token)) score += 1 }
        return score
    }

    private fun latinWords(text: String): Set<String> {
        return Regex("[a-z][a-z0-9_]{2,}").findAll(text).map { it.value }.take(80).toSet()
    }

    private fun cjkBigrams(text: String): Set<String> {
        return Regex("[\\u3400-\\u9fff]{2,}").findAll(text).flatMap { match ->
            match.value.windowed(size = 2, step = 1, partialWindows = false).asSequence()
        }.take(120).toSet()
    }

    companion object {
        private const val MAX_UI_CONTEXT_CHARS = 12_000
        private const val MAX_DETAIL_SECTIONS = 2
        private const val MIN_DETAIL_RELEVANCE = 3
        private val BASE_HEADING_PATHS = listOf(
            "App 信息",
            "App 用途",
            "页面索引",
            "页面导航",
            "弹窗与恢复",
            "厂商预设边界",
        )
    }
}
