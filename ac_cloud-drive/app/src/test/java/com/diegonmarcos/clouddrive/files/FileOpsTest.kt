package com.diegonmarcos.clouddrive.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #579 the typed IO core, exercised against real files in a temp directory: transfer
 * (unique names, move-then-copy, the free-space refusal BEFORE a byte moves, cancel),
 * archive + extract behind the Zip-Slip guard, archive browsing, rename plans with
 * rollback, mirror's quick check and --delete, the text round-trip guard, search,
 * duplicates. The same algorithms the bridge used to argue in prose, now proved.
 */
class FileOpsTest {

    private val mimes = FileOps.MimeResolver { ext -> when (ext) { "txt", "md" -> "text/plain"; "jpg" -> "image/jpeg"; "zip" -> "application/zip"; else -> null } }
    private fun tmp(): File = Files.createTempDirectory("fileops").toFile()
    private val noProgress = FileOps.Progress { _, _, _ -> }
    private fun never() = false

    @Test fun describeAndSortFoldersFirst() {
        val d = tmp()
        File(d, "b.txt").writeText("bb"); File(d, "a.jpg").writeText("a"); File(d, "zdir").mkdirs(); File(d, ".hidden").writeText("h")
        val all = FileOps.list(d, mimes, showHidden = true)
        assertEquals(4, all.size)
        assertEquals(3, FileOps.list(d, mimes, showHidden = false).size)
        val byName = FileOps.sortEntries(all, "name", false)
        assertEquals("zdir", byName.first().name)
        assertTrue(byName.first().isDirectory)
        assertEquals("inode/directory", byName.first().mime)
        val bySize = FileOps.sortEntries(all, "size", true)
        assertEquals("zdir", bySize.first().name)
        assertEquals("b.txt", bySize[1].name)
        val byType = FileOps.sortEntries(all, "type", false).filter { !it.isDirectory }
        assertEquals(listOf("", "jpg", "txt"), byType.map { it.extension })
        assertEquals("image/jpeg", all.first { it.name == "a.jpg" }.mime)
    }

    @Test fun sanitizeAndUniqueNames() {
        assertNull(FileOps.sanitize("a/b")); assertNull(FileOps.sanitize("..")); assertNull(FileOps.sanitize("  "))
        assertEquals("ok.txt", FileOps.sanitize(" ok.txt "))
        val d = tmp()
        File(d, "report.pdf").writeText("x"); File(d, "report (2).pdf").writeText("x")
        assertEquals("report (3).pdf", FileOps.uniqueIn(d, "report.pdf").name)
        assertEquals("fresh", FileOps.uniqueIn(d, "fresh").name)
        assertEquals("1.5 KB", FileOps.humanBytes(1536))
        assertEquals("12 B", FileOps.humanBytes(12))
    }

    @Test fun transferCopiesTreesWithProgressAndNeverOverwrites() {
        val src = tmp(); val dst = tmp()
        val folder = File(src, "photos").apply { mkdirs() }
        File(folder, "a.jpg").writeBytes(ByteArray(3000) { 1 }); File(folder, "sub/b.txt").apply { parentFile.mkdirs() }.writeText("hello")
        File(dst, "photos").mkdirs()
        var last = 0L; var total = 0L
        val out = FileOps.transfer(listOf(folder), dst, move = false, freeBytes = Long.MAX_VALUE, progress = { done, t, _ -> last = done; total = t }, cancelled = ::never)
        assertTrue(out.ok); assertEquals(1, out.done)
        assertTrue(File(dst, "photos (2)/a.jpg").isFile)
        assertEquals("hello", File(dst, "photos (2)/sub/b.txt").readText())
        assertTrue(File(folder, "a.jpg").isFile)
        assertEquals(3005L, total); assertEquals(3005L, last)
    }

    @Test fun moveAcrossFoldersRemovesTheSource() {
        val src = tmp(); val dst = tmp()
        val f = File(src, "m.txt").apply { writeText("move me") }
        val out = FileOps.transfer(listOf(f), dst, move = true, freeBytes = Long.MAX_VALUE, progress = noProgress, cancelled = ::never)
        assertTrue(out.ok)
        assertFalse(f.exists()); assertEquals("move me", File(dst, "m.txt").readText())
    }

    @Test fun transferRefusesBeforeAByteMovesWhenSpaceIsShort() {
        val src = tmp(); val dst = tmp()
        val f = File(src, "big.bin").apply { writeBytes(ByteArray(10_000)) }
        try {
            FileOps.transfer(listOf(f), dst, move = false, freeBytes = 100, progress = noProgress, cancelled = ::never)
            throw AssertionError("expected NotEnoughSpace")
        } catch (e: FileOps.NotEnoughSpace) {
            assertEquals(10_000L, e.needed); assertEquals(100L, e.free)
        }
        assertEquals(0, dst.listFiles()!!.size)
    }

