package bot.coati.perception

import bot.coati.coordinates.PixelBounds

enum class ElementOp { TAP, LONG, INPUT }

enum class ScrollAxis { VERTICAL, HORIZONTAL }

/** How a screen was perceived; HYBRID merges the tree with a vision parse of painted content. */
enum class ScreenSource { ACCESSIBILITY, VISION, HYBRID }

/** One actionable or informative element. [bounds] are raw display pixels. */
data class ScreenElement(
    val id: String,
    val type: String,
    val text: String,
    val purpose: String,
    val ops: List<ElementOp>,
    val state: String?,
    val bounds: PixelBounds,
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2

    /** Stable across re-parses of the same page, unlike [id]. */
    val identity: String get() = text.lineSequence().firstOrNull().orEmpty().trim().take(IDENTITY_CHARS)
}

data class ScreenRegion(
    val id: String,
    val role: String,
    val axes: Set<ScrollAxis>,
    val bounds: PixelBounds,
)

/** A structured description of one captured frame, independent of how it was produced. */
data class ParsedScreen(
    val source: ScreenSource,
    val packageName: String,
    val summary: String,
    val keyboardVisible: Boolean,
    val overlay: String?,
    val elements: List<ScreenElement>,
    val regions: List<ScreenRegion>,
    /**
     * How many leading [elements] identify the page. A hybrid screen counts only its tree elements:
     * the merged vision elements vary between parses and would make one page look like several.
     */
    val pageKeyElements: Int = Int.MAX_VALUE,
) {
    /** Page fingerprint from visible texts; tolerant of dynamic items via [samePage]. */
    val pageKey: Set<String> by lazy {
        elements.take(pageKeyElements).mapNotNull { it.identity.takeIf(String::isNotBlank) }.toSet()
    }

    fun element(id: String): ScreenElement? = elements.firstOrNull { it.id == id }

    fun region(id: String): ScreenRegion? = regions.firstOrNull { it.id == id }

    companion object {
        fun samePage(a: Set<String>, b: Set<String>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val union = (a + b).size
            return a.intersect(b).size.toDouble() / union >= SAME_PAGE_JACCARD
        }

        /**
         * A stricter [samePage] for "is this a page worth describing again": sibling native pages
         * share their top bar and bottom navigation and differ in a few labels, which 0.6 calls one
         * page (board: Settings, Advanced settings and Archive were never observed for the manual).
         */
        fun nearlySamePage(a: Set<String>, b: Set<String>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            return a.intersect(b).size.toDouble() / (a + b).size >= NEARLY_SAME_PAGE_JACCARD
        }

        private const val SAME_PAGE_JACCARD = 0.6
        private const val NEARLY_SAME_PAGE_JACCARD = 0.9
    }
}

private const val IDENTITY_CHARS = 24
