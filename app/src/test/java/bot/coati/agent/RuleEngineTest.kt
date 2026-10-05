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
    fun `shouldConfirm follows the complete mode and risk matrix`() {
        val expected = mapOf(
            SafetyMode.TRUST to listOf(false, false, false),
            SafetyMode.STANDARD to listOf(false, false, true),
            SafetyMode.CAUTIOUS to listOf(false, true, true),
        )
        for ((mode, confirmations) in expected) {
            for ((index, risk) in listOf("low", "medium", "high").withIndex()) {
                assertEquals("$mode/$risk", confirmations[index], RuleEngine.shouldConfirm(risk, mode))
            }
        }
    }

    @Test
    fun `shouldConfirm unknown and malformed levels fail closed in every mode`() {
        for (mode in SafetyMode.values()) {
            for (risk in listOf("unknown", "", "HIGH", " medium ", "invalid")) {
                assertTrue("$mode/$risk", RuleEngine.shouldConfirm(risk, mode))
            }
        }
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
