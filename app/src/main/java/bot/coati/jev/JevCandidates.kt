package bot.coati.jev

import bot.coati.perception.ElementOp
import bot.coati.perception.ParsedScreen
import bot.coati.perception.ScreenElement
import bot.coati.perception.ScrollAxis

/** Everything the executor may do on one screen. JEV only ever returns one of these keys. */
sealed interface CandidateAction {
    val key: String

    data class Element(val elementId: String, val op: ElementOp) : CandidateAction {
        override val key = "$elementId.${op.name}"
    }

    /** [direction] is content browsing (DOWN reveals lower content), matching ScrollProtocol. */
    data class Scroll(val regionId: String, val direction: String) : CandidateAction {
        override val key = "$regionId.SCROLL_$direction"
    }

    data class OpenApp(val appName: String) : CandidateAction {
        override val key = "OPEN_APP.$appName"
    }

    /** Back to the task's app after an unintended exit (e.g. BACK on its root page). */
    data class ReturnToApp(val packageName: String) : CandidateAction {
        override val key = "RETURN_TO_APP"
    }

    data object Back : CandidateAction { override val key = "BACK" }
    data object Enter : CandidateAction { override val key = "ENTER" }
    data object Wait : CandidateAction { override val key = "WAIT" }
    data object Done : CandidateAction { override val key = "DONE" }
    data object Escalate : CandidateAction { override val key = "ESCALATE" }
}

data class Candidate(val action: CandidateAction, val description: String) {
    val key: String get() = action.key
    val annotated: Boolean get() = description.contains("[tried") || description.contains("[leads")
}

object JevCandidates {

    /** Navigation candidates offered by the exploration question. */
    fun isNavigation(action: CandidateAction) =
        action !is CandidateAction.Done && action !is CandidateAction.Escalate && action !is CandidateAction.Wait

