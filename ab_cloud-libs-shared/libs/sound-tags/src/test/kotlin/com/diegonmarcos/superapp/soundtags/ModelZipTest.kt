package com.diegonmarcos.superapp.soundtags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelZipTest {
    private val prefix = ByteArray(1000) { (it * 7).toByte() }

    /** A zip appended after [prefix] by java.util.zip: offsets counted from the start of the ZIP. */
    private fun relative(entries: Map<String, ByteArray>, method: Int = ZipEntry.STORED): ByteArray {
        val z = ByteArrayOutputStream()
        ZipOutputStream(z).use { out ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.method = method
                if (method == ZipEntry.STORED) { e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = CRC32().apply { update(data) }.value }
                out.putNextEntry(e); out.write(data); out.closeEntry()
            }
        }
        return prefix + z.toByteArray()
    }

    /** The TFLite metadata writer's layout: the same zip with offsets counted from the start of the FILE. */
    private fun absolute(entries: Map<String, ByteArray>): ByteArray {
        val b = relative(entries)
        val eocd = (b.size - 22 downTo 0).first { u32(b, it) == 0x06054b50L }
        fun put32(at: Int, v: Long) { for (k in 0 until 4) b[at + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        val cenOff = u32(b, eocd + 16)
        var p = (cenOff + prefix.size).toInt()
        repeat(u16(b, eocd + 10)) {
            put32(p + 42, u32(b, p + 42) + prefix.size)
            p += 46 + u16(b, p + 28) + u16(b, p + 30) + u16(b, p + 32)
        }
        put32(eocd + 16, cenOff + prefix.size)
        return b
    }

    private val labels = "Speech\nChild speech, kid speaking\n\n  Silence  \n".toByteArray()

    @Test fun readsAStoredEntryWithRelativeOffsets() {
        val b = relative(mapOf("other.txt" to byteArrayOf(1, 2), "yamnet_label_list.txt" to labels))
        assertArrayEquals(labels, ModelZip.entry(b, "yamnet_label_list.txt"))
        assertArrayEquals(byteArrayOf(1, 2), ModelZip.entry(b, "other.txt"))
    }

    @Test fun readsAStoredEntryWithAbsoluteOffsets() {
        val b = absolute(mapOf("a.txt" to byteArrayOf(5), "labels.txt" to labels))
        assertArrayEquals(labels, ModelZip.entry(b, "labels.txt"))
        assertEquals(listOf("Speech", "Child speech, kid speaking", "Silence"), ModelZip.lines(b, "labels.txt"))
    }

    @Test fun anAbsentEntryOrNoZipIsNull() {
        assertNull(ModelZip.entry(relative(mapOf("a.txt" to labels)), "b.txt"))
        assertNull(ModelZip.entry(prefix, "a.txt"))
        assertNull(ModelZip.entry(ByteArray(10), "a.txt"))
        assertTrue(ModelZip.lines(prefix, "a.txt").isEmpty())
    }

    @Test fun aCompressedEntryIsRefusedNotMisread() {
        try { ModelZip.entry(relative(mapOf("a.txt" to labels), ZipEntry.DEFLATED), "a.txt"); fail("deflated entry read as stored") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("compressed"))
        }
    }

    @Test fun aCorruptDirectoryIsSaid() {
        val b = relative(mapOf("a.txt" to labels))
        val eocd = (b.size - 22 downTo 0).first { u32(b, it) == 0x06054b50L }
        val cen = eocd - u32(b, eocd + 12).toInt()
        val broken = b.copyOf(); broken[cen] = 0
        try { ModelZip.entry(broken, "a.txt"); fail("corrupt central directory") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("central")) }
        val badLocal = b.copyOf(); badLocal[prefix.size] = 0
        try { ModelZip.entry(badLocal, "a.txt"); fail("corrupt local header") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("local header")) }
    }
}
