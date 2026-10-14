package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AppResultFramesTest {
    @Test
    fun `keeps the latest screen of every other app, oldest first`() {
        val frames = AppResultFrames()
        frames.record("maps", 3, byteArrayOf(1))
        frames.record("keep", 10, byteArrayOf(2))
        frames.record("maps", 18, byteArrayOf(3))
        frames.record("calendar", 30, byteArrayOf(4))

        val earlier = frames.earlierThan("calendar")

        assertEquals(listOf("keep" to 10, "maps" to 18), earlier.map { it.packageName to it.step })
        assertEquals(3, earlier.last().jpeg.single().toInt())
    }

    @Test
    fun `ignored and blank packages hold no results`() {
        val frames = AppResultFrames()
        frames.record("launcher", 1, byteArrayOf(1), ignored = true)
        frames.record("", 2, byteArrayOf(2))
        frames.record("keep", 3, byteArrayOf(3))

        assertEquals(listOf("keep"), frames.earlierThan("calendar").map { it.packageName })
    }

    @Test
    fun `only the most recent apps are kept for the audit`() {
        val frames = AppResultFrames(maxEarlierApps = 2)
        listOf("a", "b", "c", "d").forEachIndexed { i, pkg -> frames.record(pkg, i, byteArrayOf(i.toByte())) }

        assertEquals(listOf("b", "c"), frames.earlierThan("d").map { it.packageName })
    }
}
