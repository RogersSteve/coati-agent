package bot.coati.ui.onboarding

import android.app.Application
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bot.coati.util.RegionDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<Preferences>,
) : AndroidViewModel(application) {

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _accessibilityGranted = MutableStateFlow(false)
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted

    private val _selectedRegion = MutableStateFlow("global")
    val selectedRegion: StateFlow<String> = _selectedRegion

    init {
        _selectedRegion.value = RegionDetector.detectRegion(application)
    }

    fun nextStep() {
        _currentStep.value = (_currentStep.value + 1).coerceAtMost(3)
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _overlayGranted.value = Settings.canDrawOverlays(context)
        _accessibilityGranted.value = hasAccessibilityPermission(context)
    }

    fun selectRegion(region: String) {
        _selectedRegion.value = region
    }

    fun finishOnboarding(onComplete: () -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_ONBOARDING_COMPLETE] = true
                prefs[KEY_REGION] = _selectedRegion.value
            }
            onComplete()
        }
    }

    private fun hasAccessibilityPermission(context: android.content.Context): Boolean {
        val pkg = context.packageName
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // Match both full and shorthand: "bot.coati/bot.coati.service.X" or "bot.coati/.service.X"
        return enabled.contains("$pkg/$pkg.service.CoatiAccessibilityService")
                || enabled.contains("$pkg/.service.CoatiAccessibilityService")
    }

    companion object {
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_REGION = stringPreferencesKey("region")
    }
}
