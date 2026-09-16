package bot.coati.experience

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject

class ManualSeedCatalog(
    private val assetManager: AssetManager,
    catalogPath: String = DEFAULT_CATALOG,
) {
    private val seeds: Map<String, ManualSeedDescriptor> = runCatching {
        val raw = assetManager.open(catalogPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        decodeCatalog(raw).associateBy { it.packageName }
    }.onFailure { Log.w(TAG, "Manual seed catalog unavailable: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyMap())

    fun create(identity: ManualAppIdentity, now: Long = System.currentTimeMillis()): AppManual? {
        val descriptor = seeds[identity.packageName] ?: return null
        return runCatching {
            val template = assetManager.open(descriptor.manualAsset)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val markdown = applyIdentity(template, identity)
            AppManualFactory.create(
                identity = identity,
                markdown = markdown,
                revision = 1L,
                protectedHeadingPaths = descriptor.protectedHeadingPaths,
                now = now,
            )
        }.onFailure { Log.w(TAG, "Manual seed invalid package=${identity.packageName} reason=${it.javaClass.simpleName}") }
            .getOrNull()
    }

    internal fun applyIdentity(template: String, identity: ManualAppIdentity): String {
        return template
            .replace("{{DISPLAY_NAME}}", safeInline(identity.displayName))
            .replace("{{PACKAGE_NAME}}", identity.packageName)
            .replace("{{VERSION_CODE}}", identity.versionCode.toString())
            .replace("{{LOCALE_TAG}}", identity.localeTag)
    }

    companion object {
        private const val TAG = "CoatiManual"
        private const val DEFAULT_CATALOG = "app_manual_seeds/catalog.json"

        internal fun decodeCatalog(raw: String): List<ManualSeedDescriptor> {
            val root = JSONObject(raw)
            val seeds = root.getJSONArray("seeds")
            return buildList {
                for (index in 0 until seeds.length()) {
                    val item = seeds.getJSONObject(index)
                    val packageName = item.getString("packageName")
                    val manualAsset = item.getString("manualAsset")
                    require(packageName.matches(Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")))
                    require(manualAsset.startsWith("app_manual_seeds/") && !manualAsset.contains(".."))
                    val protected = item.getJSONArray("protectedHeadingPaths")
                    val protectedPaths = buildSet {
                        for (pathIndex in 0 until protected.length()) add(protected.getString(pathIndex))
                    }
                    add(ManualSeedDescriptor(packageName, manualAsset, protectedPaths))
                }
            }
        }

        private fun safeInline(value: String): String = value
            .replace('`', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

internal data class ManualSeedDescriptor(
    val packageName: String,
    val manualAsset: String,
    val protectedHeadingPaths: Set<String>,
)
