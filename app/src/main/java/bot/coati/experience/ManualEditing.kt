package bot.coati.experience

internal data class ManualWorkingCopy(
    val identity: ManualAppIdentity,
    val revision: Long,
    val markdown: String,
    val protectedHeadingPaths: Set<String>,
    val observedVersionCodes: List<Long>,
    val localeTags: List<String>,
)

internal object ManualWorkingCopies {
    fun from(current: AppManual?, identity: ManualAppIdentity): ManualWorkingCopy {
        if (current != null) {
            return ManualWorkingCopy(
                identity = identity,
                revision = current.revision,
                markdown = current.markdown,
                protectedHeadingPaths = current.metadata.protectedHeadingPaths,
                observedVersionCodes = (current.metadata.observedVersionCodes + identity.versionCode).distinct().takeLast(8),
                localeTags = (current.metadata.localeTags + identity.localeTag).distinct().takeLast(8),
            )
        }
        return ManualWorkingCopy(
            identity = identity,
            revision = 0L,
            markdown = initialMarkdown(identity),
            protectedHeadingPaths = setOf("App 信息"),
            observedVersionCodes = listOf(identity.versionCode),
            localeTags = listOf(identity.localeTag),
        )
    }

    private fun initialMarkdown(identity: ManualAppIdentity): String = buildString {
        appendLine("# ${safeInline(identity.displayName)} 使用手册")
        appendLine()
        appendLine(appInfo(identity).trimEnd())
        appendLine()
        appendLine("## App 用途")
        appendLine()
        appendLine("- 尚无已验证经验。")
        appendLine()
        appendLine("## 页面索引")
        appendLine()
        appendLine("- 尚无已验证经验。")
        appendLine()
        appendLine("## 页面详情")
        appendLine()
        appendLine("- 尚无已验证经验。")
        appendLine()
        appendLine("## 页面导航")
        appendLine()
        appendLine("- 尚无已验证经验。")
        appendLine()
        appendLine("## 弹窗与恢复")
        appendLine()
        appendLine("- 尚无已验证经验。")
    }

    fun appInfo(identity: ManualAppIdentity): String = """
        ## App 信息

        - 名称：${safeInline(identity.displayName)}
        - 包名：${identity.packageName}
        - 观察版本：${identity.versionCode}
        - 语言环境：${identity.localeTag}
    """.trimIndent() + "\n"

    private fun safeInline(value: String): String = value.replace('`', '\'').replace(Regex("\\s+"), " ").trim()
}

internal class ManualEditException(
    val reasonCode: String,
    val editIndex: Int? = null,
    val matchCount: Int? = null,
) : IllegalArgumentException(reasonCode)

internal object ManualPartialEditor {
    private data class LocatedEdit(val index: Int, val start: Int, val end: Int, val replacement: String)

    fun apply(
        base: ManualWorkingCopy,
        edits: List<ManualPartialEdit>,
        allowedHeadingPaths: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): Result<AppManual> = runCatching {
        if (edits.isEmpty() || edits.size > AppManualPolicy.MAX_PARTIAL_EDITS) fail("INVALID_EDIT_COUNT")
        val before = parseOrFail(base.markdown)
        val protectedBefore = protectedBlocks(before, base.protectedHeadingPaths)
        val original = AppManualPolicy.normalizeMarkdown(base.markdown)
        // All anchors resolve against one immutable revision, not earlier edits in this batch.
        val located = edits.mapIndexed { index, edit ->
            if (edit.targetHeading !in allowedHeadingPaths) fail("TARGET_NOT_IN_CONTEXT", index)
            val section = before.section(edit.targetHeading) ?: fail("TARGET_NOT_FOUND", index)
            if (base.protectedHeadingPaths.any { edit.targetHeading == it || edit.targetHeading.startsWith("$it/") }) {
                fail("PROTECTED_HEADING_CHANGED", index)
            }
            val oldText = normalizeNewlines(edit.oldText)
            val newText = normalizeNewlines(edit.newText)
            if (oldText.isBlank() || oldText.length > AppManualPolicy.MAX_EDIT_OLD_CHARS) {
                fail("INVALID_OLD_TEXT", index)
            }
            if (newText.length > AppManualPolicy.MAX_EDIT_NEW_CHARS || oldText == newText) {
                fail("INVALID_NEW_TEXT", index)
            }
            val first = section.directMarkdown.indexOf(oldText)
            if (first < 0) fail("OLD_TEXT_NOT_FOUND", index, 0)
            var count = 1
            var next = section.directMarkdown.indexOf(oldText, first + 1)
            while (next >= 0) {
                count++
                next = section.directMarkdown.indexOf(oldText, next + 1)
            }
            if (count > 1) fail("OLD_TEXT_NOT_UNIQUE", index, count)
            LocatedEdit(index, section.sourceStart + first, section.sourceStart + first + oldText.length, newText)
        }.sortedBy(LocatedEdit::start)
        located.zipWithNext().forEach { (left, right) ->
            if (left.end > right.start) fail("OVERLAPPING_EDITS", right.index)
        }
        var candidate = original
        located.asReversed().forEach { edit -> candidate = candidate.replaceRange(edit.start, edit.end, edit.replacement) }
        candidate = removeContradictoryPageDetailsPlaceholder(candidate)
        val parsedCandidate = parseOrFail(candidate)
        // A partial edit may add page details, but may not rename/remove existing headings
        // or re-parent unseen content by inserting a new top-level section.
        if (parsedCandidate.title != before.title || !parsedCandidate.headingPaths.containsAll(before.headingPaths) ||
            parsedCandidate.sections.any { it.path !in before.headingPaths &&
                (it.level != 3 || !it.path.startsWith("页面详情/")) }
        ) fail("HEADING_STRUCTURE_CHANGED")
        if (protectedBlocks(parsedCandidate, base.protectedHeadingPaths) != protectedBefore) {
            fail("PROTECTED_HEADING_CHANGED")
        }
        candidate = refreshAppInfo(candidate, base.identity)
        createManual(base, candidate, now)
    }

