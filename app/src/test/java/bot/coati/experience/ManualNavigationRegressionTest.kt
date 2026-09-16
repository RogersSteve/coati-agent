package bot.coati.experience

import android.util.Log
import bot.coati.model.ManualPageObservation
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** Actual exported bases; observations/responses below are reconstructed offline fixtures. */
class ManualNavigationRegressionTest {
    private val root = Files.createTempDirectory("manual-navigation-regression").toFile()
    private val repository = AppManualRepository(root)

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun cleanup() {
        unmockkStatic(Log::class)
        root.deleteRecursively()
    }

    private fun fixture(name: String): AppManual {
        fun read(ext: String) = javaClass.getResource("/manual_navigation_20260906/$name.$ext")!!
            .readText(Charsets.UTF_8).replace("\r\n", "\n")
        val manual = AppManual(AppManualCodec.decodeMetadata(read("json")), read("md"))
        assertEquals(manual.metadata.manualSha256, AppManualCodec.sha256(manual.markdown))
        assertNull(AppManualPolicy.validateManual(manual))
        return manual
    }

    private fun labManual(versionCode: Long = 1L, revision: Long = 1L): AppManual {
        val identity = testIdentity("bot.coati.fixture.manual").copy(
            displayName = "Coati 手册实验室",
            versionCode = versionCode,
        )
        val markdown = testMarkdown()
            .replace("# Example 使用手册", "# Coati 手册实验室 使用手册")
            .replace("- 包名：com.example.app", "- 包名：bot.coati.fixture.manual")
        return testManual(revision = revision, markdown = markdown, identity = identity)
    }

    private fun damagedLabManual(base: AppManual): AppManual = AppManualFactory.create(
        identity = base.app.copy(versionCode = 2L),
        markdown = base.markdown.replace(
            "- 首页 → 搜索框 → 搜索结果页。",
            "- 首页 → 动态值 → 搜索结果页。",
        ),
        revision = 2L,
        now = 2L,
    )

    private fun observation(name: String, signature: String, navigation: String) = ManualPageObservation(
        "NEW_OR_CHANGED", name, null, signature, listOf(signature), listOf(navigation), null,
    )

    private fun trace(identity: ManualAppIdentity, vararg observations: ManualPageObservation): ManualAppLearningTrace {
        val collector = ManualTraceCollector()
        observations.forEach {
            assertTrue(collector.recordCompletedPage(identity, it, null, emptySet()) is ManualObservationRecordResult.Accepted)
        }
        return collector.buildRequest()!!.traces.single()
    }

    private fun response(base: ManualWorkingCopy, trace: ManualAppLearningTrace, edits: List<ManualPartialEdit>): String {
        val context = ManualCurationContextBuilder.context(base, trace)
        return JSONObject().put("expectedRevision", base.revision).put("tool", "PARTIAL_EDIT")
            .put("edits", JSONArray(edits.map { edit ->
                JSONObject().put("targetId", context.idFor(edit.targetHeading))
                    .put("oldText", edit.oldText).put("newText", edit.newText)
            })).toString()
    }

    @Test fun `lab update preserves search knowledge and clock learning remains isolated`() = runTest {
        val old = labManual()
        assertTrue(repository.save(old, 0L) is ManualSaveResult.Saved)
        val c = trace(old.app.copy(versionCode = 2L), observation("实验档案",
            "顶部实验档案标题，中部为固定维护记录列表",
            "首页 → 我的 → 设置 → 高级设置 → 实验档案"))
        val base = ManualWorkingCopies.from(old, c.identity)
        val parsed = AppManualMarkdownParser.parse(old.markdown)
        val parent = parsed.section("页面详情")!!.directMarkdown
        val nav = parsed.section("页面导航")!!.directMarkdown
        val gateway = ManualTestGateway { _, request ->
            assertTrue(request.userPrompt.contains("Page: 实验档案"))
            assertFalse(request.userPrompt.contains("动态值"))
            response(base, c, listOf(
                ManualPartialEdit("页面详情", parent, parent + "\n### 实验档案\n- 识别：${c.observations.single().visualSignature}。\n"),
                ManualPartialEdit("页面导航", nav, nav + "\n- 首页 → 我的 → 设置 → 高级设置 → 实验档案。\n"),
            ))
        }
        assertTrue(ManualUpdateProcessor(repository, gateway.gateway).process(ManualMode.LEARNING, c).saved)
        val lab = repository.load(old.app.appKey)!!
        assertEquals(2L, lab.revision)
        for (path in listOf("页面详情/首页", "页面详情/搜索结果页", "页面详情/设置页")) {
            assertEquals(parsed.section(path)!!.directMarkdown,
                AppManualMarkdownParser.parse(lab.markdown).section(path)!!.directMarkdown)
        }
        assertTrue(lab.markdown.contains("首页 → 搜索框 → 搜索结果页"))
        assertFalse(lab.markdown.contains("动态值"))
        val md = repository.manualFile(old.app.appKey).readBytes()
        val meta = repository.metadataFile(old.app.appKey).readBytes()

        val clockIdentity = fixture("d-clock").app
        val d = trace(clockIdentity,
            observation("时钟-计时器", "时间滚轮、常用计时器列表，底部有秒表与计时器入口", "底部秒表入口切换到秒表"),
            observation("秒表", "中央计时盘，下方开始按钮，底部秒表选中", "计时器 → 底部秒表 → 秒表"))
        assertEquals(listOf("时钟-计时器", "秒表"), d.observations.map { it.pageName })
        val clockBase = ManualWorkingCopies.from(null, clockIdentity)
        val clockGateway = ManualTestGateway { _, request ->
            assertTrue(request.userPrompt.contains("Page: 秒表"))
            response(clockBase, d, listOf(
                ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 用于查看计时器并切换到秒表。"),
                ManualPartialEdit("页面详情", "- 尚无已验证经验。",
                    "### 时钟-计时器\n- 时间滚轮、常用计时器列表，底部秒表入口。\n\n### 秒表\n- 中央计时盘，下方开始按钮，底部秒表选中。\n"),
                ManualPartialEdit("页面导航", "- 尚无已验证经验。", "- 计时器 → 底部秒表 → 秒表。"),
            ))
        }
        assertTrue(ManualUpdateProcessor(repository, clockGateway.gateway).process(ManualMode.LEARNING, d).saved)
        val clock = repository.load(clockIdentity.appKey)!!
        assertEquals(1L, clock.revision)
        assertTrue(clock.markdown.contains("### 秒表"))
        assertFalse(clock.markdown.contains("动态值"))
        val rendered = AppManualRenderer().render(clock, ManualMode.READ_ONLY, "查看秒表", "秒表")!!
        assertTrue(rendered.text.contains("秒表"))
        assertArrayEquals(md, repository.manualFile(old.app.appKey).readBytes())
        assertArrayEquals(meta, repository.metadataFile(old.app.appKey).readBytes())
    }

