package bot.coati.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import bot.coati.service.CoatiService
import bot.coati.ui.onboarding.OnboardingActivity
import bot.coati.ui.onboarding.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dataStore: DataStore<Preferences>

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

        // Check if A11y permission was revoked (e.g. OxygenOS kills it on swipe-away)
        if (!OnboardingViewModel.hasAccessibilityPermission(this)) {
            val intent = Intent(this, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_A11Y_RECOVERY, true)
            }
            startActivity(intent)
            finish()
            return
        }

        // Onboarding already done — start service and finish
        CoatiService.start(this)
        finish()
    }
}
