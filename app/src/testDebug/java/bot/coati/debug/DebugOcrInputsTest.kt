package bot.coati.debug

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DebugOcrInputsTest {
    @Test fun externalRemainsDefaultAndInternalDoesNotAccessExternal() {
        val base = File("files")
        val external = File("external")
        assertEquals(external, DebugOcrInputs.directory("external", base) { external })
        assertEquals(File(base, "ocr_bench"), DebugOcrInputs.directory("internal", base) { error("external accessed") })
        assertThrows(IllegalArgumentException::class.java) { DebugOcrInputs.directory("other", base) { external } }
        assertThrows(IllegalArgumentException::class.java) { DebugOcrInputs.directory("external", base) { null } }
    }

    @Test fun validatesBeforeEngineWorkAndSortsSupportedReadableFiles() {
        val dir = Files.createTempDirectory("ocr-inputs").toFile()
        try {
            assertThrows(IllegalStateException::class.java) { DebugOcrInputs.validate(dir) }
            File(dir, "ignored.txt").writeText("not an image")
            File(dir, "b.PNG").writeBytes(byteArrayOf(1))
            File(dir, "a.jpg").writeBytes(byteArrayOf(2))
            assertEquals(listOf("a.jpg", "b.PNG"), DebugOcrInputs.validate(dir).map { it.name })
            File(dir, "bad.png").mkdir()
            assertThrows(IllegalStateException::class.java) { DebugOcrInputs.validate(dir) }
            assertThrows(IllegalStateException::class.java) { DebugOcrInputs.validate(File(dir, "missing")) }
        } finally { dir.deleteRecursively() }
    }
}