    fun build(
        screen: ParsedScreen,
        memory: ExplorationMemory?,
        excluded: Set<String>,
        apps: List<String>,
        canType: Boolean,
        returnTo: String? = null,
        /** Show each element's list-row texts (loop mode: which row a "Hide" button belongs to). */
        rowContext: Boolean = false,
        /** Elements to leave out (loop mode: items the loop already acted on). */
        skip: (ScreenElement) -> Boolean = { false },
    ): List<Candidate> {
        val here = screen.pageKey
        val out = mutableListOf<Candidate>()
        // Known dead ends are removed, not merely annotated: JEV kept choosing annotated revisits
        // with >0.5 confidence and circled four pages for 100+ steps on device.
        val deadEnds = mutableListOf<Candidate>()
        fun add(action: CandidateAction, description: String, identity: ExplorationMemory.ActionIdentity) {
            if (action.key in excluded) return
            val annotation = memory?.annotation(identity, here)
            val candidate = Candidate(action, if (annotation != null) "$description ${annotation.label}" else description)
            val deadEnd = annotation is ExplorationMemory.Annotation.NoEffect ||
                annotation is ExplorationMemory.Annotation.LeadsHere ||
                (annotation is ExplorationMemory.Annotation.LeadsToVisited && annotation.exhausted)
            if (deadEnd) deadEnds += candidate else out += candidate
        }
        for (e in screen.elements) {
            if (skip(e)) continue
            for (op in e.ops) {
                if (op == ElementOp.INPUT && !canType) continue
                val state = e.state?.let { " [$it]" }.orEmpty()
                val purpose = e.purpose.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                val recovery = if (op == ElementOp.TAP && isRecovery(e.text, e.purpose)) " [recovery]" else ""
                val row = if (rowContext && e.context.isNotBlank()) " [row: ${e.context}]" else ""
                add(
                    CandidateAction.Element(e.id, op),
                    "${op.name} ${e.type} '${e.text.take(50)}'$state$purpose$recovery$row",
                    identity(e.text, op.name),
                )
            }
        }
        for (r in screen.regions) {
            val directions = buildList {
                if (ScrollAxis.VERTICAL in r.axes) addAll(listOf("DOWN" to "reveal content below", "UP" to "reveal content above"))
                if (ScrollAxis.HORIZONTAL in r.axes) {
                    addAll(listOf("RIGHT" to "reveal content to the right", "LEFT" to "reveal content to the left"))
                }
            }
            for ((direction, effect) in directions) {
                val action = CandidateAction.Scroll(r.id, direction)
                add(action, "SCROLL_$direction in ${r.role} region: $effect", scrollIdentity(r.role, direction))
            }
        }
        returnTo?.let { pkg ->
            out += Candidate(
                CandidateAction.ReturnToApp(pkg),
                "return to the task's app ($pkg): the current screen belongs to another app that was not opened on purpose",
            )
        }
        apps.forEach { app ->
            val action = CandidateAction.OpenApp(app)
            add(action, "open the app '$app'", ExplorationMemory.ActionIdentity.PageRelative(action.key))
        }
        if (screen.keyboardVisible) {
            add(CandidateAction.Enter, "press Enter/Search on the keyboard to submit the typed text",
                ExplorationMemory.ActionIdentity.PageRelative("ENTER"))
        }
        add(CandidateAction.Back, "press system back", ExplorationMemory.ActionIdentity.PageRelative("BACK"))
        // Never leave the executor without a way to move: restore dead ends if nothing else remains.
        if (out.none { isNavigation(it.action) }) out += deadEnds
        out += Candidate(CandidateAction.Wait, "wait: only when the screen is visibly loading")
        if (CandidateAction.Done.key !in excluded) {
            out += Candidate(CandidateAction.Done, "the whole task is complete and its result is visible now")
        }
        out += Candidate(
            CandidateAction.Escalate,
            "hopeless here: needs another app or text that is not available; never use it merely because the target is not visible yet",
        )
        // Keep the question within the API option limit, dropping the least useful tail first.
        return if (out.size <= JevProtocol.MAX_OPTIONS) out
        else out.take(JevProtocol.MAX_OPTIONS - 4) + out.takeLast(4)
    }

    private val RECOVERY = Regex(
        "(?i)\\b(retry|try again|reload|refresh)\\b|重试|重新加载|重新载入|刷新|再试一次|点击重试",
    )

    /** Controls that recover from failed loads; preferred over navigating away from an error page. */
    fun isRecovery(text: String, purpose: String): Boolean = RECOVERY.containsMatchIn(text) || RECOVERY.containsMatchIn(purpose)

    fun identity(elementText: String, op: String): ExplorationMemory.ActionIdentity =
        ExplorationMemory.ActionIdentity.Element(op, elementText.lineSequence().firstOrNull().orEmpty().trim().take(24))

    /** Region ids are renumbered by every vision parse; role + direction is stable. */
    private fun scrollIdentity(role: String, direction: String) =
        ExplorationMemory.ActionIdentity.PageRelative("SCROLL_$direction:$role")

    /** Entries that explore the app (element actions and scrolls), as opposed to control actions. */
    fun explorationIdentities(candidates: List<Candidate>, screen: ParsedScreen): Set<ExplorationMemory.ActionIdentity> =
        candidates.filter { it.action is CandidateAction.Element || it.action is CandidateAction.Scroll }
            .filterNot { (it.action as? CandidateAction.Element)?.op == ElementOp.INPUT }
            .map { identityOf(it.action, screen) }
            .toSet()

    fun identityOf(action: CandidateAction, screen: ParsedScreen): ExplorationMemory.ActionIdentity = when (action) {
        is CandidateAction.Element -> identity(screen.element(action.elementId)?.text.orEmpty(), action.op.name)
        is CandidateAction.Scroll -> scrollIdentity(screen.region(action.regionId)?.role.orEmpty(), action.direction)
        else -> ExplorationMemory.ActionIdentity.PageRelative(action.key)
    }
}
