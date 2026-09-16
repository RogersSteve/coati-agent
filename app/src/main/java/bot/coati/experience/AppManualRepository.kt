package bot.coati.experience

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores one human-readable manual plus one small commit sidecar per App.
 * metadata.json is moved last and its hash is the commit marker for manual.md.
 */
class AppManualRepository(private val storageRoot: File) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun load(appKey: String, namespace: ManualNamespace = ManualNamespace.CANONICAL): AppManual? {
        requireSafeAppKey(appKey)
        return lockFor(namespace, appKey).withLock { loadUnlocked(appKey, namespace) }
    }

    suspend fun save(
        manual: AppManual,
        expectedRevision: Long,
        namespace: ManualNamespace = ManualNamespace.CANONICAL,
    ): ManualSaveResult {
        AppManualPolicy.validateManual(manual)?.let { return ManualSaveResult.Rejected(it) }
        requireSafeAppKey(manual.app.appKey)
        return lockFor(namespace, manual.app.appKey).withLock {
            val current = loadUnlocked(manual.app.appKey, namespace)
            val actualRevision = current?.revision ?: 0L
            if (actualRevision != expectedRevision) return@withLock ManualSaveResult.RevisionConflict
            if (manual.revision != expectedRevision + 1L) {
                return@withLock ManualSaveResult.Rejected("NON_MONOTONIC_REVISION")
            }

            val directory = appDirectory(namespace, manual.app.appKey).apply { mkdirs() }
            current?.let { snapshotRevision(directory, it) }
            // manual first, metadata last: a crash can only produce a hash mismatch,
            // which loadUnlocked rejects before falling back to the previous snapshot.
            atomicWrite(File(directory, MANUAL_FILE), manual.markdown)
            atomicWrite(File(directory, METADATA_FILE), AppManualCodec.encodeMetadata(manual.metadata))
            pruneRevisions(directory)
            ManualSaveResult.Saved(manual)
        }
    }

    fun clearAll() {
        ManualNamespace.entries.forEach { namespace ->
            val root = rootDirectory(namespace)
            if (root.exists() && !root.deleteRecursively()) {
                Log.w(TAG, "Could not fully clear manual directory: ${root.name}")
            }
        }
    }

    fun namespaceDirectory(namespace: ManualNamespace): File = rootDirectory(namespace)

    fun manualFile(appKey: String, namespace: ManualNamespace = ManualNamespace.CANONICAL): File {
        requireSafeAppKey(appKey)
        return File(appDirectory(namespace, appKey), MANUAL_FILE)
    }

    fun metadataFile(appKey: String, namespace: ManualNamespace = ManualNamespace.CANONICAL): File {
        requireSafeAppKey(appKey)
        return File(appDirectory(namespace, appKey), METADATA_FILE)
    }

    private fun loadUnlocked(appKey: String, namespace: ManualNamespace): AppManual? {
        val directory = appDirectory(namespace, appKey)
        loadPair(File(directory, MANUAL_FILE), File(directory, METADATA_FILE), appKey)?.let { return it }
        val revisions = File(directory, REVISIONS_DIR)
            .listFiles { file -> file.isDirectory && file.name.toLongOrNull() != null }
            .orEmpty()
            .sortedByDescending { it.name.toLongOrNull() ?: -1L }
        return revisions.firstNotNullOfOrNull { revision ->
            loadPair(File(revision, MANUAL_FILE), File(revision, METADATA_FILE), appKey)
        }
    }

    private fun loadPair(manualFile: File, metadataFile: File, expectedAppKey: String): AppManual? {
        if (!manualFile.isFile || !metadataFile.isFile) return null
        val manual = runCatching {
            val markdown = manualFile.readText(Charsets.UTF_8)
            val metadata = AppManualCodec.decodeMetadata(metadataFile.readText(Charsets.UTF_8))
            AppManual(metadata, markdown)
        }.getOrNull() ?: return null
        if (manual.app.appKey != expectedAppKey) return null
        return manual.takeIf { AppManualPolicy.validateManual(it) == null }
    }

    private fun snapshotRevision(directory: File, manual: AppManual) {
        val revisionDirectory = File(File(directory, REVISIONS_DIR).apply { mkdirs() }, manual.revision.toString())
            .apply { mkdirs() }
        atomicWrite(File(revisionDirectory, MANUAL_FILE), manual.markdown)
        atomicWrite(File(revisionDirectory, METADATA_FILE), AppManualCodec.encodeMetadata(manual.metadata))
    }

    private fun pruneRevisions(directory: File) {
        File(directory, REVISIONS_DIR)
            .listFiles { file -> file.isDirectory && file.name.toLongOrNull() != null }
            .orEmpty()
            .sortedByDescending { it.name.toLongOrNull() ?: -1L }
            .drop(AppManualPolicy.MAX_REVISIONS)
            .forEach { it.deleteRecursively() }
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun lockFor(namespace: ManualNamespace, appKey: String): Mutex {
        return locks.getOrPut("${namespace.name}:$appKey") { Mutex() }
    }

    private fun appDirectory(namespace: ManualNamespace, appKey: String): File {
        return File(rootDirectory(namespace), appKey)
    }

    private fun rootDirectory(namespace: ManualNamespace): File {
        val name = if (namespace == ManualNamespace.CANONICAL) ROOT_CANONICAL else ROOT_SHADOW
        return File(storageRoot, name)
    }

    private fun requireSafeAppKey(appKey: String) {
        require(appKey.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid app key" }
    }

    companion object {
        private const val TAG = "CoatiManual"
        private const val ROOT_CANONICAL = "app_manuals_v2"
        private const val ROOT_SHADOW = "app_manuals_v2_shadow"
        private const val MANUAL_FILE = "manual.md"
        private const val METADATA_FILE = "metadata.json"
        private const val REVISIONS_DIR = "revisions"
        val LEGACY_ROOTS = listOf("app_manuals", "app_manuals_shadow")
    }
}
