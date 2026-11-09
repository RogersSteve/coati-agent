package bot.coati.sandbox

import java.io.ByteArrayOutputStream

/** Keeps the start and the end of a command's output: the model needs both the first error and the last line. */
class ShellOutput(private val headLimit: Int = 4_000, private val tailLimit: Int = 8_000) {
    private val head = ByteArrayOutputStream()
    private val tail = ByteArray(tailLimit)
    private var tailEnd = 0
    private var tailBytes = 0L
    private val lock = Any()

    fun append(bytes: ByteArray, count: Int) = synchronized(lock) {
        var from = 0
        if (head.size() < headLimit) {
            from = minOf(headLimit - head.size(), count)
            head.write(bytes, 0, from)
        }
        for (i in from until count) {
            tail[tailEnd] = bytes[i]
            tailEnd = (tailEnd + 1) % tailLimit
            tailBytes++
        }
    }

    fun render(): String = synchronized(lock) {
        val kept = minOf(tailBytes, tailLimit.toLong()).toInt()
        val last = ByteArray(kept) { tail[(tailEnd - kept + it + tailLimit) % tailLimit] }
        val start = head.toString("UTF-8")
        val end = String(last, Charsets.UTF_8)
        if (tailBytes > kept) "$start\n...[${tailBytes - kept} bytes omitted]...\n$end" else start + end
    }
}

/** POSIX TZ for Alpine, which carries no zone database. The sign is inverted: UTC+8 is written "-8". */
internal fun posixTimeZone(offsetMillis: Int): String {
    val minutes = kotlin.math.abs(offsetMillis) / 60_000
    val sign = if (offsetMillis >= 0) "-" else "+"
    return "LOC$sign${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}
