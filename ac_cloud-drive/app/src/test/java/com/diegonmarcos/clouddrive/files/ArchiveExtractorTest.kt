package com.diegonmarcos.clouddrive.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Unzip…: every format round-trips, and a hostile archive is refused whole BEFORE a byte is written (Zip Slip). */
class ArchiveExtractorTest {

    private val noProgress = FileOps.Progress { _, _, _ -> }
    private fun tmp(): File = Files.createTempDirectory("unzip").toFile()

    private fun zip(file: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(FileOutputStream(file)).use { z -> for ((n, body) in entries) { z.putNextEntry(ZipEntry(n)); if (!n.endsWith("/")) z.write(body.toByteArray()); z.closeEntry() } }
    }

    private fun tarBytes(entries: List<Pair<String, String>>, symlink: Pair<String, String>? = null): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(bos).use { t ->
            for ((n, body) in entries) {
                val e = TarArchiveEntry(n); e.size = body.toByteArray().size.toLong()
                t.putArchiveEntry(e); t.write(body.toByteArray()); t.closeArchiveEntry()
            }
            symlink?.let { (n, target) -> val e = TarArchiveEntry(n, TarConstants.LF_SYMLINK); e.linkName = target; t.putArchiveEntry(e); t.closeArchiveEntry() }
        }
        return bos.toByteArray()
    }

    private fun extract(archive: File, dest: File, cancelled: () -> Boolean = { false }, free: Long = Long.MAX_VALUE) = ArchiveExtractor.extract(archive, dest, noProgress, cancelled, free)

    @Test fun zipRoundTripWithNestedFoldersAndProgress() {
        val dir = tmp(); val a = File(dir, "pack.zip")
        zip(a, listOf("one.txt" to "1", "sub/" to "", "sub/two.txt" to "22", "sub/deep/three.txt" to "333"))
        val seen = ArrayList<String>()
        val r = ArchiveExtractor.extract(a, File(dir, "pack"), FileOps.Progress { _, _, cur -> seen += cur }, { false })
        assertEquals(3, r.files); assertEquals(6L, r.bytes)
        assertEquals("333", File(dir, "pack/sub/deep/three.txt").readText())
        assertTrue(seen.contains("three.txt"))
    }

    @Test fun zipSlipIsRejectedWholeAndNothingIsWritten() {
        for (evil in listOf("../evil.txt", "a/../../evil.txt", "/abs/evil.txt", "..\\evil.txt", "sub/..\\..\\evil.txt")) {
            val dir = tmp(); val a = File(dir, "bad.zip")
            zip(a, listOf("fine.txt" to "ok", evil to "pwned"))
            val dest = File(dir, "out")
            try { extract(a, dest); fail("accepted $evil") } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("outside the destination")) }
            assertFalse("nothing may be created for $evil", dest.exists())
            assertFalse(File(dir, "evil.txt").exists())
            assertFalse(File(dir.parentFile, "evil.txt").exists())
        }
    }

    @Test fun tarSlipIsRejectedToo() {
        val dir = tmp(); val a = File(dir, "bad.tar")
        a.writeBytes(tarBytes(listOf("fine.txt" to "ok", "../evil.txt" to "pwned")))
        val dest = File(dir, "out")
        try { extract(a, dest); fail("accepted a tar slip") } catch (e: IOException) { assertTrue(e.message!!.contains("outside the destination")) }
        assertFalse(dest.exists()); assertFalse(File(dir, "evil.txt").exists())
    }

    @Test fun sevenZipSlipIsRejectedToo() {
        val dir = tmp(); val a = File(dir, "bad.7z")
        SevenZOutputFile(a).use { z ->
            val e = SevenZArchiveEntry(); e.name = "../evil.txt"; e.setHasStream(true); e.size = 5
            z.putArchiveEntry(e); z.write("pwned".toByteArray()); z.closeArchiveEntry()
        }
        val dest = File(dir, "out")
        try { extract(a, dest); fail("accepted a 7z slip") } catch (e: IOException) { assertTrue(e.message!!.contains("outside the destination")) }
        assertFalse(dest.exists()); assertFalse(File(dir, "evil.txt").exists())
    }

    @Test fun tarTarGzAndTarXzRoundTrip() {
        val dir = tmp()
        val raw = tarBytes(listOf("a.txt" to "alpha", "d/b.txt" to "beta"))
        File(dir, "p.tar").writeBytes(raw)
        GZIPOutputStream(FileOutputStream(File(dir, "p.tar.gz"))).use { it.write(raw) }
        GZIPOutputStream(FileOutputStream(File(dir, "q.tgz"))).use { it.write(raw) }
        XZCompressorOutputStream(FileOutputStream(File(dir, "p.tar.xz"))).use { it.write(raw) }
        for (name in listOf("p.tar", "p.tar.gz", "q.tgz", "p.tar.xz")) {
            val dest = File(dir, "out-$name")
            val r = extract(File(dir, name), dest)
            assertEquals(name, 2, r.files)
            assertEquals(name, "beta", File(dest, "d/b.txt").readText())
        }
    }

    @Test fun sevenZRoundTrip() {
        val dir = tmp(); val a = File(dir, "p.7z")
        SevenZOutputFile(a).use { z ->
            for ((n, body) in listOf("x.txt" to "xx", "dir/y.txt" to "yyy")) {
                val e = SevenZArchiveEntry(); e.name = n; e.setHasStream(true); e.size = body.length.toLong()
                z.putArchiveEntry(e); z.write(body.toByteArray()); z.closeArchiveEntry()
            }
        }
        val r = extract(a, File(dir, "p"))
        assertEquals(2, r.files)
        assertEquals("yyy", File(dir, "p/dir/y.txt").readText())
    }

    @Test fun linksInATarAreSkippedNeverCreated() {
        val dir = tmp(); val a = File(dir, "l.tar")
        a.writeBytes(tarBytes(listOf("ok.txt" to "ok"), symlink = "link" to "/etc/passwd"))
        val r = extract(a, File(dir, "out"))
        assertEquals(1, r.files); assertEquals(1, r.skipped)
        assertTrue(File(dir, "out/ok.txt").isFile)
        assertFalse(Files.isSymbolicLink(File(dir, "out/link").toPath())); assertFalse(File(dir, "out/link").exists())
    }

    @Test fun cancelRemovesWhatWasWrittenAndNotEnoughSpaceIsRefusedUpFront() {
        val dir = tmp(); val a = File(dir, "c.zip")
        zip(a, listOf("1.txt" to "a", "2.txt" to "b", "3.txt" to "c"))
        var calls = 0
        val dest = File(dir, "out")
        try { extract(a, dest, cancelled = { ++calls > 5 }); fail("expected cancel") } catch (e: IOException) { assertEquals("cancelled", e.message) }
        assertEquals(emptyList<String>(), dest.walkTopDown().filter { it.isFile }.map { it.name }.toList())
        val dest2 = File(dir, "out2")
        try { extract(a, dest2, free = 2L); fail("expected NotEnoughSpace") } catch (e: FileOps.NotEnoughSpace) { assertEquals(3L, e.needed) }
        assertFalse(dest2.exists())
    }

    @Test fun kindsAndTheDefaultSiblingFolderName() {
        assertEquals(ArchiveExtractor.Kind.TAR_GZ, ArchiveExtractor.kindOf("Photos.TAR.GZ"))
        assertEquals(ArchiveExtractor.Kind.SEVEN_Z, ArchiveExtractor.kindOf("a.7z"))
        assertEquals(ArchiveExtractor.Kind.RAR, ArchiveExtractor.kindOf("a.rar"))
        assertNull(ArchiveExtractor.kindOf("a.txt")); assertNull(ArchiveExtractor.kindOf(".zip"))
        assertEquals("photos", ArchiveExtractor.defaultFolderName("photos.tar.gz"))
        assertEquals("app-debug", ArchiveExtractor.defaultFolderName("app-debug.apk"))
        assertEquals("my.backup", ArchiveExtractor.defaultFolderName("my.backup.zip"))
        assertEquals("data", ArchiveExtractor.defaultFolderName("data.7z"))
    }
}
