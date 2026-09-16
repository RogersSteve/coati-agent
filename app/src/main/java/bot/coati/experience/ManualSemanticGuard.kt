package bot.coati.experience

/** Narrow loss guard, not a general semantic verifier or a manual-wide word blacklist. */
internal object ManualSemanticGuard {
    private const val EMPTY_EXPERIENCE = "- 尚无已验证经验。"
    private val placeholders = Regex("动态值|隐私值|动态链接|\\bdynamic value\\b|\\bprivate value\\b|\\bredacted\\b", RegexOption.IGNORE_CASE)

    fun hasPlaceholder(text: String): Boolean = placeholders.containsMatchIn(text)

    /**
     * Existing prose containing these words may remain unchanged or be compacted. A new
     * placeholder-bearing sentence must have literal support in the old manual or evidence;
     * the curator may not manufacture one by substituting a formerly useful UI name.
     */
    fun validate(base: ManualWorkingCopy, candidate: AppManual, trace: ManualAppLearningTrace) {
        val sources = (base.markdown.lineSequence() +
            ManualCurationContextBuilder.evidence(trace).lineSequence()).map(::content).filter(String::isNotEmpty).toList()
        candidate.markdown.lineSequence().map(::content).filter { hasPlaceholder(it) }.forEach { line ->
            if (sources.none { source -> source.contains(line) }) {
                throw ManualEditException("UNSUPPORTED_PLACEHOLDER")
            }
        }
        validateCoverage(candidate.markdown, trace)
        validatePurpose(candidate.markdown)
        validatePurposePreservation(base.markdown, candidate.markdown)
    }

    /** Every admitted new/changed page must remain explicit, not merely implied in prose. */
    fun validateCoverage(markdown: String, trace: ManualAppLearningTrace) {
        val headings = AppManualMarkdownParser.parse(markdown).headingPaths
        val missing = trace.observations.asSequence()
            .filter { it.status == ManualObservationStatus.NEW_OR_CHANGED }
            .map { "页面详情/${it.pageName}" }
            .distinct()
            .firstOrNull { it !in headings }
        if (missing != null) throw ManualEditException("UNCOVERED_NEW_PAGE")
    }

    /** Two known pages are enough evidence for a short purpose; template residue is no longer honest. */
    fun validatePurpose(markdown: String) {
        val parsed = AppManualMarkdownParser.parse(markdown)
        val knownPages = parsed.sections.count { section ->
            section.level == 3 && section.path.startsWith("页面详情/")
        }
        if (knownPages < 2) return

        val purpose = parsed.section("App 用途") ?: throw ManualEditException("APP_PURPOSE_REQUIRED")
        val bodyLines = purpose.directMarkdown.lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (bodyLines.isEmpty() || bodyLines.any { it == EMPTY_EXPERIENCE }) {
            throw ManualEditException("APP_PURPOSE_REQUIRED")
        }
    }

    /** Incremental learning may extend an established purpose, but never replace old valid scope. */
    fun validatePurposePreservation(baseMarkdown: String, candidateMarkdown: String) {
        val oldFacts = purposeFacts(baseMarkdown)
        if (oldFacts.isEmpty()) return
        val candidateFacts = purposeFacts(candidateMarkdown).toSet()
        if (oldFacts.any { it !in candidateFacts }) {
            throw ManualEditException("APP_PURPOSE_REGRESSION")
        }
    }

    /** Host-computed prompt hint so the normal first curator call can satisfy the gate. */
    fun purposeRequiredAfterUpdate(markdown: String, trace: ManualAppLearningTrace): Boolean {
        val knownPages = AppManualMarkdownParser.parse(markdown).sections.asSequence()
            .filter { it.level == 3 && it.path.startsWith("页面详情/") }
            .map { it.path.removePrefix("页面详情/") }
            .toMutableSet()
        trace.observations.asSequence()
            .filter { it.status == ManualObservationStatus.NEW_OR_CHANGED }
            .mapTo(knownPages, VerifiedPageObservation::pageName)
        return knownPages.size >= 2
    }

    private fun content(line: String): String = line.trim().trimStart('#', '-', '*', ' ').trim()

    private fun purposeFacts(markdown: String): List<String> = AppManualMarkdownParser.parse(markdown)
        .section("App 用途")
        ?.directMarkdown
        ?.lineSequence()
        ?.drop(1)
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() && it != EMPTY_EXPERIENCE }
        ?.toList()
        .orEmpty()
}
