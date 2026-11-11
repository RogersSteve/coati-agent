package bot.coati.ui.settings

import bot.coati.data.ModelAuthMode
import bot.coati.data.ProviderConfig
import bot.coati.experience.ManualMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {

    @Test
    fun `stored guide presence is unknown until scan completes`() {
        val editor = ModelEditorState("gemini", "https://example.com", "model")
        val state = SettingsUiState(reasoning = editor, fast = editor)
        assertNull(state.hasStoredManualData)
    }

    @Test
    fun `user manual modes exclude debug-only shadow`() {
        assertEquals(
            listOf(ManualMode.OFF, ManualMode.READ_ONLY, ManualMode.LEARNING),
            USER_MANUAL_MODES,
        )
        assertFalse(ManualMode.SHADOW in USER_MANUAL_MODES)
    }

    @Test
    fun `maskApiKey exposes only last four characters`() {
        assertEquals("•••• 1234", maskApiKey("sk-secret-value-1234"))
        assertEquals("••••", maskApiKey("1234"))
        assertEquals("", maskApiKey(""))
    }

    @Test
    fun `resolveApiKey prefers entered key`() {
        assertEquals("new-key", resolveApiKey("  new-key  ", "saved-key", true))
    }

    @Test
    fun `resolveApiKey reuses saved key only for matching provider`() {
        assertEquals("saved-key", resolveApiKey("", "saved-key", true))
        assertNull(resolveApiKey("", "saved-key", false))
    }

    @Test
    fun `validateModelDraft requires endpoint model and key`() {
        assertEquals(ModelDraftIssue.ENDPOINT_REQUIRED, validateModelDraft("", "model", "key"))
        assertEquals(ModelDraftIssue.MODEL_REQUIRED, validateModelDraft("https://example.com", "", "key"))
        assertEquals(ModelDraftIssue.API_KEY_REQUIRED, validateModelDraft("https://example.com", "model", null))
        assertNull(validateModelDraft("https://example.com", "model", "key"))
    }

    @Test
    fun `selectCompatibleFastModel preserves valid choice and repairs provider changes`() {
        val models = listOf("large", "fast", "mini")
        assertEquals("mini", selectCompatibleFastModel(models, "mini", "large"))
        assertEquals("fast", selectCompatibleFastModel(models, "old-provider-model", "large"))
        assertEquals("custom-reasoning", selectCompatibleFastModel(emptyList(), "old", "custom-reasoning"))
    }

    @Test
    fun `startup issues distinguish missing permission from disconnected service`() {
        assertEquals(
            listOf(
                StartupIssue.OVERLAY_PERMISSION,
                StartupIssue.ACCESSIBILITY_PERMISSION,
                StartupIssue.REASONING_MODEL,
            ),
            startupIssues(false, false, false, false, false, true),
        )
        assertEquals(
            listOf(StartupIssue.ACCESSIBILITY_CONNECTION),
            startupIssues(true, true, false, true, true, true),
        )
        assertEquals(
            listOf(StartupIssue.FAST_MODEL),
            startupIssues(true, true, true, true, false, false),
        )
    }

    @Test
    fun `complete model configuration requires every persisted field`() {
        val complete = ProviderConfig("gemini", "secret", "https://example.com", "model")
        assertTrue(isCompleteModelConfig(complete))
        assertFalse(isCompleteModelConfig(complete.copy(apiKey = "")))
        assertFalse(isCompleteModelConfig(complete.copy(endpoint = "")))
        assertFalse(isCompleteModelConfig(complete.copy(providerId = "removed-provider")))
        assertFalse(isCompleteModelConfig(null))
        val plan = complete.copy(providerId = "openai", apiKey = "", authMode = ModelAuthMode.OPENAI_PLAN)
        assertFalse(isCompleteModelConfig(plan))
        assertTrue(isCompleteModelConfig(plan, openAiPlanSignedIn = true))
        assertFalse(isCompleteModelConfig(plan.copy(modelName = ""), openAiPlanSignedIn = true))
    }

    @Test
    fun `launcher starts overlay directly only when stopped and ready`() {
        assertTrue(shouldStartOverlayDirectly(false, false, true))
        assertFalse(shouldStartOverlayDirectly(true, false, true))
        assertFalse(shouldStartOverlayDirectly(false, true, true))
        assertFalse(shouldStartOverlayDirectly(false, false, false))
    }
}
