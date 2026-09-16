package bot.coati.experience

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextResult
import io.mockk.coEvery
import io.mockk.mockk
import bot.coati.model.CloudModelGateway

class ManualCurationTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `curator codec requires scoped edits and rejects extra fields`() {
        val headings = testTargetIds.entries.associate { it.value to it.key }
        val valid = AppManualCodec.decodeCuratorDecision(editResponse(1L, ManualPartialEdit("页面索引", "old", "new")), headings)
        assertEquals(ManualCuratorTool.PARTIAL_EDIT, valid.tool)
        assertEquals(1L, valid.expectedRevision)
        assertEquals("页面索引", valid.edits.single().targetHeading)
        listOf(
            """{"expectedRevision":1,"tool":"PARTIAL_EDIT","edits":[{"oldText":"old","newText":"new"}]}""",
            """{"expectedRevision":1,"tool":"NO_CHANGE","edits":[],"comment":"ignored"}""",
            """{"expectedRevision":1.5,"tool":"NO_CHANGE","edits":[]}""",
            """{"expectedRevision":-1,"tool":"NO_CHANGE","edits":[]}""",
            """{"expectedRevision":1,"tool":"PARTIAL_EDIT","edits":[{"targetHeading":"页面索引","oldText":"old","newText":"new"}]}""",
        ).forEach { assertTrue(runCatching { AppManualCodec.decodeCuratorDecision(it, headings) }.isFailure) }
    }

    @Test
    fun `curation prompt includes exact excerpts no original task and no unseen pages`() {
        val base = ManualWorkingCopies.from(testManual(), testIdentity())
        val prompt = ManualCurationPrompt.userPrompt(base, testTrace())
        val context = ManualCurationContextBuilder.context(base, testTrace())
        assertEquals("s6", context.idFor("页面详情/首页"))
        assertEquals(null, context.idFor("页面详情/设置页"))
        assertTrue(prompt.contains("newPageParentTargetId: s3"))
        assertTrue(prompt.contains("\"targetId\": \"s6\""))
        assertTrue(prompt.contains("顶部搜索入口"))
        assertFalse(prompt.contains("给张三发送验证码"))
        assertTrue(prompt.contains("App purpose required after this update: true"))
        assertTrue(ManualTokenEstimator.estimate(prompt) <= AppManualPolicy.MAX_CURATION_INPUT_ESTIMATED_TOKENS)
        assertTrue(ManualCurationPrompt.systemPrompt().contains("at least two 页面详情 H3 pages"))
    }

    @Test
    fun `two known pages require a real app purpose while one page may remain unknown`() {
        val onePage = ManualPartialEditor.apply(
            ManualWorkingCopies.from(null, testIdentity()),
            listOf(ManualPartialEdit("页面详情", "- 尚无已验证经验。", "### 首页\n- 底部导航。")),
            setOf("页面详情"),
        ).getOrThrow()
        ManualSemanticGuard.validatePurpose(onePage.markdown)

        val twoPages = AppManualFactory.create(
            testIdentity(),
            onePage.markdown.replace("### 首页\n- 底部导航。", "### 首页\n- 底部导航。\n\n### 资料库\n- 条目列表。"),
        )
        val error = runCatching { ManualSemanticGuard.validatePurpose(twoPages.markdown) }.exceptionOrNull()
        assertEquals("APP_PURPOSE_REQUIRED", (error as ManualEditException).reasonCode)

        val complete = AppManualFactory.create(
            testIdentity(),
            twoPages.markdown.replaceFirst("- 尚无已验证经验。", "- 用于浏览离线资料。"),
        )
        ManualSemanticGuard.validatePurpose(complete.markdown)
    }

    @Test
    fun `incremental update cannot narrow an established app purpose`() {
        val base = testManual()
        val narrowed = AppManualFactory.create(
            testIdentity(),
            base.markdown.replace("- 用于测试 App 手册。", "- 仅用于访问我的页面。"),
            revision = 2L,
        )

        val error = runCatching {
            ManualSemanticGuard.validate(ManualWorkingCopies.from(base, testIdentity()), narrowed, testTrace())
        }.exceptionOrNull()

        assertEquals("APP_PURPOSE_REGRESSION", (error as ManualEditException).reasonCode)

        val extended = AppManualFactory.create(
            testIdentity(),
            base.markdown.replace(
                "- 用于测试 App 手册。",
                "- 用于测试 App 手册。\n- 用于访问我的页面。",
            ),
            revision = 2L,
        )
        ManualSemanticGuard.validate(ManualWorkingCopies.from(base, testIdentity()), extended, testTrace())
    }

    @Test
    fun `missing purpose uses bounded repair and corrected four edit batch saves`() = runTest {
        val base = ManualWorkingCopies.from(null, testIdentity())
        val trace = testTrace().let { original ->
            original.copy(observations = original.observations + original.observations.single().copy(
                pageName = "资料库",
                matchedHeadingPath = null,
                visualSignature = "顶部资料库标题，中部条目列表",
                reusableFacts = listOf("页面用于浏览离线条目"),
                navigationFacts = listOf("首页 → 底部资料库 → 资料库"),
            ))
        }
        fun edits(includePurpose: Boolean): Array<ManualPartialEdit> = buildList {
            if (includePurpose) add(ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 用于浏览和查阅离线资料。"))
            add(ManualPartialEdit("页面索引", "- 尚无已验证经验。", "- 首页：底部导航。\n- 资料库：条目列表。"))
            add(ManualPartialEdit("页面详情", "- 尚无已验证经验。", "### 首页\n- 底部导航。\n\n### 资料库\n- 条目列表。"))
            add(ManualPartialEdit("页面导航", "- 尚无已验证经验。", "- 首页 → 底部资料库 → 资料库。"))
        }.toTypedArray()
        val fake = ManualTestGateway { attempt, _ -> editResponse(0L, *edits(includePurpose = attempt == 2)) }

        assertTrue(ManualSemanticGuard.purposeRequiredAfterUpdate(base.markdown, trace))

        val result = ManualCuratorRequester(fake.gateway).request(base, trace) as CuratorRequestResult.Success

        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[1].userPrompt.contains("APP_PURPOSE_REQUIRED"))
        assertEquals(4, result.decision.edits.size)
        assertTrue(result.candidate!!.markdown.contains("- 用于浏览和查阅离线资料。"))
    }

    @Test
    fun `legacy multi page manual with empty purpose self heals after no change`() = runTest {
        val incomplete = testManual(markdown = testMarkdown().replace(
            "- 用于测试 App 手册。",
            "- 尚无已验证经验。",
        ))
        val base = ManualWorkingCopies.from(incomplete, testIdentity())
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) toolResponse(1L)
            else editResponse(1L, ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 用于浏览页面和调整设置。"))
        }

        val result = ManualCuratorRequester(fake.gateway).request(base, testTrace()) as CuratorRequestResult.Success

        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[1].userPrompt.contains("APP_PURPOSE_REQUIRED"))
        assertEquals(2L, result.candidate!!.revision)
        assertFalse(AppManualMarkdownParser.parse(result.candidate.markdown)
            .section("App 用途")!!.directMarkdown.contains("尚无已验证经验"))
    }

    @Test
    fun `curator requester uses zero temperature and parses no change`() = runTest {
        val fake = ManualTestGateway { _, _ -> toolResponse(1L) }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(testManual(), testIdentity()), testTrace())
        assertTrue(result is CuratorRequestResult.Success)
        assertEquals(null, (result as CuratorRequestResult.Success).candidate)
        assertEquals(0.0, fake.requests.single().temperature, 0.0)
        assertEquals(AppManualPolicy.MAX_CURATION_OUTPUT_TOKENS, fake.requests.single().maxOutputTokens)
    }

    @Test
    fun `executable edit error is fed back once and complete corrected batch is validated`() = runTest {
        val base = ManualWorkingCopies.from(testManual(markdown = testMarkdown("- 搜索入口，另一个搜索入口。")), testIdentity())
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) editResponse(1L, ManualPartialEdit("页面详情/首页", "搜索入口", "导航入口"))
            else editResponse(1L, ManualPartialEdit("页面详情/首页", "- 搜索入口，另一个搜索入口。", "- 顶部导航入口。"))
        }
        val result = ManualCuratorRequester(fake.gateway).request(base, testTrace()) as CuratorRequestResult.Success
        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[1].userPrompt.contains("OLD_TEXT_NOT_UNIQUE"))
        assertTrue(fake.requests[1].userPrompt.contains("\"matchCountWithinTarget\":2"))
        assertTrue(fake.requests[1].userPrompt.contains("搜索入口，另一个搜索入口。"))
        assertEquals(2L, result.candidate!!.revision)
        assertTrue(result.candidate.markdown.contains("- 顶部导航入口。"))
    }

    @Test
    fun `schema and executable edit failures share the same two attempt budget`() = runTest {
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) "{}" else editResponse(0L, ManualPartialEdit("页面索引", "not present", "replacement"))
        }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace())
        assertEquals(CuratorRequestResult.Failure("OLD_TEXT_NOT_FOUND"), result)
        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[1].userPrompt.contains("INVALID_RESPONSE"))
    }

    @Test
    fun `unseen heading edits are denied even when anchor exists in full manual`() = runTest {
        val fake = ManualTestGateway { _, _ ->
            editResponse(1L, ManualPartialEdit("页面详情/设置页", "通用选项", "高级选项"))
        }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(testManual(), testIdentity()), testTrace())
        assertEquals(CuratorRequestResult.Failure("UNKNOWN_TARGET_ID"), result)
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `empty manual summarize request is denied then model may choose partial edit`() = runTest {
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) toolResponse(0L, "SUMMARIZE")
            else editResponse(0L, ManualPartialEdit("页面详情", "- 尚无已验证经验。", "### 首页\n- 顶部标题和底部导航。"))
        }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace())
        assertEquals(1L, (result as CuratorRequestResult.Success).candidate!!.revision)
        assertTrue(fake.requests[1].userPrompt.contains("SUMMARIZE_NOT_ALLOWED"))
    }

    @Test
    fun `requester own timeout becomes typed failure`() = runTest {
        val fake = ManualTestGateway { _, _ -> awaitCancellation() }
        val result = ManualCuratorRequester(fake.gateway, timeoutMs = 50L).request(ManualWorkingCopies.from(null, testIdentity()), testTrace())
        assertEquals(CuratorRequestResult.Failure("TIMEOUT"), result)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `summarizer own timeout does not propagate cancellation`() = runTest {
        val fake = ManualTestGateway { _, _ -> awaitCancellation() }
        val result = ManualSummarizer(fake.gateway, timeoutMs = 50L).summarize(ManualWorkingCopies.from(testManual(), testIdentity()), testTrace())
        assertEquals("TIMEOUT", (result.exceptionOrNull() as ManualEditException).reasonCode)
    }

    @Test
    fun `target table JSON preserves exact source including whitespace and unicode`() {
        val base = ManualWorkingCopies.from(testManual(markdown = testMarkdown("- 路径 \\\\ 与引号 \"你好\"。").replace("### 首页", "###  首页  ")), testIdentity())
        val context = ManualCurationContextBuilder.context(base, testTrace())
        val table = JSONArray(context.json())
        for (index in 0 until table.length()) {
            val row = table.getJSONObject(index)
            assertEquals(context.targets.getValue(row.getString("targetId")).directMarkdown, row.getString("exactMarkdown"))
        }
        assertEquals(null, context.idFor("App 信息"))
        val protected = base.copy(protectedHeadingPaths = base.protectedHeadingPaths + "App 用途")
        val reduced = ManualCurationContextBuilder.context(protected, testTrace())
        assertEquals(null, reduced.idFor("App 用途"))
        assertEquals("s1", reduced.idFor("页面索引")) // Not a fixed global ID -> heading mapping.
    }

    @Test
    fun `unknown ID is repaired against the same table without heading aliases`() = runTest {
        val base = ManualWorkingCopies.from(null, testIdentity())
        val fake = ManualTestGateway { attempt, _ ->
            val response = editResponse(0L, ManualPartialEdit("页面详情", "- 尚无已验证经验。", "### 首页\n- 顶部标题和底部导航。"))
            if (attempt == 1) response.replace("\"s3\"", "\"页面详情/首页\"") else response
        }
        val result = ManualCuratorRequester(fake.gateway).request(base, testTrace()) as CuratorRequestResult.Success
        assertEquals(1L, result.candidate!!.revision)
        assertTrue(fake.requests[1].userPrompt.contains("UNKNOWN_TARGET_ID at edit 1"))
        assertTrue(fake.requests[1].userPrompt.contains("New pages use parent ID s3"))
        assertTrue(fake.requests.all { "newPageParentTargetId: s3" in it.userPrompt })
    }

    @Test
    fun `truncated valid looking JSON never becomes a saved candidate`() = runTest {
        val gateway = mockk<CloudModelGateway>()
        var calls = 0
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            calls++
            Result.success(StructuredTextResult(editResponse(0L, ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 浏览页面。")), StructuredTextFinishReason.TRUNCATED))
        }
        assertEquals(CuratorRequestResult.Failure("TRUNCATED"), ManualCuratorRequester(gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace()))
        assertEquals(2, calls)
    }

    @Test
    fun `bounded repair transport failure cannot save a preceding invalid candidate`() = runTest {
        val gateway = mockk<CloudModelGateway>()
        var calls = 0
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            calls++
            if (calls == 1) Result.success(StructuredTextResult(
                editResponse(0L, ManualPartialEdit("invalid", "old", "new")), StructuredTextFinishReason.COMPLETE,
            )) else Result.failure(java.net.SocketTimeoutException())
        }
        assertEquals(CuratorRequestResult.Failure("REQUEST_FAILED"), ManualCuratorRequester(gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace()))
        assertEquals(2, calls)
    }

    @Test
    fun `first transport failure returns immediately without a paid retry`() = runTest {
        val gateway = mockk<CloudModelGateway>()
        var calls = 0
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            calls++
            Result.failure(java.net.SocketTimeoutException())
        }

        assertEquals(
            CuratorRequestResult.Failure("REQUEST_FAILED"),
            ManualCuratorRequester(gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace()),
        )
        assertEquals(1, calls)
    }

    @Test fun `wrapped decision creates a candidate without spending a repair attempt`() = runTest {
        val fake = ManualTestGateway { _, _ ->
            "<think>service prefix {not the answer}</think>\n```json\n" +
                editResponse(0L, ManualPartialEdit("页面详情", "- 尚无已验证经验。", "### 首页\n- 顶部标题和底部导航。")) + "\n```"
        }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(null, testIdentity()), testTrace())
        assertEquals(1L, (result as CuratorRequestResult.Success).candidate!!.revision)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `new page cannot be silently omitted by no change or unrelated edits`() = runTest {
        val base = ManualWorkingCopies.from(null, testIdentity())
        val noChange = ManualTestGateway { _, _ -> toolResponse(0L) }
        assertEquals(
            CuratorRequestResult.Failure("UNCOVERED_NEW_PAGE"),
            ManualCuratorRequester(noChange.gateway).request(base, testTrace()),
        )
        assertEquals(2, noChange.requests.size)
        assertTrue(noChange.requests[1].userPrompt.contains("Every NEW_OR_CHANGED Page"))

        val unrelated = ManualTestGateway { _, _ ->
            editResponse(0L, ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 用于浏览页面。"))
        }
        assertEquals(
            CuratorRequestResult.Failure("UNCOVERED_NEW_PAGE"),
            ManualCuratorRequester(unrelated.gateway).request(base, testTrace()),
        )
    }

    @Test fun `malformed envelope repair keeps the same bounded attempts and precise feedback`() = runTest {
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) "<think>unclosed " + toolResponse(1L) else "<think>closed</think>" + toolResponse(1L)
        }
        val result = ManualCuratorRequester(fake.gateway).request(ManualWorkingCopies.from(testManual(), testIdentity()), testTrace())
        assertTrue(result is CuratorRequestResult.Success)
        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[1].userPrompt.contains("UNCLOSED_THINK_PREFIX"))
    }

    @Test fun `unwrapping does not allow extra JSON objects trailing prose or unknown fields`() = runTest {
        listOf(toolResponse(1L) + "{}", toolResponse(1L) + " trailing prose",
            toolResponse(1L).replace("\"edits\":[]", "\"edits\":[],\"extra\":true"),
        ).forEach { payload ->
            val fake = ManualTestGateway { _, _ -> "<think>service prefix</think>$payload" }
            assertEquals(CuratorRequestResult.Failure("INVALID_RESPONSE"), ManualCuratorRequester(fake.gateway).request(
                ManualWorkingCopies.from(testManual(), testIdentity()), testTrace(),
            ))
            assertEquals(2, fake.requests.size)
            assertTrue(fake.requests[1].userPrompt.contains("matching the required schema"))
        }
    }

    @Test fun `unwrapped decisions still enforce revision and exact edit anchors`() = runTest {
        listOf(
            editResponse(99L, ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 浏览页面。")) to "REVISION_MISMATCH",
            editResponse(0L, ManualPartialEdit("App 用途", "absent", "- 浏览页面。")) to "OLD_TEXT_NOT_FOUND",
        ).forEach { (payload, reason) ->
            val fake = ManualTestGateway { _, _ -> "<think>service prefix</think>$payload" }
            assertEquals(CuratorRequestResult.Failure(reason), ManualCuratorRequester(fake.gateway).request(
                ManualWorkingCopies.from(null, testIdentity()), testTrace(),
            ))
        }
    }

    @Test fun `metadata decoding remains strict and never unwraps thinking prefixes`() {
        val metadata = testManual().metadata
        val json = AppManualCodec.encodeMetadata(metadata)
        assertEquals(metadata, AppManualCodec.decodeMetadata(json))
        assertTrue(runCatching { AppManualCodec.decodeMetadata("<think>prefix</think>$json") }.isFailure)
    }

    @Test fun `summarizer accepts bounded envelope without putting thinking text in the manual`() = runTest {
        val markdown = testMarkdown()
        val fake = ManualTestGateway { _, _ -> "<think>private reasoning</think>\n```markdown\n$markdown\n```" }
        assertEquals(markdown, ManualSummarizer(fake.gateway).summarize(
            ManualWorkingCopies.from(testManual(), testIdentity()), testTrace(),
        ).getOrThrow())
    }
}
