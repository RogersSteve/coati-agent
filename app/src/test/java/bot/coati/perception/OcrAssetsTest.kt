package bot.coati.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled ncnn models and dictionary are real, consistent files. */
class OcrAssetsTest {
    private val dir = listOf(File("src/main/assets/ocr"), File("app/src/main/assets/ocr")).first { it.isDirectory }

    @Test
    fun `models are present and look like ncnn files`() {
        listOf("PP_OCRv5_mobile_det", "PP_OCRv5_mobile_rec").forEach { name ->
            val param = File(dir, "$name.ncnn.param")
            val bin = File(dir, "$name.ncnn.bin")
            assertTrue("$param missing", param.isFile)
            assertEquals("$param is not an ncnn param file", "7767517", param.useLines { it.first().trim() })
            assertTrue("$bin missing or truncated", bin.length() > 1_000_000)
            val head = bin.inputStream().use { String(it.readNBytes(64), Charsets.ISO_8859_1) }
            assertTrue("$bin looks like a text stub", !head.startsWith("version https://git-lfs") && !head.trimStart().startsWith("<"))
        }
    }

    @Test
    fun `dictionary size matches the recognizer output classes`() {
        val dict = File(dir, "ppocrv5_dict.txt").readText(Charsets.UTF_8)
            .split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
        assertEquals(18383, dict.size)

        // The recognizer's classifier head is the last Gemm; its parameter 8 is the output width.
        val gemm = File(dir, "PP_OCRv5_mobile_rec.ncnn.param").readLines().last { it.startsWith("Gemm") }
        val classes = gemm.split(Regex("\\s+")).first { it.startsWith("8=") }.removePrefix("8=").toInt()

        // Class 0 is the CTC blank, then one class per dictionary line, then a trailing space.
        assertEquals(dict.size + 2, classes)
    }
}
