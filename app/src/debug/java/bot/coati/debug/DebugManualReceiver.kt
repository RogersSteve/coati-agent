package bot.coati.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import bot.coati.action.AgentAction
import bot.coati.agent.AgentLoop
import bot.coati.agent.CompletionAuditDecision
import bot.coati.data.ConfigStore
import bot.coati.experience.AppManualCodec
import bot.coati.experience.AppManualFactory
import bot.coati.experience.AppManualMarkdownParser
import bot.coati.experience.AppManualRenderer
import bot.coati.experience.AppManualRepository
import bot.coati.experience.ManualAppIdentity
import bot.coati.experience.ManualCuratorTool
import bot.coati.experience.ManualDeterministicFallback
import bot.coati.experience.ManualLearningPolicy
import bot.coati.experience.ManualMode
import bot.coati.experience.ManualNamespace
import bot.coati.experience.ManualObservationRecordResult
import bot.coati.experience.ManualObservationStatus
import bot.coati.experience.ManualPartialEdit
import bot.coati.experience.ManualPartialEditor
import bot.coati.experience.ManualSaveResult
import bot.coati.experience.ManualSemanticGuard
import bot.coati.experience.ManualEditException
import bot.coati.experience.ManualTraceCollector
import bot.coati.experience.ManualWorkingCopies
import bot.coati.model.GatewayUtils
import bot.coati.model.ManualPageObservation
import bot.coati.model.StructuredPayloadFormat
import bot.coati.model.StructuredTextPayloadExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DebugManualReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_MODE -> {
                val requested = intent.getStringExtra(EXTRA_MODE)?.uppercase()
                val mode = requested?.let { runCatching { ManualMode.valueOf(it) }.getOrNull() }
                if (mode == null) {
                    Log.w(TAG, "Invalid manual mode: $requested")
                    return
                }
                ConfigStore(context).setManualMode(mode)
                Log.i(TAG, "Debug manual mode set to $mode; restart Coati task to apply")
            }
            ACTION_CLEAR -> {
                listOf(context.noBackupFilesDir, context.filesDir).forEach { root ->
                    AppManualRepository(root).clearAll()
                    AppManualRepository.LEGACY_ROOTS.forEach { File(root, it).deleteRecursively() }
                }
                Log.i(TAG, "Debug manual data cleared schema=v2 legacy=true")
            }
            ACTION_STATUS -> {
                val mode = ConfigStore(context).getManualMode()
                val repository = AppManualRepository(context.noBackupFilesDir)
                val canonical = repository.namespaceDirectory(ManualNamespace.CANONICAL)
                val shadow = repository.namespaceDirectory(ManualNamespace.SHADOW)
                val canonicalManuals = canonical.walkTopDown().count { it.isFile && it.name == "manual.md" }
                val shadowManuals = shadow.walkTopDown().count { it.isFile && it.name == "manual.md" }
                Log.i(
                    TAG,
                    "Debug manual status schema=v2 mode=$mode canonical=${canonical.absolutePath} " +
                        "canonicalManuals=$canonicalManuals shadow=${shadow.absolutePath} shadowManuals=$shadowManuals",
                )
            }
            ACTION_SELF_TEST -> {
                val pending = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runSelfTest(appContext)
                        Log.i(
                            TAG,
                            "selftest result=PASS schema=v2 markdown=true metadata=true policy=true " +
                                "retrieval=true repository=true rollback=true isolation=true " +
                                "curatorCodec=true partialEdit=true unknownBootstrap=true chatPolicy=true " +
                                "observationAdmission=true scopedEdit=true batchAtomic=true targetIds=true responseEnvelope=true " +
                                "structuralFields=true semanticGuard=true terminalAuditGate=true " +
                                "postStateObservation=true coverageGate=true placeholderInvariant=true " +
                                "matchedStatusRepair=true reasoningObservation=true fastObservationDecoupled=true " +
                                "purposeGate=true purposePreservation=true localFallback=true",
                        )
                    } catch (error: Throwable) {
                        Log.e(TAG, "selftest result=FAIL reason=${error.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private suspend fun runSelfTest(context: Context) {
        check(AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.ACCEPT))
        check(!AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.UNAVAILABLE))
        check(!AgentLoop.shouldRecordTerminalManualObservation(CompletionAuditDecision.REJECT))
        check(!AgentLoop.shouldAttachManualTransition("bot.coati.selftest", "bot.coati.selftest", "same", "same"))
        check(AgentLoop.shouldAttachManualTransition("bot.coati.selftest", "bot.coati.selftest", "before", "after"))
        check(AgentLoop.shouldAttachManualTransition("bot.coati.selftest", "bot.coati.other", "same", "same"))
        val testRoot = File(context.cacheDir, "manual_v2_self_test_${System.nanoTime()}")
        try {
            val identity = ManualAppIdentity(
                appKey = "a".repeat(64),
                packageName = "bot.coati.selftest",
                signerSha256 = "b".repeat(64),
                displayName = "Manual self-test",
                versionCode = 1L,
                localeTag = "en-US",
            )
            val revision1 = AppManualFactory.create(identity, selfTestMarkdown("Home page"), now = 1L)
            check(AppManualCodec.decodeMetadata(AppManualCodec.encodeMetadata(revision1.metadata)) == revision1.metadata)
            val wrapped = "<think>synthetic service prefix</think>\n```json\n" +
                """{"expectedRevision":1,"tool":"NO_CHANGE","edits":[]}""" + "\n```"
            val payload = StructuredTextPayloadExtractor.extract(wrapped, StructuredPayloadFormat.JSON)
            check(payload.removedThinkingPrefix && payload.removedCodeFence)
            check(AppManualCodec.decodeCuratorDecision(payload.text, emptyMap()).tool == ManualCuratorTool.NO_CHANGE)
            check(AppManualMarkdownParser.parse(revision1.markdown).section("页面详情/Home") != null)
            val curatorDecision = AppManualCodec.decodeCuratorDecision(
                """{"expectedRevision":0,"tool":"PARTIAL_EDIT","edits":[{"targetId":"s2","oldText":"old","newText":"new"}]}""",
                mapOf("s2" to "页面索引"),
            )
            check(curatorDecision.tool == ManualCuratorTool.PARTIAL_EDIT)

            val unknownBase = ManualWorkingCopies.from(null, identity)
            val bootstrapped = ManualPartialEditor.apply(
                unknownBase,
                listOf(
                    ManualPartialEdit(
                        targetHeading = "页面索引",
                        oldText = "- 尚无已验证经验。",
                        newText = "- Home｜识别：顶部标题和底部导航｜入口：启动后进入",
                    ),
                    ManualPartialEdit(
                        targetHeading = "App 用途",
                        oldText = "- 尚无已验证经验。",
                        newText = "- 用于测试页面导航。",
                    ),
                ),
                allowedHeadingPaths = setOf("页面索引", "App 用途"),
                now = 1L,
            ).getOrThrow()
            check(bootstrapped.revision == 1L)
            check(bootstrapped.markdown.contains("顶部标题和底部导航"))
            val invalidBatch = ManualPartialEditor.apply(
                unknownBase,
                listOf(
                    ManualPartialEdit("页面索引", "- 尚无已验证经验。", "- Updated index"),
                    ManualPartialEdit("App 用途", "text not present", "- Invalid edit"),
                ),
                setOf("页面索引", "App 用途"),
            )
            check(invalidBatch.isFailure && "Updated index" !in unknownBase.markdown)
            check(ManualLearningPolicy.blockReason("com.tencent.mm", null) == null)
            check(ManualLearningPolicy.blockReason("com.tencent.mobileqq", null) == null)

            val observationCollector = ManualTraceCollector()
            observationCollector.recordVerifiedSuccess(identity, null, AgentAction.InputText("设备自检秘密词"), null, null, emptySet())
            val observationResult = observationCollector.recordCompletedPage(
                identity = identity,
                raw = ManualPageObservation(
                    status = "NEW_OR_CHANGED",
                    pageName = "搜索页",
                    matchedHeadingPath = null,
                    visualSignature = "顶部搜索框包含设备自检秘密词，下面是结果列表",
                    reusableFacts = listOf("顶部是搜索框，下面是结果列表"),
                    navigationFacts = listOf("首页 → 搜索框 → 搜索页"),
                    popupOrRecoveryFact = null,
                ),
                tree = null,
                knownHeadingPaths = emptySet(),
            )
            check(observationResult is ManualObservationRecordResult.Accepted)
            val sanitizedObservation = observationCollector.buildRequest()
                ?.traces?.singleOrNull()?.observations?.singleOrNull()
            check(sanitizedObservation != null)
            check("设备自检秘密词" !in sanitizedObservation.visualSignature)
            check("动态值" !in sanitizedObservation.visualSignature)

            // Tree-independent structural names survive; actual input still wins.
            val quotedCollector = ManualTraceCollector()
            val quotedPage = ManualPageObservation(
                status = "NEW_OR_CHANGED", pageName = "收藏", matchedHeadingPath = null,
                visualSignature = "顶部标题，底部收藏入口", reusableFacts = listOf("收藏页用于浏览保存的条目"),
                navigationFacts = listOf("首页 → 收藏"), popupOrRecoveryFact = null,
            )
            check(quotedCollector.recordCompletedPage(identity, quotedPage, null, emptySet()) is ManualObservationRecordResult.Accepted)
            check(quotedCollector.buildRequest()?.traces?.single()?.observations?.single()?.pageName == "收藏")
            val cleanTrace = quotedCollector.buildRequest()!!.traces.single()
            val fallbackTrace = cleanTrace.copy(observations = cleanTrace.observations +
                cleanTrace.observations.single().copy(
                    pageName = "设置",
                    visualSignature = "顶部设置标题，中部选项列表",
                    reusableFacts = listOf("页面用于查看设置选项"),
                    navigationFacts = listOf("收藏 → 设置 → 设置"),
                    terminal = true,
                ))
            val fallback = ManualDeterministicFallback.merge(unknownBase, fallbackTrace, now = 1L).getOrThrow()
            check(fallback != null && fallback.revision == 1L)
            check("页面详情/收藏" in AppManualMarkdownParser.parse(fallback.markdown).headingPaths)
            check("页面详情/设置" in AppManualMarkdownParser.parse(fallback.markdown).headingPaths)
            check(!AppManualMarkdownParser.parse(fallback.markdown)
                .section("App 用途")!!.directMarkdown.contains("尚无已验证经验"))
            val verifyObservation = GatewayUtils.parseVerifyResponse(
                """{"outcome":"SUCCESS","reason":"opened","manualObservation":{"status":"NEW_OR_CHANGED","pageName":"收藏","matchedHeadingPath":null,"visualSignature":"底部收藏入口高亮","reusableFacts":["用于浏览收藏条目"],"navigationFacts":["首页 → 收藏"],"popupOrRecoveryFact":null}}""",
            ).manualObservation
            check(verifyObservation?.pageName == "收藏")
            check(
                GatewayUtils.parseVerifyResponse(
                    """{"outcome":"SUCCESS","reason":"response was cut"""",
                ).outcome.name == "SUCCESS",
            )
            val reasoningCollector = ManualTraceCollector()
            val reasoningRecord = reasoningCollector.recordObservedCurrentPage(
                identity = identity,
                raw = quotedPage.copy(status = "NEW_OR_CHANGED"),
                tree = null,
                knownHeadingPaths = emptySet(),
                precedingAction = AgentAction.Tap(10, 10),
                beforeTree = null,
            )
            check(reasoningRecord is ManualObservationRecordResult.Accepted)
            check(reasoningCollector.buildRequest()!!.traces.single().observations.single().actionType == "tap")
            val terminalObservation = GatewayUtils.parseQuickCheckResponse(
                """{"answer":true,"category":"completion","confidence":1.0,"manualObservation":{"status":"NEW_OR_CHANGED","pageName":"实验档案","matchedHeadingPath":null,"visualSignature":"顶部实验档案标题","reusableFacts":[],"navigationFacts":[],"popupOrRecoveryFact":null}}""",
            ).manualObservation
            check(terminalObservation?.pageName == "实验档案")
            val unsupportedMatched = quotedCollector.recordCompletedPage(
                identity,
                quotedPage.copy(status = "MATCHED", matchedHeadingPath = "页面详情/不存在"),
                null,
                emptySet(),
            )
            check(unsupportedMatched is ManualObservationRecordResult.Accepted)
            check(unsupportedMatched.status == ManualObservationStatus.NEW_OR_CHANGED)
            val mismatchedKnownPage = quotedCollector.recordVerifiedCurrentPage(
                identity,
                quotedPage.copy(status = "MATCHED", pageName = "设置", matchedHeadingPath = "页面详情/收藏"),
                null,
                setOf("页面详情/收藏"),
            )
            check(mismatchedKnownPage is ManualObservationRecordResult.Accepted)
            check(mismatchedKnownPage.status == ManualObservationStatus.NEW_OR_CHANGED)
            val uncoveredError = runCatching {
                ManualSemanticGuard.validateCoverage(unknownBase.markdown, cleanTrace)
            }.exceptionOrNull()
            check((uncoveredError as? ManualEditException)?.reasonCode == "UNCOVERED_NEW_PAGE")
            val covered = ManualPartialEditor.apply(
                unknownBase,
                listOf(
                    ManualPartialEdit(
                        targetHeading = "页面详情",
                        oldText = "- 尚无已验证经验。",
                        newText = "### 收藏\n\n- 底部收藏入口高亮，用于浏览收藏条目。",
                    ),
                ),
                allowedHeadingPaths = setOf("页面详情"),
                now = 1L,
            ).getOrThrow()
            ManualSemanticGuard.validateCoverage(covered.markdown, cleanTrace)
            check("尚无已验证经验" !in AppManualMarkdownParser.parse(covered.markdown).section("页面详情")!!.directMarkdown)
            ManualSemanticGuard.validatePurpose(covered.markdown)
            val twoPageEmptyPurpose = ManualPartialEditor.apply(
                unknownBase,
                listOf(
                    ManualPartialEdit(
                        targetHeading = "页面详情",
                        oldText = "- 尚无已验证经验。",
                        newText = "### 收藏\n\n- 底部收藏入口。\n\n### 设置\n\n- 顶部设置标题。",
                    ),
                ),
                allowedHeadingPaths = setOf("页面详情"),
                now = 1L,
            ).getOrThrow()
            val purposeError = runCatching {
                ManualSemanticGuard.validatePurpose(twoPageEmptyPurpose.markdown)
            }.exceptionOrNull()
            check((purposeError as? ManualEditException)?.reasonCode == "APP_PURPOSE_REQUIRED")
            val twoPagePurpose = ManualPartialEditor.apply(
                unknownBase,
                listOf(
                    ManualPartialEdit("App 用途", "- 尚无已验证经验。", "- 用于浏览收藏内容和调整设置。"),
                    ManualPartialEdit(
                        targetHeading = "页面详情",
                        oldText = "- 尚无已验证经验。",
                        newText = "### 收藏\n\n- 底部收藏入口。\n\n### 设置\n\n- 顶部设置标题。",
                    ),
                ),
                allowedHeadingPaths = setOf("App 用途", "页面详情"),
                now = 1L,
            ).getOrThrow()
            ManualSemanticGuard.validatePurpose(twoPagePurpose.markdown)
            val narrowedPurpose = AppManualFactory.create(
                identity,
                twoPagePurpose.markdown.replace(
                    "- 用于浏览收藏内容和调整设置。",
                    "- 仅用于调整设置。",
                ),
                revision = twoPagePurpose.revision + 1L,
                now = 2L,
            )
            val purposeRegression = runCatching {
                ManualSemanticGuard.validatePurposePreservation(twoPagePurpose.markdown, narrowedPurpose.markdown)
            }.exceptionOrNull()
            check((purposeRegression as? ManualEditException)?.reasonCode == "APP_PURPOSE_REGRESSION")
            val damaged = AppManualFactory.create(identity, selfTestMarkdown("底部动态值入口"))
            val guardError = runCatching {
                ManualSemanticGuard.validate(ManualWorkingCopies.from(revision1, identity), damaged, cleanTrace)
            }.exceptionOrNull()
            check((guardError as? ManualEditException)?.reasonCode == "UNSUPPORTED_PLACEHOLDER")
            quotedCollector.recordVerifiedSuccess(identity, null, AgentAction.InputText("收藏"), null, null, emptySet())
            check(quotedCollector.buildRequest() == null)

            val repository = AppManualRepository(testRoot)
            check(repository.save(revision1, 0L) is ManualSaveResult.Saved)
            check(repository.save(revision1.copy(metadata = revision1.metadata.copy(revision = 2L)), 0L) is ManualSaveResult.RevisionConflict)
            val revision2 = AppManualFactory.create(identity, selfTestMarkdown("Updated home page"), revision = 2L, now = 2L)
            check(repository.save(revision2, 1L) is ManualSaveResult.Saved)
            repository.metadataFile(identity.appKey).writeText("corrupt", Charsets.UTF_8)
            check(repository.load(identity.appKey)?.revision == 1L)

            val renderedGuide = AppManualRenderer().render(
                revision1,
                ManualMode.READ_ONLY,
                task = "Find Home navigation",
                uiTreeHint = "Home button",
            )
            check(renderedGuide != null && "页面详情/Home" in renderedGuide.headingPaths)
            check(AppManualRenderer().render(revision1, ManualMode.SHADOW, "Home", "Home") == null)

            val shadowIdentity = identity.copy(appKey = "d".repeat(64))
            val shadow = AppManualFactory.create(shadowIdentity, selfTestMarkdown("Shadow home"), now = 3L)
            check(repository.save(shadow, 0L, ManualNamespace.SHADOW) is ManualSaveResult.Saved)
            check(repository.load(shadowIdentity.appKey, ManualNamespace.CANONICAL) == null)
            check(repository.load(shadowIdentity.appKey, ManualNamespace.SHADOW)?.revision == 1L)
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun selfTestMarkdown(detail: String): String = """
        # Self-test manual

        ## App 信息
        - Package: bot.coati.selftest

        ## App 用途
        - Test bounded retrieval.

        ## 页面索引
        - Home: visible Home button.

        ## 页面详情
        ### Home
        - $detail

        ## 页面导航
        - Start to Home.

        ## 弹窗与恢复
        - Re-observe current UI.
    """.trimIndent()

    companion object {
        private const val TAG = "CoatiManual"
        const val ACTION_SET_MODE = "bot.coati.debug.SET_MANUAL_MODE"
        const val ACTION_CLEAR = "bot.coati.debug.CLEAR_MANUALS"
        const val ACTION_STATUS = "bot.coati.debug.MANUAL_STATUS"
        const val ACTION_SELF_TEST = "bot.coati.debug.MANUAL_SELF_TEST"
        const val EXTRA_MODE = "mode"
    }
}
