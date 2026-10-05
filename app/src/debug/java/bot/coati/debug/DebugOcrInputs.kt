package bot.coati.debug

import java.io.File

/** Debug-only input validation, before initializing the Vulkan OCR engine. */
internal object DebugOcrInputs {
    fun directory(storage: String, filesDir: File, external: () -> File?): File = when (storage) {
        "external" -> requireNotNull(external()) { "Input directory unavailable" }
        "internal" -> File(filesDir, "ocr_bench")
        else -> throw IllegalArgumentException("Unknown input_storage: $storage")
    }

    fun validate(directory: File): List<File> {
        check(directory.isDirectory) { "Input directory missing: $directory" }
        val files = checkNotNull(directory.listFiles { file ->
            file.extension.lowercase() in setOf("jpg", "jpeg", "png")
        }) { "Cannot list input directory: $directory" }.sortedBy { it.name }
        check(files.isNotEmpty()) { "No PNG/JPEG inputs in $directory" }
        files.forEach { file ->
            check(file.isFile && file.canRead()) { "Unreadable input: ${file.name}" }
            file.inputStream().use { it.read() }
        }
        return files
    }
}
