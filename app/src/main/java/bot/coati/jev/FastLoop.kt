package bot.coati.jev

import bot.coati.model.LoopPlan

/**
 * A loop the Reasoning model handed to the fast path, with the progress the host has verified.
 *
 * A per-step selector has no notion of a loop: on Car Market it scrolled past the first rows of
 * "favorite the first 10 listings", hid one of six Sold listings and claimed the task done, and
 * scrolled instead of refreshing until a price dropped. Here the host keeps the count, the items
 * acted on and the stop condition; the selector only picks the next item.
 */
class FastLoop(val plan: LoopPlan) {

    enum class Stop(val description: String) {
        COUNT_REACHED("the requested number of items was handled"),
        CONDITION_MET("the stop condition holds on screen"),
        END_OF_LIST("no matching item is left: the list could not scroll further"),
        NO_PROGRESS("several attempts in a row changed nothing"),
        CONDITION_NOT_VISIBLE("the stop condition is not visible from the screen the action is on"),
        ITERATION_LIMIT("the iteration limit was reached"),
        SELECTOR_FINISHED("the selector found no further item to act on"),
    }

    /**
     * Item loops act on distinct items, so an item is never acted on twice (its control may toggle
     * back). A loop with only a stop condition repeats the same control (refresh until ...).
     */
    val itemLoop: Boolean get() = plan.count != null || scoped

    /**
     * Items in list order ("the first 10") or one control repeated ("refresh until"): once the
     * selector has found the control, the host repeats it on the next item without a model call.
     * Scoped loops ("rows marked Sold", given IDs) keep the selector choosing which item.
     */
    val hostSelects: Boolean get() = !scoped

    // A repeat-until loop may name the item it watches ("refresh until CM-9338 drops below $40,000",
    // planned with scope "car listing CM-9338"): taken as an item loop it tapped the listing, then
    // scrolled up and down for 200 steps. A scope whose every keyword appears in the stop condition
    // is that watched item, not the items to act on.

    /**
     * Whether the scope names which items (a badge, given IDs, a negated condition) rather than only
     * their order: "the first 10 listings in order" is an in-order loop.
     */
    val scoped: Boolean = plan.scope?.let { scope ->
        val keywords = keywordsOf(scope)
        (keywords.isNotEmpty() || isNegated(scope)) && !watches(keywords, plan.until)
    } ?: false

    /** The control the loop repeats ("Favorite", "Hide", "Refresh"), learned from its first success. */
    var control: String? = null
        private set
    var scrolls = 0
        private set

    /** Words of the scope that name matching items ("sold", "cm-3305"); empty when the scope is negated. */
    val scopeKeywords: Set<String> = plan.scope?.let(::keywordsOf).orEmpty()

    /**
     * Set once a scope keyword picked an item: from then on the host walks the list itself and
     * scrolls past pages without a match (the selector missed one of six Sold rows on its own).
     */
    var scopeMatched = false
        private set

    /** Whether an item's label or row texts contain a scope keyword. */
    fun matchesScope(label: String, rowContext: String): Boolean {
        if (scopeKeywords.isEmpty()) return false
        val words = tokens("$label $rowContext")
        return scopeKeywords.any { it in words }.also { if (it) scopeMatched = true }
    }

    /** A stop condition is checked by a model only for pure repeat-until loops; counts and scopes stop on their own. */
    val checksUntil: Boolean get() = plan.until != null && plan.count == null && !scoped

    private var unseen = 0

    /** The stop condition was checked on screen; null when the screen does not show what it is about. */
    fun recordConditionVisible(visible: Boolean) {
        unseen = if (visible) 0 else unseen + 1
    }

    /** A condition about something off this screen cannot stop the loop: the Reasoning model takes over. */
    val conditionUnseen: Boolean get() = unseen >= MAX_UNSEEN

    val iterationLimitReached: Boolean get() = !itemLoop && iterations >= MAX_REPEATS

    private val itemLabels = mutableListOf<String>()
    private val itemKeys = mutableSetOf<String>()

    val handled: List<String> get() = itemLabels
    var iterations = 0
        private set
    var reachedEnd = false
        private set
    var stop: Stop? = null
        private set
    private var idle = 0

    /** Whether an element names an item this loop already acted on. */
    fun isHandled(label: String): Boolean = itemLoop && itemKey(label) in itemKeys

    /** The previous loop action changed its target: one more item handled. */
    fun recordProgress(label: String) {
        iterations++
        idle = 0
        sinceProgress = 0
        if (control == null) control = controlOf(label).takeIf { it.isNotBlank() }
        val key = itemKey(label)
        if (!itemLoop || itemKeys.add(key)) itemLabels += label.lineSequence().first().take(LABEL_CHARS)
    }

    /** Any loop action, before its outcome is booked. */
    fun recordAction() {
        sinceProgress++
    }

    /** The previous loop action had no effect on its target (or opened something else). */
    fun recordNoProgress() {
        idle++
    }

    /** A scroll down that moved nothing: everything below has been seen. */
    fun recordEndOfList() {
        reachedEnd = true
    }

