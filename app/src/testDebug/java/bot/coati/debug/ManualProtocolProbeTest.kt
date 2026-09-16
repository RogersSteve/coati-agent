package bot.coati.debug

import android.util.Log
import bot.coati.model.CloudModelGateway
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextRequest
import bot.coati.model.StructuredTextResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ManualProtocolProbeTest {
    private val root = Files.createTempDirectory("manual-probe-test").toFile()
    private val runRoot = File(root, "run")
    private val gateway = mockk<CloudModelGateway>()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun cleanup() { root.deleteRecursively(); unmockkStatic(Log::class) }

    @Test fun `four probe fixtures run through real processor and disk without touching sibling store`() = runTest {
        val unrelated = File(root, "user-manual.md").apply { writeText("Keep real user manual") }
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            Result.success(StructuredTextResult(validResponse(firstArg()), StructuredTextFinishReason.COMPLETE))
        }
        assertTrue(ManualProtocolProbe(runRoot, gateway, "test/model").run {})
        val report = report()
        assertEquals("PASS", report.getString("status"))
        assertEquals(4, report.getJSONArray("cases").length())
        assertEquals("Keep real user manual", unrelated.readText())
        ManualProtocolProbe.CASES.forEach { name ->
            assertTrue(File(runRoot, "$name/call-1-request.json").isFile)
            assertTrue(File(runRoot, "$name/call-1-response.json").isFile)
            assertTrue(File(runRoot, "$name/after.md").isFile)
        }
    }

    @Test fun `invalid targets are recorded and each independent case still runs with bounded calls`() = runTest {
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            val request = firstArg<StructuredTextRequest>()
            Result.success(StructuredTextResult(validResponse(request, invalid = true), StructuredTextFinishReason.COMPLETE))
        }
        assertFalse(ManualProtocolProbe(runRoot, gateway, "test/model").run {})
        val cases = report().getJSONArray("cases")
        assertEquals(4, cases.length())
        for (index in 0 until cases.length()) {
            assertEquals(2, cases.getJSONObject(index).getInt("calls"))
            assertEquals("UNKNOWN_TARGET_ID", cases.getJSONObject(index).getString("fallbackTrigger"))
            assertFalse(cases.getJSONObject(index).getJSONObject("checks").getBoolean("modelProtocolAccepted"))
        }
        // Runtime recovery may save the admitted fixture evidence, but never the invalid target.
        assertTrue(File(runRoot, "bootstrap/after.md").isFile)
        assertTrue(File(runRoot, "new_page/after.md").readText().contains("### 收藏页"))
        assertFalse(File(runRoot, "new_page/after.md").readText().contains("页面详情/不存在"))
    }

    @Test fun `cancellation leaves an exportable terminal report rather than orphan running state`() = runTest {
        coEvery { gateway.generateStructuredText(any()) } coAnswers { awaitCancellation() }
        val job = launch { ManualProtocolProbe(runRoot, gateway, "test/model").run {} }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals("CANCELLED", report().getString("status"))
        assertEquals("JobCancellationException", JSONObject(File(runRoot, "bootstrap/call-1-response.json").readText()).getString("errorType"))
    }

    private fun report() = JSONObject(File(runRoot, "report.json").readText())

    private fun validResponse(request: StructuredTextRequest, invalid: Boolean = false): String {
        val prompt = request.userPrompt
        val revision = Regex("Expected revision: (\\d+)").find(prompt)!!.groupValues[1].toLong()
        val targets = JSONTokener(prompt.substringAfter("editTargets = ")).nextValue() as JSONArray
        val entries = (0 until targets.length()).map { targets.getJSONObject(it) }
        val isNewPage = "Page: 收藏页" in prompt
        val isChanged = "入口已从" in prompt
        val noChange = revision > 0L && !isNewPage && !isChanged
        val edit = if (noChange && !invalid) null else {
            val target = entries.first { it.getString("headingPath") == if (isChanged) "页面详情/首页" else "页面详情" }
            val old = target.getString("exactMarkdown")
            JSONObject().apply {
                put("targetId", if (invalid) "页面详情/不存在" else target.getString("targetId"))
                put("oldText", old)
                put("newText", when {
                    isChanged -> old.replace("底部设置Tab", "右上齿轮按钮")
                    isNewPage -> old + "### 收藏页\n- 顶部收藏标题，中部资料卡列表，左上返回按钮。\n\n"
                    else -> old.trimEnd() + "\n\n### 首页\n- 顶部资料卡标题，中部卡片列表，底部设置Tab。\n\n"
                })
            }
        }
        return JSONObject().apply {
            put("expectedRevision", revision)
            put("tool", if (edit == null) "NO_CHANGE" else "PARTIAL_EDIT")
            put("edits", JSONArray().apply { if (edit != null) put(edit) })
        }.toString()
    }
}
