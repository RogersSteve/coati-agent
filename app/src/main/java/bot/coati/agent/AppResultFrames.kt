package bot.coati.agent

/**
 * The last screen seen in each app during a task. A result left in one app (a saved note) stays
 * provable while another app (the calendar) is in front, so the completion audit is not limited
 * to whatever the current screen happens to show.
 */
internal class AppResultFrames(private val maxEarlierApps: Int = MAX_EARLIER_APPS) {
    class Frame(val packageName: String, val step: Int, val jpeg: ByteArray)

    // Insertion order is recency order: a package seen again moves to the end.
    private val frames = LinkedHashMap<String, Frame>()

    fun clear() = frames.clear()

    /** Records [jpeg] as the latest screen of [packageName]; blank or [ignored] packages are skipped. */
    fun record(packageName: String, step: Int, jpeg: ByteArray, ignored: Boolean = false) {
        if (packageName.isBlank() || ignored) return
        frames.remove(packageName)
        frames[packageName] = Frame(packageName, step, jpeg)
    }

    /** The latest screen of each other app, oldest first, at most [maxEarlierApps] of the most recent. */
    fun earlierThan(currentPackage: String): List<Frame> =
        frames.values.filter { it.packageName != currentPackage }.takeLast(maxEarlierApps)

    companion object {
        /** Cross-app tasks rarely span more than three result apps; more images slow the audit. */
        const val MAX_EARLIER_APPS = 3
    }
}
