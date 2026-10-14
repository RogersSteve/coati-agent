package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AppResultFramesTest {
    private fun AppResultFrames.add(pkg: String, step: Int, sig: String = "p$step", ignored: Boolean = false) =
        record(pkg, step, byteArrayOf(step.toByte()), sig, ignored)

    private fun List<AppResultFrames.Frame>.ids() = map { it.packageName to it.step }

    @Test
    fun `other apps contribute their last screen, the current app its earlier screens`() {
        val frames = AppResultFrames()
        frames.add("markor", 3)
        frames.add("joplin", 10)
        frames.add("joplin", 13)
        frames.add("calendar", 20) // filled form
        frames.add("calendar", 22) // saved list
        frames.add("calendar", 23)

        assertEquals(
            listOf("markor" to 3, "joplin" to 13, "calendar" to 20, "calendar" to 22),
            frames.evidenceFor("calendar", currentStep = 23).ids(),
        )
    }

    @Test
    fun `the same page seen again replaces its older screen`() {
        val frames = AppResultFrames()
        frames.add("calendar", 1, sig = "form")
        frames.add("calendar", 2, sig = "form")
        frames.add("calendar", 3, sig = "list")

        assertEquals(listOf("calendar" to 2), frames.evidenceFor("calendar", currentStep = 3).ids())
    }

    @Test
    fun `ignored and blank packages hold no results`() {
        val frames = AppResultFrames()
        frames.add("launcher", 1, ignored = true)
        frames.add("", 2)
        frames.add("keep", 3)

        assertEquals(listOf("keep" to 3), frames.evidenceFor("calendar", currentStep = 4).ids())
    }

    @Test
    fun `only the most recent apps and screens are kept`() {
        val frames = AppResultFrames(maxEarlierApps = 2, framesPerApp = 2)
        listOf("a", "b", "c").forEachIndexed { i, pkg -> frames.add(pkg, i) }
        listOf(10, 11, 12).forEach { frames.add("d", it) }

        assertEquals(listOf("b" to 1, "c" to 2, "d" to 11), frames.evidenceFor("d", currentStep = 12).ids())
    }
}