    /** A scroll that moved: lower items may still need handling. */
    fun recordScrolled() {
        reachedEnd = false
        scrolls++
    }

    /** Whether an element is the loop's control (on any item); false until the control is known. */
    fun isControl(label: String): Boolean = control?.equals(controlOf(label), ignoreCase = true) == true

    val countReached: Boolean get() = plan.count != null && itemLabels.size >= plan.count
    /**
     * Scrolls change the screen, so they never counted as "no effect": a loop that only scrolled up
     * and down ran to the 200-step task limit (R4, 3 of 5 runs). Any run of actions without an item
     * handled now ends the loop as well.
     */
    val stalled: Boolean get() = idle >= MAX_IDLE || sinceProgress >= MAX_ACTIONS_WITHOUT_PROGRESS
    private var sinceProgress = 0

    fun finish(reason: Stop) {
        stop = reason
    }

    /** The per-step goal for the selector while the loop runs. */
    fun guidance(): String = buildString {
        append("Repeat for each item: ${plan.each}.")
        plan.scope?.let { append(" Items: $it.") }
        plan.count?.let { append(" Handle $it items in total; ${itemLabels.size} done so far.") }
            ?: append(" ${itemLabels.size} done so far.")
        plan.until?.let { append(" Stop when: $it.") }
        append(" Act on the matching visible items from the top first; scroll down only when no matching visible item is left.")
        append(" Items already handled are not offered again.")
    }

    /** What the Reasoning model reads when the loop hands the task back. */
    fun summary(): String = "[LOOP DONE] ${itemLabels.size} items" +
        (if (itemLabels.isEmpty()) "" else ": ${itemLabels.joinToString("; ")}") +
        " ($iterations iterations; stopped because ${stop?.description ?: "it was interrupted"})."

    /** Host evidence for the completion audit. */
    fun evidence(): String = buildString {
        append("Per-item action: ${plan.each}")
        plan.scope?.let { append("; items: $it") }
        plan.count?.let { append("; requested count: $it") }
        plan.until?.let { append("; stop condition: $it") }
        append(". Items the host saw change after the action (${itemLabels.size}): ")
        append(if (itemLabels.isEmpty()) "none" else itemLabels.joinToString("; "))
        append(". Stopped because ${stop?.description ?: "it was interrupted"}.")
        if (scoped && !reachedEnd) {
            append(" The loop did NOT reach the end of the list, so matching items further down may not have been handled.")
        }
    }

    companion object {
        private const val MAX_IDLE = 3
        private const val MAX_ACTIONS_WITHOUT_PROGRESS = 8
        private const val MAX_UNSEEN = 2
        /** Repeat-until loops only; a monitor that never sees its condition must not run forever. */
        private const val MAX_REPEATS = 30
        private const val LABEL_CHARS = 60
        private val TOKEN_SPLIT = Regex("[\\s/()\\[\\]|·,:;]+")
        private val WHITESPACE = Regex("\\s+")

        private val NEGATION = setOf("not", "no", "non", "without", "except", "excluding", "unless")
        private val SCOPE_STOPWORDS = setOf(
            "the", "and", "all", "every", "each", "that", "are", "with", "which", "those", "these", "following",
            "row", "rows", "item", "items", "listing", "listings", "entry", "entries", "one", "ones", "marked",
            "labeled", "labelled", "shown", "showing", "given", "ids", "list", "badge", "status", "car", "cars",
            // Order words say which items only through their position.
            "first", "next", "top", "order", "sequence", "visible", "from", "starting", "beginning",
        )

        private fun isNegated(scope: String): Boolean = tokens(scope).any { it in NEGATION }

        private fun tokens(text: String): Set<String> =
            text.lowercase().split(TOKEN_SPLIT).map { it.trim('.', '\'', '"') }.filter { it.isNotEmpty() }.toSet()

        /** Keywords naming the items of a scope; none for a negated scope ("cars not marked Sold"). */
        fun keywordsOf(scope: String): Set<String> {
            val words = tokens(scope)
            if (isNegated(scope)) return emptySet()
            return words.filter { it.length >= 3 && it !in SCOPE_STOPWORDS }.toSet()
        }

        /** A control's own visible text: the first part of an element label ("Favorite / Favorite CM-7472"). */
        fun controlOf(label: String): String = label.substringBefore(" / ").lineSequence().first().trim()

        /**
         * The identity of the item an element acts on: its tokens that contain a digit (listing ids,
         * dates, prices), which survive a control's state flipping ("Favorite" -> "Favorited");
         * without such tokens, the whole normalized label.
         */
        internal fun watches(keywords: Set<String>, until: String?): Boolean =
            until != null && keywords.isNotEmpty() && keywords.all { until.lowercase().contains(it) }

        fun itemKey(label: String): String {
            val tokens = label.split(TOKEN_SPLIT).map { it.trim('.', '-').lowercase() }
                .filter { it.length >= 3 && it.any(Char::isDigit) }
                .toSortedSet()
            return if (tokens.isNotEmpty()) tokens.joinToString(" ") else label.lowercase().replace(WHITESPACE, " ").trim()
        }
    }
}
