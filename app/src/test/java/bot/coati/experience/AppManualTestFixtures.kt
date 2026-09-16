package bot.coati.experience

import bot.coati.model.CloudModelGateway
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextRequest
import bot.coati.model.StructuredTextResult
import io.mockk.coEvery
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject

internal fun testTrace(identity: ManualAppIdentity = testIdentity()) = ManualAppLearningTrace(
    identity,
    listOf(VerifiedPageObservation(
        status = ManualObservationStatus.NEW_OR_CHANGED,
        pageName = "首页",
        matchedHeadingPath = "页面详情/首页",
        visualSignature = "顶部搜索入口，底部首页 Tab 高亮",
        reusableFacts = listOf("页面用于进入搜索和主要导航"),
        navigationFacts = listOf("启动后进入首页"),
        popupOrRecoveryFact = null,
        actionType = "tap",
        targetHint = "id:search",
        beforeControls = listOf("id:search"),
        afterControls = listOf("id:query"),
        terminal = false,
    )),
)

internal fun editResponse(revision: Long, vararg edits: ManualPartialEdit) = JSONObject().apply {
    put("expectedRevision", revision)
    put("tool", "PARTIAL_EDIT")
    put("edits", JSONArray(edits.map { edit -> JSONObject().apply {
        put("targetId", testTargetIds[edit.targetHeading] ?: "s999")
        put("oldText", edit.oldText)
        put("newText", edit.newText)
    } }))
}.toString()

internal val testTargetIds = listOf("App 用途", "页面索引", "页面详情", "页面导航", "弹窗与恢复", "页面详情/首页")
    .mapIndexed { index, heading -> heading to "s${index + 1}" }.toMap()

internal fun toolResponse(revision: Long, tool: String = "NO_CHANGE") =
    """{"expectedRevision":$revision,"tool":"$tool","edits":[]}"""

internal class ManualTestGateway(private val response: suspend (Int, StructuredTextRequest) -> String) {
    val requests = mutableListOf<StructuredTextRequest>()
    val gateway = mockk<CloudModelGateway>()

    init {
        coEvery { gateway.generateStructuredText(any()) } coAnswers {
            val request = firstArg<StructuredTextRequest>()
            requests += request
            Result.success(StructuredTextResult(response(requests.size, request), StructuredTextFinishReason.COMPLETE))
        }
    }
}

internal fun testIdentity(packageName: String = "com.example.app") = ManualAppIdentity(
    appKey = "a".repeat(64),
    packageName = packageName,
    signerSha256 = "b".repeat(64),
    displayName = "Example",
    versionCode = 7L,
    localeTag = "zh-CN",
)

internal fun testMarkdown(homeDetail: String = "- 首页显示搜索入口和底部导航。") = """
    # Example 使用手册

    ## App 信息
    - 包名：com.example.app

    ## App 用途
    - 用于测试 App 手册。

    ## 页面索引
    - 首页｜识别：顶部搜索入口，底部首页 Tab 高亮｜入口：启动默认进入
    - 搜索结果页｜识别：顶部搜索框，中部结果列表｜入口：首页提交搜索
    - 设置页｜识别：顶部设置标题，中部选项列表｜入口：首页菜单

    ## 页面详情
    ### 首页
    $homeDetail

    ### 搜索结果页
    - 用于查看搜索结果，不应重复提交查询。

    ### 设置页
    - 用于调整 App 的通用选项。

    ## 页面导航
    - 首页 → 搜索框 → 搜索结果页。

    ## 弹窗与恢复
    - 弹窗关闭后重新观察当前页面。
""".trimIndent()

internal fun testManual(
    revision: Long = 1L,
    markdown: String = testMarkdown(),
    identity: ManualAppIdentity = testIdentity(),
) = AppManualFactory.create(identity, markdown, revision = revision, now = revision * 100L)
