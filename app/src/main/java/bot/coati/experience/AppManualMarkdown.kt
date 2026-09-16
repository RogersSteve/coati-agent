package bot.coati.experience

object AppManualMarkdownParser {
    private val headingRegex = Regex("^(#{1,3})\\s+(.+?)\\s*$")

    fun parse(raw: String): ParsedAppManual {
        val markdown = AppManualPolicy.normalizeMarkdown(raw)
        val lines = markdown.lines()
        var documentTitle: String? = null
        var currentH2: String? = null
        var currentHeading: HeadingBuilder? = null
        val sections = mutableListOf<ManualHeadingSection>()
        val paths = linkedSetOf<String>()
        var sawContentBeforeFirstH2 = false

        fun finishCurrent(endOffset: Int) {
            val heading = currentHeading ?: return
            sections += ManualHeadingSection(
                heading.level,
                heading.title,
                heading.path,
                markdown.substring(heading.startOffset, endOffset),
                heading.startOffset,
                endOffset,
            )
            currentHeading = null
        }

        var offset = 0
        lines.forEach { line ->
            val lineStart = offset
            offset += line.length + 1
            val match = headingRegex.matchEntire(line)
            if (match == null) {
                if (documentTitle == null && line.isNotBlank()) {
                    throw IllegalArgumentException("First content must be one H1 title")
                }
                if (documentTitle != null && currentHeading == null && line.isNotBlank()) {
                    sawContentBeforeFirstH2 = true
                }
                return@forEach
            }

            val level = match.groupValues[1].length
            val title = match.groupValues[2].trim()
            require(title.isNotBlank() && title.length <= AppManualPolicy.MAX_HEADING_CHARS) { "Invalid heading" }
            when (level) {
                1 -> {
                    require(documentTitle == null && sections.isEmpty() && currentHeading == null) {
                        "Manual must contain exactly one leading H1"
                    }
                    documentTitle = title
                }
                2 -> {
                    require(documentTitle != null) { "H2 requires H1" }
                    finishCurrent(lineStart)
                    currentH2 = title
                    require(paths.add(title)) { "Duplicate heading path: $title" }
                    currentHeading = HeadingBuilder(level, title, title, lineStart)
                }
                3 -> {
                    require(documentTitle != null && currentH2 != null) { "H3 requires H2" }
                    finishCurrent(lineStart)
                    val path = "$currentH2/$title"
                    require(paths.add(path)) { "Duplicate heading path: $path" }
                    currentHeading = HeadingBuilder(level, title, path, lineStart)
                }
            }
        }
        finishCurrent(markdown.length)

        require(documentTitle != null) { "Missing H1" }
        require(!sawContentBeforeFirstH2) { "Content between H1 and first H2 is not allowed" }
        require(sections.size <= AppManualPolicy.MAX_HEADINGS) { "Too many headings" }
        val h2 = sections.filter { it.level == 2 }.mapTo(linkedSetOf()) { it.title }
        require(h2.containsAll(AppManualPolicy.REQUIRED_H2)) { "Missing required H2" }
        return ParsedAppManual(documentTitle!!, sections)
    }

    private data class HeadingBuilder(
        val level: Int,
        val title: String,
        val path: String,
        val startOffset: Int,
    )
}

object AppManualFactory {
    fun create(
        identity: ManualAppIdentity,
        markdown: String,
        revision: Long = 1L,
        protectedHeadingPaths: Set<String> = setOf("App 信息"),
        now: Long = System.currentTimeMillis(),
        observedVersionCodes: List<Long> = listOf(identity.versionCode),
        localeTags: List<String> = listOf(identity.localeTag),
    ): AppManual {
        val normalized = AppManualPolicy.normalizeMarkdown(markdown)
        val metadata = AppManualMetadata(
            app = identity,
            revision = revision,
            updatedAtEpochMs = now,
            manualSha256 = AppManualCodec.sha256(normalized),
            estimatedTokens = ManualTokenEstimator.estimate(normalized),
            observedVersionCodes = observedVersionCodes.distinct().takeLast(8),
            localeTags = localeTags.distinct().takeLast(8),
            protectedHeadingPaths = protectedHeadingPaths,
        )
        return AppManual(metadata, normalized).also { manual ->
            AppManualPolicy.validateManual(manual)?.let { throw IllegalArgumentException(it) }
        }
    }
}
