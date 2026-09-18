package bot.coati.experience

import android.util.Log
import bot.coati.action.AgentAction
import bot.coati.model.ManualPageObservation
import bot.coati.model.VerifyOutcome
import bot.coati.service.UiTreeNode
import kotlinx.coroutines.CancellationException
import java.io.Closeable

class AppManualRuntime(
    private val repository: AppManualRepository,
    private val renderer: AppManualRenderer,
    private val identityResolver: AppIdentityResolver,
    private val seedCatalog: ManualSeedCatalog,
    private val coordinator: ManualUpdateCoordinator,
    private val modeProvider: () -> ManualMode,
) : Closeable {
    private var currentTask: String? = null
    private var activeTaskMode: ManualMode? = null
    private var traceCollector: ManualTraceCollector? = null
    private var traceMode: ManualMode? = null

    fun startTask(task: String) {
        currentTask = task
        val mode = runCatching(modeProvider).getOrDefault(ManualMode.OFF)
        activeTaskMode = mode
        traceMode = mode.takeIf { it == ManualMode.LEARNING || it == ManualMode.SHADOW }
        traceCollector = traceMode?.let { ManualTraceCollector() }
        if (traceMode != null) Log.i(TAG, "learning stage=V2_EDITOR mode=$mode write=ENABLED")
    }

    suspend fun promptFor(packageName: String, uiTreeHint: String): RenderedAppManual? {
        return try {
            promptForInternal(packageName, uiTreeHint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "retrieval package=$packageName result=NONE reason=${error.javaClass.simpleName}")
            null
        }
    }

    private suspend fun promptForInternal(packageName: String, uiTreeHint: String): RenderedAppManual? {
        val mode = activeTaskMode ?: modeProvider()
        if (mode == ManualMode.OFF) return null
        val identity = identityResolver.resolve(packageName) ?: return null
        val namespace = if (mode == ManualMode.SHADOW) ManualNamespace.SHADOW else ManualNamespace.CANONICAL
        var manual = repository.load(identity.appKey, namespace)
        if (manual == null) {
            val seed = seedCatalog.create(identity)
            if (seed != null) {
                manual = when (val saved = repository.save(seed, expectedRevision = 0L, namespace = namespace)) {
                    is ManualSaveResult.Saved -> {
                        Log.i(TAG, "seed package=$packageName result=SAVED mode=$mode revision=${saved.manual.revision}")
                        saved.manual
                    }
                    ManualSaveResult.RevisionConflict -> repository.load(identity.appKey, namespace)
                    is ManualSaveResult.Rejected -> {
                        Log.w(TAG, "seed package=$packageName result=REJECTED reason=${saved.reason}")
                        null
                    }
                }
            }
        }
        if (mode == ManualMode.SHADOW) return null
        if (manual == null) {
            Log.i(TAG, "retrieval mode=$mode package=$packageName result=NONE reason=NO_MANUAL")
            return null
        }
        if (identity.versionCode !in manual.metadata.observedVersionCodes ||
            identity.localeTag !in manual.metadata.localeTags
        ) {
            Log.i(
                TAG,
                "compatibility package=$packageName result=ADVISORY_CURRENT_UI_FIRST " +
                    "storedVersions=${manual.metadata.observedVersionCodes.size} storedLocales=${manual.metadata.localeTags.size}",
            )
        }
        val rendered = renderer.render(manual, mode, currentTask.orEmpty(), uiTreeHint)
        if (rendered != null) {
            Log.i(
                TAG,
                "retrieval schema=v2 mode=$mode package=$packageName revision=${rendered.revision} " +
                    "headings=${rendered.headingPaths.size} chars=${rendered.text.length} tokens=${rendered.estimatedTokens}",
            )
        } else {
            Log.i(TAG, "retrieval schema=v2 mode=$mode package=$packageName result=NONE reason=RENDER_EMPTY")
        }
        return rendered
    }

    fun learningObservationInstruction(
        packageName: String,
        tree: UiTreeNode?,
        rendered: RenderedAppManual?,
    ): String? {
        val mode = activeTaskMode ?: runCatching(modeProvider).getOrDefault(ManualMode.OFF)
        if (mode !in setOf(ManualMode.SHADOW, ManualMode.LEARNING)) return null
        if (ManualLearningPolicy.blockReason(packageName, tree) != null) return null
        val known = rendered?.headingPaths?.filter { it.startsWith("页面详情/") }.orEmpty()
        return """
            [APP MANUAL LEARNING OBSERVATION]
            Observe only the currently visible page for later local manual maintenance.
            Known page heading paths in this step: ${known.joinToString(" | ").ifBlank { "none" }}
            MATCHED means an exact manual-heading match, not that the action or task target succeeded.
            Use MATCHED only when matchedHeadingPath is copied exactly from the shown list and its description fits the current page.
            If the shown list is none, MATCHED is forbidden; use NEW_OR_CHANGED for a stable identifiable page not covered by the manual.
            Use NEW_OR_CHANGED only for stable reusable UI knowledge that is absent or contradicted.
            Use UNKNOWN when uncertain. Keep fixed visible page/tab/control names even when named in the task. Omit user/private/dynamic content; do not invent placeholder page names.
        """.trimIndent()
    }

    fun recordVerifiedAction(
        packageName: String,
        outcome: VerifyOutcome,
        action: AgentAction,
        beforeTree: UiTreeNode?,
        afterTree: UiTreeNode?,
        observation: ManualPageObservation?,
        knownHeadingPaths: Set<String>,
    ) {
        if (outcome != VerifyOutcome.SUCCESS) return
        val identity = identityResolver.resolve(packageName) ?: return
        val result = traceCollector?.recordVerifiedSuccess(
            identity,
            observation,
            action,
            beforeTree,
            afterTree,
            knownHeadingPaths,
        ) ?: return
        logObservation(packageName, result, terminal = false)
    }

    fun recordVerifiedDestination(
        packageName: String,
        outcome: VerifyOutcome,
        action: AgentAction,
        beforeTree: UiTreeNode?,
        afterTree: UiTreeNode?,
        observation: ManualPageObservation?,
        knownHeadingPaths: Set<String>,
    ): Boolean {
        if (outcome != VerifyOutcome.SUCCESS) return false
        val identity = identityResolver.resolve(packageName) ?: return false
        val result = traceCollector?.recordVerifiedDestination(
            identity,
            observation,
            action,
            beforeTree,
            afterTree,
            knownHeadingPaths,
        ) ?: return false
        logObservation(packageName, result, terminal = false)
        return result is ManualObservationRecordResult.Accepted
    }

    /** Legacy verified-current-page admission retained for protocol/self-test compatibility. */
    fun recordVerifiedCurrentPage(
        packageName: String,
        tree: UiTreeNode?,
        observation: ManualPageObservation?,
        knownHeadingPaths: Set<String>,
    ): Boolean {
        val identity = identityResolver.resolve(packageName) ?: return false
        val result = traceCollector?.recordVerifiedCurrentPage(
            identity,
            observation,
            tree,
            knownHeadingPaths,
        ) ?: return false
        logObservation(packageName, result, terminal = false)
        return result is ManualObservationRecordResult.Accepted
    }

    /**
     * Records the page visible to the normal Reasoning turn. The optional preceding action is
     * attached only when AgentLoop independently observed a package or screen-signature change.
     * Fast Verify has no role in producing or admitting this manual content.
     */
    fun recordObservedCurrentPage(
        packageName: String,
        tree: UiTreeNode?,
        observation: ManualPageObservation?,
        knownHeadingPaths: Set<String>,
        precedingAction: AgentAction? = null,
        beforeTree: UiTreeNode? = null,
    ): Boolean {
        val identity = identityResolver.resolve(packageName) ?: return false
        val result = traceCollector?.recordObservedCurrentPage(
            identity = identity,
            raw = observation,
            tree = tree,
            knownHeadingPaths = knownHeadingPaths,
            precedingAction = precedingAction,
            beforeTree = beforeTree,
        ) ?: return false
        logObservation(packageName, result, terminal = false, source = "REASONING_CURRENT")
        return result is ManualObservationRecordResult.Accepted
    }

    fun recordCompletedObservation(
        packageName: String,
        tree: UiTreeNode?,
        observation: ManualPageObservation?,
        knownHeadingPaths: Set<String>,
        independentlyVerified: Boolean,
    ) {
        if (!independentlyVerified) {
            Log.i(
                TAG,
                "observation package=$packageName result=DROPPED " +
                    "reason=COMPLETION_AUDIT_UNAVAILABLE terminal=true",
            )
            return
        }
        val identity = identityResolver.resolve(packageName) ?: return
        val result = traceCollector?.recordCompletedPage(identity, observation, tree, knownHeadingPaths) ?: return
        logObservation(packageName, result, terminal = true)
    }

    private fun logObservation(
        packageName: String,
        result: ManualObservationRecordResult,
        terminal: Boolean,
        source: String? = null,
    ) {
        val sourceField = source?.let { " source=$it" }.orEmpty()
        when (result) {
            is ManualObservationRecordResult.Accepted -> Log.i(
                TAG,
                "observation package=$packageName result=ACCEPTED status=${result.status} " +
                    "duplicate=${result.duplicate} terminal=$terminal$sourceField " +
                    "evidencePolicy=STRUCTURAL_FIELDS_V2",
            )
            is ManualObservationRecordResult.Ignored -> Log.i(
                TAG,
                "observation package=$packageName result=DROPPED reason=${result.reason} " +
                    "terminal=$terminal$sourceField",
            )
        }
    }

    fun recordAttribution(rendered: RenderedAppManual?, claimedHeadings: List<String>) {
        if (rendered == null) return
        val claimed = claimedHeadings.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().take(8).toList()
        val valid = claimed.filter(rendered.headingPaths::contains)
        val invalidCount = claimed.count { it !in rendered.headingPaths }
        Log.i(
            TAG,
            "attribution schema=v2 appKey=${rendered.appKey.take(12)} revision=${rendered.revision} " +
                "used=${valid.joinToString("|").ifBlank { "none" }} invalid=$invalidCount",
        )
    }

    fun completeTask() {
        val collector = traceCollector.also { traceCollector = null }
        val mode = traceMode.also { traceMode = null }
        if (collector != null && mode != null) {
            val request = collector.buildRequest()
            if (request == null) Log.i(TAG, "enqueue accepted=false mode=$mode reason=NO_NEW_OR_CHANGED")
            else coordinator.enqueue(request, mode)
        }
        currentTask = null
        activeTaskMode = null
    }

    fun discardTask() {
        currentTask = null
        activeTaskMode = null
        traceCollector = null
        traceMode = null
    }

    override fun close() {
        discardTask()
        coordinator.close()
    }

    suspend fun closeAndJoin() {
        discardTask()
        coordinator.closeAndJoin()
    }

    companion object {
        private const val TAG = "CoatiManual"
    }
}
