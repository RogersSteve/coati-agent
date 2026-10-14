package bot.coati.agent

/**
 * Recent screens of each app during a task. A result left in one app (a saved note) stays provable
 * while another app (the calendar) is in front, and a value shown in a form before it was saved
 * stays provable after the app returns to a list that does not show it. The completion audit is
 * therefore not limited to whatever the current screen happens to show.
 */
internal class AppResultFrames(
    private val maxEarlierApps: Int = MAX_EARLIER_APPS,
    private val framesPerApp: Int = FRAMES_PER_APP,
) {
    class Frame(val packageName: String, val step: Int, val jpeg: ByteArray, val signature: String)

    // Insertion order is recency order: a package seen again moves to the end.
    private val frames = LinkedHashMap<String, ArrayDeque<Frame>>()

    fun clear() = frames.clear()

    /**
     * Records [jpeg] as the latest screen of [packageName]; blank or [ignored] packages are skipped.
     * A screen with the same [signature] as the app's previous one replaces it (the same page).
     */
    fun record(packageName: String, step: Int, jpeg: ByteArray, signature: String, ignored: Boolean = false) {
        if (packageName.isBlank() || ignored) return
        val history = frames.remove(packageName) ?: ArrayDeque()
        if (history.lastOrNull()?.signature == signature) history.removeLast()
        history.addLast(Frame(packageName, step, jpeg, signature))
        while (history.size > framesPerApp) history.removeFirst()
        frames[packageName] = history
    }

    /**
     * Screens to audit beside the current one, oldest first: the last screen of each other app (at
     * most [maxEarlierApps] of the most recent) and the earlier screens of [currentPackage].
     * [currentStep] is the frame being audited, which the caller sends itself.
     */
    fun evidenceFor(currentPackage: String, currentStep: Int): List<Frame> {
        val others = frames.filterKeys { it != currentPackage }.values.map { it.last() }.takeLast(maxEarlierApps)
        val here = frames[currentPackage].orEmpty().filter { it.step != currentStep }
        return others + here
    }

    companion object {
        /** Cross-app tasks rarely span more than three result apps; more images slow the audit. */
        const val MAX_EARLIER_APPS = 3

        /** The current page and the two before it: a filled form, its confirmation, the list. */
        const val FRAMES_PER_APP = 3
    }
}
