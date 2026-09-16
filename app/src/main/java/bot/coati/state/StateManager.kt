package bot.coati.state

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StateManager(private val baseDir: File) {

    init {
        baseDir.mkdirs()
    }

    fun writeJson(name: String, content: String) {
        val file = File(baseDir, "$name.json")
        atomicWrite(file, content)
    }

    fun readJson(name: String): String? {
        val file = File(baseDir, "$name.json")
        return if (file.exists()) file.readText() else null
    }

    fun deleteJson(name: String) {
        val file = File(baseDir, "$name.json")
        file.delete()
    }

    fun writeMarkdown(name: String, content: String) {
        val file = File(baseDir, "$name.md")
        atomicWrite(file, content)
    }

    fun readMarkdown(name: String): String? {
        val file = File(baseDir, "$name.md")
        return if (file.exists()) file.readText() else null
    }

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
