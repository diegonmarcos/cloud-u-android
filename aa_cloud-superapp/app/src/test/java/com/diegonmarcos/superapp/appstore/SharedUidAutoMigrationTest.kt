package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * libs:appstore SharedUidMigration, the automatic half:
 *   A1 an installed member outside the shared uid is detected without any failed install
 *   A2 the one-tap run goes backup, uninstall, install, open, each once and in that order
 *   A3 it waits for the owner terminal, and asks again when a prompt is left unanswered
 */
class SharedUidAutoMigrationTest {

    private val group = "cld.termux.shared"
    private val rec = SharedUidMigration.Record("com.diegonmarcos.clouddrive", SharedUidMigration.Cause.UID_CHANGED, "v1", "7/1000", 0L)

    @Test fun `A1 outside the group means not joined, inside means joined`() {
        val drive = "com.diegonmarcos.clouddrive"
        assertTrue("no sharedUserId at all", SharedUidMigration.notJoined(group, null, drive, 10100, 10050))
        assertTrue("an id naming its own package shares nothing", SharedUidMigration.notJoined(group, drive, drive, 10100, 10050))
        assertTrue("another group", SharedUidMigration.notJoined(group, "other.shared", drive, 10100, 10050))
        assertTrue("same id but a different uid", SharedUidMigration.notJoined(group, group, drive, 10100, 10050))
        assertFalse("joined", SharedUidMigration.notJoined(group, group, drive, 10050, 10050))
        assertFalse("owner unknown, id matches", SharedUidMigration.notJoined(group, group, drive, 10100, null))
        assertFalse("no group declared", SharedUidMigration.notJoined(null, null, drive, 10100, 10050))
    }

    private class Sim(var installed: String = "7/1000", var archive: Boolean = false, var wait: String? = null) {
        val log = mutableListOf<String>()
        fun hooks(onTick: (Sim) -> Unit = {}) = SharedUidMigration.Hooks(
            installedNow = { installed }, archiveOk = { archive }, waitsFor = { wait },
            startBackup = { log += "backup"; archive = true },
            uninstall = { log += "uninstall"; installed = "" },
            install = { log += "install"; installed = "8/2000" },
            open = { log += "open" },
            sleep = { onTick(this) },
        )
    }

    @Test fun `A2 the run is backup, uninstall, install, open and nothing twice`() {
        val s = Sim()
        assertEquals(SharedUidMigration.Outcome.DONE, SharedUidMigration.runAuto(rec, s.hooks()))
        assertEquals(listOf("backup", "uninstall", "install", "open"), s.log)
    }

    @Test fun `A2 it never uninstalls before the terminal confirmed the archive`() {
        val s = Sim()
        var ticks = 0
        val h = SharedUidMigration.Hooks({ s.installed }, { s.archive }, { null },
            { s.log += "backup" }, { s.log += "uninstall"; s.installed = "" }, { s.log += "install"; s.installed = "8/2000" }, { s.log += "open" },
            { ticks++; if (ticks == 5) s.archive = true })
        assertEquals(SharedUidMigration.Outcome.DONE, SharedUidMigration.runAuto(rec, h))
        assertEquals(listOf("backup", "uninstall", "install", "open"), s.log)
        assertTrue("it waited for the archive", ticks >= 5)
    }

    @Test fun `A3 it holds while the owner terminal still has to migrate`() {
        val s = Sim(archive = true, wait = "cld.termux")
        val out = SharedUidMigration.runAuto(rec, s.hooks { if (it.log.isEmpty() && it.wait != null) { /* still waiting */ } }, maxTicks = 5)
        assertEquals(SharedUidMigration.Outcome.TIMEOUT, out)
        assertTrue("nothing ran", s.log.isEmpty())
    }

    @Test fun `A3 an unanswered uninstall prompt is asked again, not forgotten`() {
        val s = Sim(archive = true)
        val h = SharedUidMigration.Hooks({ s.installed }, { true }, { null }, { }, { s.log += "uninstall" }, { }, { }, { })
        assertEquals(SharedUidMigration.Outcome.TIMEOUT, SharedUidMigration.runAuto(rec, h, maxTicks = 70, reaskTicks = 30))
        assertEquals(listOf("uninstall", "uninstall", "uninstall"), s.log)
    }

    @Test fun `A2 a finished migration opens the app and stops`() {
        val s = Sim(installed = "8/2000", archive = true)
        assertEquals(SharedUidMigration.Outcome.DONE, SharedUidMigration.runAuto(rec, s.hooks()))
        assertEquals(listOf("open"), s.log)
    }
}
