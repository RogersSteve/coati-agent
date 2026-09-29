package bot.coati.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import bot.coati.data.ConfigStore
import bot.coati.data.SafetyMode
import bot.coati.service.CoatiService
import bot.coati.ui.onboarding.OnboardingViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

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
    }
}
