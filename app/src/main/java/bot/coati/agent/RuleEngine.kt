package bot.coati.agent

import android.graphics.Rect
import bot.coati.data.SafetyMode
import bot.coati.service.UiTreeNode
import bot.coati.state.PopupPattern

enum class UiTreeQuality { HIGH, MEDIUM, LOW }

enum class ChangeResult { CHANGED, UNCHANGED, UNCERTAIN }

data class PopupDetection(
    val dismissText: String,
    val bounds: Rect,
    val popupSignature: String? = null,
)

data class SafetyAssessment(
    val level: String,
    val reason: String? = null,
)

object RuleEngine {

    private val POPUP_BUTTON_KEYWORDS = listOf(
        // dismiss
        "关闭", "跳过", "取消", "以后再说", "不了", "暂不", "拒绝", "下次再说",
        "Close", "Skip", "Cancel", "Dismiss", "Not now", "No thanks",
        "Later", "Deny", "Got it", "OK", "I know",
        // accept
        "允许", "同意", "确定", "授权", "开启", "立即开启",
        "Allow", "Accept", "Agree", "Grant", "Enable", "Confirm", "Continue",
    )

    private val BANKING_PACKAGES = setOf(
        "com.eg.android.AlipayGphone",   // Alipay
        "com.tencent.mm",                 // WeChat
        "com.unionpay",                   // UnionPay
        "com.chinamworld.main",           // CCB
        "com.icbc.e.icbc",               // ICBC
        "com.android.bankabc",           // ABC
        "com.chinamworld.bocmbci",       // BOC
        "com.cmb.pb",                    // CMB
        "com.paypal.android.p2pmobile",  // PayPal
    )

    private val DANGEROUS_KEYWORDS = listOf(
        "delete", "remove", "pay", "purchase", "buy", "transfer", "send money",
        "confirm payment", "authorize", "uninstall",
        "删除", "支付", "付款", "购买", "转账", "汇款", "卸载",
    )

    fun assessQuality(nodeCount: Int, textNodeCount: Int): UiTreeQuality {
        return when {
            nodeCount >= 20 && textNodeCount >= 5 -> UiTreeQuality.HIGH
            nodeCount >= 5 -> UiTreeQuality.MEDIUM
            else -> UiTreeQuality.LOW
        }
    }

    fun detectKnownPopup(
        uiTree: UiTreeNode?,
        popupExperience: List<PopupPattern> = emptyList(),
    ): PopupDetection? {
        if (uiTree == null || popupExperience.isEmpty()) return null

        val buttonNodes = findNodesByKeywords(uiTree, POPUP_BUTTON_KEYWORDS)
        if (buttonNodes.isEmpty()) return null

        val signature = buildPopupSignature(uiTree) ?: return null

        val knownPattern = popupExperience.find { it.signature == signature }
            ?: matchByKeywords(uiTree, popupExperience)
            ?: return null

        val targetNode = buttonNodes.find { it.first.contains(knownPattern.buttonText, ignoreCase = true) }
            ?: return null

        return PopupDetection(
            dismissText = targetNode.first,
            bounds = targetNode.second,
            popupSignature = signature,
        )
    }

    fun detectPopupButtons(tree: UiTreeNode?): List<Pair<String, Rect>> {
        if (tree == null) return emptyList()
        return findNodesByKeywords(tree, POPUP_BUTTON_KEYWORDS)
    }

    fun buildPopupSignature(tree: UiTreeNode?): String? {
        if (tree == null) return null
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        if (texts.isEmpty()) return null
        return texts.sorted().joinToString("|").hashCode().toString(16)
    }

    fun buildScreenSignature(tree: UiTreeNode?): String {
        if (tree == null) return ""
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        return texts.sorted().joinToString("|").hashCode().toString(16)
    }

    fun extractPopupTitle(tree: UiTreeNode): String {
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        return texts.maxByOrNull { it.length } ?: ""
    }

    fun extractPopupKeywords(tree: UiTreeNode): List<String> {
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        return texts.filter { it.length >= 2 }.take(5)
    }

    fun assessSafety(appPackage: String, actionText: String, modelSafetyLevel: String): SafetyAssessment {
        if (appPackage in BANKING_PACKAGES) {
            return SafetyAssessment("high", "Banking/payment app detected: $appPackage")
        }

        val lowerAction = actionText.lowercase()
        for (keyword in DANGEROUS_KEYWORDS) {
            if (lowerAction.contains(keyword.lowercase())) {
                return SafetyAssessment("high", "Dangerous keyword detected: $keyword")
            }
        }

        return SafetyAssessment(modelSafetyLevel)
    }

    fun shouldConfirm(safetyLevel: String, safetyMode: SafetyMode): Boolean = when (safetyLevel) {
        "high" -> true
        "medium" -> safetyMode != SafetyMode.TRUST
        "low" -> safetyMode == SafetyMode.CAUTIOUS
        else -> false
    }

    fun detectScreenChange(
        prevNodeCount: Int,
        prevApp: String,
        currNodeCount: Int,
        currApp: String,
    ): ChangeResult {
        if (prevApp != currApp) return ChangeResult.CHANGED

        val diff = kotlin.math.abs(currNodeCount - prevNodeCount)
        val threshold = (prevNodeCount * 0.3).toInt().coerceAtLeast(3)
        if (diff > threshold) return ChangeResult.CHANGED

        if (diff <= 1) return ChangeResult.UNCHANGED

        return ChangeResult.UNCERTAIN
    }

    private fun matchByKeywords(uiTree: UiTreeNode, patterns: List<PopupPattern>): PopupPattern? {
        val allText = collectAllText(uiTree).lowercase()
        return patterns.find { pattern ->
            allText.contains(pattern.title.lowercase()) &&
                pattern.keywords.count { allText.contains(it.lowercase()) } >= (pattern.keywords.size + 1) / 2
        }
    }

    private fun findNodesByKeywords(node: UiTreeNode, keywords: List<String>): List<Pair<String, Rect>> {
        val results = mutableListOf<Pair<String, Rect>>()
        findNodesByKeywordsRecursive(node, keywords, results)
        return results
    }

    private fun findNodesByKeywordsRecursive(node: UiTreeNode, keywords: List<String>, out: MutableList<Pair<String, Rect>>) {
        val nodeText = node.text ?: node.contentDescription
        if (nodeText != null && node.isClickable) {
            for (keyword in keywords) {
                if (nodeText.contains(keyword, ignoreCase = true)) {
                    out.add(nodeText to node.bounds)
                    break
                }
            }
        }
        for (child in node.children) {
            findNodesByKeywordsRecursive(child, keywords, out)
        }
    }

    private fun collectAllText(tree: UiTreeNode): String {
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        return texts.joinToString(" ")
    }

    private fun collectTextNodes(tree: UiTreeNode, out: MutableList<String>) {
        val text = tree.text ?: tree.contentDescription
        if (text != null && text.isNotBlank()) {
            out.add(text)
        }
        for (child in tree.children) {
            collectTextNodes(child, out)
        }
    }
}
