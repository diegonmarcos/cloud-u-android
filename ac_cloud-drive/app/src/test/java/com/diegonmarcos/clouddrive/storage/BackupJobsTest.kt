package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.clouddrive.storage.BackupJobs.Job
import com.diegonmarcos.clouddrive.storage.BackupJobs.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fsync > Backup: a user's own rsync mirror job, its script, its refusals and its remembered result. No declared job exists. */
class BackupJobsTest {
    private val ssh = Job("docs", "Docs", "/root/cloud-shared-store/docs", Target.Ssh("me", "box.mesh.diegonmarcos.com", 22, "/srv/backup/docs"), mirror = false, useKey = true)
    private val local = Job("pics", "Pics", "/root/pics", Target.Local("/root/backup/pics"), mirror = true, useKey = false)

    @Test fun `no job exists until the user makes one`() {
        assertEquals(emptyList<Job>(), BackupJobs.fromJson(""))
        assertEquals(emptyList<Job>(), BackupJobs.fromJson("{}"))
        assertEquals(emptyList<Job>(), BackupJobs.fromJson("{\"jobs\":[]}"))
    }

    @Test fun `an ssh job is rsync over ssh, copies the folder's contents, never deletes unless the job says mirror`() {
        val s = BackupJobs.script(ssh)
        assertTrue(s, "rsync -a --partial --info=stats2 -e " in s && "'/root/cloud-shared-store/docs/'" in s && "'me@box.mesh.diegonmarcos.com:/srv/backup/docs/'" in s)
        assertFalse("--delete" in s)
        assertTrue("StrictHostKeyChecking=accept-new" in s && "BatchMode=yes" in s)
    }

    @Test fun `mirror is the only thing that adds --delete`() {
        assertTrue("--delete" in BackupJobs.script(local)); assertFalse("--delete" in BackupJobs.script(local.copy(mirror = false)))
    }

    @Test fun `the ssh key is read from stdin into an owner-only file and removed by a trap, never written in the script`() {
        val s = BackupJobs.script(ssh)
        assertTrue("umask 177" in s && "cat > " in s && "trap 'rm -f " in s && "-i " in s)
        assertFalse("PRIVATE KEY" in s)
        val noKey = BackupJobs.script(ssh.copy(useKey = false))
        assertFalse("cat > " in noKey || "-i " in noKey)
    }

    @Test fun `a local job makes its destination and mirrors into it`() {
        val s = BackupJobs.script(local)
        assertTrue("mkdir -p '/root/backup/pics'" in s && "'/root/pics/' '/root/backup/pics/'" in s)
    }

    @Test fun `anything that could escape the folder or become an option is refused`() {
        val bad = listOf(
            ssh.copy(id = "A"), ssh.copy(name = ""), ssh.copy(source = "relative"), ssh.copy(source = "/a/../b"),
            ssh.copy(target = Target.Ssh("me", "-oProxyCommand=x", 22, "/p")), ssh.copy(target = Target.Ssh("me;rm", "h", 22, "/p")),
            ssh.copy(target = Target.Ssh("me", "h", 0, "/p")), ssh.copy(target = Target.Ssh("me", "h", 22, "p")),
            local.copy(target = Target.Local("/root/pics")), local.copy(target = Target.Local("/x/../y")),
        )
        for (b in bad) { assertNotNull(b.toString(), BackupJobs.refusal(b)); var threw = false; try { BackupJobs.script(b) } catch (e: IllegalArgumentException) { threw = true }; assertTrue(b.toString(), threw) }
        assertNull(BackupJobs.refusal(ssh)); assertNull(BackupJobs.refusal(local))
    }

    @Test fun `the summary is rsync's own totals, and a failure carries its last line`() {
        val out = "Number of files: 10\nNumber of regular files transferred: 3\nTotal transferred file size: 1,204 bytes\n"
        assertEquals("ok · 3 file(s) copied · 1,204 bytes", BackupJobs.summary(0, out))
        assertEquals("ok", BackupJobs.summary(0, ""))
        assertEquals("exit 23 · rsync error: some files/attrs were not transferred", BackupJobs.summary(23, "x\nrsync error: some files/attrs were not transferred\n"))
        assertEquals("exit 255", BackupJobs.summary(255, ""))
    }

    @Test fun `the saved file round-trips, keeps the last real result and holds no secret field`() {
        val jobs = listOf(ssh.copy(lastRunEpochSeconds = 1700000000, lastExit = 0, lastSummary = "ok"), local)
        val text = BackupJobs.toJson(jobs)
        assertEquals(jobs, BackupJobs.fromJson(text))
        for (word in listOf("password", "passphrase", "private", "secret", "token")) assertFalse(word, word in text.lowercase())
    }

    @Test fun `a hand-edited job that fails validation is dropped, not run`() {
        val text = BackupJobs.toJson(listOf(ssh)).replace("/root/cloud-shared-store/docs", "../../etc")
        assertEquals(emptyList<Job>(), BackupJobs.fromJson(text))
    }
}
