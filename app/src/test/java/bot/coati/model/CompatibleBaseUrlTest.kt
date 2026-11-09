package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibleBaseUrlTest {
    @Test
    fun `a pasted v1 suffix or trailing slash is dropped`() {
        assertEquals("https://x.ai", compatibleBaseUrl("https://x.ai/v1"))
        assertEquals("https://x.ai", compatibleBaseUrl("https://x.ai/v1/ "))
        assertEquals("https://x.ai", compatibleBaseUrl("https://x.ai/"))
        assertEquals("https://x.ai/api", compatibleBaseUrl("https://x.ai/api"))
    }
}
