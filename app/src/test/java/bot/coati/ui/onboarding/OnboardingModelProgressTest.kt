package bot.coati.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingModelProgressTest {

    @Test
    fun `reasoning tab links to Fast only when Reasoning alone is verified`() {
        assertEquals(ModelReviewTarget.FAST, modelReviewTarget(0, reasoningVerified = true, fastVerified = false))
        assertEquals(ModelReviewTarget.NONE, modelReviewTarget(0, reasoningVerified = false, fastVerified = true))
        assertEquals(ModelReviewTarget.NONE, modelReviewTarget(0, reasoningVerified = true, fastVerified = true))
    }

    @Test
    fun `Fast tab links to Reasoning only when Fast alone is verified`() {
        assertEquals(ModelReviewTarget.REASONING, modelReviewTarget(1, reasoningVerified = false, fastVerified = true))
        assertEquals(ModelReviewTarget.NONE, modelReviewTarget(1, reasoningVerified = true, fastVerified = false))
        assertEquals(ModelReviewTarget.NONE, modelReviewTarget(1, reasoningVerified = true, fastVerified = true))
    }
}
