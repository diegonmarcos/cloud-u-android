package com.diegonmarcos.cloudlib.rclone.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * #1084 the Unison half of the engine on the JVM, against a FAKE unison (a shell script): the argument and
 * profile policy, the generated profile written for one run and deleted after, {PORT} and {BACKUPS} filled
 * by the engine, one run at a time, the archives' snapshot and ROLLBACK when a run is cancelled or ends
 * fatally (the interrupted-run guarantee), stale locks cleared, keep-both's copy and the SSH leg's
 * lifecycle. Exec-ing the real static binary needs a phone.
 */
class UnisonExecTest {
    private lateinit var dir: File
    private lateinit var home: File
    private lateinit var fake: File
    private val archive = "ar0123456789abcdef0123456789abcdef"
    private val fingerprint = "fp0123456789abcdef0123456789abcdef"

    @Before fun setUp() {
        dir = Files.createTempDirectory("unison-engine-test").toFile()
        home = File(dir, "unison")
        home.mkdirs()
        fake = File(dir, "libunison.so")
        // argv: <bin> <profile> -batch -ui text [extra...]; the profile is in $UNISON/<profile>.prf
        fake.writeText("""#!/bin/sh
prof="${'$'}1"
case "${'$'}prof" in
  clean)    echo "Reconciling changes"; echo "Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"; echo scribble > "${'$'}UNISON/$archive"; exit 0 ;;
  conflict) echo "Synchronization complete at 1:00:00  (0 items transferred, 1 skipped, 0 failed)"; echo "  skipped: one.txt (contents changed on both sides)"; echo newer > "${'$'}UNISON/$archive"; exit 1 ;;
  fatal)    echo "garbage" > "${'$'}UNISON/$archive"; echo "Fatal error: Lost connection with the server"; exit 3 ;;
  hang)     echo "started"; exec sleep 30 ;;
  echoargs) echo "ARGS: ${'$'}*"; echo "PROFILE:"; cat "${'$'}UNISON/${'$'}prof.prf"; exit 0 ;;
  slow)     sleep 1; echo "slow done"; exit 0 ;;
esac
""")
        fake.setExecutable(true)
        File(home, archive).writeText("good-archive")
        File(home, fingerprint).writeText("good-fingerprint")
    }

    private fun exec(binary: File = fake) = UnisonExec(binary, home) { mapOf("UNISON" to home.absolutePath, "HOME" to home.absolutePath) }
    private fun calls(access: Boolean = true, exec: UnisonExec = exec(), bridge: (SshTarget, File) -> UnisonExec.Bridged = { _, _ -> error("no ssh in this test") }) =
        UnisonCalls(exec, home, File(home, "known_hosts.txt"), { access }, bridge)

    private fun start(c: UnisonCalls, profile: String, text: String = "root = /a\nroot = /b\n", extra: List<String> = emptyList(), roots: List<String> = listOf("/a", "/b"), ssh: JSONObject? = null): JSONObject {
        val r = JSONObject().put("profile", profile).put("profile_text", text).put("extra", JSONArray(extra)).put("roots", JSONArray(roots))
        ssh?.let { r.put("ssh", it) }
        return JSONObject(c.start(r))
    }

