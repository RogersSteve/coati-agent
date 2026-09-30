package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayUtilsTest {

    @Test
    fun `extractJsonFromText extracts from markdown code block`() {
        val input = """
            Some text before
            ```json
            {"action": "tap", "params": {"x": 100}}
            ```
            Some text after
        """.trimIndent()
        val result = GatewayUtils.extractJsonFromText(input)
        assertTrue(result.startsWith("{"))
        assertTrue(result.contains("\"action\""))
    }

    @Test
    fun `extractJsonFromText extracts from code block without json label`() {
        val input = """
            ```
            {"action": "scroll"}
            ```
        """.trimIndent()
        val result = GatewayUtils.extractJsonFromText(input)
        assertTrue(result.contains("\"action\""))
    }

    @Test
    fun `extractJsonFromText handles raw JSON`() {
        val input = """{"action": "wait", "params": {"ms": 1000}}"""
        val result = GatewayUtils.extractJsonFromText(input)
        assertEquals(input, result)
    }

    @Test
    fun `extractJsonFromText returns original text when no JSON found`() {
        val input = "no json here"
        val result = GatewayUtils.extractJsonFromText(input)
        assertEquals(input, result)
    }

    @Test
    fun `buildUserPrompt contains task description`() {
        val result = GatewayUtils.buildUserPrompt("UI tree here", "Open settings")
        assertTrue(result.contains("Open settings"))
        assertTrue(result.contains("UI tree here"))
    }

    @Test
    fun `buildUserPrompt contains action format instructions`() {
        val result = GatewayUtils.buildUserPrompt("", "test")
        assertTrue(result.contains("tap"))
        assertTrue(result.contains("swipe"))
        assertTrue(result.contains("safetyLevel"))
    }

    @Test
    fun `SYSTEM_PROMPT contains key rules`() {
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("Coati"))
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("Android"))
        assertTrue(GatewayUtils.SYSTEM_PROMPT.contains("JSON"))
    }

    @Test
    fun `parseModelResponse normalizes bounded manual attribution`() {
        val response = GatewayUtils.parseModelResponse(
            """
            {
              "reasoning":"manual-assisted",
              "action":"tap",
              "params":{"x":10,"y":20},
              "safetyLevel":"low",
              "isTaskComplete":false,
              "statusUpdate":"testing",
              "usedManualHeadings":["页面详情/首页","页面详情/首页"," ","页面详情/搜索结果页"]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("页面详情/首页", "页面详情/搜索结果页"), response.usedManualHeadings)
    }

    @Test
    fun `parseModelResponse reads bounded manual learning observation`() {
        val response = GatewayUtils.parseModelResponse(
            """
            {
              "reasoning":"new page",
              "action":"wait",
              "params":{"ms":500},
              "safetyLevel":"low",
              "isTaskComplete":true,
              "statusUpdate":"done",
              "manualObservation":{
                "status":"NEW_OR_CHANGED",
                "pageName":"搜索结果页",
                "matchedHeadingPath":null,
                "visualSignature":"顶部搜索框，中部结果列表，右侧安装按钮",
                "reusableFacts":["用于查看应用搜索结果","列表项右侧有安装按钮"],
                "navigationFacts":["首页搜索框提交后进入此页"],
                "popupOrRecoveryFact":null
              }
            }
            """.trimIndent(),
        )

        val observation = response.manualObservation!!
        assertEquals("NEW_OR_CHANGED", observation.status)
        assertEquals("搜索结果页", observation.pageName)
        assertEquals("顶部搜索框，中部结果列表，右侧安装按钮", observation.visualSignature)
        assertEquals(2, observation.reusableFacts.size)
        assertEquals(listOf("首页搜索框提交后进入此页"), observation.navigationFacts)
    }

    @Test
    fun `verification response carries the independently observed after page`() {
        val result = GatewayUtils.parseVerifyResponse(
            """{"outcome":"SUCCESS","reason":"opened details","manualObservation":{"status":"NEW_OR_CHANGED","pageName":"会议准备清单","matchedHeadingPath":null,"visualSignature":"顶部标题，下方检查项目","reusableFacts":["用于查看会前检查项"],"navigationFacts":["资料库 → 会议准备清单"],"popupOrRecoveryFact":null}}""",
        )

        assertEquals(VerifyOutcome.SUCCESS, result.outcome)
        assertEquals("会议准备清单", result.manualObservation!!.pageName)
        assertEquals(listOf("资料库 → 会议准备清单"), result.manualObservation.navigationFacts)
    }

    @Test
    fun `truncated verification recovers only an explicit outcome field`() {
        val result = GatewayUtils.parseVerifyResponse(
            """{"outcome":"SUCCESS","reason":"The AFTER screenshot shows the destination"""",
        )

        assertEquals(VerifyOutcome.SUCCESS, result.outcome)
        assertTrue(result.reason.contains("explicit outcome"))
        assertNull(result.manualObservation)
    }

    @Test(expected = org.json.JSONException::class)
    fun `truncated verification without explicit outcome remains a parse failure`() {
        GatewayUtils.parseVerifyResponse("The screen probably changed")
    }

    @Test
    fun `completion audit carries the current terminal page observation`() {
        val result = GatewayUtils.parseQuickCheckResponse(
            """{"answer":true,"category":"complete","confidence":1.0,"manualObservation":{"status":"NEW_OR_CHANGED","pageName":"实验档案","matchedHeadingPath":null,"visualSignature":"顶部标题，下方档案卡片","reusableFacts":["用于查看实验档案"],"navigationFacts":["高级设置 → 实验档案"],"popupOrRecoveryFact":null}}""",
        )

        assertTrue(result.isValid)
        assertEquals("实验档案", result.manualObservation!!.pageName)
    }

    @Test
    fun `completing response carries the answer as text whatever its JSON type`() {
        fun answerOf(value: String) = GatewayUtils.parseModelResponse(
            """{"action":"screenshot","isTaskComplete":true,"answer":$value,"statusUpdate":"done"}""",
        ).answer
        assertEquals("3", answerOf("3"))
        assertEquals("Buy milk", answerOf("\" Buy milk \""))
        assertEquals("Mon, Tue", answerOf("[\"Mon\",\"Tue\"]"))
        assertNull(answerOf("null"))
        assertNull(answerOf("\"  \""))
    }

    @Test
    fun `completion audit reads the task answer from the final screen`() {
        val result = GatewayUtils.parseQuickCheckResponse(
            """{"answer":true,"category":"shown","confidence":0.9,"taskAnswer":42}""",
        )
        assertEquals("42", result.taskAnswer)
        assertNull(GatewayUtils.parseQuickCheckResponse("""{"answer":true,"confidence":0.9}""").taskAnswer)
    }
}