    @Test fun cancelledCopyLeavesNoPartialFile() {
        val src = tmp(); val dst = tmp()
        val f = File(src, "big.bin").apply { writeBytes(ByteArray(3 * 1024 * 1024)) }
        var calls = 0
        val out = FileOps.transfer(listOf(f), dst, move = false, freeBytes = Long.MAX_VALUE, progress = { _, _, _ -> calls++ }, cancelled = { calls >= 1 })
        assertFalse(out.ok)
        assertFalse(File(dst, "big.bin").exists())
    }

    @Test fun archiveThenBrowseThenExtractWithPrefix() {
        val d = tmp()
        val folder = File(d, "proj").apply { mkdirs() }
        File(folder, "readme.md").writeText("# hi"); File(folder, "src/main.kt").apply { parentFile.mkdirs() }.writeText("fun main() {}")
        File(d, "loose.txt").writeText("loose")
        val zip = File(d, "out.zip")
        val n = FileOps.archive(listOf(folder, File(d, "loose.txt")), zip, noProgress, ::never)
        assertEquals(3, n); assertTrue(zip.isFile)
        // Browsed as a folder: the root shows proj/ and loose.txt; proj/ shows readme.md and src/.
        val root = ArchiveFs.list(Location.Archive(zip.absolutePath), mimes)
        assertEquals(setOf("proj", "loose.txt"), root.map { it.name }.toSet())
        assertTrue(root.first { it.name == "proj" }.isDirectory)
        val proj = ArchiveFs.list(Location.Archive(zip.absolutePath, "proj"), mimes)
        assertEquals(setOf("readme.md", "src"), proj.map { it.name }.toSet())
        assertEquals("text/plain", proj.first { it.name == "readme.md" }.mime)
        assertNotNull(proj.first { it.name == "readme.md" }.compressedSize)
        val src = ArchiveFs.list(Location.Archive(zip.absolutePath, "proj/src"), mimes)
        assertEquals(listOf("main.kt"), src.map { it.name })
        assertEquals(Location.Archive(zip.absolutePath, "proj/src/main.kt"), src.first().location)
        // Extract only proj/src out of the archive: lands as src/main.kt, not proj/src/main.kt.
        val dest = tmp()
        val count = FileOps.extract(zip, dest, noProgress, ::never, onlyUnder = setOf("proj/src"))
        assertEquals(1, count)
        assertEquals("fun main() {}", File(dest, "src/main.kt").readText())
        assertFalse(File(dest, "proj").exists())
        // Whole extraction.
        val dest2 = tmp()
        assertEquals(3, FileOps.extract(zip, dest2, noProgress, ::never))
        assertEquals("# hi", File(dest2, "proj/readme.md").readText())
    }

    @Test fun zipSlipIsRefusedWholesaleBeforeAnythingIsWritten() {
        val d = tmp()
        val zip = File(d, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("fine.txt")); z.write("ok".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../escape.txt")); z.write("no".toByteArray()); z.closeEntry()
        }
        val dest = File(d, "dest").apply { mkdirs() }
        try { FileOps.extract(zip, dest, noProgress, ::never); throw AssertionError("expected refusal") } catch (e: IOException) { assertTrue(e.message!!.contains("outside")) }
        assertEquals(0, dest.listFiles()!!.size)
        assertFalse(File(d, "escape.txt").exists())
        assertNull(zipEntryTarget(dest, "../x")); assertNull(zipEntryTarget(dest, "/abs")); assertNull(zipEntryTarget(dest, "a\\..\\b")); assertNull(zipEntryTarget(dest, "./"))
        assertEquals(File(dest, "a/b").canonicalFile, zipEntryTarget(dest, "a\\b"))
    }

    @Test fun renamePlanValidatesAndRollsBack() {
        val d = tmp()
        val a = File(d, "a.txt").apply { writeText("a") }; val b = File(d, "b.txt").apply { writeText("b") }
        val plan = FileOps.renamePlan(listOf(a, b), "photo-{n}.{ext}")
        assertEquals(listOf("photo-1.txt", "photo-2.txt"), plan.map { it.newName })
        assertTrue(plan.all { it.reason == null })
        assertEquals(2, FileOps.applyRenamePlan(plan))
        assertTrue(File(d, "photo-1.txt").isFile)
        // A pattern that collides with itself is blocked.
        val same = FileOps.renamePlan(listOf(File(d, "photo-1.txt"), File(d, "photo-2.txt")), "same.txt")
        assertNotNull(same[1].reason)
        try { FileOps.applyRenamePlan(same); throw AssertionError("expected conflict") } catch (e: IOException) { assertTrue(e.message!!.contains("conflicts")) }
        // An unchanged name is a no-op with a reason, not a conflict.
        val noop = FileOps.renamePlan(listOf(File(d, "photo-1.txt")), "{name}.{ext}")
        assertEquals("the name does not change", noop.first().reason)
        assertEquals(0, FileOps.applyRenamePlan(noop))
    }