    /** Poll until the job is done; returns (lines, final poll answer). */
    private fun finish(c: UnisonCalls, id: String): Pair<List<String>, JSONObject> {
        val lines = mutableListOf<String>()
        var cursor = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val p = JSONObject(c.poll(JSONObject().put("id", id).put("cursor", cursor)))
            assertTrue(p.toString(), p.getBoolean("ok"))
            val l = p.getJSONArray("lines"); for (i in 0 until l.length()) lines += l.getString(i)
            cursor = p.getInt("cursor")
            if (p.getBoolean("done")) return lines to p
            Thread.sleep(30)
        }
        error("job did not finish")
    }

    // ── policy ──────────────────────────────────────────────────────────────────────────────
    @Test fun policyAllowsOnlyTheScopedResolutionFlags() {
        val roots = listOf("/a", "socket://127.0.0.1:{PORT}//x")
        assertNull(UnisonPolicy.refusal(emptyList(), roots))
        assertNull(UnisonPolicy.refusal(listOf("-path", "a/b.txt", "-prefer", "/a", "-backup", "Path a/b.txt", "-backuplocation", "central", "-maxbackups", "5", "-confirmbigdel=false"), roots))
        assertNull(UnisonPolicy.refusal(listOf("-prefer", "newer"), roots))
        assertNotNull("a flag that names a command", UnisonPolicy.refusal(listOf("-sshcmd", "sh"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-servercmd", "x"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-merge", "Name * -> diff3"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-logfile", "/sdcard/x"), roots))
        assertNotNull("discarding state", UnisonPolicy.refusal(listOf("-ignorearchives"), roots))
        assertNotNull("prefer something that is not a root", UnisonPolicy.refusal(listOf("-prefer", "/etc"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-path", "/etc/passwd"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-path", "../../x"), roots))
        assertNotNull("a big-deletion override without a scoped path", UnisonPolicy.refusal(listOf("-confirmbigdel=false"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-maxbackups", "500"), roots))
        assertNotNull(UnisonPolicy.refusal(listOf("-backup", "Name *x; rm"), roots))
    }

    @Test fun profileNamesAreFileNamesOnly() {
        assertNull(UnisonPolicy.nameRefusal("docs-pair.1"))
        for (bad in listOf("", "../x", "a/b", "-x", ".hidden", "a b", "x".repeat(65))) assertNotNull(bad, UnisonPolicy.nameRefusal(bad))
    }

    @Test fun theEngineRefusesAProfileThatNamesACommand() {
        val c = calls()
        for (k in listOf("sshcmd = sh -c id", "servercmd=/x", "copyprog = rm", "logfile = /sdcard/l", "include = other", "merge = Name * -> true"))
            assertFalse(k, start(c, "clean", "root = /a\nroot = /b\n$k\n").getBoolean("ok"))
    }

    @Test fun refusalsAreSentencesAndNoProcessStarts() {
        assertTrue(start(calls(), "../evil").getString("error").contains("not [A-Za-z0-9._-]"))
        assertTrue(start(calls(), "clean", extra = listOf("-sshcmd", "x")).getString("error").contains("-sshcmd"))
        assertTrue(start(calls(exec = exec(File(dir, "missing.so"))), "clean").getString("error").contains("binary is missing"))
        assertTrue(start(calls(), "clean", text = "").getString("error").contains("no profile_text"))
        val noAccess = start(calls(access = false), "clean", roots = listOf("/storage/emulated/0/Docs", "/x"))
        assertTrue(noAccess.getString("error").contains("All files access"))
    }

    // ── a run ──────────────────────────────────────────────────────────────────────────────
    @Test fun aCleanRunStreamsItsLinesAndEndsOkAndKeepsTheNewArchive() {
        val c = calls()
        val (lines, last) = finish(c, start(c, "clean").getString("id"))
        assertTrue(lines.any { it.contains("Synchronization complete") })
        assertEquals(0, last.getInt("rc")); assertFalse(last.getBoolean("rolled_back"))
        assertEquals("scribble", File(home, archive).readText().trim())
    }

    @Test fun aSkippedRunEndsWithExitOneAndItsArchiveIsKept() {
        val c = calls()
        val (lines, last) = finish(c, start(c, "conflict").getString("id"))
        assertEquals(1, last.getInt("rc")); assertFalse(last.getBoolean("rolled_back"))
        assertTrue(lines.any { it.contains("skipped: one.txt") })
        assertEquals("newer", File(home, archive).readText().trim())
    }

    @Test fun aFatalRunPutsTheArchivesBackAsTheyWere() {
        val c = calls()
        val (_, last) = finish(c, start(c, "fatal").getString("id"))
        assertEquals(3, last.getInt("rc")); assertTrue("the half-written archive must not survive", last.getBoolean("rolled_back"))
        assertEquals("good-archive", File(home, archive).readText())
        assertEquals("good-fingerprint", File(home, fingerprint).readText())
    }

    @Test fun aCancelledRunPutsTheArchivesBackAndEndsMinusTwo() {
        val c = calls()
        val id = start(c, "hang").getString("id")
        // wait for the child to be up, damage the archive as a killed run would, then cancel
        Thread.sleep(300)
        File(home, archive).writeText("half-written")
        c.cancel(JSONObject().put("id", id))
        val (_, last) = finish(c, id)
        assertEquals(-2, last.getInt("rc")); assertTrue(last.getBoolean("rolled_back"))
        assertEquals("good-archive", File(home, archive).readText())
    }

    @Test fun staleLocksAndTempFilesOfAKilledRunAreClearedButArchivesAndProfilesAreNot() {
        File(home, "lk1234").writeText("x"); File(home, "tmp.77").writeText("x"); File(home, "x.tmp").writeText("x")
        File(home, "other.prf").writeText("keep me")
        val c = calls()
        finish(c, start(c, "clean").getString("id"))
        assertFalse(File(home, "lk1234").exists()); assertFalse(File(home, "tmp.77").exists()); assertFalse(File(home, "x.tmp").exists())
        assertTrue(File(home, "other.prf").exists())
        assertTrue(File(home, fingerprint).exists())
    }

    @Test fun aProfileNamedLikeAnArchiveIsNotMistakenForOne() {
        File(home, "ar-notes.prf").writeText("keep")
        val c = calls()
        finish(c, start(c, "fatal").getString("id"))
        assertEquals("keep", File(home, "ar-notes.prf").readText())
    }

    @Test fun theProfileIsWrittenForTheRunWithBackupsFilledInAndDeletedAfter() {
        val c = calls()
        val (lines, _) = finish(c, start(c, "echoargs", text = "root = /a\nroot = /b\nbackupdir = {BACKUPS}\n").getString("id"))
        val text = lines.joinToString("\n")
        assertTrue(text, text.contains("ARGS: echoargs -batch -ui text"))
        assertTrue(text.contains("backupdir = ${File(home, "backups/echoargs").absolutePath}"))
        assertFalse("{BACKUPS} must be filled", text.contains("{BACKUPS}"))
        Thread.sleep(100)
        assertFalse("the generated profile is not kept after the run", File(home, "echoargs.prf").exists())
    }

    @Test fun theAllowedFlagsReachTheBinaryAfterTheFixedOnes() {
        val c = calls()
        val extra = listOf("-path", "notes/a.md", "-prefer", "/a", "-confirmbigdel=false")
        val (lines, _) = finish(c, start(c, "echoargs", extra = extra).getString("id"))
        assertTrue(lines.any { it == "ARGS: echoargs -batch -ui text -path notes/a.md -prefer /a -confirmbigdel=false" })
    }

    @Test fun onePairRunsOnceAtATimeAndOthersQueue() {
        val c = calls()
        val first = start(c, "slow")
        assertTrue(first.getBoolean("ok"))
        val second = start(c, "slow")
        assertFalse(second.getBoolean("ok")); assertTrue(second.getString("error").contains("already running"))
        val other = start(c, "clean")
        assertTrue("another pair queues behind it", other.getBoolean("ok"))
        finish(c, first.getString("id")); finish(c, other.getString("id"))
    }

    @Test fun anUnknownJobIsASentence() {
        assertFalse(JSONObject(calls().poll(JSONObject().put("id", "nope"))).getBoolean("ok"))
    }

    // ── the SSH leg ─────────────────────────────────────────────────────────────────────────
    @Test fun theBridgePortIsFilledIntoProfileAndFlagsAndTheBridgeClosesAfterTheRun() {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        var target: SshTarget? = null
        val c = calls(bridge = { t, _ -> target = t; UnisonExec.Bridged(41234) { closed.set(true) } })
        val ssh = JSONObject().put("host", "oci-apps.mesh.diegonmarcos.com").put("port", 22).put("user", "diego").put("key_path", "/k").put("passphrase", "p").put("server_cmd", "unison -server")
        val text = "root = /a\nroot = socket://127.0.0.1:{PORT}//srv/x\n"
        val extra = listOf("-prefer", "socket://127.0.0.1:{PORT}//srv/x")
        val (lines, _) = finish(c, start(c, "echoargs", text, extra, listOf("/a", "socket://127.0.0.1:{PORT}//srv/x"), ssh).getString("id"))
        val all = lines.joinToString("\n")
        assertTrue(all, all.contains("root = socket://127.0.0.1:41234//srv/x"))
        assertTrue(all, all.contains("-prefer socket://127.0.0.1:41234//srv/x"))
        Thread.sleep(150)
        assertTrue("the SSH session ends with the run", closed.get())
        assertEquals("oci-apps.mesh.diegonmarcos.com", target!!.host); assertEquals("unison -server", target!!.serverCommand)
    }

    @Test fun aBridgeThatCannotConnectEndsTheRunWithItsSentenceAndRestoresTheArchives() {
        val c = calls(bridge = { _, _ -> throw java.io.IOException("ssh diego@oci-apps.mesh.diegonmarcos.com:22: Connection refused") })
        val ssh = JSONObject().put("host", "oci-apps.mesh.diegonmarcos.com").put("user", "diego")
        val (lines, last) = finish(c, start(c, "clean", ssh = ssh).getString("id"))
        assertTrue(lines.any { it.contains("Connection refused") })
        assertEquals(-1, last.getInt("rc")); assertTrue(last.getBoolean("rolled_back"))
        assertEquals("good-archive", File(home, archive).readText())
    }

    @Test fun sshNeedsAHostAndAUser() {
        assertFalse(start(calls(), "clean", ssh = JSONObject().put("host", "oci-apps.mesh.diegonmarcos.com")).getBoolean("ok"))
    }

    // ── keep both ───────────────────────────────────────────────────────────────────────────
    @Test fun keepBothCopiesTheBackupBesideTheKeptFileAndNeverOverwrites() {
        val root = File(dir, "rootA").also { File(it, "notes").mkdirs() }
        File(root, "notes/todo.md").writeText("their version")
        val c = calls()
        File(home, "backups/docs/notes").mkdirs(); File(home, "backups/docs/notes/todo.md").writeText("my version")
        fun copy() = JSONObject(c.keepCopy(JSONObject().put("profile", "docs").put("root", root.path).put("path", "notes/todo.md").put("suffix", "conflict-local-20261021-120000")))
        val a = copy()
        assertTrue(a.toString(), a.getBoolean("ok"))
        assertEquals("notes/todo.conflict-local-20261021-120000.md", a.getString("copied"))
        assertEquals("my version", File(root, "notes/todo.conflict-local-20261021-120000.md").readText())
        assertEquals("the kept file is untouched", "their version", File(root, "notes/todo.md").readText())
        val b = copy()
        assertEquals("a second copy gets its own name", "notes/todo.conflict-local-20261021-120000-2.md", b.getString("copied"))
    }

    @Test fun keepBothRefusesWhenNoBackupWasKeptOrThePathEscapes() {
        val root = File(dir, "rootA2").also { it.mkdirs() }
        val c = calls()
        assertTrue(JSONObject(c.keepCopy(JSONObject().put("profile", "docs").put("root", root.path).put("path", "nothing.md"))).getString("error").contains("no backup"))
        assertFalse(JSONObject(c.keepCopy(JSONObject().put("profile", "docs").put("root", root.path).put("path", "../../etc/passwd"))).getBoolean("ok"))
        assertFalse(JSONObject(c.keepCopy(JSONObject().put("profile", "docs").put("root", "/nonexistent/x").put("path", "a.md"))).getBoolean("ok"))
    }

    @Test fun conflictNamesKeepTheExtensionAndSkipExistingFiles() {
        val d = File(dir, "names").also { it.mkdirs() }
        assertEquals("a.conflict.txt", UnisonCalls.conflictName(File(d, "a.txt"), "conflict").name)
        assertEquals("Makefile.conflict", UnisonCalls.conflictName(File(d, "Makefile"), "conflict").name)
        assertEquals(".bashrc.conflict", UnisonCalls.conflictName(File(d, ".bashrc"), "conflict").name)
        File(d, "a.conflict.txt").writeText("x")
        assertEquals("a.conflict-2.txt", UnisonCalls.conflictName(File(d, "a.txt"), "conflict").name)
        assertEquals("a.we_ird.txt", UnisonCalls.conflictName(File(d, "a.txt"), "we ird").name)
    }
}
