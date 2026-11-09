package bot.coati.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class TarReaderTest {
    private fun member(name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", prefix: String = ""): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000755\u0000".toByteArray().copyInto(header, 100)
        (body.size.toString(8).padStart(11, '0') + "\u0000").toByteArray().copyInto(header, 124)
        header[156] = type.code.toByte()
        link.toByteArray().copyInto(header, 157)
        "ustar\u0000".toByteArray().copyInto(header, 257)
        prefix.toByteArray().copyInto(header, 345)
        return header + body + ByteArray((512 - body.size % 512) % 512)
    }

    private data class Seen(val path: String, val type: TarEntry.Type, val body: String, val link: String)

    private fun archive(members: List<ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        members.forEach(bytes::write)
        bytes.write(ByteArray(1024))
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun readAll(vararg members: ByteArray): List<Seen> {
        val seen = mutableListOf<Seen>()
        TarReader.read(archive(members.toList())) {
            seen += Seen(it.path, it.type, it.body.readBytes().decodeToString(), it.linkTarget)
        }
        return seen
    }

    @Test
    fun `reads each member type and keeps position across unread bodies`() {
        val paths = mutableListOf<String>()
        TarReader.read(
            archive(listOf(member("./d/", '5'), member("./d/a", '0', "hello".toByteArray()), member("l", '2', link = "d/a"), member("b", '0', "x".toByteArray()))),
        ) { paths += it.path }
        assertEquals(listOf("d", "d/a", "l", "b"), paths)

        val seen = readAll(member("./d/a", '0', "hello".toByteArray()), member("l", '2', link = "d/a"))
        assertEquals(Seen("d/a", TarEntry.Type.FILE, "hello", ""), seen[0])
        assertEquals(Seen("l", TarEntry.Type.SYMLINK, "", "d/a"), seen[1])
    }

    @Test
    fun `long names come from GNU members pax records and the ustar prefix`() {
        val long = "usr/" + "n".repeat(140)
        val linkRecord = "linkpath=target\n".let { "${it.length + 3} $it" }
        val pathRecord = "path=$long\n".let { "${it.length + 4} $it" }
        val seen = readAll(
            member("././@LongLink", 'L', (long + "\u0000").toByteArray()),
            member("cut", '0', "a".toByteArray()),
            member("pax", 'x', (linkRecord + pathRecord).toByteArray()),
            member("cut", '2'),
            member("name", '0', prefix = "some/prefix"),
        )
        assertEquals(long, seen[0].path)
        assertEquals(long, seen[1].path)
        assertEquals("target", seen[1].link)
        assertEquals("some/prefix/name", seen[2].path)
    }

    @Test
    fun `a member that climbs out of the root is refused`() {
        assertThrows(IOException::class.java) { readAll(member("../evil", '0')) }
        assertThrows(IOException::class.java) { readAll(member("/etc/evil", '0')) }
    }

    @Test
    fun `shell output keeps both ends of a long stream`() {
        val output = ShellOutput(headLimit = 4, tailLimit = 4)
        val bytes = "abcdefghijkl".toByteArray()
        output.append(bytes, bytes.size)
        val text = output.render()
        assertTrue(text.startsWith("abcd"))
        assertTrue(text.endsWith("ijkl"))
        assertTrue(text.contains("4 bytes omitted"))
        val short = ShellOutput(headLimit = 4, tailLimit = 4)
        short.append("abcde".toByteArray(), 5)
        assertEquals("abcde", short.render())
        assertFalse(short.render().contains("omitted"))
    }

    @Test
    fun `time zone sign is the POSIX inverse`() {
        assertEquals("LOC-8:00", posixTimeZone(8 * 3_600_000))
        assertEquals("LOC+5:30", posixTimeZone(-(5 * 3_600_000 + 30 * 60_000)))
    }
}
