package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateTest {

    @Test
    fun `ScreenState creation with all fields`() {
        val state = ScreenState(
            screenType = ScreenType.LIST,
            appPackage = "com.example.app",
            uiTreeQuality = UiTreeQuality.HIGH,
            nodeCount = 30,
            textNodeCount = 10,
            perceptionSource = PerceptionSource.CLOUD_MODEL,
            summary = "Showing a list of items",
        )
        assertEquals(ScreenType.LIST, state.screenType)
        assertEquals("com.example.app", state.appPackage)
        assertEquals(UiTreeQuality.HIGH, state.uiTreeQuality)
        assertEquals(30, state.nodeCount)
        assertEquals(10, state.textNodeCount)
        assertEquals(PerceptionSource.CLOUD_MODEL, state.perceptionSource)
        assertEquals("Showing a list of items", state.summary)
    }

    @Test
    fun `ScreenState default summary is empty`() {
        val state = ScreenState(
            screenType = ScreenType.UNKNOWN,
            appPackage = "com.test",
            uiTreeQuality = UiTreeQuality.LOW,
            nodeCount = 2,
            textNodeCount = 0,
            perceptionSource = PerceptionSource.RULE_ENGINE,
        )
        assertEquals("", state.summary)
    }

    @Test
    fun `ScreenType enum values`() {
        val values = ScreenType.values()
        assertEquals(6, values.size)
        assertEquals(ScreenType.LIST, ScreenType.valueOf("LIST"))
        assertEquals(ScreenType.DIALOG, ScreenType.valueOf("DIALOG"))
        assertEquals(ScreenType.FORM, ScreenType.valueOf("FORM"))
        assertEquals(ScreenType.NAVIGATION, ScreenType.valueOf("NAVIGATION"))
        assertEquals(ScreenType.MEDIA, ScreenType.valueOf("MEDIA"))
        assertEquals(ScreenType.UNKNOWN, ScreenType.valueOf("UNKNOWN"))
    }

    @Test
    fun `PerceptionSource enum values`() {
        val values = PerceptionSource.values()
        assertEquals(3, values.size)
        assertEquals(PerceptionSource.CLOUD_MODEL, PerceptionSource.valueOf("CLOUD_MODEL"))
        assertEquals(PerceptionSource.UI_TREE_ONLY, PerceptionSource.valueOf("UI_TREE_ONLY"))
        assertEquals(PerceptionSource.RULE_ENGINE, PerceptionSource.valueOf("RULE_ENGINE"))
    }

    @Test
    fun `ScreenState copy works`() {
        val original = ScreenState(
            screenType = ScreenType.UNKNOWN,
            appPackage = "com.test",
            uiTreeQuality = UiTreeQuality.MEDIUM,
            nodeCount = 15,
            textNodeCount = 3,
            perceptionSource = PerceptionSource.UI_TREE_ONLY,
        )
        val copy = original.copy(screenType = ScreenType.DIALOG, summary = "Alert dialog")
        assertEquals(ScreenType.DIALOG, copy.screenType)
        assertEquals("Alert dialog", copy.summary)
        assertEquals("com.test", copy.appPackage)
    }
}
