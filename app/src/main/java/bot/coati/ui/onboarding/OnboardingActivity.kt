package bot.coati.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import bot.coati.service.CoatiService
import bot.coati.ui.theme.CoatiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.getBooleanExtra(EXTRA_A11Y_RECOVERY, false)) {
            viewModel.initA11yRecovery()
        }

        setContent {
            CoatiTheme {
                OnboardingScreen(
                    viewModel = viewModel,
                    onFinish = {
                        CoatiService.start(this)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_A11Y_RECOVERY = "a11y_recovery"
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}
