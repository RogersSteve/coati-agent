package bot.coati.agent

import android.graphics.Rect
import bot.coati.data.SafetyMode
import bot.coati.service.UiTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    // ─── assessQuality ───

    @Test
    fun `assessQuality returns HIGH for rich UI tree`() {
        assertEquals(UiTreeQuality.HIGH, RuleEngine.assessQuality(25, 8))
    }

    @Test
    fun `assessQuality returns HIGH at boundary`() {
        assertEquals(UiTreeQuality.HIGH, RuleEngine.assessQuality(20, 5))
    }

    @Test
    fun `assessQuality returns MEDIUM when nodes enough but text low`() {
        assertEquals(UiTreeQuality.MEDIUM, RuleEngine.assessQuality(20, 3))
    }

    @Test
    fun `assessQuality returns MEDIUM at boundary`() {
        assertEquals(UiTreeQuality.MEDIUM, RuleEngine.assessQuality(5, 0))
    }

    @Test
    fun `assessQuality returns LOW for sparse tree`() {
        assertEquals(UiTreeQuality.LOW, RuleEngine.assessQuality(3, 1))
    }

    @Test
    fun `assessQuality returns LOW for empty tree`() {
        assertEquals(UiTreeQuality.LOW, RuleEngine.assessQuality(0, 0))
    }

    // ─── detectKnownPopup ───

    @Test
    fun `detectKnownPopup returns null for null tree`() {
        assertNull(RuleEngine.detectKnownPopup(null))
    }

    @Test
    fun `detectKnownPopup finds chinese dismiss button`() {
        val tree = makeNode("Button", "关闭广告", clickable = true, children = emptyList())
        val result = RuleEngine.detectKnownPopup(tree)
        assertNotNull(result)
        assertEquals("关闭广告", result!!.dismissText)
    }

    @Test
    fun `detectKnownPopup finds english dismiss button`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("Button", "Skip", clickable = true, children = emptyList()),
        ))
        val result = RuleEngine.detectKnownPopup(tree)
        assertNotNull(result)
        assertEquals("Skip", result!!.dismissText)
    }

    @Test
    fun `detectKnownPopup ignores non-clickable text`() {
        val tree = makeNode("TextView", "Cancel", clickable = false, children = emptyList())
        assertNull(RuleEngine.detectKnownPopup(tree))
    }

    @Test
    fun `detectKnownPopup finds dismiss in content description`() {
        val tree = makeNode("ImageButton", null, clickable = true, children = emptyList(),
            contentDescription = "Close")
        val result = RuleEngine.detectKnownPopup(tree)
        assertNotNull(result)
        assertEquals("Close", result!!.dismissText)
    }

    @Test
    fun `detectKnownPopup returns null for no dismiss elements`() {
        val tree = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Hello World", clickable = false, children = emptyList()),
            makeNode("Button", "Submit", clickable = true, children = emptyList()),
        ))
        assertNull(RuleEngine.detectKnownPopup(tree))
    }

    // ─── assessSafety ───

    @Test
    fun `assessSafety forces high for Alipay`() {
        val result = RuleEngine.assessSafety("com.eg.android.AlipayGphone", "tap button", "low")
        assertEquals("high", result.level)
        assertNotNull(result.reason)
    }

    @Test
    fun `assessSafety forces high for WeChat`() {
        val result = RuleEngine.assessSafety("com.tencent.mm", "tap", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on delete keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "Delete this file", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on payment keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "Confirm payment", "medium")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on chinese payment keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "确认支付", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety uses model level when no rules match`() {
        val result = RuleEngine.assessSafety("com.example.app", "tap search button", "medium")
        assertEquals("medium", result.level)
        assertNull(result.reason)
    }

    // ─── shouldConfirm ───

    @Test
    fun `shouldConfirm high always true regardless of mode`() {
        assertTrue(RuleEngine.shouldConfirm("high", SafetyMode.CAUTIOUS))
        assertTrue(RuleEngine.shouldConfirm("high", SafetyMode.STANDARD))
        assertTrue(RuleEngine.shouldConfirm("high", SafetyMode.TRUST))
    }

    @Test
    fun `shouldConfirm medium true for CAUTIOUS and STANDARD`() {
        assertTrue(RuleEngine.shouldConfirm("medium", SafetyMode.CAUTIOUS))
        assertTrue(RuleEngine.shouldConfirm("medium", SafetyMode.STANDARD))
    }

    @Test
    fun `shouldConfirm medium false for TRUST`() {
        assertFalse(RuleEngine.shouldConfirm("medium", SafetyMode.TRUST))
    }

    @Test
    fun `shouldConfirm low true only for CAUTIOUS`() {
        assertTrue(RuleEngine.shouldConfirm("low", SafetyMode.CAUTIOUS))
        assertFalse(RuleEngine.shouldConfirm("low", SafetyMode.STANDARD))
        assertFalse(RuleEngine.shouldConfirm("low", SafetyMode.TRUST))
    }

    @Test
    fun `shouldConfirm unknown level returns false`() {
        assertFalse(RuleEngine.shouldConfirm("unknown", SafetyMode.CAUTIOUS))
        assertFalse(RuleEngine.shouldConfirm("unknown", SafetyMode.STANDARD))
        assertFalse(RuleEngine.shouldConfirm("unknown", SafetyMode.TRUST))
    }

    // ─── detectScreenChange ───

    @Test
    fun `detectScreenChange returns CHANGED for different app`() {
        val result = RuleEngine.detectScreenChange(30, "com.app.a", 30, "com.app.b")
        assertEquals(ChangeResult.CHANGED, result)
    }

    @Test
    fun `detectScreenChange returns CHANGED for large node count diff`() {
        val result = RuleEngine.detectScreenChange(30, "com.app.a", 50, "com.app.a")
        assertEquals(ChangeResult.CHANGED, result)
    }

    @Test
    fun `detectScreenChange returns UNCHANGED for identical counts`() {
        val result = RuleEngine.detectScreenChange(30, "com.app.a", 30, "com.app.a")
        assertEquals(ChangeResult.UNCHANGED, result)
    }

    @Test
    fun `detectScreenChange returns UNCHANGED for diff of 1`() {
        val result = RuleEngine.detectScreenChange(30, "com.app.a", 31, "com.app.a")
        assertEquals(ChangeResult.UNCHANGED, result)
    }

    @Test
    fun `detectScreenChange returns UNCERTAIN for moderate diff`() {
        val result = RuleEngine.detectScreenChange(30, "com.app.a", 35, "com.app.a")
        assertEquals(ChangeResult.UNCERTAIN, result)
    }

    // ─── Helpers ───

    private fun makeNode(
        className: String,
        text: String?,
        clickable: Boolean,
        children: List<UiTreeNode>,
        contentDescription: String? = null,
    ): UiTreeNode {
        return UiTreeNode(
            className = className,
            text = text,
            contentDescription = contentDescription,
            bounds = Rect(0, 0, 100, 50),
            isClickable = clickable,
            isScrollable = false,
            viewIdResourceName = null,
            children = children,
        )
    }
}
