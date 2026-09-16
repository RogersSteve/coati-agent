package bot.coati.experience

/**
 * Network-independent last resort for already admitted, sanitized observations.
 *
 * The model curator remains the quality-first path. This merger is intentionally
 * template based: it cannot invent facts, preserves unobserved page blocks, and
 * still has to pass the same factory and semantic guards before it can be saved.
 */
internal object ManualDeterministicFallback {
    private const val EMPTY_EXPERIENCE = "- 尚无已验证经验。"
    private val generatedPagePrefixes = listOf(
        "- 识别：",
        "- 要点：",
        "- 导航：",
        "- 弹窗与恢复：",
    )

    fun merge(
        base: ManualWorkingCopy,
        trace: ManualAppLearningTrace,
        now: Long = System.currentTimeMillis(),
    ): Result<AppManual?> = runCatching {
        val changed = trace.observations
            .filter { it.status == ManualObservationStatus.NEW_OR_CHANGED }
            .groupByTo(linkedMapOf(), VerifiedPageObservation::pageName)
        if (changed.isEmpty()) return@runCatching null

        val original = AppManualPolicy.normalizeMarkdown(base.markdown)
        val parsed = AppManualMarkdownParser.parse(original)
        val replacements = mutableListOf<Replacement>()
        val existingPages = parsed.sections
            .filter { it.level == 3 && it.path.startsWith("页面详情/") }
            .associateByTo(linkedMapOf()) { it.path.removePrefix("页面详情/") }

        changed.forEach { (pageName, observations) ->
            existingPages[pageName]?.let { section ->
                replace(replacements, section, renderPage(pageName, observations, section.directMarkdown))
            }
        }

        val newPages = changed.filterKeys { it !in existingPages }
        if (newPages.isNotEmpty()) {
            val parent = requireNotNull(parsed.section("页面详情"))
            val parentWithoutPlaceholder = parent.directMarkdown.lineSequence()
                .filterNot { it.trim() == EMPTY_EXPERIENCE }
                .joinToString("\n")
                .trimEnd()
            val addition = newPages.entries.joinToString("\n\n") { (name, observations) ->
                renderPage(name, observations, null).trim()
            }
            replace(replacements, parent, "$parentWithoutPlaceholder\n\n$addition\n\n")
        }

        replace(replacements, requireNotNull(parsed.section("页面索引")), renderIndex(parsed, changed))
        renderNavigation(parsed, changed)?.let { navigation ->
            replace(replacements, requireNotNull(parsed.section("页面导航")), navigation)
        }
        renderPopupRecovery(parsed, changed)?.let { popup ->
            replace(replacements, requireNotNull(parsed.section("弹窗与恢复")), popup)
        }

        val allPageNames = (existingPages.keys + newPages.keys).distinct()
        val purposeSection = requireNotNull(parsed.section("App 用途"))
        if (allPageNames.size >= 2 && purposeSection.directMarkdown.lineSequence().any {
                it.trim() == EMPTY_EXPERIENCE
            }
        ) {
            val examples = allPageNames.take(4).joinToString("、")
            replace(
                replacements,
                purposeSection,
                "## App 用途\n\n- 用于访问${examples}等已验证页面和功能。\n\n",
            )
        }

        replace(
            replacements,
            requireNotNull(parsed.section("App 信息")),
            ManualWorkingCopies.appInfo(base.identity).trimEnd() + "\n\n",
        )

        var markdown = original
        replacements.distinctBy { it.start to it.end }.sortedByDescending(Replacement::start).forEach { replacement ->
            markdown = markdown.replaceRange(replacement.start, replacement.end, replacement.text)
        }
        markdown = AppManualPolicy.normalizeMarkdown(markdown)
        if (markdown == original) return@runCatching null

        AppManualFactory.create(
            identity = base.identity,
            markdown = markdown,
            revision = base.revision + 1L,
            protectedHeadingPaths = base.protectedHeadingPaths,
            now = now,
            observedVersionCodes = base.observedVersionCodes,
            localeTags = base.localeTags,
        ).also { candidate -> ManualSemanticGuard.validate(base, candidate, trace) }
    }

