package bot.coati.experience

import android.util.Log
import android.graphics.Rect
import bot.coati.model.ManualPageObservation
import bot.coati.service.UiTreeNode
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ManualUpdateCoordinatorTest {
    private val root = Files.createTempDirectory("coati-manual-coordinator").toFile()
    private val repository = AppManualRepository(root)
    private var coordinator: ManualUpdateCoordinator? = null
    private val placeholder = "- 尚无已验证经验。"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        coordinator?.close()
        unmockkStatic(Log::class)
        root.deleteRecursively()
    }

    private fun bootstrap() = editResponse(0L,
        ManualPartialEdit("App 用途", placeholder, "- 用于浏览和搜索应用。"),
        ManualPartialEdit("页面索引", placeholder, "- 首页｜顶部搜索框，底部导航｜入口：启动进入"),
        ManualPartialEdit("页面详情", placeholder, "### 首页\n- 顶部搜索框，下方应用列表。"),
        ManualPartialEdit("页面导航", placeholder, "- 首页 → 搜索框 → 搜索结果页。"),
    )

    @Test
    fun `quoted navigation observation reaches curator saves a new page and Guide retrieves it`() = runTest {
        repository.save(testManual(), 0L)
        val collector = ManualTraceCollector()
        val tabTree = UiTreeNode(
            className = "android.widget.TabWidget", text = null, contentDescription = null,
            bounds = Rect(), isClickable = false, isScrollable = false, viewIdResourceName = null,
            children = listOf("首页", "收藏").map { label ->
                UiTreeNode(
                    className = "android.widget.TextView", text = label, contentDescription = null,
                    bounds = Rect(), isClickable = true, isScrollable = false,
                    viewIdResourceName = null, children = emptyList(),
                )
            },
        )
        val observation = ManualPageObservation(
            status = "NEW_OR_CHANGED", pageName = "收藏", matchedHeadingPath = null,
            visualSignature = "底部收藏页签，上方条目列表", reusableFacts = listOf("收藏用于浏览保存的条目"),
            navigationFacts = listOf("首页 → 收藏"), popupOrRecoveryFact = null,
        )
        collector.recordCompletedPage(testIdentity(), observation, tabTree, emptySet())
        val request = collector.buildRequest()!!
        val before = repository.load(testIdentity().appKey)!!
        val parent = AppManualMarkdownParser.parse(before.markdown).section("页面详情")!!.directMarkdown
        val fake = ManualTestGateway { _, modelRequest ->
            assertTrue(modelRequest.userPrompt.contains("Page: 收藏"))
            assertFalse(modelRequest.userPrompt.contains("Page: 动态值"))
            editResponse(1L,
                ManualPartialEdit("页面详情", parent, "$parent\n### 收藏\n- 底部收藏页签，上方条目列表。\n"),
                ManualPartialEdit("页面导航", "- 首页 → 搜索框 → 搜索结果页。", "- 首页 → 搜索框 → 搜索结果页。\n- 首页 → 收藏。"),
            )
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        assertTrue(worker.enqueue(request, ManualMode.LEARNING))
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(2L, saved.revision)
        assertTrue(saved.markdown.contains("### 收藏"))
        val beforePages = AppManualMarkdownParser.parse(before.markdown)
        val afterPages = AppManualMarkdownParser.parse(saved.markdown)
        for (path in listOf("页面详情/首页", "页面详情/搜索结果页", "页面详情/设置页")) {
            assertEquals(beforePages.section(path)!!.directMarkdown, afterPages.section(path)!!.directMarkdown)
        }
        val mdBytes = repository.manualFile(saved.app.appKey).readBytes()
        val metaBytes = repository.metadataFile(saved.app.appKey).readBytes()
        val guide = AppManualRenderer().render(saved, ManualMode.READ_ONLY, "查看收藏", "收藏页签")!!
        assertTrue("页面详情/收藏" in guide.headingPaths)
        assertFalse(worker.enqueue(request, ManualMode.READ_ONLY))
        advanceUntilIdle()
        assertEquals(1, fake.requests.size)
        assertTrue(mdBytes.contentEquals(repository.manualFile(saved.app.appKey).readBytes()))
        assertTrue(metaBytes.contentEquals(repository.metadataFile(saved.app.appKey).readBytes()))
    }

    @Test
    fun `queue response codec scoped edits save and reload bootstrap unknown app`() = runTest {
        val fake = ManualTestGateway { _, _ -> bootstrap() }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        assertTrue(worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING))
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(1L, saved.revision)
        assertTrue(saved.markdown.contains("### 首页"))
        assertTrue(repository.manualFile(saved.app.appKey).isFile)
        assertTrue(repository.metadataFile(saved.app.appKey).isFile)
        assertEquals(null, AppManualPolicy.validateManual(saved))
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `bad batch is corrected once and persisted as a single revision`() = runTest {
        repository.save(testManual(markdown = testMarkdown("- 搜索入口，另一个搜索入口。")), 0L)
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) editResponse(1L,
                ManualPartialEdit("页面导航", "- 首页 → 搜索框 → 搜索结果页。", "- 首页 → 搜索框 → 搜索结果页。\n- 首页 → 收藏。"),
                ManualPartialEdit("页面详情/首页", "搜索入口", "导航入口"),
            ) else {
                // The first, valid edit of the rejected batch must not have been saved either.
                val unchanged = repository.load(testIdentity().appKey)!!
                assertEquals(1L, unchanged.revision)
                assertFalse(unchanged.markdown.contains("首页 → 收藏"))
                editResponse(1L,
                    ManualPartialEdit("页面导航", "- 首页 → 搜索框 → 搜索结果页。", "- 首页 → 搜索框 → 搜索结果页。\n- 首页 → 收藏。"),
                    ManualPartialEdit("页面详情/首页", "- 搜索入口，另一个搜索入口。", "- 顶部导航入口。"),
                )
            }
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING)
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(2L, saved.revision)
        assertTrue(saved.markdown.contains("用于测试 App 手册"))
        assertTrue(saved.markdown.contains("首页 → 收藏"))
        assertTrue(saved.markdown.contains("顶部导航入口"))
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `exhausted correction falls back once and next identical request is idempotent`() = runTest {
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt <= 2) editResponse(0L,
                ManualPartialEdit("App 用途", placeholder, "- 用于浏览应用。"),
                ManualPartialEdit("页面索引", "missing anchor", "- 首页"),
            ) else toolResponse(1L)
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        repeat(2) { assertTrue(worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING)) }
        advanceUntilIdle()
        assertEquals(1L, repository.load(testIdentity().appKey)!!.revision)
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun `new evidence cannot be silently discarded and off or guide never enqueue`() = runTest {
        val fake = ManualTestGateway { _, _ -> toolResponse(0L) }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        val request = ManualLearningRequest(listOf(testTrace()))
        assertFalse(worker.enqueue(request, ManualMode.OFF))
        assertFalse(worker.enqueue(request, ManualMode.READ_ONLY))
        assertTrue(worker.enqueue(request, ManualMode.LEARNING))
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(1L, saved.revision)
        assertTrue(saved.markdown.contains("### 首页"))
        assertTrue(repository.namespaceDirectory(ManualNamespace.CANONICAL).exists())
        // NEW_OR_CHANGED gets one bounded model correction and then the guarded local merger.
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `shadow update remains isolated from canonical`() = runTest {
        val fake = ManualTestGateway { _, _ -> bootstrap() }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.SHADOW)
        advanceUntilIdle()
        assertEquals(null, repository.load(testIdentity().appKey))
        assertEquals(1L, repository.load(testIdentity().appKey, ManualNamespace.SHADOW)!!.revision)
    }

    @Test
    fun `unnecessary summary is repaired then falls back without killing queue worker`() = runTest {
        repository.save(testManual(), 0L)
        val broadTrace = testTrace().let { trace -> trace.copy(observations = listOf("首页", "分类页", "我的页").map { name ->
            trace.observations.single().copy(pageName = name)
        }) }
        val fake = ManualTestGateway { attempt, _ ->
            when (attempt) {
                1 -> { delay(60L); toolResponse(1L, "SUMMARIZE") }
                2 -> { delay(60L); testMarkdown() }
                else -> toolResponse(2L)
            }
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 100L)
        coordinator = worker
        worker.enqueue(ManualLearningRequest(listOf(broadTrace)), ManualMode.LEARNING)
        worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING)
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(2L, saved.revision)
        assertTrue(saved.markdown.contains("用于测试 App 手册"))
        assertTrue(saved.markdown.contains("### 分类页"))
        assertTrue(saved.markdown.contains("### 我的页"))
        assertEquals(3, fake.requests.size)
        assertEquals(75L, testScheduler.currentTime)
    }

    @Test
    fun `total deadline reloads and commits admitted evidence through local fallback`() = runTest {
        val nearLimitMarkdown = testMarkdown().replace(
            "## 页面导航",
            "### 归档\n- ${"稳".repeat(16_000)}\n\n## 页面导航",
        )
        repository.save(testManual(markdown = nearLimitMarkdown), 0L)
        val fake = ManualTestGateway { attempt, _ ->
            if (attempt == 1) {
                delay(50L)
                toolResponse(1L, "SUMMARIZE")
            } else {
                awaitCancellation()
            }
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 100L)
        coordinator = worker

        assertTrue(worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING))
        advanceUntilIdle()

        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(2L, saved.revision)
        assertTrue(saved.markdown.contains("- 识别：顶部搜索入口，底部首页 Tab 高亮。"))
        assertTrue(saved.markdown.contains("- 用于测试 App 手册。"))
        assertEquals(2, fake.requests.size)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun `curator timeout falls back locally and leaves consumer ready for next request`() = runTest {
        val fake = ManualTestGateway { attempt, _ -> if (attempt == 1) awaitCancellation() else toolResponse(1L) }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 100L)
        coordinator = worker
        repeat(2) { worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING) }
        advanceUntilIdle()
        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(1L, saved.revision)
        assertTrue(saved.markdown.contains("### 首页"))
        assertEquals(2, fake.requests.size)
        assertEquals(75L, testScheduler.currentTime)
    }

    @Test
    fun `transport failure saves admitted observations through guarded local fallback`() = runTest {
        val gateway = io.mockk.mockk<bot.coati.model.CloudModelGateway>()
        var calls = 0
        io.mockk.coEvery { gateway.generateStructuredText(any()) } coAnswers {
            calls++
            Result.failure(java.net.SocketTimeoutException())
        }
        val source = testTrace().observations.single()
        val trace = testTrace().copy(observations = listOf(
            source.copy(pageName = "资料库", matchedHeadingPath = null),
            source.copy(pageName = "条目详情", matchedHeadingPath = null, terminal = true),
        ))
        val worker = ManualUpdateCoordinator(repository, gateway, StandardTestDispatcher(testScheduler), 100L)
        coordinator = worker

        assertTrue(worker.enqueue(ManualLearningRequest(listOf(trace)), ManualMode.LEARNING))
        advanceUntilIdle()

        val saved = repository.load(testIdentity().appKey)!!
        assertEquals(1L, saved.revision)
        assertTrue(saved.markdown.contains("### 资料库"))
        assertTrue(saved.markdown.contains("### 条目详情"))
        assertFalse(saved.markdown.substringAfter("## App 用途").substringBefore("## 页面索引")
            .contains("尚无已验证经验"))
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent manual revision wins over stale model response without retry`() = runTest {
        val newer = testManual(markdown = testMarkdown("- 人工已更新的首页。"))
        val fake = ManualTestGateway { _, _ ->
            repository.save(newer, 0L)
            bootstrap()
        }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        worker.enqueue(ManualLearningRequest(listOf(testTrace())), ManualMode.LEARNING)
        advanceUntilIdle()
        assertEquals(newer, repository.load(testIdentity().appKey))
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `close cancels in flight update rejects new work and writes nothing`() = runTest {
        val fake = ManualTestGateway { _, _ -> awaitCancellation() }
        val worker = ManualUpdateCoordinator(repository, fake.gateway, StandardTestDispatcher(testScheduler), 120_000L)
        coordinator = worker
        val request = ManualLearningRequest(listOf(testTrace()))
        worker.enqueue(request, ManualMode.LEARNING)
        runCurrent()
        worker.close()
        advanceUntilIdle()
        assertFalse(worker.enqueue(request, ManualMode.LEARNING))
        assertEquals(null, repository.load(testIdentity().appKey))
        assertEquals(1, fake.requests.size)
    }
}
