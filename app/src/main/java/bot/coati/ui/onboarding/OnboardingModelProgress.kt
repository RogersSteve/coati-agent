package bot.coati.ui.onboarding

internal enum class ModelReviewTarget {
    NONE,
    REASONING,
    FAST,
}

internal fun modelReviewTarget(
    selectedTab: Int,
    reasoningVerified: Boolean,
    fastVerified: Boolean,
): ModelReviewTarget = when {
    selectedTab == 0 && reasoningVerified && !fastVerified -> ModelReviewTarget.FAST
    selectedTab == 1 && fastVerified && !reasoningVerified -> ModelReviewTarget.REASONING
    else -> ModelReviewTarget.NONE
}
