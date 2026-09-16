package bot.coati.experience

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/** Strict codec for the small metadata sidecar. Manual Markdown is never duplicated into JSON. */
object AppManualCodec {
    fun encodeMetadata(metadata: AppManualMetadata): String {
        return JSONObject().apply {
            put("schemaVersion", metadata.schemaVersion)
            put("app", encodeIdentity(metadata.app))
            put("revision", metadata.revision)
            put("updatedAtEpochMs", metadata.updatedAtEpochMs)
            put("manualSha256", metadata.manualSha256)
            put("estimatedTokens", metadata.estimatedTokens)
            put("observedVersionCodes", JSONArray(metadata.observedVersionCodes))
            put("localeTags", JSONArray(metadata.localeTags))
            put("protectedHeadingPaths", JSONArray(metadata.protectedHeadingPaths.sorted()))
        }.toString(2)
    }

    fun decodeMetadata(raw: String): AppManualMetadata {
        val root = parseObject(raw, "app manual metadata")
        return AppManualMetadata(
            schemaVersion = requireInt(root, "schemaVersion"),
            app = decodeIdentity(requireObject(root, "app")),
            revision = requireLong(root, "revision"),
            updatedAtEpochMs = requireLong(root, "updatedAtEpochMs"),
            manualSha256 = requireString(root, "manualSha256"),
            estimatedTokens = requireInt(root, "estimatedTokens"),
            observedVersionCodes = requireArray(root, "observedVersionCodes").mapLongs(),
            localeTags = requireArray(root, "localeTags").mapStrings(),
            protectedHeadingPaths = requireArray(root, "protectedHeadingPaths").mapStrings().toSet(),
        )
    }

    fun decodeCuratorDecision(raw: String, targetHeadings: Map<String, String>): ManualCuratorDecision {
        val root = parseObject(raw, "manual curator decision")
        requireOnlyKeys(root, setOf("expectedRevision", "tool", "edits"))
        val tool = runCatching { ManualCuratorTool.valueOf(requireString(root, "tool").uppercase()) }
            .getOrElse { throw IllegalArgumentException("Invalid curator tool") }
        val edits = requireArray(root, "edits").let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val edit = array.get(index) as? JSONObject
                        ?: throw IllegalArgumentException("Edit must be an object")
                    requireOnlyKeys(edit, setOf("targetId", "oldText", "newText"))
                    val targetId = requireString(edit, "targetId")
                    val heading = targetHeadings[targetId]
                        ?: throw ManualEditException("UNKNOWN_TARGET_ID", index)
                    add(ManualPartialEdit(
                        heading,
                        requireString(edit, "oldText"),
                        requireString(edit, "newText"),
                    ))
                }
            }
        }
        return ManualCuratorDecision(
            expectedRevision = requireValue(root, "expectedRevision").let { value ->
                require(value is Int || value is Long) { "Revision must be an integer" }
                (value as Number).toLong().also { require(it >= 0L) }
            },
            tool = tool,
            edits = edits,
        )
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encodeIdentity(identity: ManualAppIdentity): JSONObject = JSONObject().apply {
        put("appKey", identity.appKey)
        put("packageName", identity.packageName)
        put("signerSha256", identity.signerSha256)
        put("displayName", identity.displayName)
        put("versionCode", identity.versionCode)
        put("localeTag", identity.localeTag)
    }

    private fun decodeIdentity(json: JSONObject): ManualAppIdentity = ManualAppIdentity(
        appKey = requireString(json, "appKey"),
        packageName = requireString(json, "packageName"),
        signerSha256 = requireString(json, "signerSha256"),
        displayName = requireString(json, "displayName"),
        versionCode = requireLong(json, "versionCode"),
        localeTag = requireString(json, "localeTag"),
    )

    private fun parseObject(raw: String, label: String): JSONObject {
        return runCatching {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) {
                throw IllegalArgumentException("$label must contain exactly one JSON object")
            }
            value
        }.getOrElse { throw IllegalArgumentException("Invalid $label JSON", it) }
    }

    private fun requireObject(json: JSONObject, key: String): JSONObject {
        return requireValue(json, key) as? JSONObject ?: throw IllegalArgumentException("$key must be an object")
    }

    private fun requireArray(json: JSONObject, key: String): JSONArray {
        return requireValue(json, key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array")
    }

    private fun requireString(json: JSONObject, key: String): String {
        return requireValue(json, key) as? String ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun requireLong(json: JSONObject, key: String): Long {
        return (requireValue(json, key) as? Number)?.toLong()
            ?: throw IllegalArgumentException("$key must be a number")
    }

    private fun requireInt(json: JSONObject, key: String): Int {
        return (requireValue(json, key) as? Number)?.toInt()
            ?: throw IllegalArgumentException("$key must be a number")
    }

    private fun requireValue(json: JSONObject, key: String): Any {
        if (!json.has(key) || json.isNull(key)) throw IllegalArgumentException("Missing required field: $key")
        return json.get(key)
    }

    private fun requireOnlyKeys(json: JSONObject, allowed: Set<String>) {
        val keys = buildSet { json.keys().forEachRemaining(::add) }
        if (keys != allowed) throw IllegalArgumentException("Unexpected or missing fields")
    }

    private fun JSONArray.mapStrings(): List<String> = buildList {
        for (index in 0 until this@mapStrings.length()) {
            add(this@mapStrings.get(index) as? String ?: throw IllegalArgumentException("Array item must be a string"))
        }
    }

    private fun JSONArray.mapLongs(): List<Long> = buildList {
        for (index in 0 until this@mapLongs.length()) {
            add((this@mapLongs.get(index) as? Number)?.toLong() ?: throw IllegalArgumentException("Array item must be a number"))
        }
    }
}
