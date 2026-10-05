package bot.coati.agent

import bot.coati.data.SafetyMode
import bot.coati.service.UiTreeNode

enum class UiTreeQuality { HIGH, MEDIUM, LOW }

enum class ChangeResult { CHANGED, UNCHANGED, UNCERTAIN }

data class SafetyAssessment(
    val level: String,
    val reason: String? = null,
)

object RuleEngine {

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

    private val CONTEXT_FREE_ACTIONS = setOf("screenshot", "wait")

    fun assessQuality(nodeCount: Int, textNodeCount: Int): UiTreeQuality {
        return when {
            nodeCount >= 20 && textNodeCount >= 5 -> UiTreeQuality.HIGH
            nodeCount >= 5 -> UiTreeQuality.MEDIUM
            else -> UiTreeQuality.LOW
        }
    }

    fun buildScreenSignature(tree: UiTreeNode?): String {
        if (tree == null) return ""
        val texts = mutableListOf<String>()
        collectTextNodes(tree, texts)
        return texts.sorted().joinToString("|").hashCode().toString(16)
    }

    fun assessSafety(
        appPackage: String,
        actionName: String,
        actionContext: String,
        modelSafetyLevel: String,
    ): SafetyAssessment {
        if (appPackage in BANKING_PACKAGES) {
            return SafetyAssessment("high", "Banking/payment app detected: $appPackage")
        }

        // Screenshot and wait cannot perform the dangerous operation mentioned in a task/status
        // description. Scanning their inherited context creates sticky high-risk false positives
        // after the real destructive action has already completed.
        if (actionName.lowercase() !in CONTEXT_FREE_ACTIONS) {
            val lowerAction = "$actionName: $actionContext".lowercase()
            for (keyword in DANGEROUS_KEYWORDS) {
                if (lowerAction.contains(keyword.lowercase())) {
                    return SafetyAssessment("high", "Dangerous keyword detected: $keyword")
                }
            }
        }

        return SafetyAssessment(modelSafetyLevel)
    }

    fun shouldConfirm(safetyLevel: String, safetyMode: SafetyMode): Boolean = when (safetyLevel) {
        "high" -> safetyMode != SafetyMode.TRUST
        "medium" -> safetyMode == SafetyMode.CAUTIOUS
        "low" -> false
        // Unknown or malformed risk must fail closed instead of being auto-approved.
        else -> true
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
