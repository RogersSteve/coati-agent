package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrPostprocessTest {

    @Test
    fun `det input keeps small images and rounds sides to multiples of 32`() {
        assertEquals(320 to 224, OcrPostprocess.detInputSize(320, 230))
        assertEquals(32 to 32, OcrPostprocess.detInputSize(5, 5))
    }

    @Test
    fun `det input caps the long side at 960`() {
        val (w, h) = OcrPostprocess.detInputSize(1080, 2400)
        assertEquals(960, h)
        assertEquals(0, w % 32)
        assertEquals(1080f / 2400f, w.toFloat() / h, 0.02f)
    }

    @Test
    fun `unclip distance is area times ratio over perimeter`() {
        assertEquals(100f * 20f * 1.5f / 240f, OcrPostprocess.unclipDistance(100f, 20f), 1e-4f)
        assertEquals(0f, OcrPostprocess.unclipDistance(0f, 0f), 0f)
    }

    private fun map(width: Int, height: Int, vararg rects: Pair<IntArray, Float>): FloatArray {
        val prob = FloatArray(width * height) { 0.05f }
        rects.forEach { (r, p) ->
            for (y in r[1] until r[3]) for (x in r[0] until r[2]) prob[y * width + x] = p
        }
        return prob
    }

    @Test
    fun `db extracts one unclipped box per component`() {
        val prob = map(100, 60, intArrayOf(10, 10, 50, 20) to 0.9f, intArrayOf(10, 40, 90, 50) to 0.8f)

        val boxes = OcrPostprocess.extractBoxes(prob, 100, 60).sortedBy { it.top }

        assertEquals(2, boxes.size)
        val d = OcrPostprocess.unclipDistance(40f, 10f)
        val first = boxes[0]
        assertEquals(10f - d, first.left, 1e-3f)
        assertEquals(10f - d, first.top, 1e-3f)
        assertEquals(50f + d, first.right, 1e-3f)
        assertEquals(20f + d, first.bottom, 1e-3f)
        assertEquals(0.9f, first.score, 1e-4f)
        assertEquals(0.8f, boxes[1].score, 1e-4f)
    }

    @Test
    fun `db drops low score and tiny components and clamps to the map`() {
        val prob = map(
            100, 60,
            intArrayOf(10, 10, 50, 20) to 0.5f, // above threshold but below box score
            intArrayOf(70, 5, 72, 30) to 0.9f, // 2 px wide
            intArrayOf(0, 40, 30, 60) to 0.95f, // touches the map edge
        )

        val boxes = OcrPostprocess.extractBoxes(prob, 100, 60)

        assertEquals(1, boxes.size)
        assertEquals(0f, boxes[0].left, 0f)
        assertEquals(60f, boxes[0].bottom, 0f)
    }

    @Test
    fun `diagonal neighbours join one component`() {
        val prob = FloatArray(20 * 20) { 0f }
        for (i in 0 until 10) prob[i * 20 + i] = 0.9f
        assertEquals(1, OcrPostprocess.extractBoxes(prob, 20, 20).size)
    }

    @Test
    fun `boxes map back to image pixels`() {
        val box = OcrBox(10f, 5f, 50.2f, 20f, 0.9f)
        assertEquals(PixelBounds(20, 10, 101, 40), OcrPostprocess.mapToImage(box, 100, 50, 200, 100))
    }

    @Test
    fun `rec width keeps aspect at height 48 and is capped`() {
        assertEquals(240, OcrPostprocess.recInputWidth(200, 40))
        assertEquals(OcrPostprocess.REC_MAX_WIDTH, OcrPostprocess.recInputWidth(5000, 20))
        assertEquals(320, OcrPostprocess.recInputWidth(5000, 20, maxWidth = 320))
    }

    @Test
    fun `rec tensor normalizes to minus one one and zero pads`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val out = FloatArray(3 * 1 * 3) { 9f }
        OcrPostprocess.recTensorInto(intArrayOf(white, black), 2, 1, 3, out, 0)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(-1f, out[1], 1e-6f)
        assertEquals(9f, out[2], 0f) // padding left untouched (the caller zero-fills)
        assertEquals(1f, out[3], 1e-6f)
        assertEquals(-1f, out[7], 1e-6f)
    }

    @Test
    fun `det tensor is chw with imagenet normalization`() {
        val red = 0xFFFF0000.toInt()
        val out = OcrPostprocess.detTensor(intArrayOf(red), 1, 1)
        assertEquals((1f - 0.485f) / 0.229f, out[0], 1e-4f)
        assertEquals((0f - 0.456f) / 0.224f, out[1], 1e-4f)
        assertEquals((0f - 0.406f) / 0.225f, out[2], 1e-4f)
    }

    private fun oneHot(classes: Int, vararg best: Int, p: Float = 0.9f): FloatArray {
        val out = FloatArray(best.size * classes) { (1f - p) / (classes - 1) }
        best.forEachIndexed { t, c -> out[t * classes + c] = p }
        return out
    }

    @Test
    fun `ctc collapses repeats and drops blanks`() {
        val charset = OcrPostprocess.buildCharset(listOf("a", "b", "c"))
        val classes = charset.size + 1
        val probs = oneHot(classes, 1, 1, 0, 1, 2, 2, 0, 0, 3, 4)

        val (text, conf) = OcrPostprocess.ctcDecode(probs, 0, 10, classes, charset)

        assertEquals("aabc ", text)
        assertEquals(0.9f, conf, 1e-5f)
    }

    @Test
    fun `ctc decodes a batch slot at its offset and handles all blanks`() {
        val charset = OcrPostprocess.buildCharset(listOf("x", "y"))
        val classes = charset.size + 1
        val probs = oneHot(classes, 0, 0, 0) + oneHot(classes, 2, 0, 1)

        assertEquals("" to 0f, OcrPostprocess.ctcDecode(probs, 0, 3, classes, charset))
        assertEquals("yx", OcrPostprocess.ctcDecode(probs, 3 * classes, 3, classes, charset).first)
    }

    @Test
    fun `lines sort by rows then left to right`() {
        val a = OcrLine("a", PixelBounds(300, 102, 400, 140))
        val b = OcrLine("b", PixelBounds(10, 100, 200, 138))
        val c = OcrLine("c", PixelBounds(10, 200, 200, 240))
        val d = OcrLine("d", PixelBounds(500, 10, 600, 40))

        val sorted = OcrPostprocess.sortLines(listOf(a, c, b, d))

        assertEquals(listOf("d", "b", "a", "c"), sorted.map { it.text })
    }

    @Test
    fun `bundled dictionary and models are real files`() {
        val dir = listOf(File("src/main/assets/ocr"), File("app/src/main/assets/ocr")).first { it.isDirectory }
        val dict = File(dir, "ppocr_keys_v1.txt").readLines(Charsets.UTF_8)
        assertEquals(6623, dict.size)
        assertEquals(6624, OcrPostprocess.buildCharset(dict).size)
        listOf("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx").forEach { name ->
            val model = File(dir, name)
            assertTrue("$name missing or truncated", model.length() > 1_000_000)
            val head = model.inputStream().use { String(it.readNBytes(64), Charsets.ISO_8859_1) }
            assertTrue("$name looks like text, not ONNX", !head.startsWith("version https://git-lfs") && !head.trimStart().startsWith("<"))
        }
    }
}
