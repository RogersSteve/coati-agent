package bot.coati.jev

import bot.coati.coordinates.PixelBounds
import bot.coati.perception.ElementOp
import bot.coati.perception.ParsedScreen
import bot.coati.perception.VisualChange

/** What the host itself can prove about the previous action, without any model. */
enum class HostVerdict {
    /** Neither pixels nor texts nor the target's state moved: the action certainly had no effect. */
    NO_CHANGE,

    /** The target element's own state/text changed, or pixels changed on it (e.g. a custom switch). */
    CHANGED_AT_TARGET,

    /** Something changed, but not at the target (toast, spinner, another widget). */
    CHANGED_ELSEWHERE,

    /** A different page, dialog or large content change. */
    PAGE_CHANGED,

    /** Not enough evidence (frames not comparable). */
    UNKNOWN,
}

data class HostObservation(
    val verdict: HostVerdict,
    val changedFraction: Double?,
    val sameTexts: Boolean,
    val note: String,
)

/**
 * Deterministic half of step verification. The expectation is phrased by the host from the
 * action type, so JEV verifies against a concrete claim instead of a vague "did it work".
 */
object StepVerifier {
    /**
     * Below this fraction of changed cells (of a 1/8-scale frame) the frame is visually unchanged:
     * a caret blink touches a few cells, while a toggled switch already changes ~0.4% of the screen.
     */
    const val NO_CHANGE_FRACTION = 0.0005
    private const val PAGE_CHANGE_FRACTION = 0.35

    fun expectation(action: CandidateAction, screen: ParsedScreen, typedText: String?): String = when (action) {
        is CandidateAction.Element -> {
            val element = screen.element(action.elementId)
            val name = element?.text?.lineSequence()?.firstOrNull()?.take(30).orEmpty().ifBlank { element?.type ?: "target" }
            when {
                action.op == ElementOp.INPUT -> "the field shows the text '${typedText.orEmpty().take(40)}'"
                action.op == ElementOp.LONG -> "a context menu, selection mode or detail popup appears for '$name'"
                element?.type == "switch" || element?.type == "checkbox" ->
                    "the '$name' toggle flips from '${element.state ?: "unknown"}' to the opposite state"
                element?.type == "tab" -> "the '$name' tab becomes selected and its content is shown"
                else -> "the page or section for '$name' opens, or '$name' visibly responds"
            }
        }
        is CandidateAction.Scroll -> "new content scrolls into the ${screen.region(action.regionId)?.role ?: "content"} region"
        is CandidateAction.OpenApp -> "the app '${action.appName}' comes to the foreground"
        is CandidateAction.ReturnToApp -> "the task's app (${action.packageName}) is in the foreground again"
        CandidateAction.Back -> "the previous page returns, or a dialog/keyboard closes"
        CandidateAction.Enter -> "the typed query/message is submitted and results or a new state appear"
        CandidateAction.Wait -> "loading finishes"
        CandidateAction.Done, CandidateAction.Escalate -> ""
    }

    fun observe(
        before: ParsedScreen,
        after: ParsedScreen,
        action: CandidateAction,
        typedText: String?,
        visual: VisualChange?,
    ): HostObservation {
        val sameTexts = before.packageName == after.packageName &&
            before.elements.map { it.text to it.state } == after.elements.map { it.text to it.state }
        val target = (action as? CandidateAction.Element)?.let { before.element(it.elementId) }
        val targetAfter = target?.let { t -> after.elements.firstOrNull { it.identity == t.identity && it.type == t.type } }
        val targetStateChanged = target != null && targetAfter != null && targetAfter.state != target.state
        val typedVisible = typedText?.takeIf { it.isNotBlank() }?.let { text ->
            after.elements.any { it.text.contains(text) }
        } ?: false

        val fraction = visual?.changedFraction
        // Pixels moving on the target itself are evidence even when tiny (a switch knob), so that
        // check precedes the no-change threshold.
        val changedAtTarget = target != null && visual?.bounds != null && overlaps(target.bounds, visual.bounds)
        val verdict = when {
            before.packageName != after.packageName -> HostVerdict.PAGE_CHANGED
            fraction == null -> if (sameTexts) HostVerdict.UNKNOWN else classifyByTexts(before, after)
            targetStateChanged || typedVisible -> HostVerdict.CHANGED_AT_TARGET
            // A state label flipping under the finger ("Visible" -> "Hidden") can be far below the
            // global threshold; pixels moving on the target itself still count.
            changedAtTarget && fraction > 0.0 && fraction < NO_CHANGE_FRACTION -> HostVerdict.CHANGED_AT_TARGET
            // Only a frame with no changed cell at all is a certain no-change: a counter beside a
            // "+" button changes a few cells next to the target, not on it.
            fraction < NO_CHANGE_FRACTION && sameTexts && visual?.bounds == null -> HostVerdict.NO_CHANGE
            fraction > PAGE_CHANGE_FRACTION || !ParsedScreen.samePage(before.pageKey, after.pageKey) ->
                HostVerdict.PAGE_CHANGED
            changedAtTarget -> HostVerdict.CHANGED_AT_TARGET
            else -> HostVerdict.CHANGED_ELSEWHERE
        }
        // Name the elements the pixel change touched, so JEV can match it against the expectation.
        val changedNear = visual?.bounds?.takeIf { fraction != null && fraction <= PAGE_CHANGE_FRACTION }?.let { box ->
            after.elements
                .map { it to overlapArea(it.bounds, box) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(MAX_CHANGED_NEAR)
                .map { (e, _) -> e.text.lineSequence().firstOrNull()?.take(24).orEmpty().ifBlank { e.type } }
        }.orEmpty()
        val note = buildString {
            append("host check: ${verdict.name.lowercase().replace('_', ' ')}")
            fraction?.let { append(", ${"%.1f".format(it * 100)}% of the screen changed") }
            if (changedNear.isNotEmpty()) append(", pixels changed at ${changedNear.joinToString { "'$it'" }}")
            if (targetStateChanged) append(", target state ${target?.state} -> ${targetAfter?.state}")
            if (typedText != null) append(if (typedVisible) ", typed text is visible" else ", typed text is NOT visible")
        }
        return HostObservation(verdict, fraction, sameTexts, note)
    }

    private fun classifyByTexts(before: ParsedScreen, after: ParsedScreen) =
        if (ParsedScreen.samePage(before.pageKey, after.pageKey)) HostVerdict.CHANGED_ELSEWHERE else HostVerdict.PAGE_CHANGED

    private fun overlaps(a: PixelBounds, b: PixelBounds) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun overlapArea(a: PixelBounds, b: PixelBounds): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0
    }

    private const val MAX_CHANGED_NEAR = 3
}
