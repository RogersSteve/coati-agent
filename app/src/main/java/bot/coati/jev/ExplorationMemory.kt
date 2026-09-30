package bot.coati.jev

import bot.coati.perception.ParsedScreen

/**
 * What an action did last time, recorded by the host rather than guessed by a model.
 *
 * JEV reliably picks a visible target, but when the target is not on screen it tends to revisit
 * familiar pages. Annotating candidates with their known destinations, and exploring untried
 * entries first, turned a looping 25-step failure into a 6-step success in device tests.
 */
class ExplorationMemory {

    /** Element actions are global (a bottom tab leads to the same page from anywhere). */
    sealed interface ActionIdentity {
        data class Element(val op: String, val text: String) : ActionIdentity
        data class PageRelative(val key: String) : ActionIdentity
    }

    private data class Edge(
        val from: Set<String>,
        val action: ActionIdentity,
        val to: Set<String>,
        val toSummary: String,
    )

    private val edges = mutableListOf<Edge>()
    private val visits = mutableListOf<Set<String>>()
    private val offered = mutableListOf<Pair<Set<String>, Set<ActionIdentity>>>()

    /** Records the navigation entries a page offered, so exhausted pages can be recognized. */
    fun recordOffered(page: Set<String>, actions: Set<ActionIdentity>) {
        offered += page to actions
    }

    /**
     * A page is exhausted when every navigation entry it ever offered has been tried. Only such
     * destinations are dead ends; a revisited page with untried entries is still frontier.
     */
    fun isExhausted(page: Set<String>): Boolean {
        val entries = offered.filter { ParsedScreen.samePage(it.first, page) }.flatMap { it.second }.toSet()
        if (entries.isEmpty()) return false
        return entries.all { action ->
            edges.any { edge ->
                edge.action == action && (action is ActionIdentity.Element || ParsedScreen.samePage(edge.from, page))
            }
        }
    }

    fun visit(page: Set<String>) {
        visits += page
    }

    fun record(from: Set<String>, action: ActionIdentity, to: Set<String>, toSummary: String) {
        edges += Edge(from, action, to, toSummary)
    }

    fun visitCount(page: Set<String>): Int = visits.count { ParsedScreen.samePage(it, page) }

    /**
     * True when the last [window] visits bounce between 2..[maxDistinct] pages: every step
     * "succeeds" (the screen changes) yet the task makes no progress. Staying on one page is not a
     * cycle: filling a form changes state without changing pages (see [isStagnant]).
     */
    fun isCycling(window: Int = 6, maxDistinct: Int = 2): Boolean {
        // Only visits since the last intervention count; the break marker itself is not a page.
        val recent = visits.drop(visits.indexOfLast { CYCLE_BREAK in it } + 1)
        if (recent.size < window) return false
        val distinct = distinctPages(recent.takeLast(window))
        return distinct in 2..maxDistinct
    }

    /**
     * True when none of the last [window] visits reached a page never seen before: the executor
     * is circling through known pages (a cycle over any number of pages, not just two).
     */
    fun isStagnant(window: Int = 8, singlePageWindow: Int = 12): Boolean {
        val real = visits.withIndex().filter { CYCLE_BREAK !in it.value }
        val breakAt = visits.indexOfLast { CYCLE_BREAK in it }
        fun allRevisits(n: Int) = real.size > n && real.takeLast(n).all { (index, page) ->
            index > breakAt && visits.subList(0, index).any { ParsedScreen.samePage(it, page) }
        }
        // One page for a while is normal on forms and settings pages, so it gets a longer window.
        val onOnePage = real.size >= window && distinctPages(real.takeLast(window).map { it.value }) == 1
        return if (onOnePage) allRevisits(singlePageWindow) else allRevisits(window)
    }

    private fun distinctPages(pages: List<Set<String>>): Int {
        val distinct = mutableListOf<Set<String>>()
        pages.forEach { page -> if (distinct.none { ParsedScreen.samePage(it, page) }) distinct += page }
        return distinct.size
    }

    /** Starts a fresh cycle window after an intervention. */
    fun breakCycle() {
        visits += setOf(CYCLE_BREAK)
    }

    private companion object {
        const val CYCLE_BREAK = "\u0000cycle-break"
    }

    fun annotation(action: ActionIdentity, here: Set<String>): Annotation? {
        val edge = edges.lastOrNull { edge ->
            edge.action == action &&
                (action is ActionIdentity.Element || ParsedScreen.samePage(edge.from, here))
        } ?: return null
        return when {
            ParsedScreen.samePage(edge.to, edge.from) -> Annotation.NoEffect
            ParsedScreen.samePage(edge.to, here) -> Annotation.LeadsHere
            else -> Annotation.LeadsToVisited(edge.toSummary, visitCount(edge.to), isExhausted(edge.to))
        }
    }

    sealed interface Annotation {
        val label: String

        data object NoEffect : Annotation {
            override val label = "[tried: no effect]"
        }

        data object LeadsHere : Annotation {
            override val label = "[leads to this same page]"
        }

        data class LeadsToVisited(val summary: String, val visits: Int, val exhausted: Boolean) : Annotation {
            override val label = "[tried: leads to visited page '${summary.take(40)}' (${visits}x" +
                (if (exhausted) ", fully explored" else ", has untried entries") + ")]"
        }
    }
}
