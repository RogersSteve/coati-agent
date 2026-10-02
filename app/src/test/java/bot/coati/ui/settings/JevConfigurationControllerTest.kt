package bot.coati.ui.settings

import android.content.Context
import bot.coati.data.ConfigStore
import bot.coati.data.JevConfig
import bot.coati.jev.JevClient
import bot.coati.jev.JevProviders
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JevConfigurationControllerTest {
    private val context = mockk<Context>()
    private val store = mockk<ConfigStore>()
    private var saved: JevConfig? = null

    @Before fun setup() {
        every { context.getString(any()) } returns "message"
        every { store.getJevConfig() } answers { saved }
        every { store.saveJevConfig(any(), any(), any()) } answers {
            saved = JevConfig(firstArg(), secondArg(), thirdArg())
        }
        every { store.clearJevConfig() } answers { saved = null }
        mockkConstructor(JevClient::class)
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `provider switch never sends the previous providers saved key`() = runTest {
        saved = JevConfig("typesafe-secret", JevProviders.typeSafe.endpoint, JevProviders.typeSafe.model)
        val editor = JevConfigurationController(context, store, OkHttpClient(), this)
        editor.selectProvider("openrouter")
        editor.verifyAndApply()
        advanceUntilIdle()
        assertFalse(editor.state.value.hasSavedKey)
        assertNotNull(editor.state.value.errorMessage)
        verify(exactly = 0) { store.saveJevConfig(any(), any(), any()) }
        assertEquals("typesafe-secret", saved?.apiKey)
        editor.discardDraft()
        assertEquals("typesafe", editor.state.value.providerId)
        assertFalse(editor.state.value.dirty)
    }

    @Test fun `verified OpenRouter draft saves the compatible endpoint and pinned model`() = runTest {
        coEvery { anyConstructed<JevClient>().testConnection() } returns Result.success("ok")
        val editor = JevConfigurationController(context, store, OkHttpClient(), this)
        editor.selectProvider("openrouter")
        editor.updateKey("openrouter-secret")
        editor.verifyAndApply()
        advanceUntilIdle()
        assertEquals(JevConfig("openrouter-secret", "https://openrouter.ai/api", "jev-1.13"), saved)
        assertTrue(editor.state.value.configured)
        assertFalse(editor.state.value.dirty)
        assertEquals("", editor.state.value.apiKeyInput)
    }

    @Test fun `switching provider cancels pending verification without saving`() = runTest {
        coEvery { anyConstructed<JevClient>().testConnection() } coAnswers { awaitCancellation() }
        val editor = JevConfigurationController(context, store, OkHttpClient(), this)
        editor.updateKey("typesafe-secret")
        editor.verifyAndApply()
        runCurrent()
        assertTrue(editor.state.value.verifying)
        editor.selectProvider("openrouter")
        advanceUntilIdle()
        assertFalse(editor.state.value.verifying)
        assertEquals("", editor.state.value.apiKeyInput)
        assertNull(saved)
        verify(exactly = 0) { store.saveJevConfig(any(), any(), any()) }
    }

    @Test fun `failed verification preserves the active configuration`() = runTest {
        val original = JevConfig("old-secret", JevProviders.typeSafe.endpoint, JevProviders.typeSafe.model)
        saved = original
        coEvery { anyConstructed<JevClient>().testConnection() } returns Result.failure(Exception("rejected"))
        val editor = JevConfigurationController(context, store, OkHttpClient(), this)
        editor.selectProvider("openrouter")
        editor.updateKey("new-secret")
        editor.verifyAndApply()
        advanceUntilIdle()
        assertEquals(original, saved)
        assertTrue(editor.state.value.dirty)
        assertNotNull(editor.state.value.errorMessage)
        assertFalse(editor.state.value.verifying)
    }
}
