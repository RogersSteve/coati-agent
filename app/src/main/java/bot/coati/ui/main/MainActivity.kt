package bot.coati.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import bot.coati.data.ConfigStore
import bot.coati.service.CoatiService
import bot.coati.ui.onboarding.OnboardingActivity
import bot.coati.ui.onboarding.OnboardingViewModel
import bot.coati.ui.settings.SettingsScreen
import bot.coati.ui.settings.SettingsViewModel
import bot.coati.ui.settings.isCompleteModelConfig
import bot.coati.ui.settings.shouldStartOverlayDirectly
import bot.coati.ui.theme.CoatiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var configStore: ConfigStore

    private val settingsViewModel: SettingsViewModel by viewModels()
    private var settingsUiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val onboardingComplete = runBlocking {
            dataStore.data.first()[OnboardingViewModel.KEY_ONBOARDING_COMPLETE] == true
        }

        if (!onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (routeLaunchIntent(intent)) return

        enableEdgeToEdge()

        settingsUiReady = true
        setContent {
            CoatiTheme {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { finish() },
                    onStartCoati = ::startCoatiFromSettings,
                    onOpenOverlayPermission = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onOpenAccessibilityPermission = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!routeLaunchIntent(intent) && settingsUiReady) {
            settingsViewModel.refreshPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        if (settingsUiReady) {
            CoatiService.setSettingsVisible(visible = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dataStore.isInitialized) {
            settingsViewModel.refreshPermissions()
        }
    }

    override fun onStop() {
        if (settingsUiReady && !isChangingConfigurations) {
            CoatiService.setSettingsVisible(visible = false)
        }
        super.onStop()
    }

    private fun routeLaunchIntent(launchIntent: Intent): Boolean {
        val openSettings = launchIntent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        val shouldStart = shouldStartOverlayDirectly(
            openSettingsRequested = openSettings,
            serviceRunning = CoatiService.isRunning(),
            requirementsComplete = hasCompleteStartupRequirements(),
        )
        if (!shouldStart) return false

        startCoatiAndFinish()
        return true
    }

    private fun startCoatiFromSettings() {
        if (!hasCompleteStartupRequirements()) {
            settingsViewModel.refreshPermissions()
            return
        }
        startCoatiAndFinish()
    }

    private fun startCoatiAndFinish() {
        if (CoatiService.isRunning()) {
            finish()
            return
        }

        CoatiService.setSettingsVisible(visible = false)
        settingsUiReady = false
        CoatiService.start(this)
        finish()
    }

    private fun hasCompleteStartupRequirements(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (!OnboardingViewModel.hasAccessibilityPermission(this)) return false

        val reasoningConfig = configStore.getReasoningConfig() ?: configStore.getActiveProviderConfig()
        return isCompleteModelConfig(reasoningConfig) && isCompleteModelConfig(configStore.getFastConfig())
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"

        fun settingsIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_OPEN_SETTINGS, true)
            }
        }
    }
}