    fun applySummary(
        base: ManualWorkingCopy,
        summarizedMarkdown: String,
        now: Long = System.currentTimeMillis(),
    ): Result<AppManual> = runCatching {
        if (base.revision < 1L) fail("CANNOT_SUMMARIZE_EMPTY_MANUAL")
        val before = parseOrFail(base.markdown)
        val normalizedSummary = removeContradictoryPageDetailsPlaceholder(summarizedMarkdown)
        val after = parseOrFail(normalizedSummary)
        if (protectedBlocks(after, base.protectedHeadingPaths) != protectedBlocks(before, base.protectedHeadingPaths)) {
            fail("PROTECTED_HEADING_CHANGED")
        }
        val refreshed = refreshAppInfo(normalizedSummary, base.identity)
        val manual = createManual(base, refreshed, now)
        if (manual.metadata.estimatedTokens >= ManualTokenEstimator.estimate(base.markdown)) {
            fail("SUMMARY_NOT_SMALLER")
        }
        manual
    }

    private fun removeContradictoryPageDetailsPlaceholder(markdown: String): String {
        val normalized = AppManualPolicy.normalizeMarkdown(markdown)
        val parsed = parseOrFail(normalized)
        if (parsed.sections.none { it.level == 3 && it.path.startsWith("页面详情/") }) return normalized
        val parent = parsed.section("页面详情") ?: return normalized
        val placeholder = "- 尚无已验证经验。"
        val nextH2Start = parsed.sections.firstOrNull {
            it.level == 2 && it.sourceStart > parent.sourceStart
        }?.sourceStart ?: normalized.length
        val pageDetailsBlock = normalized.substring(parent.sourceStart, nextH2Start)
        if (!pageDetailsBlock.lineSequence().any { it.trim() == placeholder }) return normalized
        val cleaned = pageDetailsBlock.lineSequence()
            .filterNot { it.trim() == placeholder }
            .joinToString("\n")
            .trimEnd() + "\n\n"
        return normalized.replaceRange(parent.sourceStart, nextH2Start, cleaned)
    }

    private fun refreshAppInfo(markdown: String, identity: ManualAppIdentity): String {
        val normalized = AppManualPolicy.normalizeMarkdown(markdown)
        val parsed = parseOrFail(normalized)
        val current = parsed.section("App 信息") ?: fail("MISSING_APP_INFO")
        return normalized.replaceRange(
            current.sourceStart,
            current.sourceEndExclusive,
            ManualWorkingCopies.appInfo(identity).trimEnd() + "\n\n",
        )
    }

    private fun protectedBlocks(
        parsed: ParsedAppManual,
        paths: Set<String>,
    ): Map<String, String> = paths.associateWith { path ->
        if (parsed.section(path) == null) fail("MISSING_PROTECTED_HEADING")
        parsed.sections
            .filter { section -> section.path == path || section.path.startsWith("$path/") }
            .joinToString("\n") { section -> section.directMarkdown.trimEnd() }
    }

    private fun parseOrFail(markdown: String): ParsedAppManual = runCatching {
        AppManualMarkdownParser.parse(markdown)
    }.getOrElse { fail("INVALID_MARKDOWN_STRUCTURE") }

    private fun createManual(base: ManualWorkingCopy, markdown: String, now: Long): AppManual = try {
        AppManualFactory.create(
            identity = base.identity,
            markdown = markdown,
            revision = base.revision + 1L,
            protectedHeadingPaths = base.protectedHeadingPaths,
            now = now,
            observedVersionCodes = base.observedVersionCodes,
            localeTags = base.localeTags,
        )
    } catch (error: IllegalArgumentException) {
        val reason = error.message?.takeIf { it.matches(Regex("^[A-Z0-9_]{1,64}$")) }
            ?: "INVALID_MANUAL"
        fail(reason)
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun fail(reason: String, index: Int? = null, matches: Int? = null): Nothing =
        throw ManualEditException(reason, index, matches)
}
