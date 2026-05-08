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
    fun `reasoning system prompt does not mention pixel coordinates`() {
        val prompt = PromptBuilder.buildReasoningSystemPrompt()
        assertFalse(prompt.contains("pixel"))
    }

    // ─── Experience prompt section ───

    @Test
    fun `experience prompt for unknown app asks for experienceNote`() {
        val prompt = PromptBuilder.buildExperiencePromptSection("com.new.app", null)
        assertTrue(prompt.contains("first time"))
        assertTrue(prompt.contains("com.new.app"))
        assertTrue(prompt.contains("experienceNote"))
    }

    @Test
    fun `experience prompt with existing experience includes markdown`() {
        val markdown = "# MyApp\n## Known Pages\n- Home: Main feed"
        val prompt = PromptBuilder.buildExperiencePromptSection("com.test.app", markdown)
        assertTrue(prompt.contains("previous experience"))
        assertTrue(prompt.contains("Main feed"))
    }

    @Test
    fun `experience prompt with outdated pages includes warning`() {
        val markdown = "# MyApp\n## Known Pages\n- \u26a0\ufe0f **Home**: Main feed"
        val prompt = PromptBuilder.buildExperiencePromptSection("com.test.app", markdown)
        assertTrue(prompt.contains("outdated"))
    }

    @Test
    fun `experience prompt without outdated pages has no warning`() {
        val markdown = "# MyApp\n## Known Pages\n- Home: Main feed"
        val prompt = PromptBuilder.buildExperiencePromptSection("com.test.app", markdown)
        assertFalse(prompt.contains("outdated"))
    }

    @Test
    fun `reasoning user prompt includes experienceNote field`() {
        val prompt = PromptBuilder.buildReasoningUserPrompt("", "test", 1080, 2400)
        assertTrue(prompt.contains("experienceNote"))
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
    }

    @Test
    fun `verify user prompt mentions BEFORE and AFTER`() {
        val prompt = PromptBuilder.buildVerifyUserPrompt("tap", "screen changes")
        assertTrue(prompt.contains("BEFORE"))
        assertTrue(prompt.contains("AFTER"))
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
