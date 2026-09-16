package bot.coati.debug

import android.util.Log
import bot.coati.experience.*
import bot.coati.model.CloudModelGateway
import bot.coati.model.StructuredTextRequest
import bot.coati.model.StructuredTextResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Synthetic fixtures ONLY. Never accepts a real task, tree, screenshot or manual root. */
internal class ManualProtocolProbe(
    private val runRoot: File,
    private val gateway: CloudModelGateway,
    private val modelLabel: String,
) {
    private val results = JSONArray()
    private val identity = ManualAppIdentity(
        appKey = "e".repeat(64), packageName = "bot.coati.protocol.fixture",
        signerSha256 = "f".repeat(64), displayName = "资料卡演示", versionCode = 1L, localeTag = "zh-CN",
    )

    suspend fun run(progress: (String) -> Unit): Boolean {
        check(runRoot.mkdirs()) { "Probe directory must be new" }
        writeReport("RUNNING")
        try {
            for (name in CASES) {
                progress("$name 正在测试…")
                runCase(name)
                writeReport("RUNNING")
                progress("$name: ${results.getJSONObject(results.length() - 1).getString("result")}")
            }
            val passed = (0 until results.length()).all { results.getJSONObject(it).getString("result") == "PASS" }
            writeReport(if (passed) "PASS" else "FAIL")
            Log.i(TAG, "probe result=${if (passed) "PASS" else "FAIL"} cases=${results.length()} report=${runRoot.name}/report.json")
            return passed
        } catch (error: CancellationException) {
            writeReport("CANCELLED")
            Log.i(TAG, "probe result=CANCELLED report=${runRoot.name}/report.json")
            throw error
        } catch (error: Exception) {
            writeReport("ERROR", error.javaClass.simpleName)
            Log.w(TAG, "probe result=ERROR errorType=${error.javaClass.simpleName}")
            throw error
        }
    }

    private suspend fun runCase(name: String) {
        Log.i(TAG, "probe case=$name result=START syntheticOnly=true")
        val caseRoot = File(runRoot, name).apply { mkdirs() }
        val repository = AppManualRepository(File(caseRoot, "store"))
        val initial = if (name == "bootstrap") null else AppManualFactory.create(identity, fixtureMarkdown(), now = 1L)
        if (initial != null) check(repository.save(initial, 0L) is ManualSaveResult.Saved)
        val base = ManualWorkingCopies.from(initial, identity)
        File(caseRoot, "before.md").writeText(base.markdown, Charsets.UTF_8)
        val initialMetadata = initial?.let { repository.metadataFile(identity.appKey).readBytes() }
        val trace = trace(name)
        var calls = 0
        val recordingGateway = object : CloudModelGateway by gateway {
            override suspend fun generateStructuredText(request: StructuredTextRequest): Result<StructuredTextResult> {
                calls++
                // Only the synthetic request/response is exported, never gateway keys or endpoints.
                writeJson(File(caseRoot, "call-$calls-request.json"), JSONObject().apply {
                    put("systemPrompt", request.systemPrompt)
                    put("userPrompt", request.userPrompt)
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxOutputTokens)
                })
                val started = System.nanoTime()
                return try {
                    gateway.generateStructuredText(request).also { response ->
                        writeJson(File(caseRoot, "call-$calls-response.json"), JSONObject().apply {
                            put("durationMs", (System.nanoTime() - started) / 1_000_000)
                            response.getOrNull()?.let {
                                put("finishReason", it.finishReason.name)
                                put("outputTokens", it.outputTokens)
                                put("text", it.text.take(100_000))
                                put("exportTruncated", it.text.length > 100_000)
                            }
                            response.exceptionOrNull()?.let { put("errorType", it.javaClass.simpleName) }
                        })
                    }
                } catch (error: Exception) {
                    writeJson(File(caseRoot, "call-$calls-response.json"), JSONObject().put("errorType", error.javaClass.simpleName))
                    throw error
                }
            }
        }
        val result = ManualUpdateProcessor(repository, recordingGateway).process(ManualMode.LEARNING, trace)
        val after = repository.load(identity.appKey)
        after?.let { File(caseRoot, "after.md").writeText(it.markdown, Charsets.UTF_8) }
        val checks = linkedMapOf<String, Boolean>()
        checks["boundedCalls"] = calls in 1..AppManualPolicy.MAX_UPDATE_ATTEMPTS
        // The runtime may recover through its local merger, but this probe still reports that
        // the configured model failed the curator protocol instead of masking the incompatibility.
        checks["modelProtocolAccepted"] = result.fallbackTrigger == null
        checks["noExampleContent"] = after?.markdown?.contains("示例页") != true
        if (name == "no_change") {
            checks["noChangeDecision"] = result.reason == "MODEL_NO_CHANGE"
            checks["markdownUnchanged"] = after?.markdown == initial?.markdown
            checks["metadataUnchanged"] = initialMetadata?.contentEquals(repository.metadataFile(identity.appKey).readBytes()) == true
        } else {
            checks["saved"] = result.saved
            checks["revision"] = after?.revision == (initial?.revision ?: 0L) + 1L
            val parsed = after?.let { AppManualMarkdownParser.parse(it.markdown) }
            val page = if (name == "new_page") "收藏页" else "首页"
            checks["pageExists"] = parsed?.section("页面详情/$page") != null
            if (name == "existing_page") {
                val home = parsed?.section("页面详情/首页")?.directMarkdown.orEmpty()
                checks["updatedFact"] = "齿轮" in home && "底部设置Tab" !in home
            }
            if (initial != null) {
                val before = AppManualMarkdownParser.parse(initial.markdown)
                checks["unrelatedPagePreserved"] = before.section("页面详情/设置页")?.directMarkdown ==
                    parsed?.section("页面详情/设置页")?.directMarkdown
                if (name == "new_page") checks["existingPagePreserved"] = before.section("页面详情/首页")?.directMarkdown ==
                    parsed?.section("页面详情/首页")?.directMarkdown
            }
        }
        val passed = checks.values.all { it }
        results.put(JSONObject().apply {
            put("case", name)
            put("result", if (passed) "PASS" else "FAIL")
            put("stage", result.stage)
            put("reason", result.reason)
            put("fallbackTrigger", result.fallbackTrigger)
            put("revision", result.revision)
            put("calls", calls)
            put("checks", JSONObject(checks as Map<*, *>))
        })
        Log.i(TAG, "probe case=$name result=${if (passed) "PASS" else "FAIL"} stage=${result.stage} reason=${result.reason} " +
            "calls=$calls failedChecks=${checks.filterValues { !it }.keys.joinToString(",")}")
    }

    private fun writeReport(status: String, errorType: String? = null) = writeJson(File(runRoot, "report.json"), JSONObject().apply {
        put("protocol", "target_ids_v1")
        put("syntheticOnly", true)
        put("model", modelLabel)
        put("status", status)
        put("errorType", errorType)
        put("cases", results)
    })

    private fun writeJson(file: File, json: JSONObject) = file.writeText(json.toString(2), Charsets.UTF_8)

    private fun trace(name: String): ManualAppLearningTrace {
        val page = if (name == "new_page") "收藏页" else "首页"
        val signature = when (name) {
            "new_page" -> "顶部收藏标题，中部资料卡列表，左上返回按钮"
            "existing_page" -> "顶部资料卡标题，右上齿轮设置按钮；底部不再有设置Tab"
            else -> "顶部资料卡标题，中部卡片列表，底部设置Tab"
        }
        val fact = when (name) {
            "new_page" -> "收藏页用于查看已收藏的资料卡。"
            "existing_page" -> "首页进入设置的入口已从底部设置Tab变为右上齿轮按钮。"
            else -> "首页用于浏览资料卡，底部设置Tab进入设置页。"
        }
        return ManualAppLearningTrace(identity, listOf(VerifiedPageObservation(
            status = ManualObservationStatus.NEW_OR_CHANGED,
            pageName = page,
            matchedHeadingPath = if (name in setOf("no_change", "existing_page")) "页面详情/首页" else null,
            visualSignature = signature,
            reusableFacts = listOf(fact),
            navigationFacts = when (name) {
                "new_page" -> listOf("首页顶部收藏按钮 → 收藏页；左上返回 → 首页。")
                "existing_page" -> listOf("首页右上齿轮 → 设置页。")
                else -> listOf("首页底部设置Tab → 设置页。")
            },
            popupOrRecoveryFact = null, actionType = "tap", targetHint = null,
            beforeControls = emptyList(), afterControls = emptyList(), terminal = false,
        )))
    }

    private fun fixtureMarkdown() = """
        # 资料卡演示 使用手册

        __APP_INFO__

        ## App 用途
        - 用于浏览资料卡。

        ## 页面索引
        - 首页｜识别：顶部资料卡标题，中部卡片列表，底部设置Tab｜入口：启动进入
        - 设置页｜识别：顶部设置标题，下方选项列表｜入口：首页底部设置Tab

        ## 页面详情
        ### 首页
        - 识别：顶部资料卡标题，中部卡片列表，底部设置Tab。
        - 功能：首页用于浏览资料卡，底部设置Tab进入设置页。

        ### 设置页
        - 识别：顶部设置标题，下方选项列表。
        - 功能：调整显示选项；左上返回回到首页。

        ## 页面导航
        - 首页底部设置Tab → 设置页。

        ## 弹窗与恢复
        - 尚无已验证经验。
    """.trimIndent().replace("__APP_INFO__", ManualWorkingCopies.appInfo(identity).trimEnd())

    companion object {
        private const val TAG = "CoatiManual"
        val CASES = listOf("bootstrap", "new_page", "existing_page", "no_change")
    }
}
