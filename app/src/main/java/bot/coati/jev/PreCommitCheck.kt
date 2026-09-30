package bot.coati.jev

import android.util.Log
import bot.coati.model.CloudModelGateway
import bot.coati.model.GatewayUtils
import bot.coati.model.ModelCallPurpose
import bot.coati.model.StructuredTextRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One Fast-model look at the screen before a commit action (submit, save, export, send...).
 *
 * JEV picks among candidates and weighs the background plan only as a guess, so it can submit a
 * form whose values are not all set yet (board: an order submitted with 2 copies instead of 3, a
 * canvas saved red instead of blue). Undoing a commit costs several steps and an audit rejection;
 * one check before it costs a single call.
 */
class PreCommitCheck(private val gateway: CloudModelGateway) {

    data class Verdict(val ready: Boolean, val missing: String?)

    /** Null when the check could not run; the caller then proceeds as before. */
    suspend fun check(task: String, actionLabel: String, screenshotJpeg: ByteArray): Verdict? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(ModelCallPurpose.PRECOMMIT_GATE) {
                gateway.generateStructuredText(
                    StructuredTextRequest(
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = userPrompt(task, actionLabel),
                        temperature = 0.0,
                        maxOutputTokens = MAX_OUTPUT_TOKENS,
                        imageJpeg = screenshotJpeg,
                        lowLatency = true,
                    ),
                )
            }
        }?.getOrNull()?.let { parse(it.text) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Pre-commit check failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "CoatiJev"
        private const val TIMEOUT_MS = 20_000L
        private const val MAX_OUTPUT_TOKENS = 400

        private val COMMIT = Regex(
            "(?i)\\b(submit|save|send|export|confirm|place order|checkout|pay|apply|publish|post|finish)\\b" +
                "|提交|保存|发送|导出|确认|支付|发布|完成",
        )

        /** Controls that commit when tapped; a tab or switch named "Export" only navigates or toggles. */
        private val COMMIT_TYPES = setOf("button", "text_btn", "item", "card")

        /** Whether tapping this control commits what the screen holds. */
        fun isCommit(label: String, type: String): Boolean = type in COMMIT_TYPES && COMMIT.containsMatchIn(label)

        fun parse(text: String): Verdict? = try {
            val json = JSONObject(GatewayUtils.extractJsonFromText(text))
            Verdict(
                ready = json.getBoolean("ready"),
                missing = json.optString("missing").trim().takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (e: Exception) {
            null
        }

        private val SYSTEM_PROMPT = """
            You check a phone screen before an agent taps a button that commits the user's input.
            Output JSON only, never markdown.
        """.trimIndent()

        private fun userPrompt(task: String, actionLabel: String) = """
            Task: $task
            The agent is about to tap: $actionLabel
            Look at the screenshot. Does it already show every value the task asks to set before this
            tap (selections, counts, toggles, typed text)? Ignore steps that come after this tap.
            Output ONLY: {"ready":true|false,"missing":"what is still wrong, <=20 words, or null"}
        """.trimIndent()
    }
}
