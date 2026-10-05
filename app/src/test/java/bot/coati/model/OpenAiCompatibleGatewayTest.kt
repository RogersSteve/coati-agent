package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiCompatibleGatewayTest {
    @Test
    fun `output budget leaves room for thinking but stays within 8192`() {
        assertEquals(8192, OpenAiCompatibleGateway.withThinking(4096))
        assertEquals(5120, OpenAiCompatibleGateway.withThinking(1024))
        assertEquals(8192, OpenAiCompatibleGateway.withThinking(8192))
        assertEquals(12000, OpenAiCompatibleGateway.withThinking(12000))
    }
}
