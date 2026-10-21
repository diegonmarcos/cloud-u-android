package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.JobPathMigration
import com.diegonmarcos.cloudlib.rclone.RcloneJob
import com.diegonmarcos.cloudlib.rclone.RcloneJobStore
import com.diegonmarcos.cloudlib.sharedstore.ExecCopier
import com.diegonmarcos.cloudlib.sharedstore.StoreLegs
import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1070: Sync's engines (rclone, git) cannot open the store in cld.termux; legs are staged, git goes through cld.termux, saved jobs migrate. */
class StoreLegsTest {
    private val p = StorePaths("cld.termux", "cloud-shared-store", "CloudDrive", "cloud-drive-shared-store")  // compat_link: the old name is the rule's input
    private val root = p.hostRoot(0)
    private val sd = "/storage/emulated/" + 0
    @get:Rule val tmp = TemporaryFolder()

    private class Rec : StoreLegs.Copier {
        val log = mutableListOf<String>()
        override fun copy(from: String, to: String, replace: Boolean): String? { log += "cp $from -> $to${if (replace) " (replace)" else ""}"; return null }
        override fun remove(path: String): String? { log += "rm $path"; return null }
    }

    @Test fun `the staging root is shared storage, never the store, and a store path maps to its twin`() {
        assertEquals("$sd/CloudDrive/.store-stage", p.stagingRoot(0))
        assertFalse(p.inStore(p.stagingRoot(0), 0))
        assertEquals("$sd/CloudDrive/.store-stage/t/src/git/a", p.stagePath("$root/git/a/", 0, "t/src"))
        assertNull(p.stagePath("$sd/Download/x", 0, "t"))
        assertNull("a .. escape is not in the store", p.stagePath("$root/../x", 0, "t"))
    }

    @Test fun `a download from a remote into the store is staged and copied back, a sync mirrors`() {
        val r = Rec(); val l = StoreLegs(p, 0, r)
        val plan = l.plan(listOf("copy", "drive:a", "$root/mirror", "--fast-list"), "t")!!
        assertEquals(listOf("copy", "drive:a", "${p.stagingRoot(0)}/t/dst/mirror", "--fast-list"), plan.args)
        assertNull(l.pull(plan)); assertNull(l.finish(plan, 0))
        assertEquals(listOf("cp $root/mirror -> ${p.stagingRoot(0)}/t/dst/mirror (replace)", "cp ${p.stagingRoot(0)}/t/dst/mirror -> $root/mirror", "rm ${p.stagingRoot(0)}/t"), r.log)
        val sync = StoreLegs(p, 0, Rec()).plan(listOf("sync", "drive:a", "$root/mirror"), "t")!!
        assertTrue("sync must make the store leg an exact copy", sync.pushes.single().third)
    }

    @Test fun `an upload from the store pulls first, a failed run copies nothing back, move removes its source only on success`() {
        val r = Rec(); val l = StoreLegs(p, 0, r)
        val plan = l.plan(listOf("move", "$root/out", "drive:b"), "t")!!
        l.pull(plan); l.finish(plan, 1)
        assertEquals(listOf("cp $root/out -> ${p.stagingRoot(0)}/t/src/out (replace)", "rm ${p.stagingRoot(0)}/t"), r.log)
        val ok = Rec(); val l2 = StoreLegs(p, 0, ok); val plan2 = l2.plan(listOf("move", "$root/out", "drive:b"), "t")!!
        l2.finish(plan2, 0)
        assertTrue(ok.log.contains("rm $root/out"))
    }

    @Test fun `a job with no leg in the store is untouched and bisync on the store is refused with a sentence`() {
        val l = StoreLegs(p, 0, Rec())
        assertNull(l.plan(listOf("copy", "drive:a", "$sd/Download/x"), "t"))
        assertNull(l.plan(listOf("lsjson", "drive:"), "t"))
        assertNotNull(l.refusal(listOf("bisync", "$root/a", "drive:a")))
        assertNull(l.refusal(listOf("bisync", "drive:a", "drive:b")))
    }

    @Test fun `engines reach the store through cld-termux in both access modes`() {
        assertTrue(StorePaths.viaOwner(StorePaths.Mode.DIRECT))
        assertTrue(StorePaths.viaOwner(StorePaths.Mode.EXEC))
        assertFalse(StorePaths.viaOwner(StorePaths.Mode.NONE))
    }

    @Test fun `the exec copy runs in the guest with every path quoted and swaps a replaced leg in`() {
        val s = ExecCopier.copyScript("/root/cloud-shared-store/it's", "$sd/CloudDrive/x", true)
        assertTrue(s.contains("'/root/cloud-shared-store/it'\\''s'"))
        assertTrue(s.contains("mv '$sd/CloudDrive/x.incoming' '$sd/CloudDrive/x'"))
        assertFalse(ExecCopier.copyScript("/a", "/b", false).contains("mv "))
    }

    @Test fun `the file copier copies a tree both ways and replaces rather than merges`() {
        val a = tmp.newFolder("a"); File(a, "n").mkdirs(); File(a, "n/f.txt").writeText("1")
        val b = File(tmp.root, "b"); b.mkdirs(); File(b, "stale").writeText("x")
        val c = StoreLegs.FileCopier()
        assertNull(c.copy(a.path, b.path, true))
        assertEquals("1", File(b, "n/f.txt").readText()); assertFalse(File(b, "stale").exists())
        assertNull(c.remove(b.path)); assertFalse(b.exists())
    }

    @Test fun `saved sync jobs on the old location migrate once and a second run changes nothing`() {
        val store = RcloneJobStore(File(tmp.root, "jobs.json"))
        store.save(listOf(
            RcloneJob(id = "1", name = "a", op = "copy", source = "drive:x", destination = "$sd/CloudDrive/mirror"),
            RcloneJob(id = "2", name = "b", op = "sync", source = "/data/data/cld.termux.nix/files/home/cloud-drive-shared-store/git", destination = "drive:y"),  // compat_link: the old name is the rule's input
            RcloneJob(id = "3", name = "c", op = "copy", source = "drive:z", destination = "$sd/Download/keep"),
        ))
        val rule = { x: String -> p.migrateLegacy(x, 0) }
        assertEquals(2, JobPathMigration.migrateJobs(store, rule))
        val j = store.load().associateBy { it.id }
        assertEquals("$root/mirror", j.getValue("1").destination); assertEquals("$root/git", j.getValue("2").source)
        assertEquals("$sd/Download/keep", j.getValue("3").destination)
        assertEquals(0, JobPathMigration.migrateJobs(store, rule))
    }
}
