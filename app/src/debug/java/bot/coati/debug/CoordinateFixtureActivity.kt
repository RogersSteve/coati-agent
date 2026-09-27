package bot.coati.debug

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.coordinates.DisplaySpace
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.ModelCoordinateAction
import bot.coati.coordinates.PixelBounds
import bot.coati.coordinates.ScreenshotCoordinates
import bot.coati.model.ScreenContext
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** DUMP protected, Debug only. No gateway/configuration/relay/AW dependency. */
class CoordinateFixtureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val session = CoordinateFixtureSession()
    private val instance = UUID.randomUUID().toString()
    private lateinit var surface: Targets
    private var directory: File? = null
    private var runId = ""
    private var stale: ScreenshotCoordinates? = null
    private var service: CoatiAccessibilityService? = null
    private val report = JSONObject().put("version", 1).put("model_calls", 0).put("instance_id", instance)
        .put("actions", JSONArray()).put("artifacts", JSONArray())
    private var resumed = false
    private val firstFailure = FixtureFirstFailure()
    private var step = "create"
    private val diagnosticEvents = JSONArray()
    private var droppedEvents = 0
    private val startedAt = android.os.SystemClock.elapsedRealtime()

    private fun trace(event: String) {
        val current = CoatiAccessibilityService.instance
        val decor = window.decorView
        val item = JSONObject().put("event", event).put("step", step)
            .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - startedAt)
            .put("resumed", resumed).put("window_focus", hasWindowFocus())
            .put("finishing", isFinishing).put("destroyed", isDestroyed)
            .put("attached", decor.isAttachedToWindow).put("shown", decor.isShown)
            .put("window_visibility", decor.windowVisibility)
            .put("width", decor.width).put("height", decor.height)
            .put("product_service_running", CoatiService.isRunning())
            .put("accessibility_present", current != null)
            .put("accessibility_same", service == null || service === current)
        // Retain recent context, while first failure is kept separately forever.
        if (diagnosticEvents.length() == 96) { diagnosticEvents.remove(0); droppedEvents++ }
        diagnosticEvents.put(item)
        if (event == "guard_failed" && !report.has("first_failure_state")) report.put("first_failure_state", item)
        persist()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        active = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = Targets()
        setContentView(surface)
        trace("onCreate")
        // Process/activity recreation must never restart a claimed run.
        if (state != null) { surface.message = "Interrupted: no replay. Export the original report."; return }
        try {
            check(intent.getStringExtra("op") == "run") { "invalid_operation" }
            runId = intent.getStringExtra("run_id").orEmpty()
            require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "invalid_run_id" }
            val dir = File(filesDir, "coordinate-fixture/$runId")
            check(dir.mkdirs()) { "run_already_claimed" }
            directory = dir
            report.put("run_id", runId)
            persist()
            job = scope.launch {
                try { delay(700); session.start(); runActions() }
                catch (e: Exception) { failed(e) }
            }
        } catch (e: Exception) { failed(e) }
    }

    override fun onStart() { super.onStart(); trace("onStart") }
    override fun onResume() { super.onResume(); resumed = true; trace("onResume") }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); trace(if (hasFocus) "focus_gained" else "focus_lost")
    }
    override fun onStop() { trace("onStop"); super.onStop() }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config); trace("configuration_changed")
    }
    override fun onPause() {
        resumed = false
        trace("onPause")
        if (session.phase == "running" || session.phase == "stale_armed" || session.phase == "validating") {
            firstFailure.record("fixture_left_foreground", step); session.fail(); persist()
            job?.cancel()
        }
        super.onPause()
    }
    override fun onDestroy() { trace("onDestroy"); if(active?.get() === this) active = null; scope.cancel(); super.onDestroy() }

    private fun validateStale(requestRun: String?, requestInstance: String?): Boolean {
        if (requestRun != runId || requestInstance != instance || session.phase != "stale_armed") return false
        session.validateOnce()
        job = scope.launch {
            try {
                ready("stale_validation")
                val bound = requireNotNull(stale)
                val current = requireNotNull(DisplaySpaceReader.read(requireNotNull(service))) { "display_unavailable" }
                report.put("stale_current_display", displayJson(current))
                check(current.rotation != bound.display.rotation || current.width != bound.display.width ||
                    current.height != bound.display.height) { "configuration_not_changed" }
                var dispatched = false
                val touchesBefore = surface.events.size
                try {
                    bound.dispatch({ current }) { dispatched = true }
                    error("stale_mapping_accepted")
                } catch (e: IllegalArgumentException) {
                    check(e.message == "stale_screenshot_coordinates" && !dispatched) { "unexpected_rejection" }
                }
                check(surface.events.size == touchesBefore) { "unexpected_touch" }
                report.put("stale_rejection", "stale_screenshot_coordinates").put("stale_dispatches", 0)
                    .put("guarded_execution_entered", dispatched)
                session.finish(); persist()
                surface.message = "PASS: 3 gestures hit; stale screenshot rejected without dispatch."
            } catch (e: Exception) { failed(e) }
        }
        return true
    }

    companion object {
        private var active: java.lang.ref.WeakReference<CoordinateFixtureActivity>? = null
        fun validate(run: String?, instance: String?): Boolean = active?.get()?.validateStale(run, instance) ?: false
    }

    private fun ready(checkpoint: String) {
        step = checkpoint
        val current = CoatiAccessibilityService.instance
        val state = FixtureForegroundState(resumed, hasWindowFocus(), CoatiService.isRunning(),
            current != null, service == null || service === current)
        val failure = state.failure()
        if (failure != null) firstFailure.record(failure, checkpoint)
        trace(if (failure == null) "guard_passed" else "guard_failed")
        check(failure == null) { requireNotNull(failure) }
        service = requireNotNull(current)
    }

    private suspend fun capture(label: String): ScreenContext {
        ready("capture_before_interval:$label")
        delay(1100) // respect the screenshot API interval; no retry
        ready("capture_before_screenshot:$label")
        val screen = requireNotNull(ScreenContext.capture(requireNotNull(service))) { "capture_failed" }
        check(screen.appPackage == packageName) { "wrong_foreground_package" }
        val bound = requireNotNull(screen.coordinates) { "mapping_unavailable" }
        val name = "$label.jpg"
        File(requireNotNull(directory), name).writeBytes(screen.screenshotJpeg)
        report.getJSONArray("artifacts").put(JSONObject().put("name", name).put("sha256",
            MessageDigest.getInstance("SHA-256").digest(screen.screenshotJpeg).joinToString("") { "%02x".format(it) }))
        report.put("${label}_binding", bindingJson(bound))
        persist()
        return screen
    }

    private suspend fun runActions() {
        ready("run_start")
        val engine = ActionEngine(requireNotNull(service))
        for (type in listOf("tap", "longpress", "swipe")) {
            surface.message = "Testing $type. Do not touch the screen."
            val screen = capture(type)
            val bound = requireNotNull(screen.coordinates)
            val start = surface.screenTarget(if (type == "swipe") "swipe_start" else type)
            val end = if (type == "swipe") surface.screenTarget("swipe_end") else start
            // The fixture only derives deterministic INPUT from its measured target. The production
            // resolver below is the sole forward normalized->gesture transformation.
            val a = normalizedInput(start, bound); val b = normalizedInput(end, bound)
            val params: Map<String, Any> = if (type == "swipe") mapOf("x1" to a.first, "y1" to a.second,
                "x2" to b.first, "y2" to b.second, "durationMs" to 500) else mapOf("durationMs" to 1000)
            val action = ModelCoordinateAction.resolve(type, params, if(type == "swipe") null else a.first,
                if(type == "swipe") null else a.second, bound)
            val entry = JSONObject().put("type", type).put("input_start", JSONArray(listOf(a.first,a.second)))
                .put("input_end", JSONArray(listOf(b.first,b.second))).put("target_start", boundsJson(start))
                .put("target_end", boundsJson(end)).put("gesture", gestureJson(action))
            report.getJSONArray("actions").put(entry)
            ready("before_dispatch:$type"); surface.events.clear()
            bound.dispatch({ DisplaySpaceReader.read(requireNotNull(service)) }) {
                ready("inside_dispatch:$type"); session.claim(type); persist()
                val result = withTimeout(10000) { engine.execute(action, bound) }
                entry.put("dispatch_returned", result.success)
            }
            delay(100) // allow delivered touch events to finish on the target UI queue
            val touches = surface.events.toList()
            entry.put("touches", JSONArray(touches.map { JSONObject().put("kind",it.kind).put("x",it.x).put("y",it.y).put("time",it.time) }))
            val hit = CoordinateFixtureChecks.hit(type, touches, start, end)
            entry.put("hit", hit); persist()
            check(entry.optBoolean("dispatch_returned")) { "gesture_failed" }
            check(hit) { "target_missed" }
        }
        surface.message = "Capturing stale-coordinate checkpoint."
        stale = capture("stale").coordinates
        val bound = requireNotNull(stale)
        val input = normalizedInput(surface.screenTarget("tap"), bound)
        val oldAction = ModelCoordinateAction.resolve("tap", emptyMap(), input.first, input.second, bound)
        report.put("stale_input", JSONArray(listOf(input.first,input.second))).put("stale_gesture",gestureJson(oldAction))
        session.arm(); persist()
        surface.message = "3 hits. Rotate device manually, then send validate-stale with this run and instance."
    }

    private fun normalizedInput(b: PixelBounds, bound: ScreenshotCoordinates): Pair<Int,Int> =
        (b.left + b.right) / 2 * 1000 / bound.rawWidth to ((b.top + b.bottom) / 2 * 1000 / bound.rawHeight)

    private fun persist() {
        report.put("phase", session.phase).put("dispatches", session.dispatches)
            .put("diagnostics_version", 1).put("lifecycle_events", diagnosticEvents)
            .put("dropped_events", droppedEvents)
        firstFailure.code?.let { report.put("error", it).put("failure_step", firstFailure.step) }
        directory?.let {
            val temporary = File(it, "report.pending")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(File(it, "report.json"))) { "report_write_failed" }
        }
    }
    private fun failed(e: Exception) {
        session.fail()
        val allowed = setOf("invalid_operation","invalid_run_id","run_already_claimed","fixture_not_foreground",
            "fixture_not_resumed","fixture_window_unfocused",
            "stop_coati_overlay_service_first","accessibility_not_ready","accessibility_instance_changed","display_unavailable",
            "configuration_not_changed","stale_mapping_accepted","unexpected_rejection","unexpected_touch","capture_failed",
            "wrong_foreground_package","mapping_unavailable","gesture_failed","target_missed","stale_screenshot_coordinates")
        val code = e.message?.takeIf { it in allowed } ?: if (e is CancellationException) "cancelled" else "fixture_failed"
        firstFailure.record(code, step); persist(); surface.message = "STOP: ${firstFailure.code}. No retries. Export report."
    }
    private fun boundsJson(b: PixelBounds) = JSONArray(listOf(b.left,b.top,b.right,b.bottom))
    private fun displayJson(d: DisplaySpace) = JSONObject().put("width",d.width).put("height",d.height)
        .put("rotation",d.rotation).put("display_id",d.displayId).put("window",boundsJson(d.windowBounds))
        .put("app",boundsJson(d.appBounds)).put("insets",boundsJson(d.insets)).put("visible_insets",boundsJson(d.visibleInsets))
        .put("density_dpi",d.densityDpi).put("window_id",d.windowId).put("package",d.packageName).put("magnification",d.magnification)
    private fun bindingJson(b: ScreenshotCoordinates) = JSONObject().put("display",displayJson(b.display))
        .put("raw",JSONArray(listOf(b.rawWidth,b.rawHeight))).put("encoded",JSONArray(listOf(b.imageWidth,b.imageHeight)))
        .put("crop","none").put("model_image_sent",false)
        .put("service_resources",JSONArray(listOf(requireNotNull(service).resources.displayMetrics.widthPixels,
            requireNotNull(service).resources.displayMetrics.heightPixels)))
    private fun gestureJson(a: AgentAction): JSONArray = when(a) {
        is AgentAction.Tap -> JSONArray(listOf(a.x,a.y))
        is AgentAction.LongPress -> JSONArray(listOf(a.x,a.y,a.durationMs))
        is AgentAction.Swipe -> JSONArray(listOf(a.x1,a.y1,a.x2,a.y2,a.durationMs))
        else -> error("unexpected_action")
    }

    private inner class Targets : View(this@CoordinateFixtureActivity) {
        val events = mutableListOf<FixtureTouch>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var message = "Preparing no-model fixture"; set(value) { field=value; invalidate() }
        init { contentDescription = "Local coordinate test targets"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        private fun local(name: String): PixelBounds {
            val cx = when(name) { "swipe_start" -> width/5; "swipe_end" -> width*4/5; else -> width/2 }
            val cy = when(name) { "tap" -> height*92/100; "longpress" -> height/3; else -> height*3/5 }
            val hx = width/12; val hy = height/60
            return PixelBounds(cx-hx,cy-hy,cx+hx,cy+hy)
        }
        fun screenTarget(name: String): PixelBounds {
            val origin=IntArray(2); getLocationOnScreen(origin)
            val b=local(name)
            return PixelBounds(b.left+origin[0],b.top+origin[1],b.right+origin[0],b.bottom+origin[1])
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            paint.color=Color.BLACK; paint.textSize=18*resources.displayMetrics.scaledDensity
            canvas.drawText(message.take(78),20f,60f,paint)
            for(name in listOf("tap","longpress","swipe_start","swipe_end")) {
                val b=local(name); paint.color=Color.rgb(0,100,160)
                canvas.drawRect(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat(),paint)
                paint.color=Color.BLACK; canvas.drawText(name,b.left.toFloat(),b.top-8f,paint)
            }
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility") // Test recorder consumes raw strokes, not a product button.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(events.size < 2000) events.add(FixtureTouch(event.actionMasked,event.rawX,event.rawY,event.eventTime))
            return true
        }
    }
}
