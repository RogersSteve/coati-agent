package bot.coati.state

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File writes that survive a crash or power loss either completely or not at all. */
object DurableFiles {
    /**
     * Writes [content] to a temporary file, syncs it to disk, then renames it over [file]. A rename
     * of an unsynced file can leave a 0-byte [file] after a power loss: task.json and
     * task_result.json came back empty after an emulator was killed, and an empty task state blocks
     * the next task.
     */
    fun writeAtomically(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
