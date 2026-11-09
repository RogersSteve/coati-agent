package bot.coati.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderTest {

    @Test
    fun `registry has 6 providers`() {
        assertEquals(6, ModelProviders.all.size)
    }

    @Test
    fun `findById returns correct provider`() {
        val gemini = ModelProviders.findById("gemini")
        assertNotNull(gemini)
        assertEquals("Gemini (Recommended)", gemini!!.displayName)
        assertEquals(ApiFormat.GEMINI, gemini.apiFormat)
        assertEquals(AuthStyle.QUERY_PARAM, gemini.authStyle)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(ModelProviders.findById("nonexistent"))
    }

    @Test
    fun `gemini provider has correct models`() {
        val gemini = ModelProviders.findById("gemini")!!
        assertEquals(4, gemini.models.size)
        assertTrue(gemini.models.contains("gemini-2.5-flash"))
        assertFalse(gemini.isCustom)
    }

    @Test
    fun `openai provider uses OpenAI-compatible format`() {
        val openai = ModelProviders.findById("openai")!!
        assertEquals(ApiFormat.OPENAI_COMPATIBLE, openai.apiFormat)
        assertEquals(AuthStyle.BEARER, openai.authStyle)
        assertEquals("https://api.openai.com", openai.defaultEndpoint)
        assertFalse(openai.isCustom)
    }

    @Test
    fun `claude provider uses Claude-compatible format`() {
        val claude = ModelProviders.findById("claude")!!
        assertEquals(ApiFormat.CLAUDE_COMPATIBLE, claude.apiFormat)
        assertEquals(AuthStyle.ANTHROPIC_HEADER, claude.authStyle)
        assertEquals("https://api.anthropic.com", claude.defaultEndpoint)
        assertFalse(claude.isCustom)
    }

    @Test
    fun `deepseek uses OpenAI-compatible format`() {
        val deepseek = ModelProviders.findById("deepseek")!!
        assertEquals(ApiFormat.OPENAI_COMPATIBLE, deepseek.apiFormat)
        assertEquals(AuthStyle.BEARER, deepseek.authStyle)
        assertEquals(1, deepseek.models.size)
        assertFalse(deepseek.isCustom)
    }

    @Test
    fun `openai_compatible is custom with empty defaults`() {
        val custom = ModelProviders.findById("openai_compatible")!!
        assertTrue(custom.isCustom)
        assertEquals("", custom.defaultEndpoint)
        assertTrue(custom.models.isEmpty())
        assertEquals(ApiFormat.OPENAI_COMPATIBLE, custom.apiFormat)
    }

    @Test
    fun `claude_compatible is custom with empty defaults`() {
        val custom = ModelProviders.findById("claude_compatible")!!
        assertTrue(custom.isCustom)
        assertEquals("", custom.defaultEndpoint)
        assertTrue(custom.models.isEmpty())
        assertEquals(ApiFormat.CLAUDE_COMPATIBLE, custom.apiFormat)
    }

    @Test
    fun `all provider ids are unique`() {
        val ids = ModelProviders.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
