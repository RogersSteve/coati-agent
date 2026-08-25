package bot.coati.agent

import android.graphics.Rect
import bot.coati.data.SafetyMode
import bot.coati.service.UiTreeNode
import bot.coati.state.PopupPattern
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

    // ─── detectKnownPopup (experience-based) ───

    @Test
    fun `detectKnownPopup returns null for null tree`() {
        assertNull(RuleEngine.detectKnownPopup(null))
    }

    @Test
    fun `detectKnownPopup returns null without experience`() {
        val tree = makeNode("Button", "关闭广告", clickable = true, children = emptyList())
        assertNull(RuleEngine.detectKnownPopup(tree))
    }

    @Test
    fun `detectKnownPopup returns null with empty experience`() {
        val tree = makeNode("Button", "Skip", clickable = true, children = emptyList())
        assertNull(RuleEngine.detectKnownPopup(tree, emptyList()))
    }

    @Test
    fun `detectKnownPopup matches by signature`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Update available", clickable = false, children = emptyList()),
            makeNode("Button", "Later", clickable = true, children = emptyList()),
            makeNode("Button", "Update", clickable = true, children = emptyList()),
        ))
        val sig = RuleEngine.buildPopupSignature(tree)!!
        val experience = listOf(
            PopupPattern(sig, "Update available", listOf("Update available"), "Later", 3),
        )
        val result = RuleEngine.detectKnownPopup(tree, experience)
        assertNotNull(result)
        assertEquals("Later", result!!.dismissText)
        assertEquals(sig, result.popupSignature)
    }

    @Test
    fun `detectKnownPopup matches by keywords when signature differs`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Allow access to photos?", clickable = false, children = emptyList()),
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
            makeNode("Button", "Deny", clickable = true, children = emptyList()),
        ))
        // Experience with a different signature but matching title/keywords
        val experience = listOf(
            PopupPattern("different_sig", "Allow access to photos?", listOf("Allow access", "photos"), "Allow", 2),
        )
        val result = RuleEngine.detectKnownPopup(tree, experience)
        assertNotNull(result)
        assertEquals("Allow", result!!.dismissText)
    }

    @Test
    fun `detectKnownPopup returns null when button text changed`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Update available", clickable = false, children = emptyList()),
            makeNode("Button", "Remind me tomorrow", clickable = true, children = emptyList()),
        ))
        val sig = RuleEngine.buildPopupSignature(tree)!!
        // Experience says to click "Later" but that button doesn't exist
        val experience = listOf(
            PopupPattern(sig, "Update available", listOf("Update available"), "Later", 3),
        )
        val result = RuleEngine.detectKnownPopup(tree, experience)
        assertNull(result)
    }

    @Test
    fun `detectKnownPopup returns null when no popup buttons present`() {
        val tree = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Hello World", clickable = false, children = emptyList()),
            makeNode("Button", "Submit", clickable = true, children = emptyList()),
        ))
        val experience = listOf(
            PopupPattern("abc", "Some popup", listOf("popup"), "Close", 1),
        )
        assertNull(RuleEngine.detectKnownPopup(tree, experience))
    }

    // ─── buildPopupSignature ───

    @Test
    fun `buildPopupSignature produces same hash for same content`() {
        val tree1 = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Allow access?", clickable = false, children = emptyList()),
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
        ))
        val tree2 = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Allow access?", clickable = false, children = emptyList()),
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
        ))
        assertEquals(RuleEngine.buildPopupSignature(tree1), RuleEngine.buildPopupSignature(tree2))
    }

    @Test
    fun `buildPopupSignature returns null for null tree`() {
        assertNull(RuleEngine.buildPopupSignature(null))
    }

    @Test
    fun `buildPopupSignature returns null for tree without text`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = emptyList())
        assertNull(RuleEngine.buildPopupSignature(tree))
    }

    // ─── buildScreenSignature ───

    @Test
    fun `buildScreenSignature produces same hash for same tree`() {
        val tree1 = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Item 1", clickable = false, children = emptyList()),
            makeNode("TextView", "Item 2", clickable = false, children = emptyList()),
        ))
        val tree2 = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Item 1", clickable = false, children = emptyList()),
            makeNode("TextView", "Item 2", clickable = false, children = emptyList()),
        ))
        assertEquals(RuleEngine.buildScreenSignature(tree1), RuleEngine.buildScreenSignature(tree2))
    }

    @Test
    fun `buildScreenSignature produces different hash for different content`() {
        val tree1 = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "File A", clickable = false, children = emptyList()),
        ))
        val tree2 = makeNode("LinearLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "File B", clickable = false, children = emptyList()),
        ))
        assertTrue(RuleEngine.buildScreenSignature(tree1) != RuleEngine.buildScreenSignature(tree2))
    }

    @Test
    fun `buildScreenSignature returns empty string for null tree`() {
        assertEquals("", RuleEngine.buildScreenSignature(null))
    }

    // ─── extractPopupTitle ───

    @Test
    fun `extractPopupTitle returns longest text`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "Allow access to your camera?", clickable = false, children = emptyList()),
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
            makeNode("Button", "Deny", clickable = true, children = emptyList()),
        ))
        val title = RuleEngine.extractPopupTitle(tree)
        assertEquals("Allow access to your camera?", title)
    }

    // ─── extractPopupKeywords ───

    @Test
    fun `extractPopupKeywords filters short texts and limits to 5`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("TextView", "A", clickable = false, children = emptyList()), // too short
            makeNode("TextView", "Allow access", clickable = false, children = emptyList()),
            makeNode("TextView", "Camera", clickable = false, children = emptyList()),
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
            makeNode("Button", "Deny", clickable = true, children = emptyList()),
        ))
        val keywords = RuleEngine.extractPopupKeywords(tree)
        assertTrue(keywords.contains("Allow access"))
        assertTrue(keywords.contains("Camera"))
        assertFalse(keywords.contains("A"))
        assertTrue(keywords.size <= 5)
    }

    // ─── detectPopupButtons ───

    @Test
    fun `detectPopupButtons finds clickable popup buttons`() {
        val tree = makeNode("FrameLayout", null, clickable = false, children = listOf(
            makeNode("Button", "Allow", clickable = true, children = emptyList()),
            makeNode("Button", "Deny", clickable = true, children = emptyList()),
            makeNode("Button", "Submit", clickable = true, children = emptyList()),
        ))
        val buttons = RuleEngine.detectPopupButtons(tree)
        assertEquals(2, buttons.size)
        assertTrue(buttons.any { it.first == "Allow" })
        assertTrue(buttons.any { it.first == "Deny" })
    }

    @Test
    fun `detectPopupButtons returns empty for null tree`() {
        assertTrue(RuleEngine.detectPopupButtons(null).isEmpty())
    }

    // ─── assessSafety ───

    @Test
    fun `assessSafety forces high for Alipay`() {
        val result = RuleEngine.assessSafety("com.eg.android.AlipayGphone", "tap", "button", "low")
        assertEquals("high", result.level)
        assertNotNull(result.reason)
    }

    @Test
    fun `assessSafety forces high for WeChat`() {
        val result = RuleEngine.assessSafety("com.tencent.mm", "tap", "", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on delete keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "tap", "Delete this file", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on payment keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "tap", "Confirm payment", "medium")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety escalates on chinese payment keyword`() {
        val result = RuleEngine.assessSafety("com.example.app", "tap", "确认支付", "low")
        assertEquals("high", result.level)
    }

    @Test
    fun `assessSafety uses model level when no rules match`() {
        val result = RuleEngine.assessSafety("com.example.app", "tap", "search button", "medium")
        assertEquals("medium", result.level)
        assertNull(result.reason)
    }

    @Test
    fun `assessSafety does not inherit dangerous keywords for screenshot`() {
        val result = RuleEngine.assessSafety(
            "com.example.app",
            "screenshot",
            "The note was deleted successfully",
            "low",
        )

        assertEquals("low", result.level)
        assertNull(result.reason)
    }

    @Test
    fun `assessSafety does not inherit dangerous keywords for wait`() {
        val result = RuleEngine.assessSafety(
            "com.example.app",
            "wait",
            "Wait for payment confirmation",
            "low",
        )

        assertEquals("low", result.level)
        assertNull(result.reason)
    }

    @Test
    fun `assessSafety keeps banking override for context-free actions`() {
        val result = RuleEngine.assessSafety(
            "com.eg.android.AlipayGphone",
            "screenshot",
            "Payment completed",
            "low",
        )

        assertEquals("high", result.level)
        assertNotNull(result.reason)
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
    fun `shouldConfirm unknown level always fails closed`() {
        assertTrue(RuleEngine.shouldConfirm("unknown", SafetyMode.CAUTIOUS))
        assertTrue(RuleEngine.shouldConfirm("unknown", SafetyMode.STANDARD))
        assertTrue(RuleEngine.shouldConfirm("unknown", SafetyMode.TRUST))
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
