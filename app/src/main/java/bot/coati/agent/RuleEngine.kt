package bot.coati.agent

import android.graphics.Rect
import bot.coati.service.UiTreeNode

enum class UiTreeQuality { HIGH, MEDIUM, LOW }

enum class ChangeResult { CHANGED, UNCHANGED, UNCERTAIN }

data class PopupDetection(
    val dismissText: String,
    val bounds: Rect,
)

data class SafetyAssessment(
    val level: String,
    val reason: String? = null,
)

object RuleEngine {

    private val DISMISS_KEYWORDS = listOf(
        "关闭", "跳过", "取消", "以后再说", "不了", "暂不", "拒绝", "下次再说",
        "Close", "Skip", "Cancel", "Dismiss", "Not now", "No thanks",
        "Later", "Deny", "Got it", "OK", "I know",
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

    fun detectKnownPopup(uiTree: UiTreeNode?): PopupDetection? {
        if (uiTree == null) return null
        return findDismissNode(uiTree)
    }

    private fun findDismissNode(node: UiTreeNode): PopupDetection? {
        val nodeText = node.text ?: node.contentDescription
        if (nodeText != null && node.isClickable) {
            for (keyword in DISMISS_KEYWORDS) {
                if (nodeText.contains(keyword, ignoreCase = true)) {
                    return PopupDetection(dismissText = nodeText, bounds = node.bounds)
                }
            }
        }
        for (child in node.children) {
            val result = findDismissNode(child)
            if (result != null) return result
        }
        return null
    }

    fun assessSafety(appPackage: String, actionText: String, modelSafetyLevel: String): SafetyAssessment {
        // Force high for banking/payment apps
        if (appPackage in BANKING_PACKAGES) {
            return SafetyAssessment("high", "Banking/payment app detected: $appPackage")
        }

        // Escalate on dangerous keywords
        val lowerAction = actionText.lowercase()
        for (keyword in DANGEROUS_KEYWORDS) {
            if (lowerAction.contains(keyword.lowercase())) {
                return SafetyAssessment("high", "Dangerous keyword detected: $keyword")
            }
        }

        return SafetyAssessment(modelSafetyLevel)
    }

    fun detectScreenChange(
        prevNodeCount: Int,
        prevApp: String,
        currNodeCount: Int,
        currApp: String,
    ): ChangeResult {
        // Different app → definitely changed
        if (prevApp != currApp) return ChangeResult.CHANGED

        // Big node count difference → changed
        val diff = kotlin.math.abs(currNodeCount - prevNodeCount)
        val threshold = (prevNodeCount * 0.3).toInt().coerceAtLeast(3)
        if (diff > threshold) return ChangeResult.CHANGED

        // Very similar → unchanged
        if (diff <= 1) return ChangeResult.UNCHANGED

        return ChangeResult.UNCERTAIN
    }
}
