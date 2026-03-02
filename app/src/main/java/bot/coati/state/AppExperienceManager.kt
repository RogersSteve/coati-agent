package bot.coati.state

class AppExperienceManager(private val stateManager: StateManager) {

    fun loadForPrompt(packageName: String): String? {
        val json = stateManager.readAppJson(packageName) ?: return null
        return try {
            AppProfile.fromJson(json).toMarkdown()
        } catch (_: Exception) {
            null
        }
    }

    fun recordObservation(packageName: String, page: String, observation: String) {
        val profile = loadProfile(packageName)
        profile.upsertPage(page, observation)
        saveProfile(profile)
    }

    fun recordSuccessPath(packageName: String, pathName: String, steps: List<String>) {
        val profile = loadProfile(packageName)
        profile.addOrUpdatePath(pathName, steps)
        saveProfile(profile)
    }

    fun markPageOutdated(packageName: String, pageName: String) {
        val profile = loadProfile(packageName)
        profile.markOutdated(pageName)
        saveProfile(profile)
    }

    private fun loadProfile(packageName: String): AppProfile {
        val json = stateManager.readAppJson(packageName)
        return if (json != null) {
            try {
                AppProfile.fromJson(json)
            } catch (_: Exception) {
                AppProfile(packageName = packageName)
            }
        } else {
            AppProfile(packageName = packageName)
        }
    }

    private fun saveProfile(profile: AppProfile) {
        stateManager.writeAppJson(profile.packageName, profile.toJson())
    }
}