    @Test fun `damaged lab candidate is rejected while admitted page evidence is recovered`() = runTest {
        val old = labManual()
        val bad = damagedLabManual(old)
        repository.save(old, 0L)
        val c = trace(old.app, observation("实验档案", "顶部实验档案标题，中部维护记录列表", "高级设置 → 实验档案"))
        val base = ManualWorkingCopies.from(old, old.app)
        val oldNav = AppManualMarkdownParser.parse(old.markdown).section("页面导航")!!.directMarkdown
        val badNav = AppManualMarkdownParser.parse(bad.markdown).section("页面导航")!!.directMarkdown
        val gateway = ManualTestGateway { _, _ -> response(base, c, listOf(ManualPartialEdit("页面导航", oldNav, badNav))) }
        val result = ManualUpdateProcessor(repository, gateway.gateway).process(ManualMode.LEARNING, c)
        assertTrue(result.saved)
        assertEquals("UNSUPPORTED_PLACEHOLDER", result.fallbackTrigger)
        assertEquals(2, gateway.requests.size)
        val recovered = repository.load(old.app.appKey)!!
        assertEquals(2L, recovered.revision)
        assertTrue(recovered.markdown.contains("### 实验档案"))
        assertFalse(recovered.markdown.contains("动态值"))
        val before = AppManualMarkdownParser.parse(old.markdown)
        val after = AppManualMarkdownParser.parse(recovered.markdown)
        for (path in listOf("页面详情/首页", "页面详情/搜索结果页", "页面详情/设置页")) {
            assertEquals(before.section(path)!!.directMarkdown, after.section(path)!!.directMarkdown)
        }
    }

    @Test fun `guard rejects recorded C and D damage but is not a whole manual word blacklist`() {
        val old = labManual()
        val c = trace(old.app, observation("实验档案", "顶部实验档案标题", "高级设置 → 实验档案"))
        for ((base, candidate) in listOf(ManualWorkingCopies.from(old, old.app) to damagedLabManual(old),
            ManualWorkingCopies.from(null, fixture("d-clock").app) to fixture("d-clock"))) {
            val error = runCatching { ManualSemanticGuard.validate(base, candidate, c) }.exceptionOrNull()
            assertEquals("UNSUPPORTED_PLACEHOLDER", (error as ManualEditException).reasonCode)
        }
        val human = testManual(markdown = testMarkdown("- 此页面展示动态值，每次观察应以实时界面为准。"))
        // Legitimate human prose can remain or shorten; it is not rejected merely by keyword.
        ManualSemanticGuard.validate(ManualWorkingCopies.from(human, human.app), human, testTrace())
    }

    @Test fun `summary path cannot bypass loss guard and local recovery contains no damaged text`() = runTest {
        val old = testManual(markdown = testMarkdown("- 榜单入口。")
            .replace("- 榜单入口。", "- 榜单入口。\n" + "- 无关重复描述。\n".repeat(30)))
        repository.save(old, 0L)
        val broad = testTrace().let { t -> t.copy(observations = listOf("首页", "榜单", "设置").map {
            t.observations.single().copy(pageName = it)
        }) }
        val gateway = ManualTestGateway { attempt, _ ->
            if (attempt == 1) toolResponse(1L, "SUMMARIZE") else testMarkdown("- 动态值入口。")
        }
        val result = ManualUpdateProcessor(repository, gateway.gateway).process(ManualMode.LEARNING, broad)
        assertTrue(result.saved)
        assertTrue(result.fallbackTrigger != null)
        val recovered = repository.load(old.app.appKey)!!
        assertEquals(2L, recovered.revision)
        assertTrue(recovered.markdown.contains("### 榜单"))
        assertTrue(recovered.markdown.contains("### 设置"))
        assertTrue(recovered.markdown.contains("- 用于测试 App 手册。"))
        assertFalse(recovered.markdown.contains("动态值"))
    }
}
