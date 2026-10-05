package bot.coati.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import bot.coati.agent.TaskHooks
import bot.coati.agent.TaskResult
import org.json.JSONObject
import java.io.File

/**
 * Benchmark hooks of debug builds: the ablation and perturbation switches set by
 * DebugAgentReceiver (`--ez verify false`, `--ez withhold_tree true`), and the result of every task
 * as files/task_result.json plus the CoatiUsage and CoatiResult log lines that harnesses read
 * (scripts/device_bench). Release builds contain none of this and keep [TaskHooks] defaults.
 */
internal class BenchmarkHooks(private val context: Context) : TaskHooks {
    override fun verifyEnabled(): Boolean = BenchmarkSettings.verifyEnabled(context)

    override fun treeWithheld(): Boolean = BenchmarkSettings.treeWithheld(context)

    override fun onTaskFinished(result: TaskResult) {
        Log.i(USAGE_TAG, "Task usage status=${result.status} ${result.usage}")
        val json = result.toJson().toString()
        Log.i(RESULT_TAG, "Task result $json")
        val file = File(context.filesDir, RESULT_FILE)
        val tmp = File(context.filesDir, "$RESULT_FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val USAGE_TAG = "CoatiUsage"
        private const val RESULT_TAG = "CoatiResult"
        const val RESULT_FILE = "task_result.json"
    }
}

/** Switches that exist only in debug builds; the configuration persists like other settings. */
internal object BenchmarkSettings {
    private const val PREFS = "benchmark"
    private const val KEY_VERIFY = "verify_enabled"
    private const val KEY_TREE_WITHHELD = "tree_withheld"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun verifyEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VERIFY, true)

    fun setVerifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERIFY, enabled).apply()
    }

    fun treeWithheld(context: Context): Boolean = prefs(context).getBoolean(KEY_TREE_WITHHELD, false)

    fun setTreeWithheld(context: Context, withheld: Boolean) {
        prefs(context).edit().putBoolean(KEY_TREE_WITHHELD, withheld).apply()
    }
}

internal fun TaskResult.toJson(): JSONObject = JSONObject()
    .put("run_id", runId ?: JSONObject.NULL)
    .put("goal", goal)
    .put("status", status)
    .put("reason", reason ?: JSONObject.NULL)
    .put("declared_done", declaredDone)
    .put("done_claims", doneClaims)
    .put("audit_rejects", auditRejects)
    .put("final_audit", finalAudit?.name ?: JSONObject.NULL)
    .put("route", route)
    .put("steps", steps)
    .put("max_steps", maxSteps)
    .put("duration_ms", durationMs)
    .put("fast_steps", fastSteps)
    .put("escalations", escalations)
    .put("verify", verify)
    .put("tree_withheld", treeWithheld)
    .put("answer", answer ?: JSONObject.NULL)
    .put("refunded_steps", refundedSteps)
    .put("usage", usage)

/**
 * Installs [BenchmarkHooks] when the process starts, before any task can run, so they survive
 * Coati restarts during long benchmarks. Declared only in the debug manifest.
 */
class BenchmarkHooksInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        TaskHooks.installed = BenchmarkHooks(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
