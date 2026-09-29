package bot.coati.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import bot.coati.R
import bot.coati.action.ActionEngine
import bot.coati.agent.AgentLoop
import bot.coati.agent.JevStepExecutor
import bot.coati.jev.JevClient
import bot.coati.jev.JevDecisionEngine
import bot.coati.perception.OcrEngine
import bot.coati.perception.OcrEngines
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import bot.coati.data.ConfigStore
import bot.coati.experience.AppIdentityResolver
import bot.coati.experience.AppManualRenderer
import bot.coati.experience.AppManualRepository
import bot.coati.experience.AppManualRuntime
import bot.coati.experience.ManualSeedCatalog
import bot.coati.experience.ManualUpdateCoordinator
import bot.coati.model.GatewayFactory
import bot.coati.model.ResilientModelGateway
import bot.coati.model.ModelProviders
import bot.coati.model.ModelRouter
import bot.coati.state.StateManager
import bot.coati.state.TaskRecovery
import bot.coati.ui.main.MainActivity
import bot.coati.ui.overlay.OverlayAutoCollapsePolicy
import bot.coati.ui.overlay.OverlayManager
import bot.coati.ui.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoatiService : Service() {

    private var overlayManager: OverlayManager? = null
    private var agentLoop: AgentLoop? = null
    private var manualRuntime: AppManualRuntime? = null
    private lateinit var configStore: ConfigStore
    private var telephonyManager: TelephonyManager? = null
    private var callStateCallback: Any? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var ocrEngine: Deferred<OcrEngine?>? = null
    private val homePackages: Set<String> by lazy(::resolveHomePackages)
    private var overlayHiddenForSettings = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                overlayManager?.collapseExpandedInput(reason = "screen_off")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        configStore = ConfigStore(this)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayManager = OverlayManager(this, wm).also { overlay ->
            overlay.setSafetyMode(configStore.getSafetyMode())
            overlay.setManualMode(configStore.getManualMode())
            overlay.onSafetyModeChanged = { mode ->
                configStore.setSafetyMode(mode)
                Log.i(TAG, "Confirmation mode selected: $mode")
            }
            overlay.onSettings = {
                hideIdleOverlayForSettings()
                startActivity(MainActivity.settingsIntent(this))
            }
            overlay.onExit = {
                Log.i(TAG, "Exiting Coati from idle overlay")
                agentLoop?.stop()
                stopSelf()
            }
            if (settingsVisible) {
                overlay.setIdleOverlaySuppressed(suppressed = true)
                overlayHiddenForSettings = true
            } else {
                overlay.show()
            }
        }
        CoatiAccessibilityService.onForegroundWindowChanged = { packageName, className ->
            if (OverlayAutoCollapsePolicy.shouldCollapse(packageName, className, homePackages)) {
                serviceScope.launch {
                    overlayManager?.collapseExpandedInput(reason = "system_navigation")
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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

        val stateManager = StateManager(filesDir)

        // Check for task recovery BEFORE a11y check: the recovery dialog does not need
        // AccessibilityService, and force-stop kills A11y along with the process (BUG-19).
        val taskRecovery = TaskRecovery(stateManager)
        if (taskRecovery.hasIncompleteTask()) {
            val incompleteTask = taskRecovery.getIncompleteTask()!!
            serviceScope.launch {
                val overlay = overlayManager ?: return@launch
                val resume = overlay.awaitRecoveryDialog(
                    incompleteTask.goal,
                    incompleteTask.currentStep,
                    incompleteTask.totalSteps,
                    taskRecovery.getInterruptedAgo(incompleteTask),
                )
                if (resume) {
                    val a11y = CoatiAccessibilityService.instance
                    if (a11y == null) {
                        // A11y not yet available (e.g. OxygenOS killed it on swipe-away).
                        // User should manually re-enable A11y in Settings; onConnected will
                        // trigger initializeAgent() again and show the recovery dialog once more.
                        Log.w(TAG, "Cannot resume: AccessibilityService not available")
                        overlay.collapse()
                        return@launch
                    }
                    setupAgent(stateManager, a11y)
                    agentLoop?.start(incompleteTask.goal)
                } else {
                    taskRecovery.markCancelled()
                    overlay.collapse()
                }
            }
            return
        }

        val a11y = CoatiAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "AccessibilityService not available, agent will not be initialized")
            return
        }
        setupAgent(stateManager, a11y)
    }

    /**
     * The GPU OCR engine, loaded once in the background (model load and pipeline compilation take
     * several seconds) and warmed up. Null inside the Deferred when the device has no usable GPU.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ocrEngine(): Deferred<OcrEngine?> = ocrEngine ?: serviceScope.async(Dispatchers.Default) {
        OcrEngines.create(this@CoatiService)?.also { it.warmUp() }
    }.also { ocrEngine = it }

    private fun setupAgent(stateManager: StateManager, a11y: CoatiAccessibilityService) {
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

        val client = GatewayFactory.createRuntimeClient()

        // Runtime calls retry transient failures (e.g. relay-truncated JSON); settings checks do not.
        val reasoningGateway = ResilientModelGateway(
            GatewayFactory.create(reasoningProvider, client, reasoningConfig.apiKey, reasoningConfig.endpoint, reasoningConfig.modelName),
        )
        val fastGateway = ResilientModelGateway(
            GatewayFactory.create(fastProvider, client, fastConfig.apiKey, fastConfig.endpoint, fastConfig.modelName),
        )
        val modelRouter = ModelRouter(reasoningGateway, fastGateway)
        val actionEngine = ActionEngine(a11y)
        val overlay = overlayManager ?: return

        manualRuntime?.close()
        // Learned manuals are derived cross-task knowledge and must not enter
        // Android Auto Backup or device-to-device restore.
        val manualRepository = AppManualRepository(noBackupFilesDir)
        val newManualRuntime = AppManualRuntime(
            repository = manualRepository,
            renderer = AppManualRenderer(),
            identityResolver = AppIdentityResolver(this),
            seedCatalog = ManualSeedCatalog(assets),
            coordinator = ManualUpdateCoordinator(manualRepository, modelRouter.knowledgeUpdater),
            modeProvider = { configStore.getManualMode() },
        )
        manualRuntime = newManualRuntime
        // With a JEV key, the fast path takes each step first; without one, Reasoning decides every step.
        val jevConfig = configStore.getJevConfig()
        val ocr = if (jevConfig != null) ocrEngine() else null
        val fastExecutor = jevConfig?.let {
            JevStepExecutor(
                overlay, modelRouter, actionEngine, a11y,
                JevDecisionEngine(JevClient(client, it.apiKey, it.endpoint, it.model)),
                newManualRuntime,
                ocrProvider = { ocr?.takeIf { d -> d.isCompleted }?.getCompleted() },
            )
        }
        val loop = AgentLoop(
            overlay, modelRouter, actionEngine, a11y,
            stateManager, newManualRuntime,
            safetyModeProvider = { configStore.getSafetyMode() },
            fastExecutor = fastExecutor,
        )
        agentLoop = loop

        // Wire emergency stop from accessibility service
        a11y.onEmergencyStop = {
            Log.i(TAG, "Stopping agent: source=accessibility_emergency_stop")
            loop.emergencyStop = true
            loop.stop()
        }

        // Wire overlay callbacks to agent loop
        overlay.onExecuteTask = { task -> loop.start(task) }
        overlay.onStopTask = {
            Log.i(TAG, "Stopping agent: source=overlay_stop")
            loop.stop()
            overlay.collapse()
        }
        overlay.onPauseTask = {
            Log.i(TAG, "Stopping agent: source=overlay_pause")
            loop.stop()
            overlay.collapse()
        }
        overlay.onResumeTask = {
            serviceScope.launch { loop.resumeFromPause() }
        }
        Log.i(
            TAG,
            "Agent initialized: reasoning=${reasoningConfig.providerId}/${reasoningConfig.modelName}, " +
                "fast=${fastConfig.providerId}/${fastConfig.modelName}, jev=${jevConfig?.model ?: "off"}, " +
                "manualSchema=v2-learning, knowledgeUpdater=reasoning, " +
                "confirmationMode=${configStore.getSafetyMode()}, manualMode=${configStore.getManualMode()}",
        )
    }

    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "Call-state auto-pause is unavailable below Android 12")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Call-state auto-pause is disabled because phone permission was not granted")
            return
        }
        registerCallStateListenerApi31()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallStateListenerApi31() {
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
            tm.registerTelephonyCallback(mainExecutor, callback)
            Log.i(TAG, "Call state listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register call state listener: ${e.message}")
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterCallStateListenerApi31()
        }
        callStateCallback = null
        telephonyManager = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterCallStateListenerApi31() {
        try {
            (callStateCallback as? TelephonyCallback)?.let { callback ->
                telephonyManager?.unregisterTelephonyCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister call state listener: ${e.message}")
        }
    }

    private fun hideIdleOverlayForSettings() {
        overlayManager?.setIdleOverlaySuppressed(suppressed = true)
        overlayHiddenForSettings = true
    }

    private fun restoreOverlayAfterSettings() {
        if (!overlayHiddenForSettings) return
        overlayManager?.setIdleOverlaySuppressed(suppressed = false)
        overlayManager?.setSafetyMode(configStore.getSafetyMode())
        overlayManager?.setManualMode(configStore.getManualMode())
        overlayManager?.show()
        overlayHiddenForSettings = false
    }

    private fun handleSettingsVisibility(visible: Boolean) {
        if (visible) {
            hideIdleOverlayForSettings()
        } else {
            restoreOverlayAfterSettings()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveHomePackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(homeIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.i(TAG, "Stopping Coati from notification")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_SAFETY_MODE -> {
                overlayManager?.setSafetyMode(configStore.getSafetyMode())
                Log.i(TAG, "Confirmation mode refreshed: ${configStore.getSafetyMode()}")
            }
            ACTION_REFRESH_MANUAL_MODE -> {
                overlayManager?.setManualMode(configStore.getManualMode())
                Log.i(TAG, "App knowledge mode refreshed: ${configStore.getManualMode()}")
            }
            ACTION_RELOAD_MODEL_CONFIGURATION -> {
                Log.i(TAG, "Reloading verified model configuration")
                agentLoop?.stop()
                agentLoop = null
                initializeAgent()
            }
            else -> {
                if (agentLoop == null) {
                    initializeAgent()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun clearAppManualData(): Boolean {
        agentLoop?.stop()
        agentLoop = null
        TaskRecovery(StateManager(filesDir)).let { recovery ->
            if (recovery.hasIncompleteTask()) recovery.markCancelled()
        }
        val runtime = manualRuntime
        manualRuntime = null
        runtime?.closeAndJoin()
        return try {
            withContext(Dispatchers.IO) {
                AppManualRepository(noBackupFilesDir).clearAll()
            }
        } finally {
            initializeAgent()
        }
    }

    override fun onDestroy() {
        unregisterCallStateListener()
        runCatching { unregisterReceiver(screenOffReceiver) }
        CoatiAccessibilityService.onConnected = null
        CoatiAccessibilityService.onForegroundWindowChanged = null
        CoatiAccessibilityService.instance?.onEmergencyStop = null
        agentLoop?.stop()
        agentLoop = null
        ocrEngine?.let { engine ->
            ocrEngine = null
            engine.cancel()
            if (engine.isCompleted && !engine.isCancelled) runCatching { engine.getCompleted()?.close() }
        }
        manualRuntime?.close()
        manualRuntime = null
        overlayManager?.hide()
        overlayManager = null
        if (instance === this) instance = null
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
        val settingsPendingIntent = PendingIntent.getActivity(
            this,
            0,
            MainActivity.settingsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CoatiService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_idle))
            .setSmallIcon(R.drawable.ic_coati)
            .setContentIntent(settingsPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_coati),
                    getString(R.string.notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CoatiService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "coati_service"
        private const val ACTION_REFRESH_SAFETY_MODE = "bot.coati.action.REFRESH_SAFETY_MODE"
        private const val ACTION_REFRESH_MANUAL_MODE = "bot.coati.action.REFRESH_MANUAL_MODE"
        private const val ACTION_RELOAD_MODEL_CONFIGURATION = "bot.coati.action.RELOAD_MODEL_CONFIGURATION"
        private const val ACTION_STOP_SERVICE = "bot.coati.action.STOP_SERVICE"

        @Volatile
        private var instance: CoatiService? = null

        @Volatile
        private var settingsVisible = false

        fun start(context: Context) {
            val intent = Intent(context, CoatiService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CoatiService::class.java)
            context.stopService(intent)
        }

        fun isRunning(): Boolean = instance != null

        fun setSettingsVisible(visible: Boolean) {
            settingsVisible = visible
            instance?.handleSettingsVisibility(visible)
        }

        fun refreshSafetyMode(context: Context) {
            val intent = Intent(context, CoatiService::class.java).apply {
                action = ACTION_REFRESH_SAFETY_MODE
            }
            context.startForegroundService(intent)
        }

        fun refreshManualMode(context: Context) {
            val intent = Intent(context, CoatiService::class.java).apply {
                action = ACTION_REFRESH_MANUAL_MODE
            }
            context.startForegroundService(intent)
        }

        suspend fun clearAppManualData(context: Context): Boolean {
            return withContext(NonCancellable) {
                val running = instance
                if (running != null) {
                    withContext(Dispatchers.Main.immediate) {
                        running.clearAppManualData()
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        AppManualRepository(context.noBackupFilesDir).clearAll()
                    }
                }
            }
        }

        fun reloadModelConfiguration(context: Context) {
            val intent = Intent(context, CoatiService::class.java).apply {
                action = ACTION_RELOAD_MODEL_CONFIGURATION
            }
            context.startForegroundService(intent)
        }

        /** Stops the running task exactly as the overlay's Stop button does. */
        fun stopTask(): Boolean {
            val stop = instance?.overlayManager?.onStopTask ?: return false
            stop()
            return true
        }

        /** Starts a task exactly as the overlay's Execute button does. Returns false when not ready. */
        fun executeTask(task: String): Boolean {
            val overlay = instance?.overlayManager ?: return false
            val execute = overlay.onExecuteTask ?: return false
            execute(task)
            return true
        }
    }
}
