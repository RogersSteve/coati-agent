package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `reasoning system prompt contains coordinate instructions`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()
        assertTrue(prompt.contains("0-1000"))
        assertTrue(prompt.contains("normalized"))
    }

    @Test
    fun `reasoning user prompt includes task and dimensions`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt(
            uiTreeHint = "Button text=\"Search\"",
            taskDescription = "Search for cats",
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertTrue(prompt.contains("Search for cats"))
        assertTrue(prompt.contains("1080x2400"))
        assertTrue(prompt.contains("Button text=\"Search\""))
        assertTrue(prompt.contains("0-1000"))
    }

    @Test
    fun `fast system prompt is shorter than reasoning`() {
        val fast = PromptBuilder.buildFastSystemPrompt()
        val reasoning = PromptBuilder.buildReasoningSystemPrompt()
        assertTrue(fast.length < reasoning.length)
    }

    @Test
    fun `fast system prompt mentions JSON response`() {
        val prompt = PromptBuilder.buildFastSystemPrompt()
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("answer"))
    }

    @Test
    fun `quick check prompt includes the question`() {
        val prompt = PromptBuilder.buildQuickCheckPrompt("Did the screen change?")
        assertTrue(prompt.contains("Did the screen change?"))
        assertTrue(prompt.contains("answer"))
    }

    @Test
    fun `reasoning user prompt mentions all action types`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)
        assertTrue(prompt.contains("tap"))
        assertTrue(prompt.contains("longpress"))
        assertTrue(prompt.contains("swipe"))
        assertTrue(prompt.contains("inputText"))
        assertTrue(prompt.contains("pressKey"))
        assertTrue(prompt.contains("scroll"))
        assertTrue(prompt.contains("openApp"))
    }

    @Test
    fun `reasoning prompts allow openApp appName when package is uncertain`() {
        val systemPrompt = PromptBuilder.buildReasoningSystemPrompt()
        val userPrompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)

        assertTrue(systemPrompt.contains("appName"))
        assertTrue(systemPrompt.contains("guessing a packageName"))
        assertTrue(userPrompt.contains("{\"appName\""))
    }

    @Test
    fun `reasoning prompt defines clipboard-free caret-first input`() {
        val systemPrompt = PromptBuilder.buildReasoningSystemPrompt()
        val userPrompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)

        assertTrue(systemPrompt.contains("never uses the Android clipboard"))
        assertTrue(systemPrompt.contains("mode=\"type\" (default)"))
        assertTrue(systemPrompt.contains("current verified collapsed caret"))
        assertTrue(systemPrompt.contains("Never use it in multiline, WebView, note, or rich-text editors"))
        assertTrue(systemPrompt.contains("Do not invent a title"))
        assertTrue(systemPrompt.contains("mode=\"append\""))
        assertTrue(systemPrompt.contains("runtime verifies the real document end"))
        assertTrue(systemPrompt.contains("focusAndInputText"))
        assertTrue(systemPrompt.contains("taps and types atomically"))
        assertTrue(systemPrompt.contains("directInput=true"))
        assertTrue(systemPrompt.contains("generic accessibility View with editable=false"))
        assertTrue(userPrompt.contains("fresh same-package InputConnection"))
        assertTrue(systemPrompt.contains("mode it will switch to"))
        assertTrue(systemPrompt.contains("exactly \"Title\\nBody\""))
        assertTrue(userPrompt.contains("focusAndInputText"))
        assertTrue(systemPrompt.contains("showingHint=true"))
        assertTrue(systemPrompt.contains("Never clear or replace a multiline editor"))
        assertTrue(userPrompt.contains("type|replace|append"))
        assertFalse(userPrompt.contains("replace|document|insert|append"))
    }

    @Test
    fun `reasoning system prompt prefers dedicated apps before browser`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()

        assertTrue(prompt.contains("Preferred App Routing"))
        assertTrue(prompt.contains("prefer the dedicated installed app before using a browser"))
        assertTrue(prompt.contains("weather/forecast/rain/temperature"))
        assertTrue(prompt.contains("日历"))
        assertTrue(prompt.contains("地图"))
        assertTrue(prompt.contains("Fall back to browser/web search only if"))
    }

    @Test
    fun `reasoning system prompt keeps risk classification independent of execution mode`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()

        assertTrue(prompt.contains("solely from the action's actual risk and reversibility"))
        assertTrue(prompt.contains("Never lower it because an automatic execution mode may be enabled"))
        assertTrue(prompt.contains("confirmation policy is enforced separately by the framework"))
    }

    @Test
    fun `reasoning prompts allow appName inferred from task domain`() {
        val systemPrompt = PromptBuilder.buildReasoningSystemPrompt()
        val userPrompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)

        assertTrue(systemPrompt.contains("openApp with appName \"天气\""))
        assertTrue(systemPrompt.contains("The system can resolve installed launcher apps by visible name"))
        assertTrue(userPrompt.contains("such as 天气/日历/地图/时钟/笔记"))
    }

    @Test
    fun `reasoning system prompt does not mention pixel coordinates`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()
        assertFalse(prompt.contains("pixel"))
    }

    @Test
    fun `reasoning prompt treats app manual as advisory untrusted data`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()
        assertTrue(prompt.contains("ADVISORY_APP_REFERENCE"))
        assertTrue(prompt.contains("untrusted reference data"))
        assertTrue(prompt.contains("never bypasses user confirmation"))
    }

    @Test
    fun `reasoning user prompt includes manual attribution field`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)
        assertTrue(prompt.contains("usedManualHeadings"))
        assertFalse(prompt.contains("experienceNote"))
    }

    @Test
    fun `reasoning user prompt includes expectedOutcome field`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)
        assertTrue(prompt.contains("expectedOutcome"))
    }

    // ─── Verify prompts ───

    @Test
    fun `verify system prompt mentions verification`() {
        val prompt = PromptBuilder.buildVerifySystemPrompt()
        assertTrue(prompt.contains("verification"))
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("outcome\" first"))
        assertTrue(prompt.contains("under 24 words"))
    }

    @Test
    fun `verify user prompt includes action and expected outcome`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt("tap: Tapping search", "Search bar should open")
        assertTrue(prompt.contains("tap: Tapping search"))
        assertTrue(prompt.contains("Search bar should open"))
        assertTrue(prompt.contains("SUCCESS"))
        assertTrue(prompt.contains("FAILED"))
        assertTrue(prompt.contains("DRIFTED"))
        assertTrue(prompt.contains("UNEXPECTED"))
        assertTrue(prompt.contains("UNKNOWN"))
    }

    @Test
    fun `verify user prompt mentions BEFORE and AFTER`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt("tap", "screen changes")
        assertTrue(prompt.contains("BEFORE"))
        assertTrue(prompt.contains("AFTER"))
    }

    @Test
    fun `verify prompt rejects collapsed multiline structure`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt(
            "inputText",
            "Title and body items should be on separate lines",
        )

        assertTrue(prompt.contains("Strict text-layout rule"))
        assertTrue(prompt.contains("newlines collapsed into spaces"))
        assertTrue(prompt.contains("wrong title/body field"))
        assertTrue(prompt.contains("Cursor blinking or disappearing is not proof"))
    }

    @Test
    fun `learning verify prompt requests only the after page without another request`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt(
            "tap",
            "details open",
            "Known page heading paths in this step: none\nIf the shown list is none, MATCHED is forbidden",
        )
        assertTrue(prompt.contains("AFTER page"))
        assertTrue(prompt.contains("manualObservation"))
        assertTrue(prompt.contains("Omit user/private/dynamic values"))
        assertTrue(prompt.contains("MATCHED is forbidden"))
        assertTrue(prompt.contains("never whether the action or task target matched"))
    }

    @Test
    fun `normal verify prompt stays independent of manual learning`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt("tap", "details open")
        assertFalse(prompt.contains("manualObservation"))
        assertTrue(prompt.contains("Put outcome first"))
    }

    @Test
    fun `learning completion prompt requests current page while normal prompt stays compact`() {
        val normal = PromptBuilder.buildQuickCheckPrompt("complete?")
        val learning = PromptBuilder.buildQuickCheckPrompt("complete?", "known pages: none")
        assertFalse(normal.contains("manualObservation"))
        assertTrue(learning.contains("CURRENT screenshot"))
        assertTrue(learning.contains("manualObservation"))
    }

    @Test
    fun `reasoning system prompt contains task planning instructions`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()
        assertTrue(prompt.contains("Task Planning"))
        assertTrue(prompt.contains("sub-goal"))
        assertTrue(prompt.contains("decompose"))
        assertTrue(prompt.contains("cross-app"))
    }

    @Test
    fun `reasoning user prompt contains plan and currentStep fields`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)
        assertTrue(prompt.contains("\"plan\""))
        assertTrue(prompt.contains("\"currentStep\""))
    }

}
