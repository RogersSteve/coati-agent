package bot.coati.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import bot.coati.action.ActionEngine
import bot.coati.action.AgentAction
import bot.coati.coordinates.DisplaySpaceReader
import bot.coati.coordinates.ModelCoordinateAction
import bot.coati.coordinates.ScreenshotCoordinates
import bot.coati.model.ScreenContext
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native scroll offsets are observations, never updated from the requested direction. Debug only. */
class ScrollFixtureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val session = ScrollFixtureSession()
    private val firstFailure = FixtureFirstFailure()
    private var resumed = false
    private var dir: File? = null
    private var run = ""
    private val instance = UUID.randomUUID().toString()
    private var service: CoatiAccessibilityService? = null
    private var bound: ScreenshotCoordinates? = null
    private val touches = mutableListOf<FixtureTouch>()
    private var recording = false
    private lateinit var viewport: View
    private lateinit var items: LinearLayout
    private val report = JSONObject().put("version",1).put("model_calls",0).put("instance_id",instance)
        .put("actions",JSONArray()).put("artifacts",JSONArray())
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text="Preparing bounded scroll fixture" })
        if (saved != null) return // Never replay on recreation.
        active = java.lang.ref.WeakReference(this)
        try {
            check(intent.getStringExtra("op") == "run") { "invalid_operation" }
            run=intent.getStringExtra("run_id").orEmpty()
            check(run.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "invalid_run_id" }
            val target=File(filesDir,"scroll-fixture/$run")
            check(target.mkdirs()) { "run_already_claimed" }; dir=target
            report.put("run_id",run); persist()
            scope.launch { try { execute() } catch(e:Exception) { fail(e) } }
        } catch(e:Exception) { fail(e) }
    }
    override fun onResume() { super.onResume(); resumed=true }
    override fun onPause() {
        resumed=false
        if(session.phase in setOf("running","stale_armed","validating")) {
            fail(IllegalStateException("fixture_left_foreground")); scope.cancel()
        }
        super.onPause()
    }
    override fun onDestroy() { if(active?.get() === this) active=null; scope.cancel(); super.onDestroy() }
    override fun dispatchTouchEvent(e:MotionEvent):Boolean {
        if(recording && touches.size < 2000) touches.add(FixtureTouch(e.actionMasked,e.rawX,e.rawY,e.eventTime))
        return super.dispatchTouchEvent(e)
    }
    private fun ready() {
        val current=CoatiAccessibilityService.instance
        val state=FixtureForegroundState(resumed,hasWindowFocus(),CoatiService.isRunning(),current!=null,service==null || service===current)
        report.put("foreground",JSONObject().put("resumed",resumed).put("focus",hasWindowFocus())
            .put("product_service",CoatiService.isRunning()).put("accessibility",current!=null))
        check(state.failure()==null) { state.failure()!! }; service=requireNotNull(current)
    }
    private suspend fun laidOut() {
        withTimeout(5000) { while(!resumed || !hasWindowFocus() || !viewport.isLaidOut || viewport.width==0 || viewport.height==0) delay(16) }
        ready()
    }
    private fun page(vertical:Boolean) {
        items=LinearLayout(this).apply { orientation=if(vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        repeat(100) { index -> items.addView(TextView(this).apply {
            text="ITEM $index"; textSize=24f; setTextColor(android.graphics.Color.BLACK)
            setBackgroundColor(if(index%2==0) 0xffdddddd.toInt() else 0xffffffff.toInt())
        },LinearLayout.LayoutParams(if(vertical) -1 else 100,if(vertical) 100 else -1)) }
        viewport=if(vertical) ScrollView(this).apply { isFillViewport=true; addView(items) }
            else HorizontalScrollView(this).apply { isFillViewport=true; addView(items) }
        setContentView(viewport)
    }
    private fun offset(vertical:Boolean)=if(vertical) viewport.scrollY else viewport.scrollX
    private fun visible():List<Int> = (0 until items.childCount).filter { i ->
        val rect=Rect(); items.getChildAt(i).getGlobalVisibleRect(rect) && rect.width()>0 && rect.height()>0
    }
    private suspend fun settled(vertical:Boolean) {
        withTimeout(5000) {
            var previous=offset(vertical); var same=0
            while(same<4) { delay(100); ready(); val now=offset(vertical); same=if(now==previous)same+1 else 0; previous=now }
        }
    }
    private suspend fun capture(label:String):ScreenshotCoordinates {
        delay(1100) // Screenshot API interval, not a retry or readiness assumption.
        ready()
        val screen=requireNotNull(ScreenContext.capture(requireNotNull(service))) { "capture_failed" }
        check(screen.appPackage==packageName) { "wrong_foreground_package" }
        val b=requireNotNull(screen.coordinates) { "mapping_unavailable" }
        val file=File(requireNotNull(dir),"$label.jpg"); file.writeBytes(screen.screenshotJpeg)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(screen.screenshotJpeg).joinToString("") { "%02x".format(it) }
        report.getJSONArray("artifacts").put(JSONObject().put("name",file.name).put("sha256",hash))
        report.put(label+"_binding",JSONObject().put("raw",JSONArray(listOf(b.rawWidth,b.rawHeight)))
            .put("encoded",JSONArray(listOf(b.imageWidth,b.imageHeight))).put("rotation",b.display.rotation)
            .put("display_id",b.display.displayId).put("window_id",b.display.windowId).put("display",b.display.toString()))
        persist(); return b
    }
    private suspend fun execute() {
        page(true); laidOut(); session.start(); persist()
        for ((index,direction) in ScrollFixtureChecks.directions.withIndex()) {
            val vertical=index<2
            page(vertical); laidOut()
            // Deterministic middle position only BEFORE capture; never mutate offset after dispatch.
            viewport.scrollTo(if(vertical)0 else 4000,if(vertical)4000 else 0)
            settled(vertical)
            val b=capture(direction+"_before"); bound=b
            val params=mutableMapOf<String,Any>("direction" to direction)
            if(index%2==1) params["amount"]=501
            val action=ModelCoordinateAction.resolve("scroll",params,null,null,b) as AgentAction.Scroll
            val gesture=ModelCoordinateAction.scrollGesture(action,b)
            val before=offset(vertical); val visibleBefore=visible(); check(visibleBefore.isNotEmpty()) { "no_visible_items" }
            val entry=JSONObject().put("direction",direction).put("default_amount",index%2==0).put("amount",action.amount)
                .put("gesture",JSONArray(listOf(gesture.x1,gesture.y1,gesture.x2,gesture.y2)))
                .put("before_offset",before).put("before_visible",JSONArray(visibleBefore))
            report.getJSONArray("actions").put(entry)
            touches.clear(); recording=true
            b.dispatch({DisplaySpaceReader.read(requireNotNull(service))}) {
                ready(); session.claim(direction); persist()
                entry.put("dispatch_returned",withTimeout(10000) { ActionEngine(requireNotNull(service)).execute(action,b) }.success)
            }
            settled(vertical); recording=false
            val after=offset(vertical); val visibleAfter=visible()
            entry.put("after_offset",after).put("after_visible",JSONArray(visibleAfter))
                .put("touches",JSONArray(touches.map { JSONObject().put("kind",it.kind).put("x",it.x).put("y",it.y).put("time",it.time) }))
            val movement=visibleAfter.isNotEmpty() && ScrollFixtureChecks.movement(direction,before,after,visibleBefore.first(),visibleAfter.first())
            val endpoints=ScrollFixtureChecks.endpoints(touches,gesture)
            entry.put("content_moved",movement).put("endpoints_match",endpoints); persist()
            check(entry.getBoolean("dispatch_returned") && movement && endpoints) { "scroll_assertion_failed" }
            capture(direction+"_after")
        }
        val b=requireNotNull(bound); var forbidden=0
        val rejected=JSONArray()
        for(value in listOf<Any>(0,-1,1.5,"500",b.rawHeight,Int.MAX_VALUE)) {
            var rejection=false
            try {
                ModelCoordinateAction.resolve("scroll",mapOf("direction" to "DOWN","amount" to value),null,null,b)
                forbidden++ // trap: never dispatch an invalid action even if validation regresses
            } catch(_:IllegalArgumentException) { rejection=true }
            rejected.put(rejection)
        }
        report.put("invalid_rejected",rejected).put("invalid_execution_entries",forbidden);persist()
        check(forbidden==0) { "invalid_amount_accepted" }
        bound=capture("stale"); session.arm(); persist()
        title="4 scroll checks passed. Rotate, then validate-stale."
    }
    private fun validate(runId:String?,instanceId:String?):Boolean {
        if(runId!=run || instanceId!=instance || session.phase!="stale_armed") return false
        session.validate()
        scope.launch { try {
            ready(); val b=requireNotNull(bound); val now=requireNotNull(DisplaySpaceReader.read(requireNotNull(service)))
            check(now.rotation!=b.display.rotation || now.width!=b.display.width || now.height!=b.display.height) { "configuration_not_changed" }
            var entered=false; var rejected=false
            try { b.dispatch({now}) { entered=true } } catch(e:IllegalArgumentException) { rejected=e.message=="stale_screenshot_coordinates" }
            report.put("stale_execution_entered",entered).put("stale_rejected",rejected).put("stale_dispatches",0)
                .put("stale_current_display",now.toString())
            check(rejected && !entered) { "stale_mapping_accepted" }
            session.finish();persist();title="PASS: scroll fixture"
        } catch(e:Exception) { fail(e) } }
        return true
    }
    private fun fail(e:Exception) {
        recording=false;session.fail()
        val safe=setOf("invalid_operation","invalid_run_id","run_already_claimed","fixture_left_foreground",
            "fixture_not_resumed","fixture_window_unfocused","stop_coati_overlay_service_first","accessibility_not_ready",
            "accessibility_instance_changed","capture_failed","wrong_foreground_package","mapping_unavailable",
            "no_visible_items","scroll_assertion_failed","invalid_amount_accepted","configuration_not_changed","stale_mapping_accepted")
        firstFailure.record(e.message?.takeIf { it in safe } ?: if(e is TimeoutCancellationException) "bounded_wait_timeout" else "fixture_failed",session.phase)
        report.put("error",firstFailure.code);persist();title="STOP: ${firstFailure.code}"
    }
    private fun persist() {
        report.put("phase",session.phase).put("dispatches",session.dispatches)
        dir?.let { val tmp=File(it,"report.pending");tmp.writeText(report.toString(2));check(tmp.renameTo(File(it,"report.json"))) }
    }
    companion object {
        private var active:java.lang.ref.WeakReference<ScrollFixtureActivity>?=null
        fun validate(run:String?,instance:String?)=active?.get()?.validate(run,instance) ?: false
    }
}

class ScrollFixtureControlReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val accepted=intent.getStringExtra("op")=="validate-stale" && ScrollFixtureActivity.validate(intent.getStringExtra("run_id"),intent.getStringExtra("instance_id"))
        if(isOrderedBroadcast)setResult(if(accepted)0 else 1,if(accepted)"accepted" else "rejected",null)
    }
}
