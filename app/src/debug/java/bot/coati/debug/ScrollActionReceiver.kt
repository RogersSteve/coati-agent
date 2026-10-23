package bot.coati.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.action.ScrollProtocol
import bot.coati.coordinates.ModelCoordinateAction
import bot.coati.model.ScreenContext
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** One bounded real ActionEngine call, without a model or agent task. DUMP permission, debug only. */
class ScrollActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("run_id") ?: return
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return
        val expected = intent.getStringExtra("expected_package") ?: return
        val dir = File(context.filesDir, "scroll-actions/$id")
        if (!dir.mkdirs()) return // Never replay or overwrite a run.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            val report = JSONObject().put("run_id", id).put("model_calls", 0)
            try {
                check(!CoatiService.isRunning()) { "stop_overlay_service_before_debug_action" }
                val service = requireNotNull(CoatiAccessibilityService.instance) { "accessibility_not_ready" }
                fun foreground() {
                    val app = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
                    val root = app?.root ?: error("no_app_window")
                    try { check(root.packageName?.toString() == expected) { "unexpected_app_window" } }
                    finally { root.recycle() }
                }
                foreground()
                val swipe = AgentAction.Swipe(intent.getIntExtra("x1", -1), intent.getIntExtra("y1", -1),
                    intent.getIntExtra("x2", -1), intent.getIntExtra("y2", -1), intent.getLongExtra("duration_ms", 600))
                check(swipe.durationMs in 1..2000 && listOf(swipe.x1, swipe.y1, swipe.x2, swipe.y2).all { it >= 0 }) { "invalid_gesture" }
                val action = intent.getStringExtra("direction")?.let { AgentAction.Scroll(it) } ?: swipe
                val bound = if (action is AgentAction.Scroll) {
                    requireNotNull(ScreenContext.capture(service)?.coordinates) { "screenshot_binding_unavailable" }
                } else null
                val actualSwipe = if (action is AgentAction.Scroll) {
                    ModelCoordinateAction.scrollGesture(action, requireNotNull(bound),
                        service.scrollableContainerBounds(ScrollProtocol.isVertical(action.direction)))
                } else swipe
                val session = service.prepareScroll(actualSwipe)
                report.put("semantic_eligible", session != null).put("window_id", session?.windowId)
                    .put("action_id", session?.actionId).put("path", session?.candidate?.path?.let { JSONArray(it) })
                    .put("axis", session?.candidate?.axis?.name)
                val result = withTimeout(10_000) { ActionEngine(service).execute(action, bound) }
                foreground()
                report.put("success", result.success).put("message", result.message).put("failure_code", result.failureCode?.name)
            } catch (e: Exception) {
                report.put("error", e.message?.take(160) ?: e.javaClass.simpleName)
            } finally {
                File(dir, "result.json").writeText(report.toString(2))
                pending.finish()
            }
        }
    }
}
