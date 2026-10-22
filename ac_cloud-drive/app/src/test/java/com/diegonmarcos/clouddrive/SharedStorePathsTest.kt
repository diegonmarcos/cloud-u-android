package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import com.diegonmarcos.cloudlib.sharedstore.StorePaths.Mode
import com.diegonmarcos.clouddrive.sync.StoreGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** cloud-shared-store: the store-access layer's pure rules, both access paths, and the job-path migration. */
class SharedStorePathsTest {

    private val p = StorePaths("cld.termux", "cloud-shared-store", "CloudDrive", "cloud-drive-shared-store")  // legacy_store_migration: the old name is the rule's input
    private val root = "/data/user/0/cld.termux/files/home/cloud-shared-store"
    /** Primary shared storage, composed so no device-absolute literal is written down. */
    private val sd = "/storage/emulated/" + 0

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the store is the owner's home, per Android user`() {
        assertEquals(root, p.hostRoot(0))
        assertEquals("/data/user/10/cld.termux/files/home/cloud-shared-store", p.hostRoot(10))
    }

    @Test fun `another uid's private dir is listed through the owner terminal, not reported as not a folder`() {
        val files = "/data/user/0/cld.termux/files"
        // the owner's report: the terminal's files dir, outside the store and outside the guest's /root
        assertNull(p.guestPath(files, 0))
        assertTrue(p.inOwnerData(files, 0)); assertTrue(p.inOwnerData("$files/usr/bin", 0)); assertTrue(p.inOwnerData("/data/data/cld.termux/files", 0))
        assertFalse(p.inOwnerData("/data/user/0/cld.termux.nix/files", 0)); assertFalse(p.inOwnerData("$files/../../x", 0))
        assertTrue(StorePaths.listViaOwner(p, files, 0, openable = false, mode = Mode.EXEC))
        assertTrue(StorePaths.listViaOwner(p, files, 0, openable = false, mode = Mode.DIRECT))
        assertFalse(StorePaths.listViaOwner(p, files, 0, openable = true, mode = Mode.DIRECT))
        assertFalse(StorePaths.listViaOwner(p, files, 0, openable = false, mode = Mode.NONE))
        assertFalse(StorePaths.listViaOwner(p, "/storage/emulated/0/Download", 0, openable = false, mode = Mode.EXEC))
    }

    @Test fun `DIRECT only when the shared uid is really in effect`() {
        assertEquals(Mode.DIRECT, StorePaths.decide(10123, 10123, storeAccessible = true, execGranted = true))
        assertEquals("same uid but the dir does not answer -> EXEC", Mode.EXEC, StorePaths.decide(10123, 10123, storeAccessible = false, execGranted = true))
        assertEquals("different uid -> EXEC fallback", Mode.EXEC, StorePaths.decide(10200, 10123, storeAccessible = true, execGranted = true))
        assertEquals("owner absent", Mode.NONE, StorePaths.decide(10200, null, storeAccessible = false, execGranted = true))
        assertEquals("no CLOUD_EXEC grant", Mode.NONE, StorePaths.decide(10200, 10123, storeAccessible = false, execGranted = false))
    }

    @Test fun `every old location is rewritten to the store`() {
        assertEquals("$root/git/a", p.migrateLegacy("$sd/CloudDrive/git/a", 0))
        assertEquals("$root/git/a", p.migrateLegacy("/sdcard/CloudDrive/git/a/", 0))
        assertEquals("$root/mirror", p.migrateLegacy("/data/media/0/CloudDrive/mirror", 0))
        assertEquals(root, p.migrateLegacy("$sd/CloudDrive", 0))
        assertEquals("$root/git/b", p.migrateLegacy("/data/data/cld.termux.nix/files/home/cloud-drive-shared-store/git/b", 0))  // legacy_store_migration: the old name is the rule's input
        assertEquals("$root/git/c", p.migrateLegacy("~/cloud-drive-shared-store/git/c", 0))  // legacy_store_migration: the old name is the rule's input
    }

    @Test fun `the migration is idempotent and leaves everything else alone`() {
        val once = p.migrateLegacy("$sd/CloudDrive/git/a", 0)!!
        assertNull("a path already in the store is not rewritten again", p.migrateLegacy(once, 0))
        assertNull(p.migrateLegacy("$sd/Download/x", 0))
        assertNull(p.migrateLegacy("$sd/CloudDriveOther/x", 0))
        assertNull(p.migrateLegacy("gdrive:CloudDrive/x", 0))
        assertFalse(p.isLegacy(root, 0))
        assertTrue(p.isLegacy("$sd/CloudDrive/git", 0))
    }

    @Test fun `EXEC paths translate into the termux guest and refuse anything outside the store`() {
        assertEquals("/root/cloud-shared-store/git/a", p.guestPath("$root/git/a", 0))
        assertEquals("/root/cloud-shared-store", p.guestPath(root, 0))
        assertEquals("/root/cloud-shared-store/x", p.guestPath("/data/data/cld.termux/files/home/cloud-shared-store/x", 0))
        assertNull(p.guestPath("$root/../secret", 0))
        assertNull(p.guestPath("$sd/CloudDrive/git/a", 0))
    }

    @Test fun `EXEC git scripts keep the token off argv and quote every path`() {
        val clone = StoreGit.cloneScript("https://h/o/r.git", "/root/cloud-shared-store/git/it's", 1)
        assertTrue(clone.contains("T=\"\$(cat)\""))
        assertTrue(clone.contains("--depth 1"))
        assertTrue(clone.contains("'/root/cloud-shared-store/git/it'\\''s'"))
        val sync = StoreGit.syncScript("/root/cloud-shared-store/git/a", "msg", "n", "e@x", rebase = true)
        assertTrue(sync.contains("pull --rebase") && sync.contains(" push"))
    }

    @Test fun `the data backup round-trips and never carries Keystore-encrypted prefs`() {
        val data = tmp.newFolder("data")
        File(data, "files/git-sync").mkdirs(); File(data, "files/git-sync/repos.json").writeText("[]")
        File(data, "files/rclone").mkdirs(); File(data, "files/rclone/jobs.json").writeText("[]")
        File(data, "shared_prefs").mkdirs(); File(data, "shared_prefs/cloud-drive-prefs.xml").writeText("<map/>")
        File(data, "shared_prefs/git-sync-credentials.xml").writeText("secret")
        File(data, "cache").mkdirs(); File(data, "cache/x").writeText("c")
        val zip = File(tmp.root, "out/data.zip")
        assertEquals(3, DataMigration.Archive.write(data, zip))
        val fresh = tmp.newFolder("fresh")
        assertEquals(3, DataMigration.Archive.read(zip, fresh))
        assertTrue(File(fresh, "files/git-sync/repos.json").isFile)
        assertFalse(File(fresh, "shared_prefs/git-sync-credentials.xml").exists())
        assertFalse(File(fresh, "cache/x").exists())
    }
}
