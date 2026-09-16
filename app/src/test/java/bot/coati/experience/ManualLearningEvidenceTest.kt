package bot.coati.experience

import android.graphics.Rect
import bot.coati.action.AgentAction
import bot.coati.model.ManualPageObservation
import bot.coati.service.UiTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualLearningEvidenceTest {
    @Test
    fun `navigation names survive absent large and unfamiliar trees without task parsing`() {
        for (name in listOf("榜单", "秒表", "时钟秒表页", "Bookmarks", "我")) {
            for (screen in listOf(null, tree(*Array(700) { child(text = "条目", clickable = true) }))) {
                val collector = ManualTraceCollector()
                val raw = observation("NEW_OR_CHANGED").copy(
                    pageName = name, visualSignature = "底部$name 导航，上方分类列表",
                    navigationFacts = listOf("首页 → $name"),
                )
                assertTrue(collector.recordCompletedPage(testIdentity(), raw, screen, emptySet()) is ManualObservationRecordResult.Accepted)
                val saved = collector.buildRequest()!!.traces.single().observations.single()
                assertEquals(name, saved.pageName)
                assertEquals(raw.visualSignature, saved.visualSignature)
                assertEquals(raw.navigationFacts, saved.navigationFacts)
            }
        }
    }

    @Test
    fun `actual input cannot manufacture an embedded placeholder page identity`() {
        for (action in listOf(AgentAction.InputText("榜单"), AgentAction.FocusAndInputText(10, 10, "榜单"))) {
            val collector = ManualTraceCollector()
            val result = collector.recordVerifiedSuccess(
                testIdentity(), observation("NEW_OR_CHANGED").copy(pageName = "Coati榜单页"),
                action, null, null, emptySet(),
            )
            assertEquals(ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.MISSING_PAGE_NAME), result)
            assertNull(collector.buildRequest())
        }
    }

    @Test
    fun `late actual input removes contaminated fields without replacing old knowledge`() {
        val collector = ManualTraceCollector()
        collector.recordCompletedPage(testIdentity(), observation("NEW_OR_CHANGED",
            signature = "首页展示较早看到的内容", facts = listOf("顶部搜索框", "列表含较早看到的内容")),
            null, emptySet())
        collector.recordVerifiedSuccess(testIdentity(), null, AgentAction.InputText("较早看到的内容"), null, null, emptySet())
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals("顶部搜索框", saved.visualSignature)
        assertEquals(listOf("顶部搜索框"), saved.reusableFacts)
        assertFalse(saved.toString().contains("动态值"))
        assertFalse(saved.toString().contains("较早看到的内容"))
    }

    @Test
    fun `late actual input cannot leave a new page without reusable support`() {
        val collector = ManualTraceCollector()
        collector.recordCompletedPage(testIdentity(),
            observation("NEW_OR_CHANGED", facts = listOf("稍后输入的值")).copy(navigationFacts = emptyList()),
            null, emptySet())
        collector.recordVerifiedSuccess(testIdentity(), null, AgentAction.InputText("稍后输入的值"), null, null, emptySet())
        assertNull(collector.buildRequest())
    }

    @Test
    fun `explicit sensitive types omit fields but retain independent structural facts`() {
        for (value in listOf("13812345678", "test@example.com", "https://example.com/user", "123456")) {
            val collector = ManualTraceCollector()
            collector.recordCompletedPage(testIdentity(), observation("NEW_OR_CHANGED",
                signature = "顶部搜索框", facts = listOf("显示$value", "右上角搜索按钮")),
                null, emptySet())
            val saved = collector.buildRequest()!!.traces.single().observations.single()
            assertEquals(listOf("右上角搜索按钮"), saved.reusableFacts)
            assertFalse(saved.toString().contains(value))
            assertFalse(saved.toString().contains("动态值"))
        }
    }

    @Test
    fun `model authored placeholder identity is not admitted`() {
        for (name in listOf("实验室动态值页", "dynamic value page", "隐私值")) {
            val collector = ManualTraceCollector()
            val result = collector.recordCompletedPage(testIdentity(),
                observation("NEW_OR_CHANGED").copy(pageName = name), null, emptySet())
            assertEquals(ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.MISSING_PAGE_NAME), result)
            assertNull(collector.buildRequest())
        }
    }

    @Test
    fun `matched observation alone does not request an update`() {
        val collector = ManualTraceCollector()
        val result = collector.recordCompletedPage(
            testIdentity(),
            observation("MATCHED"),
            null,
            setOf("页面详情/首页"),
        )
        assertTrue(result is ManualObservationRecordResult.Accepted)
        assertNull(collector.buildRequest())
    }

    @Test
    fun `matched heading with a different page name becomes new evidence`() {
        val collector = ManualTraceCollector()
        val raw = observation("MATCHED").copy(
            pageName = "设置",
            matchedHeadingPath = "页面详情/首页",
            visualSignature = "顶部设置标题，中部设置选项",
            reusableFacts = listOf("页面用于查看设置选项"),
        )

        val result = collector.recordVerifiedCurrentPage(
            testIdentity(),
            raw,
            null,
            setOf("页面详情/首页"),
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals(ManualObservationStatus.NEW_OR_CHANGED, saved.status)
        assertEquals("设置", saved.pageName)
        assertNull(saved.matchedHeadingPath)
    }

    @Test
    fun `new structural observation requests an update`() {
        val collector = ManualTraceCollector()
        val result = collector.recordCompletedPage(testIdentity(), observation("NEW_OR_CHANGED"), null, emptySet())
        assertTrue(result is ManualObservationRecordResult.Accepted)
        val request = collector.buildRequest()
        assertEquals(1, request!!.traces.size)
        assertEquals(ManualObservationStatus.NEW_OR_CHANGED, request.traces.single().observations.single().status)
    }

    @Test
    fun `verified destination uses after page policy and preserves transition context`() {
        val collector = ManualTraceCollector()
        val before = tree(child(text = "会议准备清单", clickable = true))
        val after = tree(child(text = "检查项目", clickable = true), child(text = "返回", clickable = true))
        val raw = observation("NEW_OR_CHANGED").copy(
            pageName = "会议准备清单",
            visualSignature = "顶部标题，下方检查项目",
            navigationFacts = listOf("资料库 → 会议准备清单"),
        )

        val result = collector.recordVerifiedDestination(
            testIdentity(), raw, AgentAction.Tap(20, 20), before, after, emptySet(),
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals("会议准备清单", saved.pageName)
        assertEquals(listOf("资料库 → 会议准备清单"), saved.navigationFacts)
        assertTrue(saved.afterControls.any { "检查项目" in it })
    }

    @Test
    fun `reasoning current page preserves an observed transition without Fast evidence`() {
        val collector = ManualTraceCollector()
        val before = tree(child(text = "会议准备清单", clickable = true))
        val current = tree(child(text = "检查项目", clickable = true), child(text = "返回", clickable = true))
        val raw = observation("NEW_OR_CHANGED").copy(
            pageName = "会议准备清单",
            visualSignature = "顶部标题，下方检查项目",
            navigationFacts = listOf("资料库 → 会议准备清单"),
        )

        val result = collector.recordObservedCurrentPage(
            identity = testIdentity(),
            raw = raw,
            tree = current,
            knownHeadingPaths = emptySet(),
            precedingAction = AgentAction.Tap(20, 20),
            beforeTree = before,
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals("tap", saved.actionType)
        assertTrue(saved.beforeControls.any { "会议准备清单" in it })
        assertTrue(saved.afterControls.any { "检查项目" in it })
    }

    @Test
    fun `unknown observation is ignored without becoming an invalid update`() {
        val collector = ManualTraceCollector()
        val raw = ManualPageObservation(
            status = "UNKNOWN",
            pageName = null,
            matchedHeadingPath = null,
            visualSignature = null,
            reusableFacts = emptyList(),
            navigationFacts = emptyList(),
            popupOrRecoveryFact = null,
        )
        val result = collector.recordCompletedPage(testIdentity(), raw, null, emptySet())
        assertEquals(
            ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.UNKNOWN_OBSERVATION),
            result,
        )
        assertNull(collector.buildRequest())
    }

    @Test
    fun `typed values are omitted and independent facts remain`() {
        val collector = ManualTraceCollector()
        val raw = observation(
            status = "NEW_OR_CHANGED",
            signature = "顶部搜索框显示秘密关键词",
            facts = listOf("列表展示秘密关键词的结果", "顶部搜索框，下方列表"),
        )
        val result = collector.recordVerifiedSuccess(
            identity = testIdentity(),
            raw = raw,
            action = AgentAction.InputText("秘密关键词"),
            beforeTree = null,
            afterTree = null,
            knownHeadingPaths = emptySet(),
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val evidence = collector.buildRequest()!!.traces.single().observations.single()
        val flattened = listOf(evidence.visualSignature) + evidence.reusableFacts + evidence.navigationFacts
        assertFalse(flattened.any { "动态值" in it })
        assertFalse(flattened.any { "秘密关键词" in it || "另一段隐私输入" in it })
    }

    @Test
    fun `stable ui controls provide a structural signature when model omits it`() {
        val collector = ManualTraceCollector()
        val raw = ManualPageObservation(
            status = "NEW_OR_CHANGED",
            pageName = "搜索页",
            matchedHeadingPath = null,
            visualSignature = null,
            reusableFacts = emptyList(),
            navigationFacts = emptyList(),
            popupOrRecoveryFact = null,
        )
        val stableTree = tree(
            child(viewId = "com.example:id/search_box", hint = "搜索"),
            child(viewId = "com.example:id/search_button", text = "搜索"),
        )

        val result = collector.recordCompletedPage(testIdentity(), raw, stableTree, emptySet())

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val observation = collector.buildRequest()!!.traces.single().observations.single()
        assertTrue(observation.visualSignature.startsWith("稳定控件："))
    }

    @Test
    fun `dynamic controls are removed before the bounded structural fallback is selected`() {
        val dynamic = (1..9).map { "动态条目$it" }
        val collector = ManualTraceCollector()
        dynamic.forEach { value ->
            collector.recordVerifiedSuccess(testIdentity(), null, AgentAction.InputText(value), null, null, emptySet())
        }
        val nodes = dynamic.map { child(text = it, clickable = true) } + listOf(
            child(viewId = "com.example:id/search_box", hint = "搜索"),
            child(viewId = "com.example:id/search_button", text = "搜索"),
        )
        val raw = observation("NEW_OR_CHANGED").copy(
            visualSignature = null, reusableFacts = emptyList(), navigationFacts = emptyList(),
        )
        val result = collector.recordCompletedPage(testIdentity(), raw, tree(*nodes.toTypedArray()), emptySet())
        assertTrue(result is ManualObservationRecordResult.Accepted)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals(listOf("id:search_box", "id:search_button"), saved.beforeControls)
        assertTrue(saved.visualSignature.startsWith("稳定控件："))
    }

    @Test
    fun `unsupported matched claim is normalized to new evidence instead of losing the page`() {
        val collector = ManualTraceCollector()
        val result = collector.recordCompletedPage(
            testIdentity(),
            observation("MATCHED"),
            null,
            setOf("页面详情/设置"),
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        assertEquals(ManualObservationStatus.NEW_OR_CHANGED, (result as ManualObservationRecordResult.Accepted).status)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals(ManualObservationStatus.NEW_OR_CHANGED, saved.status)
        assertNull(saved.matchedHeadingPath)
        assertEquals("首页", saved.pageName)
    }

    @Test
    fun `unsupported matched claim still requires complete reusable evidence`() {
        val collector = ManualTraceCollector()
        val raw = observation("MATCHED").copy(
            visualSignature = null,
            reusableFacts = emptyList(),
            navigationFacts = emptyList(),
        )

        val result = collector.recordCompletedPage(testIdentity(), raw, null, emptySet())

        assertEquals(
            ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.MISSING_STRUCTURE),
            result,
        )
        assertNull(collector.buildRequest())
    }

    @Test
    fun `unsupported matched claim can use stable screen controls as bounded support`() {
        val collector = ManualTraceCollector()
        val raw = observation("MATCHED").copy(
            pageName = "资料库",
            visualSignature = null,
            reusableFacts = emptyList(),
            navigationFacts = emptyList(),
        )
        val screen = tree(
            child(viewId = "bot.coati.fixture.manual:id/catalog_list", text = "条目列表"),
            child(viewId = "bot.coati.fixture.manual:id/home_tab", text = "首页"),
        )

        val result = collector.recordCompletedPage(testIdentity(), raw, screen, emptySet())

        assertTrue(result is ManualObservationRecordResult.Accepted)
        val saved = collector.buildRequest()!!.traces.single().observations.single()
        assertEquals(ManualObservationStatus.NEW_OR_CHANGED, saved.status)
        assertTrue(saved.visualSignature.startsWith("稳定控件："))
    }

    @Test
    fun `exact known matched heading remains read only`() {
        val collector = ManualTraceCollector()
        val result = collector.recordCompletedPage(
            testIdentity(),
            observation("MATCHED"),
            null,
            setOf("页面详情/首页"),
        )

        assertTrue(result is ManualObservationRecordResult.Accepted)
        assertEquals(ManualObservationStatus.MATCHED, (result as ManualObservationRecordResult.Accepted).status)
        assertNull(collector.buildRequest())
    }

    @Test
    fun `invalid status reports a precise admission reason`() {
        val collector = ManualTraceCollector()
        val result = collector.recordCompletedPage(testIdentity(), observation("NOVEL"), null, emptySet())

        assertEquals(
            ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.INVALID_STATUS),
            result,
        )
    }

    @Test
    fun `wechat and qq host apps are learnable`() {
        assertNull(ManualLearningPolicy.blockReason("com.tencent.mm", null))
        assertNull(ManualLearningPolicy.blockReason("com.tencent.mobileqq", null))
    }

    @Test
    fun `wechat mini program requires multiple independent signals`() {
        val semanticOnly = tree(child(text = "小程序"))
        assertFalse(ManualLearningPolicy.isWeChatMiniProgram("com.tencent.mm", semanticOnly))

        val capsule = child(text = "更多", clickable = true, bounds = Rect(800, 20, 950, 100))
        val miniProgram = tree(
            child(className = "com.tencent.mm.plugin.appbrand.Page", text = "小程序"),
            capsule,
        )
        assertTrue(ManualLearningPolicy.isWeChatMiniProgram("com.tencent.mm", miniProgram))
        assertFalse(ManualLearningPolicy.isWeChatMiniProgram("com.example.app", miniProgram))
    }

    private fun observation(
        status: String,
        signature: String = "顶部标题和底部导航",
        facts: List<String> = listOf("页面用于浏览主要入口"),
    ) = ManualPageObservation(
        status = status,
        pageName = "首页",
        matchedHeadingPath = if (status == "MATCHED") "页面详情/首页" else null,
        visualSignature = signature,
        reusableFacts = facts,
        navigationFacts = listOf("启动应用后进入首页"),
        popupOrRecoveryFact = null,
    )

    private fun tree(vararg children: UiTreeNode) = UiTreeNode(
        className = "android.widget.FrameLayout",
        text = null,
        contentDescription = null,
        bounds = Rect(0, 0, 1080, 2400),
        isClickable = false,
        isScrollable = false,
        viewIdResourceName = null,
        children = children.toList(),
    )

    private fun child(
        className: String = "android.widget.TextView",
        text: String? = null,
        clickable: Boolean = false,
        bounds: Rect = Rect(0, 0, 200, 100),
        viewId: String? = null,
        hint: String? = null,
        description: String? = null,
        editable: Boolean = false,
    ) = UiTreeNode(
        className = className,
        text = text,
        contentDescription = description,
        hintText = hint,
        bounds = bounds,
        isClickable = clickable || viewId != null,
        isScrollable = false,
        isEditable = editable,
        viewIdResourceName = viewId,
        children = emptyList(),
    )
}
