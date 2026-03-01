package bot.coati.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import bot.coati.R
import bot.coati.action.ActionEngine
import bot.coati.agent.AgentRunner
import bot.coati.data.ConfigStore
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.ui.main.MainActivity
import bot.coati.ui.overlay.OverlayManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CoatiService : Service() {

    private var overlayManager: OverlayManager? = null
    private var agentRunner: AgentRunner? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayManager = OverlayManager(this, wm)
        overlayManager?.show()

        initializeAgent()
    }

    private fun initializeAgent() {
        val a11y = CoatiAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "AccessibilityService not available, agent will not be initialized")
            return
        }

        val configStore = ConfigStore(this)
        val config = configStore.getActiveProviderConfig()
        if (config == null) {
            Log.w(TAG, "No API key configured, agent will not be initialized")
            return
        }

        val provider = ModelProviders.findById(config.providerId)
        if (provider == null) {
            Log.w(TAG, "Unknown provider: ${config.providerId}, agent will not be initialized")
            return
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val gateway = GatewayFactory.create(provider, client, config.apiKey, config.endpoint, config.modelName)
        val actionEngine = ActionEngine(a11y)
        val overlay = overlayManager ?: return

        val runner = AgentRunner(overlay, gateway, actionEngine, a11y)
        agentRunner = runner

        // Wire overlay callbacks to agent
        overlay.onExecuteTask = { task -> runner.start(task) }
        overlay.onStopTask = { runner.stop() }
        overlay.onConfirmAction = { runner.onConfirm() }
        overlay.onCancelAction = { runner.onCancel() }

        Log.i(TAG, "Agent initialized with provider: ${config.providerId}, model: ${config.modelName}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-initialize agent if it wasn't available during onCreate
        if (agentRunner == null) {
            initializeAgent()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        agentRunner?.stop()
        agentRunner = null
        overlayManager?.hide()
        overlayManager = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_idle))
            .setSmallIcon(R.drawable.ic_coati)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CoatiService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "coati_service"

        fun start(context: Context) {
            val intent = Intent(context, CoatiService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CoatiService::class.java)
            context.stopService(intent)
        }
    }
}
