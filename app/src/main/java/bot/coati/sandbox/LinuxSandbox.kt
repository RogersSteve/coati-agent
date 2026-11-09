package bot.coati.sandbox

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TimeZone

data class ShellResult(val exitCode: Int?, val timedOut: Boolean, val output: String, val delivered: List<String>)

/**
 * A Linux userland (Alpine under PRoot) shipped inside the APK and unpacked on first use. Each task gets
 * a fresh /workspace. PRoot isolates paths for convenience and is not a security boundary.
 * Commands run one at a time because Android kills apps that keep many child processes alive.
 */
class LinuxSandbox(private val context: Context) {
    private val root = File(context.filesDir, "sandbox")
    private val rootfs = File(root, "rootfs")
    private val marker = File(root, "rootfs.version")
    private val tmp = File(root, "tmp")
    private val workspace = File(root, "workspace")
    private val output = File(workspace, "output")
    private val hiddenProcess = File(root, "hidden-process")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val mutex = Mutex()

    /** Output-relative path -> the Download entry and the file state it holds, for this task. */
    private val exports = mutableMapOf<String, Export>()
    private val commands = ArrayDeque<String>()
    private var taskFresh = true

    private class Export(val uri: Uri, val signature: String)

    fun newTask() {
        taskFresh = true
        exports.clear()
        commands.clear()
    }

