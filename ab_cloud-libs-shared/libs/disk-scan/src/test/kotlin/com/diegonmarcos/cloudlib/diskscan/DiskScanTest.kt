package com.diegonmarcos.cloudlib.diskscan

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #813 the Disk Management engine's arithmetic, on real files in a temp tree. */
class DiskScanTest {

    private fun tree(): File = Files.createTempDirectory("disk").toFile()
    private fun File.put(rel: String, bytes: ByteArray): File = File(this, rel).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    private fun e(path: String, bytes: Long) = Entry(path, bytes, 0)

    // ── walk + map ──────────────────────────────────────────────────────

    @Test fun `walk finds every file with its size and the map adds up to the total`() {
        val r = tree()
        r.put("a/x.bin", ByteArray(10)); r.put("a/b/y.bin", ByteArray(5)); r.put("c/z.bin", ByteArray(30)); r.put("top.txt", ByteArray(2))
        val t = Walk.tree(r)
        assertFalse(t.truncated)
        assertEquals(4, t.entries.size)
        assertEquals(47L, t.bytes)
        val map = StorageMap.slices(t)
        assertEquals(listOf("c", "a", StorageMap.ROOT_FILES), map.map { it.name })
        assertEquals(listOf(30L, 15L, 2L), map.map { it.bytes })
        assertEquals(listOf(1, 2, 1), map.map { it.files })
        assertEquals(t.bytes, map.sumOf { it.bytes })
    }

    @Test fun `map ties break by name and ignores paths outside the root`() {
        val t = Tree("/r", listOf(e("/r/b/1", 5), e("/r/a/1", 5), e("/elsewhere/x", 99)), false)
        val m = StorageMap.slices(t)
        assertEquals(listOf("a", "b"), m.map { it.name })
        assertEquals(10L, m.sumOf { it.bytes })
    }

    @Test fun `walk stops at its cap and says so, and skips what it is told`() {
        val r = tree()
        repeat(5) { r.put("f$it", ByteArray(1)) }
        r.put("skip/me", ByteArray(100))
        val capped = Walk.tree(r, maxFiles = 3)
        assertTrue(capped.truncated)
        assertEquals(3, capped.entries.size)
        val skipped = Walk.tree(r, skip = { it.name == "skip" })
        assertEquals(5, skipped.entries.size)
        assertFalse(skipped.truncated)
        assertEquals(6, Walk.tree(r, maxFiles = 6).entries.size)
        assertFalse(Walk.tree(r, maxFiles = 6).truncated)
    }

    @Test fun `walk never follows a symbolic link`() {
        val r = tree()
        r.put("real/x", ByteArray(7))
        Files.createSymbolicLink(File(r, "loop").toPath(), r.toPath())
        val t = Walk.tree(r)
        assertEquals(listOf(7L), t.entries.map { it.bytes })
    }

    // ── huge files ─────────────────────────────────────────────────────

    @Test fun `huge files are at or above the threshold, largest first, capped`() {
        val es = listOf(e("/a", 99), e("/b", 100), e("/c", 300), e("/d", 200), e("/e", 100))
        assertEquals(listOf("/c", "/d", "/b", "/e"), HugeFiles.find(es, 100, 10).map { it.path })
        assertEquals(listOf("/c", "/d"), HugeFiles.find(es, 100, 2).map { it.path })
        assertEquals(emptyList<Entry>(), HugeFiles.find(es, 301, 10))
        assertEquals(listOf("/c"), HugeFiles.find(es, 300, 10).map { it.path })
        assertEquals(emptyList<Entry>(), HugeFiles.find(es, 0, 0))
        assertEquals(5, HugeFiles.find(es, 0, 10).size)
    }

