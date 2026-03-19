package bot.coati.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import bot.coati.R
import bot.coati.action.ActionEngine
import bot.coati.agent.AgentLoop
import bot.coati.data.ConfigStore
import bot.coati.model.GatewayFactory
import bot.coati.model.ModelProviders
import bot.coati.model.ModelRouter
import bot.coati.state.AppExperienceManager
import bot.coati.state.StateManager
import bot.coati.state.TaskRecovery
import bot.coati.ui.main.MainActivity
import bot.coati.ui.overlay.OverlayManager
import bot.coati.ui.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoatiService : Service() {

    private var overlayManager: OverlayManager? = null
    private var agentLoop: AgentLoop? = null
    private var telephonyManager: TelephonyManager? = null
    private var callStateCallback: TelephonyCallback? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayManager = OverlayManager(this, wm)
        overlayManager?.show()

        initializeAgent()
        registerCallStateListener()
    }

    private fun initializeAgent() {
        // Register reconnection callback before any early returns, so that when the user
        // manually re-enables A11y in Settings (e.g. after OxygenOS kills it on swipe-away),
        // onServiceConnected() will trigger initializeAgent() automatically (BUG-4).
        CoatiAccessibilityService.onConnected = {
            if (agentLoop == null) {
                initializeAgent()
            }
        }

        val a11y = CoatiAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "AccessibilityService not available, agent will not be initialized")
            return
        }

        val configStore = ConfigStore(this)

        // Load reasoning config (with fallback to old single config)
        val reasoningConfig = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
        if (reasoningConfig == null) {
            Log.w(TAG, "No reasoning config, agent will not be initialized")
            return
        }

        val reasoningProvider = ModelProviders.findById(reasoningConfig.providerId)
        if (reasoningProvider == null) {
            Log.w(TAG, "Unknown reasoning provider: ${reasoningConfig.providerId}")
            return
        }

        // Load fast config
        val fastConfig = configStore.getFastConfig() ?: reasoningConfig
        val fastProvider = ModelProviders.findById(fastConfig.providerId) ?: reasoningProvider

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val reasoningGateway = GatewayFactory.create(reasoningProvider, client, reasoningConfig.apiKey, reasoningConfig.endpoint, reasoningConfig.modelName)
        val fastGateway = GatewayFactory.create(fastProvider, client, fastConfig.apiKey, fastConfig.endpoint, fastConfig.modelName)
        val modelRouter = ModelRouter(reasoningGateway, fastGateway)
        val actionEngine = ActionEngine(a11y)
        val overlay = overlayManager ?: return

        val stateManager = StateManager(filesDir)
        val experienceManager = AppExperienceManager(stateManager)
        val safetyMode = configStore.getSafetyMode()

        val loop = AgentLoop(
            overlay, modelRouter, actionEngine, a11y,
            stateManager, experienceManager, safetyMode,
        )
        agentLoop = loop

        // Wire emergency stop from accessibility service
        a11y.onEmergencyStop = {
            loop.emergencyStop = true
            loop.stop()
        }

        // Wire recovery
        val taskRecovery = TaskRecovery(stateManager)
        if (taskRecovery.hasIncompleteTask()) {
            val incompleteTask = taskRecovery.getIncompleteTask()!!
            serviceScope.launch {
                val interruptedAgo = taskRecovery.getInterruptedAgo(incompleteTask)
                val resume = overlay.awaitRecoveryDialog(
                    incompleteTask.goal,
                    incompleteTask.currentStep,
                    incompleteTask.totalSteps,
                    interruptedAgo,
                )
                if (resume) {
                    loop.start(incompleteTask.goal)
                } else {
                    taskRecovery.markCancelled()
                    overlay.collapse()
                }
            }
        }

        // Wire overlay callbacks to agent loop
        overlay.onExecuteTask = { task -> loop.start(task) }
        overlay.onStopTask = {
            loop.stop()
            overlay.collapse()
        }
        overlay.onPauseTask = {
            loop.stop()
            overlay.collapse()
        }
        overlay.onResumeTask = {
            serviceScope.launch { loop.resumeFromPause() }
        }
        overlay.onSettings = {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        overlay.onExit = {
            loop.stop()
            stop(this)
        }

        Log.i(TAG, "Agent initialized: reasoning=${reasoningConfig.providerId}/${reasoningConfig.modelName}, fast=${fastConfig.providerId}/${fastConfig.modelName}")
    }

    private fun registerCallStateListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager = tm

            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_RINGING ||
                        state == TelephonyManager.CALL_STATE_OFFHOOK
                    ) {
                        val overlay = overlayManager ?: return
                        val currentState = overlay.overlayState.value
                        if (currentState is OverlayState.FrameExecuting) {
                            Log.i(TAG, "Incoming call detected, auto-pausing agent")
                            agentLoop?.stop()
                            overlay.pause(
                                taskName = currentState.taskName,
                                step = currentState.step,
                                totalSteps = currentState.totalSteps,
                            )
                        }
                    }
                }
            }
            callStateCallback = callback
            tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
            Log.i(TAG, "Call state listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register call state listener: ${e.message}")
        }
    }

    private fun unregisterCallStateListener() {
        try {
            callStateCallback?.let { callback ->
                telephonyManager?.unregisterTelephonyCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister call state listener: ${e.message}")
        }
        callStateCallback = null
        telephonyManager = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (agentLoop == null) {
            initializeAgent()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterCallStateListener()
        agentLoop?.stop()
        agentLoop = null
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
