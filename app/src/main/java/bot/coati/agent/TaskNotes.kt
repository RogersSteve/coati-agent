package bot.coati.agent

/**
 * Facts the Reasoning model copied from screens it is about to leave (records to copy into another
 * app, items to count across pages, a time to report), kept verbatim by the host and shown on every
 * step. Conversation history is summarized every few steps and screenshots are not kept, so without
 * them a value read ten steps earlier was re-read by flipping between apps (13-23 switches in a
 * recipe copy task) or answered from memory (a meeting reported at the wrong hour).
 *
 * Each entry is "label: value". An entry whose label matches an earlier one replaces it; others are
 * appended. The oldest entries go first when the bounds are exceeded.
 */
internal class TaskNotes(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxChars: Int = MAX_CHARS,
) {
    private val entries = mutableListOf<String>()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun clear() = entries.clear()

    fun entries(): List<String> = entries.toList()

    /** Merges the model's new or updated entries; returns how many were accepted. */
    fun merge(update: List<String>): Int {
        var accepted = 0
        for (raw in update) {
            val entry = raw.trim().take(MAX_ENTRY_CHARS)
            if (entry.isEmpty()) continue
            val key = labelOf(entry)
            val existing = entries.indexOfFirst { labelOf(it) == key }
            if (existing >= 0) entries.removeAt(existing)
            entries.add(entry)
            accepted++
        }
        while (entries.size > maxEntries || (entries.size > 1 && entries.sumOf { it.length + 1 } > maxChars)) {
            entries.removeAt(0)
        }
        return accepted
    }

    /** The [NOTES] block of the task description, or null when there are none. */
    fun render(): String? = if (entries.isEmpty()) null else buildString {
        append("[NOTES] Facts you recorded from earlier screens (kept verbatim by the host; trust them over memory):")
        entries.forEach { append("\n- ").append(it) }
    }

    companion object {
        const val MAX_ENTRIES = 40
        const val MAX_CHARS = 6_000
        private const val MAX_ENTRY_CHARS = 600
        private const val MAX_LABEL_CHARS = 80

        /** The label before the first ':' (case-insensitive), or the whole entry when there is none. */
        internal fun labelOf(entry: String): String {
            val colon = entry.indexOf(':')
            val label = if (colon in 1..MAX_LABEL_CHARS) entry.substring(0, colon) else entry
            return label.trim().lowercase()
        }
    }
}
