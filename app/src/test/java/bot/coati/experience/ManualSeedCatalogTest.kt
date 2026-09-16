package bot.coati.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSeedCatalogTest {
    @Test
    fun `catalog references markdown assets and protected headings`() {
        val raw = """{"seeds":[{"packageName":"com.test.app","manualAsset":"app_manual_seeds/com.test.app.md","protectedHeadingPaths":["App 信息"]}]}"""
        val seed = ManualSeedCatalog.decodeCatalog(raw).single()
        assertEquals("com.test.app", seed.packageName)
        assertTrue(seed.manualAsset.endsWith(".md"))
        assertEquals(setOf("App 信息"), seed.protectedHeadingPaths)
    }

    @Test
    fun `catalog rejects traversal and malformed packages`() {
        val traversal = """{"seeds":[{"packageName":"com.test.app","manualAsset":"app_manual_seeds/../secret","protectedHeadingPaths":[]}]}"""
        val malformed = """{"seeds":[{"packageName":"Browser","manualAsset":"app_manual_seeds/browser.md","protectedHeadingPaths":[]}]}"""
        assertTrue(runCatching { ManualSeedCatalog.decodeCatalog(traversal) }.isFailure)
        assertTrue(runCatching { ManualSeedCatalog.decodeCatalog(malformed) }.isFailure)
    }
}