    @Test fun `a negative threshold is refused`() {
        try { HugeFiles.find(emptyList(), -1, 1); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `thresholds parse with binary suffixes`() {
        assertEquals(100L, HugeFiles.parseThreshold("100"))
        assertEquals(2048L, HugeFiles.parseThreshold("2K"))
        assertEquals(5L * 1024 * 1024, HugeFiles.parseThreshold("5MB"))
        assertEquals(1L * 1024 * 1024 * 1024, HugeFiles.parseThreshold(" 1g "))
        assertNull(HugeFiles.parseThreshold("abc"))
        assertNull(HugeFiles.parseThreshold(""))
        assertNull(HugeFiles.parseThreshold(null))
        assertNull(HugeFiles.parseThreshold("-5"))
        assertNull(HugeFiles.parseThreshold("M"))
    }

    // ── duplicates ─────────────────────────────────────────────────────

    @Test fun `duplicates need a full-hash match, not just a size and a first block`() {
        val r = tree()
        val big = Duplicates.PARTIAL_BYTES.toInt() + 10
        val base = ByteArray(big) { (it % 251).toByte() }
        val a = r.put("a.bin", base); val b = r.put("dir/b.bin", base.copyOf())
        // same size, same first block, different tail: NOT a duplicate
        val c = r.put("c.bin", base.copyOf().also { it[big - 1] = 99 })
        // small identical pair (whole file inside the partial window)
        val s1 = r.put("s1", byteArrayOf(1, 2, 3)); val s2 = r.put("s2", byteArrayOf(1, 2, 3))
        // same size, different content
        r.put("s3", byteArrayOf(9, 9, 9))
        // empty files are never grouped
        r.put("e1", ByteArray(0)); r.put("e2", ByteArray(0))
        val groups = Duplicates.groups(Walk.tree(r).entries)
        assertEquals(2, groups.size)
        assertEquals(listOf(a.absolutePath, b.absolutePath).sorted(), groups[0].paths)
        assertEquals(big.toLong(), groups[0].bytes)
        assertEquals(big.toLong(), groups[0].reclaimable)
        assertFalse(groups.any { c.absolutePath in it.paths })
        assertEquals(listOf(s1.absolutePath, s2.absolutePath).sorted(), groups[1].paths)
        assertEquals(3L, groups[1].reclaimable)
        assertEquals(Duplicates.sha256(a.absolutePath, null), groups[0].hash)
    }

    @Test fun `duplicates read the fewest bytes and honour the minimum size`() {
        val calls = ArrayList<Pair<String, Long?>>()
        val h: (String, Long?) -> String? = { p, l -> calls += p to l; if (p == "/u") "u" else "same" }
        val es = listOf(e("/x", 10), e("/y", 10), e("/u", 10), e("/lonely", 11), e("/big1", 1_000_000), e("/big2", 1_000_000))
        val g = Duplicates.groups(es, hash = h)
        assertEquals(listOf(listOf("/big1", "/big2"), listOf("/x", "/y")), g.map { it.paths })
        assertFalse("a unique size is never hashed", calls.any { it.first == "/lonely" })
        assertFalse("a small file is hashed once, inside the partial window", calls.any { it.first == "/x" && it.second == null })
        assertTrue("a big file is hashed whole after its partial matched", calls.contains("/big1" to null))
        assertEquals(1, Duplicates.groups(es, minBytes = 11, hash = h).size)
        assertEquals(2, Duplicates.groups(es, minBytes = 10, hash = h).size)
        // an unreadable file is never called a duplicate
        assertEquals(emptyList<Duplicates.Group>(), Duplicates.groups(listOf(e("/p", 5), e("/q", 5)), hash = { _, _ -> null }))
        // the full hash disagrees: not duplicates
        val split: (String, Long?) -> String? = { p, l -> if (l == null) p else "head" }
        assertEquals(emptyList<Duplicates.Group>(), Duplicates.groups(listOf(e("/m", 999_999), e("/n", 999_999)), hash = split))
    }

    @Test fun `keep-one deletes every other copy and refuses a stranger`() {
        val g = Duplicates.Group("h", 4, listOf("/a", "/b", "/c"))
        assertEquals(listOf("/a", "/c"), Duplicates.keepOne(g, "/b"))
        assertEquals(8L, g.reclaimable)
        try { Duplicates.keepOne(g, "/z"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `sha256 honours its limit and answers null for a missing file`() {
        val r = tree()
        val f = r.put("f", "abcdef".toByteArray())
        val g = r.put("g", "abc".toByteArray())
        assertEquals(Duplicates.sha256(g.absolutePath, null), Duplicates.sha256(f.absolutePath, 3))
        assertEquals("bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721", Duplicates.sha256(f.absolutePath, null))
        assertNull(Duplicates.sha256(File(r, "nope").absolutePath, null))
    }

    // ── clean plan ─────────────────────────────────────────────────────

    @Test fun `the dry run is exactly what the run reclaims`() {
        val r = tree()
        r.put("dl/movie.mp4.part", ByteArray(500)); r.put("doc/~\$draft.docx", ByteArray(40)); r.put("x.TMP", ByteArray(7))
        r.put("keep/photo.jpg", ByteArray(900)); r.put("pending/app.apk.tmp", ByteArray(1000))
        val entries = Walk.tree(r).entries
        val plan = CleanPlan.plan(CleanPlan.temp(entries), protect = { "/pending/" in it })
        assertEquals(547L, plan.bytes)
        assertEquals(3, plan.items.size)
        val res = CleanPlan.run(plan, size = { p -> File(p).takeIf { it.isFile }?.length() }, delete = { File(it).delete() })
        assertEquals(plan.bytes, res.reclaimed)
        assertEquals(plan.items.map { it.path }, res.deleted)
        assertTrue(res.skipped.isEmpty())
        assertTrue(File(r, "keep/photo.jpg").isFile)
        assertTrue("a protected path is never touched", File(r, "pending/app.apk.tmp").isFile)
        assertEquals(mapOf("temp" to 547L), plan.bySource())
    }

    @Test fun `a file that changed or vanished since the preview is skipped, not counted`() {
        val plan = CleanPlan.plan(listOf(CleanPlan.Item("/a", 10, "c"), CleanPlan.Item("/b", 20, "c"), CleanPlan.Item("/c", 30, "c"), CleanPlan.Item("/a", 10, "c")))
        assertEquals(60L, plan.bytes)
        val sizes = mapOf("/a" to 10L, "/b" to 21L)
        val res = CleanPlan.run(plan, size = { sizes[it] }, delete = { true })
        assertEquals(10L, res.reclaimed)
        assertEquals(listOf("/a"), res.deleted)
        assertEquals(listOf("/b", "/c"), res.skipped)
        val refused = CleanPlan.run(plan, size = { mapOf("/a" to 10L, "/b" to 20L, "/c" to 30L)[it] }, delete = { it != "/b" })
        assertEquals(40L, refused.reclaimed)
        assertEquals(listOf("/b"), refused.skipped)
    }

    @Test fun `temp names are recognised and ordinary names are not`() {
        listOf("/a/b.tmp", "/a/B.TEMP", "/a/c.part", "/x.partial", "/y.crdownload", "/.z.swp", "/~\$doc.docx", "/.~lock.sheet.ods#").forEach { assertTrue(it, CleanPlan.isTemp(it)) }
        listOf("/a/tmp", "/a/part.txt", "/temp/file.jpg", "/a/b.tmpx", "/~doc").forEach { assertFalse(it, CleanPlan.isTemp(it)) }
        assertEquals(listOf(CleanPlan.Item("/q", 3, "s")), CleanPlan.everything(listOf(e("/q", 3)), "s"))
    }

    @Test fun `bytes read as people read them`() {
        assertEquals("0 B", Bytes.human(0))
        assertEquals("1023 B", Bytes.human(1023))
        assertEquals("1.0 KB", Bytes.human(1024))
        assertEquals("1.5 MB", Bytes.human(1024L * 1024 * 3 / 2))
        assertEquals("1024.0 MB", Bytes.human(1024L * 1024 * 1024 - 1))
        assertEquals("2.0 GB", Bytes.human(2L * 1024 * 1024 * 1024))
        assertEquals("3.0 TB", Bytes.human(3L * 1024 * 1024 * 1024 * 1024))
        assertEquals("2048.0 TB", Bytes.human(2048L * 1024 * 1024 * 1024 * 1024))
    }
}
