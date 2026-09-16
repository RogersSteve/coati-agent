package bot.coati.experience

enum class ManualMode {
    OFF,
    SHADOW,
    READ_ONLY,
    LEARNING,
}

data class ManualAppIdentity(
    val appKey: String,
    val packageName: String,
    val signerSha256: String,
    val displayName: String,
    val versionCode: Long,
    val localeTag: String,
)

/** Small machine-readable sidecar. Markdown remains the only source of manual content. */
data class AppManualMetadata(
    val schemaVersion: Int = AppManualPolicy.SCHEMA_VERSION,
    val app: ManualAppIdentity,
    val revision: Long,
    val updatedAtEpochMs: Long,
    val manualSha256: String,
    val estimatedTokens: Int,
    val observedVersionCodes: List<Long>,
    val localeTags: List<String>,
    val protectedHeadingPaths: Set<String>,
)

data class AppManual(
    val metadata: AppManualMetadata,
    val markdown: String,
) {
    val app: ManualAppIdentity get() = metadata.app
    val revision: Long get() = metadata.revision
}

data class ManualHeadingSection(
    val level: Int,
    val title: String,
    val path: String,
    /** Heading plus its direct body, excluding child headings. */
    val directMarkdown: String,
    /** Exact half-open span in the normalized source, never in a rendered excerpt. */
    val sourceStart: Int,
    val sourceEndExclusive: Int,
)

data class ParsedAppManual(
    val title: String,
    val sections: List<ManualHeadingSection>,
) {
    val headingPaths: Set<String> = sections.mapTo(linkedSetOf()) { it.path }

    fun section(path: String): ManualHeadingSection? = sections.firstOrNull { it.path == path }
}

data class RenderedAppManual(
    val appKey: String,
    val revision: Long,
    val text: String,
    val headingPaths: Set<String>,
    val estimatedTokens: Int,
)

enum class ManualObservationStatus {
    MATCHED,
    NEW_OR_CHANGED,
    UNKNOWN,
}

/** Sanitized, bounded evidence derived from a page the Reasoning VLM actually saw. */
data class VerifiedPageObservation(
    val status: ManualObservationStatus,
    val pageName: String,
    val matchedHeadingPath: String?,
    val visualSignature: String,
    val reusableFacts: List<String>,
    val navigationFacts: List<String>,
    val popupOrRecoveryFact: String?,
    val actionType: String?,
    val targetHint: String?,
    val beforeControls: List<String>,
    val afterControls: List<String>,
    val terminal: Boolean,
)

data class ManualAppLearningTrace(
    val identity: ManualAppIdentity,
    val observations: List<VerifiedPageObservation>,
)

data class ManualLearningRequest(
    val traces: List<ManualAppLearningTrace>,
)

enum class ManualCuratorTool {
    NO_CHANGE,
    PARTIAL_EDIT,
    SUMMARIZE,
}

data class ManualPartialEdit(
    val targetHeading: String,
    val oldText: String,
    val newText: String,
)

data class ManualCuratorDecision(
    val expectedRevision: Long,
    val tool: ManualCuratorTool,
    val edits: List<ManualPartialEdit>,
)

enum class ManualNamespace {
    CANONICAL,
    SHADOW,
}

sealed class ManualSaveResult {
    data class Saved(val manual: AppManual) : ManualSaveResult()
    data object RevisionConflict : ManualSaveResult()
    data class Rejected(val reason: String) : ManualSaveResult()
}
