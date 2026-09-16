package bot.coati.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDeterministicFallbackTest {
    @Test
    fun `unknown app becomes a compact guarded manual without a model`() {
        val identity = testIdentity()
        val first = testTrace(identity).observations.single().copy(
            pageName = "资料库",
            matchedHeadingPath = null,
            visualSignature = "顶部资料库标题，中部条目列表",
            reusableFacts = listOf("列表项提供查看详情入口"),
            navigationFacts = listOf("首页 → 底部资料库 → 资料库"),
        )
        val second = first.copy(
            pageName = "条目详情",
            visualSignature = "顶部条目详情标题，中部检查项目",
            reusableFacts = listOf("页面展示条目简介和检查项目"),
            navigationFacts = listOf("资料库 → 查看详情 → 条目详情"),
            terminal = true,
        )
        val trace = ManualAppLearningTrace(identity, listOf(first, second))
        val base = ManualWorkingCopies.from(null, identity)

        val candidate = ManualDeterministicFallback.merge(base, trace, now = 10L).getOrThrow()!!

        assertEquals(1L, candidate.revision)
        assertTrue(candidate.markdown.contains("- 用于访问资料库、条目详情等已验证页面和功能。"))
        assertTrue(candidate.markdown.contains("### 资料库"))
        assertTrue(candidate.markdown.contains("### 条目详情"))
        assertTrue(candidate.markdown.contains("检查项目"))
        assertFalse(AppManualMarkdownParser.parse(candidate.markdown)
            .section("页面详情")!!.directMarkdown.contains("尚无已验证经验"))
        assertEquals(null, AppManualPolicy.validateManual(candidate))

        val sameBase = ManualWorkingCopies.from(candidate, identity)
        assertNull(ManualDeterministicFallback.merge(sameBase, trace, now = 20L).getOrThrow())
    }

    @Test
    fun `incremental merge preserves every unobserved page block byte for byte`() {
        val identity = testIdentity()
        val beforeManual = testManual(identity = identity)
        val before = ManualWorkingCopies.from(beforeManual, identity)
        val beforeParsed = AppManualMarkdownParser.parse(before.markdown)
        val source = testTrace(identity).observations.single()
        val trace = ManualAppLearningTrace(identity, listOf(
            source.copy(
                pageName = "高级设置",
                matchedHeadingPath = null,
                visualSignature = "顶部高级设置标题，中部实验档案入口",
                reusableFacts = listOf("实验档案入口位于页面中部"),
                navigationFacts = listOf("设置页 → 高级设置 → 高级设置"),
            ),
            source.copy(
                pageName = "实验档案",
                matchedHeadingPath = null,
                visualSignature = "顶部实验档案标题，下方档案卡片",
                reusableFacts = listOf("页面用于查看实验档案记录"),
                navigationFacts = listOf("高级设置 → 实验档案 → 实验档案"),
                terminal = true,
            ),
        ))

        val candidate = ManualDeterministicFallback.merge(before, trace, now = 20L).getOrThrow()!!
        val afterParsed = AppManualMarkdownParser.parse(candidate.markdown)

        listOf("页面详情/首页", "页面详情/搜索结果页", "页面详情/设置页").forEach { path ->
            assertEquals(beforeParsed.section(path)!!.directMarkdown, afterParsed.section(path)!!.directMarkdown)
        }
        assertTrue(candidate.markdown.contains("### 高级设置"))
        assertTrue(candidate.markdown.contains("### 实验档案"))
        assertEquals(2L, candidate.revision)
    }

    @Test
    fun `factory and semantic guards reject unsafe fallback output`() {
        val identity = testIdentity()
        val observation = testTrace(identity).observations.single().copy(
            visualSignature = "```不可信结构```",
        )

        val result = ManualDeterministicFallback.merge(
            ManualWorkingCopies.from(null, identity),
            ManualAppLearningTrace(identity, listOf(observation)),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `matched only evidence is a deterministic no change`() {
        val identity = testIdentity()
        val observation = testTrace(identity).observations.single().copy(
            status = ManualObservationStatus.MATCHED,
        )

        val result = ManualDeterministicFallback.merge(
            ManualWorkingCopies.from(null, identity),
            ManualAppLearningTrace(identity, listOf(observation)),
        )

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }
}
