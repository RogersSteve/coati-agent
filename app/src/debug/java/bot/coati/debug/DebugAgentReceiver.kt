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
import bot.coati.data.ConfigStore
import bot.coati.data.SafetyMode
import bot.coati.service.CoatiAccessibilityService
import bot.coati.service.CoatiService
import bot.coati.service.UiTreeNode
import bot.coati.ui.onboarding.OnboardingViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only control for device benchmarks: configure models and start or stop a task without
 * touching the UI. Protected by android.permission.DUMP, so only adb shell can send it. A full model
 * configuration also marks onboarding complete, so a fresh install can be benchmarked unattended
 * (overlay and accessibility permissions are still granted from adb).
 *
 * adb shell am broadcast -a bot.coati.debug.CONFIGURE_AGENT -n bot.coati/.debug.DebugAgentReceiver \
 *   --es provider openai_compatible --es endpoint https://relay.example --es api_key KEY \
 *   --es reasoning_model m1 --es fast_model m2 --es safety TRUST
 * adb shell am broadcast -a bot.coati.debug.RUN_TASK -n bot.coati/.debug.DebugAgentReceiver --es task "..."
 * adb shell am broadcast -a bot.coati.debug.STOP_TASK -n bot.coati/.debug.DebugAgentReceiver
 * adb shell am broadcast -a bot.coati.debug.DUMP_TREE -n bot.coati/.debug.DebugAgentReceiver
 * adb shell am broadcast -a bot.coati.debug.DUMP_NODES -n bot.coati/.debug.DebugAgentReceiver
 *   (then: adb shell run-as bot.coati cat files/ui_nodes.json)
 */
class DebugAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIGURE -> configure(context, intent)
            ACTION_RUN_TASK -> {
                val task = intent.getStringExtra("task")?.takeIf { it.isNotBlank() } ?: return
                val started = CoatiService.executeTask(task)
                Log.i(TAG, "Debug task started=$started task=$task")
            }
            ACTION_STOP_TASK -> Log.i(TAG, "Debug task stopped=${CoatiService.stopTask()}")
            ACTION_DUMP_TREE -> dumpTree()
            ACTION_DUMP_NODES -> dumpNodes(context)
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
        intent.getStringExtra("safety")?.let { name ->
            runCatching { SafetyMode.valueOf(name.uppercase()) }.getOrNull()?.let(store::setSafetyMode)
        }
        if (CoatiService.isRunning()) CoatiService.reloadModelConfiguration(context)
        Log.i(TAG, "Debug agent configured: safety=${store.getSafetyMode()}")
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

    companion object {
        private const val TAG = "CoatiDebugAgent"
        const val ACTION_CONFIGURE = "bot.coati.debug.CONFIGURE_AGENT"
        const val ACTION_RUN_TASK = "bot.coati.debug.RUN_TASK"
        const val ACTION_STOP_TASK = "bot.coati.debug.STOP_TASK"
        const val ACTION_DUMP_TREE = "bot.coati.debug.DUMP_TREE"
        const val ACTION_DUMP_NODES = "bot.coati.debug.DUMP_NODES"
        private const val NODES_FILE = "ui_nodes.json"
    }
}
