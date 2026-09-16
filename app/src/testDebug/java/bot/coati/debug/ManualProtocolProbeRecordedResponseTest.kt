package bot.coati.debug

import android.util.Log
import bot.coati.experience.AppManualPolicy
import bot.coati.model.CloudModelGateway
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files

@RunWith(Parameterized::class)
class ManualProtocolProbeRecordedResponseTest(private val attempt: Int) {
    private val root = Files.createTempDirectory("manual-recorded-probe").toFile()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun cleanup() { root.deleteRecursively(); unmockkStatic(Log::class) }

    @Test fun `captured complete responses pass all four production transactions without retry`() = runTest {
        val gateway = mockk<CloudModelGateway>()
        var calls = 0
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            check(calls < ManualProtocolProbe.CASES.size) { "Unexpected repair retry" }
            val name = ManualProtocolProbe.CASES[calls++]
            val record = JSONObject(fixture("$name/call-$attempt-response.json"))
            assertFalse(record.getBoolean("exportTruncated"))
            assertTrue(record.getString("text").startsWith("<think>"))
            Result.success(StructuredTextResult(
                text = record.getString("text"),
                finishReason = StructuredTextFinishReason.valueOf(record.getString("finishReason")),
                outputTokens = record.getInt("outputTokens"),
            ))
        }
        val runRoot = File(root, "run")
        assertTrue(ManualProtocolProbe(runRoot, gateway, "recorded/attempt-$attempt").run {})
        assertEquals(4, calls)
        val report = JSONObject(File(runRoot, "report.json").readText())
        assertEquals("PASS", report.getString("status"))
        val cases = report.getJSONArray("cases")
        assertEquals(4, cases.length())
        ManualProtocolProbe.CASES.forEachIndexed { index, name ->
            assertEquals("PASS", cases.getJSONObject(index).getString("result"))
            assertEquals(1, cases.getJSONObject(index).getInt("calls"))
            // Recorded fixtures are checked semantically across Windows/CI line-ending policy.
            assertEquals(
                canonicalMarkdown(fixture("$name/before.md")),
                canonicalMarkdown(File(runRoot, "$name/before.md").readText()),
            )
            assertTrue(File(runRoot, "$name/after.md").isFile)
            val files = File(runRoot, "$name/store").walkTopDown().filter { it.isFile }.toList()
            assertTrue(files.any { it.name == "manual.md" })
            assertTrue(files.any { it.name == "metadata.json" })
            val exported = JSONObject(File(runRoot, "$name/call-1-response.json").readText())
            assertEquals(JSONObject(fixture("$name/call-$attempt-response.json")).getString("text"), exported.getString("text"))
        }
    }

    private fun fixture(path: String) = checkNotNull(javaClass.getResourceAsStream("/manual_protocol_stage23/$path")) {
        "Missing captured fixture $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun canonicalMarkdown(markdown: String): String = AppManualPolicy.normalizeMarkdown(markdown)
        .lineSequence()
        .filter(String::isNotBlank)
        .joinToString("\n") { it.trimEnd() }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "captured attempt {0}")
        fun attempts() = listOf(arrayOf(1), arrayOf(2))
    }
}
