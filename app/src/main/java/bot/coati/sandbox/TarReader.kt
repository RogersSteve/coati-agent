package bot.coati.sandbox

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** One archive member. [body] is valid only during the visit and is empty unless the member is a file. */
class TarEntry(
    val path: String,
    val type: Type,
    val mode: Int,
    val size: Long,
    val linkTarget: String,
    val body: InputStream,
) {
    enum class Type { FILE, DIRECTORY, SYMLINK }
}

/** Streaming reader for the ustar, GNU long-name and pax members that the bundled rootfs uses. */
object TarReader {
    private const val BLOCK = 512

    fun read(input: InputStream, visit: (TarEntry) -> Unit) {
        var longPath: String? = null
        var longLink: String? = null
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readBlock(input, header) || header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> longPath = cString(readBody(input, size), 0, size.toInt())
                'K' -> longLink = cString(readBody(input, size), 0, size.toInt())
                'x' -> paxRecords(readBody(input, size)).let { pax ->
                    pax["path"]?.let { longPath = it }
                    pax["linkpath"]?.let { longLink = it }
                }
                'g' -> readBody(input, size)
                else -> {
                    val path = normalize(longPath ?: headerPath(header))
                    val link = longLink ?: cString(header, 157, 100)
                    longPath = null
                    longLink = null
                    val kind = when (type) {
                        '0', '\u0000', '7' -> TarEntry.Type.FILE
                        '5' -> TarEntry.Type.DIRECTORY
                        '2' -> TarEntry.Type.SYMLINK
                        else -> throw IOException("unsupported tar member type '$type' at $path")
                    }
                    val bodySize = if (kind == TarEntry.Type.FILE) size else 0L
                    val body = Bounded(input, bodySize)
                    if (path.isNotEmpty()) {
                        visit(TarEntry(path, kind, octal(header, 100, 8).toInt(), bodySize, link, body))
                    }
                    body.drain()
                    skipPadding(input, bodySize)
                }
            }
        }
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var filled = 0
        while (filled < block.size) {
            val n = input.read(block, filled, block.size - filled)
            if (n < 0) {
                if (filled == 0) return false
                throw EOFException("truncated tar block")
            }
            filled += n
        }
        return true
    }

    private fun readBody(input: InputStream, size: Long): ByteArray {
        val bytes = readExactly(input, size.toInt())
        skipPadding(input, size)
        return bytes
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = input.read(bytes, filled, size - filled)
            if (n < 0) throw EOFException("truncated tar member")
            filled += n
        }
        return bytes
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (padding > 0) readExactly(input, padding)
    }

    private fun headerPath(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val isUstar = cString(header, 257, 5) == "ustar"
        val prefix = if (isUstar) cString(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** Relative, without "./" or a trailing slash; a member that climbs out of the root is rejected. */
    private fun normalize(raw: String): String {
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in parts || raw.startsWith("/")) throw IOException("unsafe tar path: $raw")
        return parts.joinToString("/")
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
        val text = cString(bytes, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** Each pax record is "<decimal record length> <key>=<value>\n", the length counting the whole record. */
    private fun paxRecords(body: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var at = 0
        while (at < body.size) {
            var space = at
            while (space < body.size && body[space] != ' '.code.toByte()) space++
            if (space >= body.size) break
            val length = String(body, at, space - at, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || at + length > body.size) break
            val record = String(body, space + 1, at + length - space - 2, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) records[record.substring(0, equals)] = record.substring(equals + 1)
            at += length
        }
        return records
    }

    private class Bounded(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = input.read()
            if (value < 0) throw EOFException("truncated tar member")
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("truncated tar member")
            remaining -= n
            return n
        }

        fun drain() {
            val scrap = ByteArray(8192)
            while (read(scrap, 0, scrap.size) >= 0) Unit
        }
    }
}
