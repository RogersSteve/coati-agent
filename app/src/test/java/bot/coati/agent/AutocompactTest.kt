package bot.coati.agent

import bot.coati.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompactTest {

    private val autocompact = Autocompact(compactInterval = 15)

    @Test
    fun `shouldCompact at interval`() {
        assertTrue(autocompact.shouldCompact(15))
        assertTrue(autocompact.shouldCompact(30))
        assertTrue(autocompact.shouldCompact(45))
    }

    @Test
    fun `shouldCompact false between intervals`() {
        assertFalse(autocompact.shouldCompact(1))
        assertFalse(autocompact.shouldCompact(14))
        assertFalse(autocompact.shouldCompact(16))
    }

    @Test
    fun `shouldCompact false at zero`() {
        assertFalse(autocompact.shouldCompact(0))
    }

    @Test
    fun `shouldCompact with custom interval`() {
        val custom = Autocompact(compactInterval = 5)
        assertTrue(custom.shouldCompact(5))
        assertTrue(custom.shouldCompact(10))
        assertFalse(custom.shouldCompact(3))
    }

    @Test
    fun `pruneScreenshotCache keeps last N`() {
        val cache = mutableListOf(
            byteArrayOf(1), byteArrayOf(2), byteArrayOf(3),
            byteArrayOf(4), byteArrayOf(5),
        )
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(3, cache.size)
        assertEquals(3.toByte(), cache[0][0])
        assertEquals(4.toByte(), cache[1][0])
        assertEquals(5.toByte(), cache[2][0])
    }

    @Test
    fun `pruneScreenshotCache does nothing when under limit`() {
        val cache = mutableListOf(byteArrayOf(1), byteArrayOf(2))
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(2, cache.size)
    }

    @Test
    fun `pruneScreenshotCache with empty list`() {
        val cache = mutableListOf<ByteArray>()
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(0, cache.size)
    }

    @Test
    fun `pruneScreenshotCache with exact limit`() {
        val cache = mutableListOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        autocompact.pruneScreenshotCache(cache, keep = 3)
        assertEquals(3, cache.size)
    }
}
