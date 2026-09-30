package bot.coati.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundAdvisorTest {

    @Test
    fun `plan output yields subgoals, text values and apps`() {
        val guidance = BackgroundAdvisor.parse(
            """{"subgoals":["打开资料库","搜索"],"text_values":{"query":"meeting","empty":""},"apps":["WeChat"]}""",
            step = 0,
        )

        assertEquals(listOf("打开资料库", "搜索"), guidance.subgoals)
        assertEquals(mapOf("query" to "meeting"), guidance.textValues)
        assertEquals(listOf("WeChat"), guidance.apps)
        assertNull(guidance.hint)
        assertNull(guidance.manualObservation)
    }

    @Test
    fun `advice may carry a manual observation`() {
        val guidance = BackgroundAdvisor.parse(
            """
            {"subgoals":[],"hint":"try 设置","manualObservation":{"status":"NEW_OR_CHANGED","pageName":"我的",
             "visualSignature":"two cards","reusableFacts":["设置 opens preferences"],"navigationFacts":[]}}
            """.trimIndent(),
            step = 3,
        )

        assertEquals("try 设置", guidance.hint)
        assertEquals("我的", guidance.manualObservation?.pageName)
        assertEquals(3, guidance.forStep)
    }
}