    private fun renderPage(
        pageName: String,
        observations: List<VerifiedPageObservation>,
        existing: String?,
    ): String {
        val signature = observations.maxByOrNull { it.visualSignature.length }!!.visualSignature
        val reusable = observations.flatMap(VerifiedPageObservation::reusableFacts).distinct().take(4)
        val navigation = observations.flatMap(VerifiedPageObservation::navigationFacts).distinct().take(3)
        val popup = observations.mapNotNull(VerifiedPageObservation::popupOrRecoveryFact).distinct().take(2)
        val preserved = existing.orEmpty().lineSequence()
            .drop(1)
            .map(String::trim)
            .filter { line ->
                line.isNotEmpty() && line != EMPTY_EXPERIENCE && generatedPagePrefixes.none(line::startsWith)
            }
            .toList()

        return buildString {
            appendLine("### $pageName")
            appendLine("- 识别：${sentence(signature)}")
            if (reusable.isNotEmpty()) appendLine("- 要点：${sentence(reusable.joinToString("；"))}")
            if (navigation.isNotEmpty()) appendLine("- 导航：${sentence(navigation.joinToString("；"))}")
            if (popup.isNotEmpty()) appendLine("- 弹窗与恢复：${sentence(popup.joinToString("；"))}")
            preserved.forEach(::appendLine)
            appendLine()
        }
    }

    private fun renderIndex(
        parsed: ParsedAppManual,
        changed: Map<String, List<VerifiedPageObservation>>,
    ): String {
        val section = requireNotNull(parsed.section("页面索引"))
        val entries = linkedMapOf<String, String>()
        val unkeyed = mutableListOf<String>()
        bodyLines(section).filterNot { it == EMPTY_EXPERIENCE }.forEach { line ->
            indexName(line)?.let { entries[it] = line } ?: unkeyed.add(line)
        }
        changed.forEach { (pageName, observations) ->
            val signature = observations.maxByOrNull { it.visualSignature.length }!!.visualSignature
            val entry = observations.flatMap(VerifiedPageObservation::navigationFacts).firstOrNull()
            entries[pageName] = buildString {
                append("- $pageName｜识别：${plain(signature)}")
                entry?.let { append("｜入口：${plain(it)}") }
            }
        }
        return section("页面索引", entries.values + unkeyed)
    }

    private fun renderNavigation(
        parsed: ParsedAppManual,
        changed: Map<String, List<VerifiedPageObservation>>,
    ): String? {
        val additions = changed.values.flatten().flatMap(VerifiedPageObservation::navigationFacts).distinct()
        if (additions.isEmpty()) return null
        val current = bodyLines(requireNotNull(parsed.section("页面导航")))
            .filterNot { it == EMPTY_EXPERIENCE }
        val merged = (current + additions.map { "- ${sentence(it)}" }).distinct()
        return section("页面导航", merged)
    }

    private fun renderPopupRecovery(
        parsed: ParsedAppManual,
        changed: Map<String, List<VerifiedPageObservation>>,
    ): String? {
        val additions = changed.values.flatten().mapNotNull(VerifiedPageObservation::popupOrRecoveryFact).distinct()
        if (additions.isEmpty()) return null
        val current = bodyLines(requireNotNull(parsed.section("弹窗与恢复")))
            .filterNot { it == EMPTY_EXPERIENCE }
        val merged = (current + additions.map { "- ${sentence(it)}" }).distinct()
        return section("弹窗与恢复", merged)
    }

    private fun bodyLines(section: ManualHeadingSection): List<String> = section.directMarkdown
        .lineSequence()
        .drop(1)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    private fun section(title: String, lines: Collection<String>): String = buildString {
        appendLine("## $title")
        appendLine()
        lines.forEach(::appendLine)
        appendLine()
    }

    private fun indexName(line: String): String? {
        if (!line.startsWith("- ")) return null
        return line.removePrefix("- ")
            .substringBefore('｜')
            .substringBefore('：')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun sentence(value: String): String = plain(value).trimEnd('。', '.', '；', ';') + "。"

    private fun plain(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun replace(replacements: MutableList<Replacement>, section: ManualHeadingSection, text: String) {
        if (section.directMarkdown != text) {
            replacements += Replacement(section.sourceStart, section.sourceEndExclusive, text)
        }
    }

    private data class Replacement(val start: Int, val end: Int, val text: String)
}