    @Test fun mirrorQuickCheckAndDelete() {
        val src = tmp(); val dst = tmp()
        File(src, "keep.txt").writeText("keep"); File(src, "deep/x.txt").apply { parentFile.mkdirs() }.writeText("x")
        File(dst, "extra.txt").writeText("gone soon")
        val first = FileOps.mirror(src, dst, deleteExtra = true, cancelled = ::never) { _, _ -> }
        assertEquals(2, first.copied); assertEquals(1, first.deleted); assertTrue(first.ok)
        assertFalse(File(dst, "extra.txt").exists())
        val second = FileOps.mirror(src, dst, deleteExtra = false, cancelled = ::never) { _, _ -> }
        assertEquals(0, second.copied); assertEquals(2, second.skipped)
        try { FileOps.mirror(src, File(src, "inside"), false, ::never) { _, _ -> }; throw AssertionError("expected refusal") } catch (e: IOException) { assertTrue(e.message!!.contains("inside")) }
    }

    @Test fun textRoundTripGuardAndAtomicSave() {
        val d = tmp()
        val t = File(d, "t.txt").apply { writeText("héllo") }
        assertEquals("héllo", FileOps.readText(t))
        val bin = File(d, "b.bin").apply { writeBytes(byteArrayOf(0x41, 0x00, 0x42)) }
        try { FileOps.readText(bin); throw AssertionError() } catch (e: IOException) { assertTrue(e.message!!.contains("not a text file")) }
        val latin = File(d, "l.txt").apply { writeBytes(byteArrayOf(0x68, 0xE9.toByte(), 0x6C)) }
        try { FileOps.readText(latin); throw AssertionError() } catch (e: IOException) { assertTrue(e.message!!.contains("UTF-8")) }
        FileOps.writeText(t, "changed")
        assertEquals("changed", t.readText())
        assertEquals("no scratch file is left behind", 0, d.listFiles()!!.count { it.name.endsWith(".clouddrive-save") })
    }

    @Test fun searchByNameAndContentWithCeiling() {
        val d = tmp()
        File(d, "notes/todo.md").apply { parentFile.mkdirs() }.writeText("buy milk")
        File(d, "other.txt").writeText("nothing here")
        File(d, "MILK-photo.jpg").writeText("binary")
        val hits = mutableListOf<FileOps.Hit>()
        val truncated = FileOps.search(d, "milk", contentToo = true, mimes, ::never, onScanned = {}, onHit = { hits += it })
        assertFalse(truncated)
        assertEquals(setOf("todo.md", "MILK-photo.jpg"), hits.map { it.entry.name }.toSet())
        assertTrue(hits.first { it.entry.name == "todo.md" }.matchedByContent)
        assertFalse(hits.first { it.entry.name == "MILK-photo.jpg" }.matchedByContent)
        val nameOnly = mutableListOf<FileOps.Hit>()
        FileOps.search(d, "milk", contentToo = false, mimes, ::never, {}, { nameOnly += it })
        assertEquals(listOf("MILK-photo.jpg"), nameOnly.map { it.entry.name })
    }

    @Test fun duplicatesHashOnlyInsideSizeBuckets() {
        val d = tmp()
        File(d, "a.bin").writeBytes(ByteArray(100) { 7 }); File(d, "b.bin").writeBytes(ByteArray(100) { 7 }); File(d, "c.bin").writeBytes(ByteArray(100) { 8 }); File(d, "d.bin").writeBytes(ByteArray(50))
        val groups = FileOps.duplicates(d, ::never) {}
        assertEquals(1, groups.size)
        assertEquals(setOf(File(d, "a.bin").absolutePath, File(d, "b.bin").absolutePath), groups.first().paths.toSet())
        assertEquals(100L, groups.first().size)
    }

    @Test fun propertiesCountsAndHashes() {
        val d = tmp()
        val f = File(d, "h.txt").apply { writeText("abc") }
        val p = FileOps.properties(f, ::never, noProgress)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", p.md5)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", p.sha256)
        assertEquals(1, p.files); assertEquals(3L, p.size)
        File(d, "sub/x").apply { parentFile.mkdirs() }.writeText("xx")
        val folder = FileOps.properties(d, ::never, noProgress)
        assertEquals(2, folder.files); assertEquals(1, folder.folders); assertEquals(5L, folder.size); assertNull(folder.md5)
    }
}
