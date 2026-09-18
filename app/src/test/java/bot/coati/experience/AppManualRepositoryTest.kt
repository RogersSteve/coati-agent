package bot.coati.experience

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppManualRepositoryTest {
    @Test
    fun `repository writes readable pair enforces CAS and recovers paired revision`() = runTest {
        val root = Files.createTempDirectory("coati-manual-v2").toFile()
        try {
            val repository = AppManualRepository(root)
            val revision1 = testManual()
            assertTrue(repository.save(revision1, 0L) is ManualSaveResult.Saved)
            assertTrue(repository.manualFile(revision1.app.appKey).readText().startsWith("# Example"))
            assertTrue(!repository.metadataFile(revision1.app.appKey).readText().contains("首页显示搜索入口"))
            assertTrue(repository.save(testManual(revision = 2L), 0L) is ManualSaveResult.RevisionConflict)

            val revision2 = testManual(revision = 2L, markdown = testMarkdown("- 更新后的首页说明。"))
            assertTrue(repository.save(revision2, 1L) is ManualSaveResult.Saved)
            repository.metadataFile(revision1.app.appKey).writeText("corrupt")
            assertEquals(1L, repository.load(revision1.app.appKey)?.revision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manual metadata hash mismatch falls back and namespaces remain isolated`() = runTest {
        val root = Files.createTempDirectory("coati-manual-v2-isolation").toFile()
        try {
            val repository = AppManualRepository(root)
            val canonical1 = testManual()
            repository.save(canonical1, 0L)
            repository.save(testManual(2L, testMarkdown("- Revision two.")), 1L)
            repository.manualFile(canonical1.app.appKey).appendText("tampered")
            assertEquals(1L, repository.load(canonical1.app.appKey)?.revision)

            val shadowIdentity = testIdentity().copy(appKey = "d".repeat(64))
            val shadow = testManual(identity = shadowIdentity)
            repository.save(shadow, 0L, ManualNamespace.SHADOW)
            assertEquals(null, repository.load(shadowIdentity.appKey, ManualNamespace.CANONICAL))
            assertEquals(1L, repository.load(shadowIdentity.appKey, ManualNamespace.SHADOW)?.revision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clear all removes canonical and shadow data and reports success`() = runTest {
        val root = Files.createTempDirectory("coati-manual-v2-clear").toFile()
        try {
            val repository = AppManualRepository(root)
            val canonical = testManual()
            val shadowIdentity = testIdentity().copy(appKey = "d".repeat(64))
            val shadow = testManual(identity = shadowIdentity)
            repository.save(canonical, 0L)
            repository.save(shadow, 0L, ManualNamespace.SHADOW)

            assertTrue(repository.hasAnyData())
            assertTrue(repository.clearAll())
            assertTrue(!repository.hasAnyData())
            assertEquals(null, repository.load(canonical.app.appKey))
            assertEquals(null, repository.load(shadow.app.appKey, ManualNamespace.SHADOW))
        } finally {
            root.deleteRecursively()
        }
    }
}
