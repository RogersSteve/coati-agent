package bot.coati.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProfileTest {

    @Test
    fun `toJson and fromJson roundtrip`() {
        val profile = AppProfile(
            packageName = "com.example.app",
            appName = "Example",
            uiTreeQuality = "HIGH",
            lastUpdated = "2026-03-01T10:00:00",
            pages = mutableListOf(
                AppPage("Home", "Main feed with posts", false),
                AppPage("Settings", "App settings page", true),
            ),
            commonPaths = mutableListOf(
                CommonPath("Open Settings", listOf("Tap menu", "Tap Settings"), "2026-03-01T10:00:00", 3),
            ),
            knownIssues = mutableListOf("Popup on first launch"),
        )

        val json = profile.toJson()
        val restored = AppProfile.fromJson(json)

        assertEquals(profile.packageName, restored.packageName)
        assertEquals(profile.appName, restored.appName)
        assertEquals(profile.uiTreeQuality, restored.uiTreeQuality)
        assertEquals(2, restored.pages.size)
        assertEquals("Home", restored.pages[0].name)
        assertEquals("Main feed with posts", restored.pages[0].description)
        assertFalse(restored.pages[0].outdated)
        assertTrue(restored.pages[1].outdated)
        assertEquals(1, restored.commonPaths.size)
        assertEquals("Open Settings", restored.commonPaths[0].name)
        assertEquals(listOf("Tap menu", "Tap Settings"), restored.commonPaths[0].steps)
        assertEquals(3, restored.commonPaths[0].successCount)
        assertEquals(1, restored.knownIssues.size)
        assertEquals("Popup on first launch", restored.knownIssues[0])
    }

    @Test
    fun `upsertPage adds new page`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPage("Home", "Landing page")
        assertEquals(1, profile.pages.size)
        assertEquals("Home", profile.pages[0].name)
        assertEquals("Landing page", profile.pages[0].description)
    }

    @Test
    fun `upsertPage updates existing page`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPage("Home", "Old description")
        profile.upsertPage("Home", "New description")
        assertEquals(1, profile.pages.size)
        assertEquals("New description", profile.pages[0].description)
        assertFalse(profile.pages[0].outdated)
    }

    @Test
    fun `upsertPage clears outdated flag`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPage("Home", "desc")
        profile.markOutdated("Home")
        assertTrue(profile.pages[0].outdated)
        profile.upsertPage("Home", "updated desc")
        assertFalse(profile.pages[0].outdated)
    }

    @Test
    fun `markOutdated sets flag`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPage("Home", "desc")
        profile.markOutdated("Home")
        assertTrue(profile.pages[0].outdated)
    }

    @Test
    fun `markOutdated does nothing for unknown page`() {
        val profile = AppProfile(packageName = "com.test")
        profile.markOutdated("NonExistent")
        assertTrue(profile.pages.isEmpty())
    }

    @Test
    fun `addOrUpdatePath creates new path`() {
        val profile = AppProfile(packageName = "com.test")
        profile.addOrUpdatePath("Open Settings", listOf("Tap menu", "Tap Settings"))
        assertEquals(1, profile.commonPaths.size)
        assertEquals(1, profile.commonPaths[0].successCount)
    }

    @Test
    fun `addOrUpdatePath increments success count`() {
        val profile = AppProfile(packageName = "com.test")
        profile.addOrUpdatePath("Open Settings", listOf("Tap menu", "Tap Settings"))
        profile.addOrUpdatePath("Open Settings", listOf("Tap menu", "Tap Settings"))
        assertEquals(1, profile.commonPaths.size)
        assertEquals(2, profile.commonPaths[0].successCount)
    }

    @Test
    fun `toMarkdown marks outdated pages with warning`() {
        val profile = AppProfile(
            packageName = "com.test",
            appName = "Test",
            pages = mutableListOf(AppPage("Home", "desc", true)),
        )
        val md = profile.toMarkdown()
        assertTrue(md.contains("\u26a0\ufe0f"))
        assertTrue(md.contains("Home"))
    }

    @Test
    fun `toMarkdown includes common paths with step counts`() {
        val profile = AppProfile(
            packageName = "com.test",
            appName = "Test",
            commonPaths = mutableListOf(
                CommonPath("Search", listOf("Tap search bar", "Type query"), "now", 5),
            ),
        )
        val md = profile.toMarkdown()
        assertTrue(md.contains("used 5x"))
        assertTrue(md.contains("Tap search bar"))
    }

    @Test
    fun `empty profile roundtrip`() {
        val profile = AppProfile(packageName = "com.empty")
        val json = profile.toJson()
        val restored = AppProfile.fromJson(json)
        assertEquals("com.empty", restored.packageName)
        assertTrue(restored.pages.isEmpty())
        assertTrue(restored.commonPaths.isEmpty())
        assertTrue(restored.knownIssues.isEmpty())
        assertTrue(restored.popupPatterns.isEmpty())
    }

    // ─── PopupPattern ───

    @Test
    fun `PopupPattern serialization roundtrip`() {
        val profile = AppProfile(
            packageName = "com.test",
            appName = "Test",
            popupPatterns = mutableListOf(
                PopupPattern("abc123", "Allow access?", listOf("Allow", "access"), "Allow", 3),
            ),
        )
        val json = profile.toJson()
        val restored = AppProfile.fromJson(json)
        assertEquals(1, restored.popupPatterns.size)
        val popup = restored.popupPatterns[0]
        assertEquals("abc123", popup.signature)
        assertEquals("Allow access?", popup.title)
        assertEquals(listOf("Allow", "access"), popup.keywords)
        assertEquals("Allow", popup.buttonText)
        assertEquals(3, popup.hitCount)
    }

    @Test
    fun `upsertPopup adds new popup`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPopup("sig1", "Update?", listOf("update"), "Later")
        assertEquals(1, profile.popupPatterns.size)
        assertEquals(1, profile.popupPatterns[0].hitCount)
    }

    @Test
    fun `upsertPopup increments hitCount for same signature`() {
        val profile = AppProfile(packageName = "com.test")
        profile.upsertPopup("sig1", "Update?", listOf("update"), "Later")
        profile.upsertPopup("sig1", "Update?", listOf("update"), "Later")
        assertEquals(1, profile.popupPatterns.size)
        assertEquals(2, profile.popupPatterns[0].hitCount)
    }

    @Test
    fun `toMarkdown includes Known Popups`() {
        val profile = AppProfile(
            packageName = "com.test",
            appName = "Test",
            popupPatterns = mutableListOf(
                PopupPattern("sig1", "Allow camera?", listOf("camera"), "Allow", 5),
            ),
        )
        val md = profile.toMarkdown()
        assertTrue(md.contains("Known Popups"))
        assertTrue(md.contains("Allow camera?"))
        assertTrue(md.contains("seen 5x"))
    }

    @Test
    fun `multiple popup patterns roundtrip`() {
        val profile = AppProfile(
            packageName = "com.test",
            appName = "Test",
            popupPatterns = mutableListOf(
                PopupPattern("sig1", "Title 1", listOf("kw1"), "OK", 1),
                PopupPattern("sig2", "Title 2", listOf("kw2", "kw3"), "Cancel", 2),
            ),
        )
        val json = profile.toJson()
        val restored = AppProfile.fromJson(json)
        assertEquals(2, restored.popupPatterns.size)
        assertEquals("sig2", restored.popupPatterns[1].signature)
        assertEquals(2, restored.popupPatterns[1].keywords.size)
    }
}