    suspend fun run(command: String, timeoutSeconds: Int, onPreparing: suspend () -> Unit): ShellResult = mutex.withLock {
        val unpacked = withContext(Dispatchers.IO) { marker.takeIf(File::isFile)?.readText() == ROOTFS_VERSION }
        if (!unpacked) onPreparing()
        withContext(Dispatchers.IO) {
            if (!unpacked) unpack()
            if (taskFresh) {
                deleteTree(workspace.toPath())
                taskFresh = false
            }
            output.mkdirs()
            tmp.mkdirs()
            hiddenProcess.mkdirs()
            File(rootfs, "etc/resolv.conf").writeText(RESOLV_CONF)
            val result = execute(command, timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS))
            commands.addLast("exit ${if (result.timedOut) "timeout" else result.exitCode}: ${command.take(RECORD_COMMAND_CHARS)}")
            while (commands.size > RECORD_COMMANDS) commands.removeFirst()
            result
        }
    }

    /** What the host itself observed in the workspace, so the model and the audit need not trust a claim. */
    fun record(): String? {
        if (commands.isEmpty()) return null
        val base = workspace.toPath()
        val files = runCatching {
            Files.walk(base).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.limit(RECORD_FILES.toLong()).toList()
            }.map { "${base.relativize(it)} (${Files.size(it)} bytes)" }
        }.getOrDefault(emptyList())
        return buildString {
            append("Recent commands, oldest first:\n")
            commands.forEach { append("- ").append(it).append('\n') }
            append("Files in /workspace: ").append(if (files.isEmpty()) "none" else files.joinToString("; "))
            if (exports.isNotEmpty()) {
                append("\nDelivered to the phone's Download/$EXPORT_DIR folder: ").append(exports.keys.joinToString("; "))
            }
        }
    }

    private fun unpack() {
        Log.i(TAG, "Unpacking the Linux rootfs")
        val started = System.currentTimeMillis()
        val partial = File(root, "rootfs.partial")
        deleteTree(partial.toPath())
        deleteTree(rootfs.toPath())
        marker.delete()
        partial.mkdirs()
        val directoryModes = ArrayList<Pair<File, Int>>()
        context.assets.open(ROOTFS_ASSET).use { asset ->
            TarReader.read(XZInputStream(BufferedInputStream(asset, IO_BUFFER))) { entry ->
                val target = File(partial, entry.path)
                target.parentFile?.mkdirs()
                when (entry.type) {
                    TarEntry.Type.DIRECTORY -> {
                        target.mkdirs()
                        directoryModes += target to entry.mode
                    }
                    TarEntry.Type.SYMLINK -> Os.symlink(entry.linkTarget, target.path)
                    TarEntry.Type.FILE -> {
                        target.outputStream().use { entry.body.copyTo(it, IO_BUFFER) }
                        Os.chmod(target.path, entry.mode and PERMISSIONS or OWNER_READ_WRITE)
                    }
                }
            }
        }
        // Applied last: a read-only directory could not have received its files.
        directoryModes.forEach { (dir, mode) -> Os.chmod(dir.path, mode and PERMISSIONS or OWNER_ALL) }
        if (!partial.renameTo(rootfs)) throw IOException("could not move the unpacked rootfs into place")
        marker.writeText(ROOTFS_VERSION)
        Log.i(TAG, "Linux rootfs ready in ${System.currentTimeMillis() - started} ms")
    }

    private suspend fun execute(command: String, timeoutSeconds: Int): ShellResult {
        val builder = ProcessBuilder(
            File(nativeDir, PROOT).path, "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.path,
            "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/urandom:/dev/random",
            "-b", "/proc", "-b", "/sys",
            // Commands see an empty directory in place of the app's own process (memory, environment).
            "-b", "${hiddenProcess.path}:/proc/${Process.myPid()}",
            "-b", "/proc/self/fd:/dev/fd", "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout", "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${workspace.path}:/workspace",
            "-w", "/workspace",
            "/bin/bash", "-c", command,
        ).redirectErrorStream(true).redirectInput(File("/dev/null"))
        builder.environment().apply {
            clear()
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
            put("TZ", posixTimeZone(TimeZone.getDefault().getOffset(System.currentTimeMillis())))
            put("PROOT_LOADER", File(nativeDir, PROOT_LOADER).path)
            put("PROOT_TMP_DIR", tmp.path)
        }
        val process = builder.start()
        val collected = ShellOutput()
        // A plain thread: its blocking read ends only when the process closes the pipe.
        val reader = Thread({
            runCatching {
                val buffer = ByteArray(IO_BUFFER)
                process.inputStream.use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        collected.append(buffer, n)
                    }
                }
            }
        }, "coati-sandbox-output").apply { isDaemon = true; start() }
        try {
            val exited = withTimeoutOrNull(timeoutSeconds * 1000L) {
                while (process.isAlive) delay(POLL_MS)
                true
            } == true
            if (!exited) process.destroyForcibly()
            reader.join(READER_JOIN_MS)
            return ShellResult(
                exitCode = if (exited) process.exitValue() else null,
                timedOut = !exited,
                output = collected.render(),
                delivered = runCatching { exportOutputs() }
                    .onFailure { Log.w(TAG, "Could not export workspace files: ${it.message}") }
                    .getOrDefault(emptyList()),
            )
        } finally {
            process.destroyForcibly()
        }
    }

    /** Copies new or changed files of /workspace/output to Download/Coati, where the user and other apps can open them. */
    private fun exportOutputs(): List<String> {
        val base = output.toPath()
        val files = Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        val delivered = mutableListOf<String>()
        for (file in files.take(MAX_EXPORT_FILES)) {
            val relative = base.relativize(file).toString()
            if (Files.size(file) > MAX_EXPORT_BYTES) {
                Log.w(TAG, "Not exporting oversized file $relative")
                continue
            }
            val signature = "${Files.size(file)}:${Files.getLastModifiedTime(file).toMillis()}"
            val known = exports[relative]
            if (known?.signature == signature) continue
            val uri = known?.uri ?: createDownload(relative)
            context.contentResolver.openOutputStream(uri, "wt").use { out ->
                requireNotNull(out) { "no output stream for $relative" }
                Files.copy(file, out)
            }
            exports[relative] = Export(uri, signature)
            delivered += relative
        }
        return delivered
    }

    private fun createDownload(relative: String): Uri {
        val file = File(relative)
        val folder = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, EXPORT_DIR, file.parent).joinToString("/")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Download entry refused for $relative")
    }

    /** Does not follow symlinks: a link in the rootfs may name a path that also exists on the host. */
    private fun deleteTree(start: Path) {
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Os.chmod(dir.toString(), OWNER_ALL)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        private const val TAG = "LinuxSandbox"
        private const val PROOT = "libproot.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val ROOTFS_ASSET = "sandbox/rootfs.tar.xz"

        /** Bump when the bundled rootfs changes: an older unpacked copy is then replaced. */
        private const val ROOTFS_VERSION = "alpine-3.22.8-1"
        private const val EXPORT_DIR = "Coati"
        private const val RESOLV_CONF = "nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val MAX_EXPORT_FILES = 50
        private const val MAX_EXPORT_BYTES = 200L * 1024 * 1024
        private const val RECORD_COMMANDS = 8
        private const val RECORD_COMMAND_CHARS = 100
        private const val RECORD_FILES = 40
        private const val IO_BUFFER = 64 * 1024
        private const val POLL_MS = 100L
        private const val READER_JOIN_MS = 2_000L
        private const val PERMISSIONS = 0x1FF // 0777: no setuid, setgid or sticky on app storage
        private const val OWNER_READ_WRITE = 0x180 // 0600
        private const val OWNER_ALL = 0x1C0 // 0700

        const val DEFAULT_TIMEOUT_SECONDS = 120

        /** Set at service start. Prompts offer the shell only on a device that ships the binaries. */
        @Volatile
        var available: Boolean = false

        fun isSupported(context: Context): Boolean =
            File(context.applicationInfo.nativeLibraryDir, PROOT).isFile
    }
}
