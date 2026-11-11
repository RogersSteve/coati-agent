package bot.coati.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import bot.coati.data.ConfigStore
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Debug source set only. A visible Activity avoids long work in a BroadcastReceiver's goAsync. */
class ManualProtocolProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var status: TextView

    @SuppressLint("SetTextI18n") // Debug-only diagnostic UI; strings intentionally stay beside the probe protocol.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        status = TextView(this).apply {
            textSize = 18f
            text = "Living Manual v2 · Protocol probe\n\n" +
                "先结束正在运行的 Coati 任务。此测试不操作手机，只将四组虚构页面资料发送给当前 Reasoning 模型。\n" +
                "最多 8 次付费模型请求，每组最多 120 秒。请保持此页前台；离开或取消会中止。\n" +
                "已有经验、模式和配置均不改变。完整材料保存在独立 no_backup/manual_protocol_probes 目录。\n\n"
        }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "运行一次：全部 4 项"
            setOnClickListener {
                if (active.compareAndSet(false, true)) {
                    isEnabled = false
                    job = scope.launch {
                        try { runProbe() }
                        catch (_: CancellationException) { status.append("\n已取消；请导出报告。") }
                        catch (error: Exception) {
                            Log.e("CoatiManual", "probe ui result=ERROR errorType=${error.javaClass.simpleName} " +
                                "frames=${error.stackTrace.take(12).joinToString(" | ")}")
                            status.append("\n测试错误：${error.javaClass.simpleName}；请导出报告。")
                        }
                    }.also { it.invokeOnCompletion { active.set(false) } }
                } else status.append("\n另一个测试仍在退出，请稍后重新打开此页。")
            }
        })
        column.addView(Button(this).apply {
            text = "取消测试"
            setOnClickListener { job?.cancel() }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private suspend fun runProbe() {
        val configStore = ConfigStore(this)
        val config = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
        val provider = config?.let { ModelProviders.findById(it.providerId) }
        if (config == null || provider == null) {
            status.append("\n没有有效 Reasoning 配置；没有发送请求。")
            return
        }
        val label = "${config.providerId}/${config.modelName}"
        status.append("\n模型：$label\n")
        val parent = File(noBackupFilesDir, "manual_protocol_probes").apply { mkdirs() }
        val runName = "run_${System.currentTimeMillis()}_${System.nanoTime()}"
        val runRoot = File(parent, runName)
        File(parent, "latest-run.txt").writeText(runName, Charsets.UTF_8)
        val client = GatewayFactory.createRuntimeClient()
        var passed: Boolean
        var cleanupOk = true
        try {
            val planAuth = bot.coati.model.openai.OpenAiPlanAuth.create(this, client, configStore)
            val gateway = GatewayFactory.create(
                provider, client, config.apiKey, config.endpoint, config.modelName, config.authMode, planAuth,
            )
            passed = withContext(Dispatchers.IO) {
                ManualProtocolProbe(runRoot, gateway, label).run { message ->
                    runOnUiThread { status.append("\n$message") }
                }
            }
        } finally {
            val failures = closeProbeClient(client)
            cleanupOk = failures.isEmpty()
            Log.i("CoatiManual", "probe cleanup result=${if (cleanupOk) "PASS" else "WARNING"} " +
                "failures=${failures.joinToString(",") { "${it.step}:${it.errorType}" }}")
            // A cleanup/reporting problem must not replace a completed probe result or cancellation.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    if (runRoot.isDirectory) File(runRoot, "cleanup.json").writeText(JSONObject().apply {
                        put("result", if (failures.isEmpty()) "PASS" else "WARNING")
                        put("failures", JSONArray(failures.map { failure -> JSONObject().apply {
                            put("step", failure.step)
                            put("errorType", failure.errorType)
                            put("frames", JSONArray(failure.frames))
                        } }))
                    }.toString(2), Charsets.UTF_8)
                }.onFailure {
                    cleanupOk = false
                    Log.w("CoatiManual", "probe cleanup report=FAILED errorType=${it.javaClass.simpleName}")
                }
            }
        }
        status.append("\n\n${if (passed) "PASS · 4/4" else "FAIL · 请导出材料，不要重复碰运气"}\n$runName")
        if (!cleanupOk) status.append("\n连接清理有警告；四项结果仍有效，请一并导出 cleanup.json。")
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { private val active = AtomicBoolean(false) }
}
