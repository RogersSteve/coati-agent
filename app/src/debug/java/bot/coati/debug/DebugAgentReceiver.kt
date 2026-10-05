package bot.coati.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import bot.coati.agent.AgentLoop
import bot.coati.agent.TaskRunOptions
import bot.coati.data.ConfigStore
import bot.coati.data.SafetyMode
import android.graphics.BitmapFactory
import bot.coati.coordinates.PixelBounds
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.model.ResilientModelGateway
import bot.coati.perception.OcrEngines
import bot.coati.perception.OcrTextRefiner
import bot.coati.perception.ParsedScreen
import bot.coati.perception.VisionScreenParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import bot.coati.service.UiTreeNode
import bot.coati.ui.onboarding.OnboardingViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Debug-only control for device benchmarks: configure models and start or stop a task without
 * touching the UI. Protected by android.permission.DUMP, so only adb shell can send it. A full model
 * configuration also marks onboarding complete, so a fresh install can be benchmarked unattended
 * (overlay and accessibility permissions are still granted from adb).
 *
 * adb shell am broadcast -a bot.coati.debug.CONFIGURE_AGENT -n bot.coati/.debug.DebugAgentReceiver \
 *   --es provider openai_compatible --es endpoint https://relay.example --es api_key KEY \
 *   --es reasoning_model m1 --es fast_model m2 --es safety TRUST --es jev_key KEY [--ez verify false] [--ez withhold_tree true]
 * (an empty jev_key removes it, so every step uses the Reasoning model)
 * adb shell am broadcast -a bot.coati.debug.RUN_TASK -n bot.coati/.debug.DebugAgentReceiver --es task "..." \
 *   [--es run_id ID] [--ei max_steps N]
 * (when the task ends, files/task_result.json holds its result; see bot.coati.agent.TaskResult)
 * adb shell am broadcast -a bot.coati.debug.STOP_TASK -n bot.coati/.debug.DebugAgentReceiver
 * adb shell am broadcast -a bot.coati.debug.DUMP_TREE -n bot.coati/.debug.DebugAgentReceiver
 * adb push shot.jpg /sdcard/Android/data/bot.coati/files/ocr_bench/
 * adb shell am broadcast -a bot.coati.debug.OCR_BENCH -n bot.coati/.debug.DebugAgentReceiver --ei repeat 3
 * adb shell am broadcast -a bot.coati.debug.DUMP_NODES -n bot.coati/.debug.DebugAgentReceiver
 *   (then: adb shell run-as bot.coati cat files/ui_nodes.json)
 */
class DebugAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIGURE -> configure(context, intent)
            ACTION_RUN_TASK -> {
                val task = intent.getStringExtra("task")?.takeIf { it.isNotBlank() } ?: return
                val options = runCatching {
                    TaskRunOptions(
                        runId = intent.getStringExtra("run_id"),
                        maxSteps = intent.getIntExtra("max_steps", AgentLoop.MAX_STEPS),
                    )
                }.getOrElse {
                    Log.w(TAG, "Debug task rejected: ${it.message}")
                    return
                }
                val started = CoatiService.executeTask(task, options)
                Log.i(TAG, "Debug task started=$started runId=${options.runId} maxSteps=${options.maxSteps} task=$task")
            }
            ACTION_STOP_TASK -> Log.i(TAG, "Debug task stopped=${CoatiService.stopTask()}")
            ACTION_DUMP_TREE -> dumpTree()
            ACTION_OCR_BENCH -> ocrBench(context, intent)
            ACTION_PARSE_BENCH -> parseBench(context, intent)
            ACTION_DUMP_NODES -> dumpNodes(context)
        }
    }

    /**
     * Runs on-device OCR over JPEG/PNG inputs. External app storage is the default;
     * input_storage=internal selects File(context.filesDir, "ocr_bench") for run-as uploads.
     * and logs per-image latency and the recognized lines. Each image runs `repeat` times; model loading
     * is logged separately as init ms, and the first run of each new input size includes GPU
     * pipeline setup. OCR runs on the Vulkan GPU only; without one the bench logs that OCR is
     * unavailable. The work outlives the broadcast
     * (a receiver may only run for about a minute), so start Coati's service first to keep the
     * process alive until "OCR bench done" is logged.
     */
    private fun ocrBench(context: Context, intent: Intent) {
        val repeat = intent.getIntExtra("repeat", 3).coerceAtLeast(1)
        val inputStorage = intent.getStringExtra("input_storage") ?: "external"
        CoroutineScope(Dispatchers.Default).launch {
            // Validate every input before the expensive Vulkan initialization.
            val input = try {
                val dir = DebugOcrInputs.directory(inputStorage, context.filesDir) {
                    context.getExternalFilesDir("ocr_bench")
                }
                val files = DebugOcrInputs.validate(dir)
                Log.i(TAG, "OCR bench input validated: storage=$inputStorage dir=$dir names=${files.map { it.name }} repeat=$repeat")
                dir to files
            } catch (e: Exception) {
                Log.w(TAG, "OCR bench input failed: storage=$inputStorage", e)
                Log.i(TAG, "OCR bench done")
                return@launch
            }
            val initStarted = System.nanoTime()
            val ocr = OcrEngines.create(context)
            if (ocr == null) {
                Log.w(TAG, "OCR bench: OCR unavailable (no usable Vulkan GPU or native init failed)")
                Log.i(TAG, "OCR bench done")
                return@launch
            }
            Log.i(TAG, "OCR bench config: engine=ncnn-vulkan PP-OCRv5 mobile, init ms=${(System.nanoTime() - initStarted) / 1_000_000}")
            try {
                val (dir, files) = input
                Log.i(TAG, "OCR bench: ${files.size} images in $dir, repeat=$repeat")
                files.forEach { file ->
                    val bytes = file.readBytes()
                    val runs = (1..repeat).map {
                        val started = System.nanoTime()
                        val lines = ocr.recognize(bytes, 1f)
                        ((System.nanoTime() - started) / 1_000_000) to lines
                    }
                    val lines = runs.last().second
                    Log.i(TAG, "OCR ${file.name}: ms=${runs.map { it.first }} lines=${lines.size}")
                    lines.forEach { line ->
                        Log.i(TAG, "OCR ${file.name} | ${line.text} | conf=${"%.3f".format(Locale.US, line.confidence)} | ${line.bounds}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OCR bench failed", e)
            } finally {
                ocr.close()
                Log.i(TAG, "OCR bench done")
            }
        }
    }

    private fun configure(context: Context, intent: Intent) {
        val store = ConfigStore(context)
        val provider = intent.getStringExtra("provider")
        val apiKey = intent.getStringExtra("api_key")
        val endpoint = intent.getStringExtra("endpoint").orEmpty()
        val reasoningModel = intent.getStringExtra("reasoning_model")
        if (provider != null && apiKey != null && reasoningModel != null) {
            store.saveRoleConfig(ConfigStore.ROLE_REASONING, provider, apiKey, endpoint, reasoningModel)
            store.saveFastSameAsReasoning(true)
            store.saveFastModelName(intent.getStringExtra("fast_model") ?: reasoningModel)
            completeOnboarding(context)
        }
        intent.getStringExtra("jev_key")?.let { key ->
            if (key.isBlank()) store.clearJevConfig()
            else store.saveJevConfig(
                key,
                intent.getStringExtra("jev_endpoint")?.takeIf { it.isNotBlank() } ?: ConfigStore.DEFAULT_JEV_ENDPOINT,
                intent.getStringExtra("jev_model")?.takeIf { it.isNotBlank() } ?: ConfigStore.DEFAULT_JEV_MODEL,
            )
        }
        if (intent.hasExtra("ocr")) store.setOcrEnabled(intent.getBooleanExtra("ocr", true))
        if (intent.hasExtra("verify")) BenchmarkSettings.setVerifyEnabled(context, intent.getBooleanExtra("verify", true))
        if (intent.hasExtra("withhold_tree")) {
            BenchmarkSettings.setTreeWithheld(context, intent.getBooleanExtra("withhold_tree", false))
        }
        intent.getStringExtra("safety")?.let { name ->
            runCatching { SafetyMode.valueOf(name.uppercase()) }.getOrNull()?.let(store::setSafetyMode)
        }
        if (CoatiService.isRunning()) CoatiService.reloadModelConfiguration(context)
        Log.i(TAG, "Debug agent configured: safety=${store.getSafetyMode()}, jev=${store.getJevConfig() != null}, ocr=${store.isOcrEnabled()}, verify=${BenchmarkSettings.verifyEnabled(context)}, treeWithheld=${BenchmarkSettings.treeWithheld(context)}")
    }

    /** Logs what the accessibility service can read right now: every window and the active tree's text. */
    private fun dumpTree() {
        val a11y = CoatiAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "Dump: accessibility service not connected")
            return
        }
        a11y.windows.forEach { window ->
            Log.i(TAG, "Dump window: type=${window.type} layer=${window.layer} title=${window.title} " +
                "package=${window.root?.packageName} active=${window.isActive}")
        }
        val tree = a11y.getUiTree()
        if (tree == null) {
            Log.i(TAG, "Dump tree: no active window root")
            return
        }
        val texts = mutableListOf<String>()
        fun collect(node: UiTreeNode) {
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.forEach(texts::add)
            node.children.forEach(::collect)
        }
        collect(tree)
        Log.i(TAG, "Dump tree: ${texts.size} labels: ${texts.joinToString(" | ")}")
    }

    /**
     * Visible nodes of the active window as JSON, for benchmark checks. Through Coati's own service:
     * `uiautomator dump` suppresses accessibility services while it runs, and on the test board
     * Coati's screenshots then failed with error 1 until its process restarted.
     */
    private fun dumpNodes(context: Context) {
        val out = File(context.filesDir, NODES_FILE)
        // The topmost app window, not the active one: right after a task Coati's own overlay can be
        // the active window, and the app under it is what the checks read.
        val root = CoatiAccessibilityService.instance?.windows
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.maxByOrNull { it.layer }?.root
        val nodes = JSONArray()
        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                nodes.put(JSONObject().apply {
                    put("text", node.text?.toString().orEmpty())
                    put("desc", node.contentDescription?.toString().orEmpty())
                    put("cls", node.className?.toString()?.substringAfterLast('.').orEmpty())
                    put("checked", node.isChecked)
                    put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                })
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        root?.let(::visit)
        out.writeText(JSONObject().put("ok", root != null).put("nodes", nodes).toString())
        Log.i(TAG, "Dump nodes: ${nodes.length()} visible nodes")
    }

    private fun completeOnboarding(context: Context) {
        val dataStore = EntryPointAccessors
            .fromApplication(context.applicationContext, DebugAgentEntryPoint::class.java)
            .dataStore()
        // A tiny local write; blocking keeps the broadcast ordered before the next adb command.
        runBlocking { dataStore.edit { it[OnboardingViewModel.KEY_ONBOARDING_COMPLETE] = true } }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DebugAgentEntryPoint {
        fun dataStore(): DataStore<Preferences>
    }

    /**
     * Parses every image in getExternalFilesDir("parse_bench") with the app's own vision parser
     * (Fast model) and GPU OCR, and writes the plain vision parse, the OCR lines and the OCR-repaired
     * parse as JSON to parse_bench/out/<image>_r<n>.json, in display pixels. Extras: `repeat`
     * (default 2), `display_width`/`display_height` (default 1080x1920), `status_bar` (default 24).
     * Like OCR_BENCH, start Coati's service first to keep the process alive.
     */
    private fun parseBench(context: Context, intent: Intent) {
        val repeat = intent.getIntExtra("repeat", 2).coerceAtLeast(1)
        val displayWidth = intent.getIntExtra("display_width", 1080)
        val displayHeight = intent.getIntExtra("display_height", 1920)
        val statusBar = intent.getIntExtra("status_bar", 24)
        CoroutineScope(Dispatchers.Default).launch {
            val ocr = OcrEngines.create(context)
            try {
                val store = ConfigStore(context)
                val config = store.getFastConfig() ?: error("no Fast model configured")
                val provider = ModelProviders.findById(config.providerId) ?: error("unknown provider ${config.providerId}")
                val gateway = ResilientModelGateway(
                    GatewayFactory.create(provider, GatewayFactory.createRuntimeClient(), config.apiKey, config.endpoint, config.modelName),
                )
                val parser = VisionScreenParser(gateway)
                val dir = context.getExternalFilesDir("parse_bench") ?: error("no external files dir")
                val out = File(dir, "out").apply { mkdirs() }
                val files = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }.orEmpty().sortedBy { it.name }
                Log.i(TAG, "PARSE bench: ${files.size} images, repeat=$repeat, ocr=${ocr != null}")
                for (file in files) {
                    val jpeg = file.readBytes()
                    val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        .also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it) }
                    val scale = displayWidth.toFloat() / size.outWidth
                    repeat(repeat) { run ->
                        val t0 = System.currentTimeMillis()
                        val vision = parser.parse(jpeg, size.outWidth, size.outHeight, "bench") { x, y ->
                            minOf(x * displayWidth / 1000, displayWidth - 1) to minOf(y * displayHeight / 1000, displayHeight - 1)
                        }.getOrElse {
                            Log.w(TAG, "PARSE ${file.name} r$run: vision failed: ${it.message}")
                            return@repeat
                        }
                        val t1 = System.currentTimeMillis()
                        val lines = ocr?.recognize(jpeg, scale).orEmpty()
                        val t2 = System.currentTimeMillis()
                        val refined = OcrTextRefiner.refine(vision, lines, statusBar)
                        val json = JSONObject()
                            .put("vision", elementsJson(vision))
                            .put("refined", elementsJson(refined))
                            .put("ocr", JSONArray(lines.map { JSONObject().put("text", it.text).put("conf", it.confidence.toDouble()).put("bounds", boundsJson(it.bounds)) }))
                            .put("vision_ms", t1 - t0)
                            .put("ocr_ms", t2 - t1)
                        File(out, "${file.nameWithoutExtension}_r$run.json").writeText(json.toString())
                        Log.i(TAG, "PARSE ${file.name} r$run: vision ${t1 - t0}ms (${vision.elements.size} els), ocr ${t2 - t1}ms (${lines.size} lines), refined ${refined.elements.size} els")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PARSE bench failed", e)
            } finally {
                ocr?.close()
                Log.i(TAG, "PARSE bench done")
            }
        }
    }

    private fun elementsJson(screen: ParsedScreen) = JSONArray(screen.elements.map { e ->
        JSONObject().put("id", e.id).put("type", e.type).put("text", e.text).put("purpose", e.purpose)
            .put("ops", JSONArray(e.ops.map { it.name })).put("bounds", boundsJson(e.bounds))
    })

    private fun boundsJson(b: PixelBounds) = JSONArray(listOf(b.left, b.top, b.right, b.bottom))

    companion object {
        private const val TAG = "CoatiDebugAgent"
        const val ACTION_CONFIGURE = "bot.coati.debug.CONFIGURE_AGENT"
        const val ACTION_RUN_TASK = "bot.coati.debug.RUN_TASK"
        const val ACTION_STOP_TASK = "bot.coati.debug.STOP_TASK"
        const val ACTION_DUMP_TREE = "bot.coati.debug.DUMP_TREE"
        const val ACTION_OCR_BENCH = "bot.coati.debug.OCR_BENCH"
        const val ACTION_PARSE_BENCH = "bot.coati.debug.PARSE_BENCH"
        const val ACTION_DUMP_NODES = "bot.coati.debug.DUMP_NODES"
        private const val NODES_FILE = "ui_nodes.json"
    }
}
