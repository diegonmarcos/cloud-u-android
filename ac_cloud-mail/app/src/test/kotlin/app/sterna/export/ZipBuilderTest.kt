package app.sterna.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipBuilderTest {
    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val z = ZipInputStream(ByteArrayInputStream(bytes))
        val out = ArrayList<Pair<String, ByteArray>>()
        while (true) { val e = z.nextEntry ?: break; out += e.name to z.readBytes() }
        return out
    }

    @Test fun `duplicate and missing names are made unique, case-insensitively, extension kept`() {
        assertEquals(
            listOf("a.pdf", "a (2).pdf", "A (3).pdf", "attachment", "attachment (2)", "noext", "noext (2)"),
            dedupeNames(listOf("a.pdf", "a.pdf", "A.pdf", null, "", "noext", "noext")),
        )
    }

    @Test fun `path tricks cannot escape the archive`() {
        assertEquals(listOf("_evil.txt", "x_y.txt"), dedupeNames(listOf("../evil.txt", "x/y.txt")))
        dedupeNames(listOf("../../etc/passwd", "C:\\win\\a.txt")).forEach {
            assertEquals(it, false, it.contains('/') || it.contains('\\') || it.contains(':'))
        }
    }

    @Test fun `zip holds every attachment under its deduped name with identical bytes`() {
        val big = ByteArray(200_000) { (it % 255).toByte() }
        val out = ByteArrayOutputStream()
        writeAttachmentsZip(out, listOf(part("r.bin", big), part("r.bin", byteArrayOf(9, 8)), part(null, byteArrayOf(7))))
        val entries = unzip(out.toByteArray())
        assertEquals(listOf("r.bin", "r (2).bin", "attachment"), entries.map { it.first })
        assertArrayEquals(big, entries[0].second)
        assertArrayEquals(byteArrayOf(9, 8), entries[1].second)
    }

    @Test fun `cancel raises ExportCancelled`() {
        try {
            writeAttachmentsZip(ByteArrayOutputStream(), listOf(part("a", ByteArray(10))), isCancelled = { true })
            org.junit.Assert.fail()
        } catch (_: ExportCancelled) {}
    }
}
