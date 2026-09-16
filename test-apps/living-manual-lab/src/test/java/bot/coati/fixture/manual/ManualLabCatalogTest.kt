package bot.coati.fixture.manual

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualLabCatalogTest {
    @Test
    fun `empty query returns the stable full catalog`() {
        assertEquals(
            listOf("travel", "meeting", "emergency"),
            ManualLabCatalog.search(" ").map { it.id },
        )
    }

    @Test
    fun `search matches title category and summary`() {
        assertEquals(listOf("travel"), ManualLabCatalog.search("旅行").map { it.id })
        assertEquals(listOf("meeting"), ManualLabCatalog.search("工作").map { it.id })
        assertEquals(listOf("emergency"), ManualLabCatalog.search("应急用品").map { it.id })
    }

    @Test
    fun `unknown query returns no entries`() {
        assertEquals(emptyList<LabEntry>(), ManualLabCatalog.search("不存在的资料"))
    }
}
