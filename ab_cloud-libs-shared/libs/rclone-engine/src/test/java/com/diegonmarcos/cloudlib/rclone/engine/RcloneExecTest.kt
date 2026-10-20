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

/**
 * The engine's whole logic on the JVM, against a FAKE rclone (a shell script that prints what it
 * was given): the argument policy, the caller-owned rclone.conf (written for one run, deleted after,
 * handed back when rclone rewrote it), the job poll protocol, the chunked answer for a long listing,
 * the cancel, and the two refusals (no binary, no all-files access). Exec-ing the real static binary
 * needs a phone and is what the Remotes tab's Test button does.
 */
class RcloneExecTest {
    private lateinit var dir: File
    private lateinit var work: File
    private lateinit var fake: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("rclone-engine-test").toFile()
        work = File(dir, "conf")
        fake = File(dir, "librclone.so")
        // argv: <bin> --config <file> --cache-dir <dir> <verb> <args...>
        fake.writeText("""#!/bin/sh
conf="${'$'}2"; shift 4
case "${'$'}1" in
  version) echo "rclone v9.9.9" ;;
  obscure) echo "obscured-${'$'}2" ;;
  lsjson) cat "${'$'}conf" ;;
  copyto) echo "copied ${'$'}*"; echo "token = refreshed" >> "${'$'}conf"; echo "boom" >&2; exit 3 ;;
  copy) echo "line one"; echo "line two"; echo "token = refreshed" >> "${'$'}conf"; exit 0 ;;
  check) head -c 900000 /dev/zero | tr '\0' 'x'; exit 0 ;;
  sync) exec sleep 30 ;;
esac
""")
        fake.setExecutable(true)
    }

    private fun exec(binary: File = fake) = RcloneExec(binary, File(dir, "cache"), work) { mapOf("HTTPS_PROXY" to "http://127.0.0.1:1") }
    private fun calls(access: Boolean = true, binary: File = fake) = RcloneCalls(exec(binary), "v9.9.9") { access }
    private fun req(config: String, vararg args: String) = JSONObject().put("config", config).put("args", JSONArray(args.toList()))

    @Test fun policyAllowsTheDeclaredVerbsAndRefusesTheRest() {
        for (v in ArgPolicy.VERBS) assertNull(v, ArgPolicy.refusal(listOf(v, "a:b")))
        assertNotNull(ArgPolicy.refusal(emptyList()))
        assertNotNull(ArgPolicy.refusal(listOf("serve", "http", "a:")))
        assertNotNull(ArgPolicy.refusal(listOf("config", "show")))
        assertNotNull("a flag that repoints the config", ArgPolicy.refusal(listOf("lsjson", "--config", "/x", "a:")))
        assertNotNull("the same with =", ArgPolicy.refusal(listOf("lsjson", "--config=/x", "a:")))
        assertNotNull(ArgPolicy.refusal(listOf("copy", "--password-command", "sh -c id", "a:", "b:")))
        assertNotNull(ArgPolicy.refusal(listOf("sync", "--rc", "a:", "b:")))
        assertNotNull(ArgPolicy.refusal(listOf("sync", "--rc-addr=0.0.0.0:5572", "a:", "b:")))
        assertNull("an ordinary flag passes", ArgPolicy.refusal(listOf("sync", "--exclude", "*.tmp", "--use-json-log", "a:", "b:")))
    }

    @Test fun sharedStorageIsRecognisedOnPathsOnly() {
        assertTrue(ArgPolicy.touchesSharedStorage(listOf("copy", "gdrive:docs", "/storage/emulated/0/Docs")))
        assertTrue(ArgPolicy.touchesSharedStorage(listOf("sync", "/sdcard/Pictures", "oci:pics")))
        assertFalse(ArgPolicy.touchesSharedStorage(listOf("lsjson", "--no-mimetype", "gdrive:")))
    }

    @Test fun aRunAnswersItsOutputAndExitCodeAndDeletesTheConfigItWrote() {
        val a = JSONObject(calls().run(req("[x]\ntype = s3\n", "version")))
        assertTrue(a.getBoolean("ok")); assertEquals(0, a.getInt("rc")); assertTrue(a.getString("stdout").startsWith("rclone v9.9.9"))
        assertFalse("nothing changed the config, so none is handed back", a.has("config"))
        assertEquals("the temp rclone.conf is gone after the run", 0, work.listFiles()!!.size)
    }

    @Test fun theCallersConfigReachesRcloneVerbatim() {
        val text = "[drive]\ntype = drive\ntoken = {\"a\":1}\n"
        assertEquals(text, JSONObject(calls().run(req(text, "lsjson", "drive:"))).getString("stdout"))
    }

    @Test fun aConfigRcloneRewroteIsHandedBackAndAFailureKeepsItsStderr() {
        val a = JSONObject(calls().run(req("[x]\n", "copyto", "x:a", "/tmp/b")))
        assertTrue(a.getBoolean("ok")); assertEquals(3, a.getInt("rc"))
        assertEquals("boom", a.getString("stderr").trim())
        assertEquals("[x]\ntoken = refreshed\n", a.getString("config"))
    }

    @Test fun anArgumentTheEngineWouldNotRunIsRefusedBeforeAnythingStarts() {
        val a = JSONObject(calls().run(req("", "lsjson", "--config", "/etc/passwd", "x:")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").contains("--config"))
        assertEquals(0, work.listFiles().orEmpty().size)
    }

    @Test fun aLongListingComesBackInChunksThatJoinToTheWhole() {
        val c = calls()
        val first = JSONObject(c.run(req("", "check", "a:", "b:")))
        assertTrue(first.getBoolean("more")); assertEquals(RcloneCalls.CHUNK, first.getString("stdout").length)
        val sb = StringBuilder(first.getString("stdout")); var more = true; val id = first.getString("id")
        while (more) {
            val next = JSONObject(c.outChunk(JSONObject().put("id", id).put("offset", sb.length)))
            sb.append(next.getString("stdout")); more = next.getBoolean("more")
        }
        assertEquals(900000, sb.length)
        assertFalse("the finished answer is forgotten", JSONObject(c.outChunk(JSONObject().put("id", id).put("offset", 0))).getBoolean("ok"))
    }

    @Test fun aJobIsPolledToTheEndWithItsLinesItsExitCodeAndItsRewrittenConfig() {
        val c = calls()
        val id = JSONObject(c.jobStart(req("[x]\n", "copy", "x:a", "x:b"))).getString("id")
        val lines = ArrayList<String>(); var cursor = 0; var end: JSONObject? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (end == null && System.currentTimeMillis() < deadline) {
            val p = JSONObject(c.jobPoll(JSONObject().put("id", id).put("cursor", cursor)))
            assertTrue(p.getBoolean("ok"))
            val ls = p.getJSONArray("lines"); for (i in 0 until ls.length()) lines += ls.getString(i)
            cursor = p.getInt("cursor")
            if (p.getBoolean("done")) end = p else Thread.sleep(50)
        }
        assertNotNull("the job finished", end)
        assertEquals(listOf("line one", "line two"), lines)
        assertEquals(0, end!!.getInt("rc"))
        assertEquals("[x]\ntoken = refreshed\n", end.getString("config"))
        assertFalse("a finished, drained job is forgotten", JSONObject(c.jobPoll(JSONObject().put("id", id))).getBoolean("ok"))
        assertEquals(0, work.listFiles()!!.size)
    }

    @Test fun cancellingAJobEndsItWithTheCancelledCode() {
        val c = calls()
        val id = JSONObject(c.jobStart(req("", "sync", "a:", "b:"))).getString("id")
        c.jobCancel(JSONObject().put("id", id))
        var rc: Int? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (rc == null && System.currentTimeMillis() < deadline) {
            val p = JSONObject(c.jobPoll(JSONObject().put("id", id)))
            if (p.getBoolean("done")) rc = p.getInt("rc") else Thread.sleep(50)
        }
        assertEquals(-2, rc)
    }

    @Test fun aSharedStoragePathWithoutAllFilesAccessSaysWhatToGrant() {
        val a = JSONObject(calls(access = false).jobStart(req("", "copy", "x:a", "/storage/emulated/0/Docs")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").contains("All files access"))
        assertTrue("a remote-only run needs no grant", JSONObject(calls(access = false).run(req("", "version"))).getBoolean("ok"))
    }

    @Test fun aMissingBinaryIsNamedNotCrashed() {
        val a = JSONObject(calls(binary = File(dir, "absent")).run(req("", "version")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").contains("binary is missing"))
        assertFalse(JSONObject(calls(binary = File(dir, "absent")).info()).getBoolean("available"))
    }

    @Test fun infoNamesTheVersionAndTheGrant() {
        val i = JSONObject(calls(access = false).info())
        assertEquals("v9.9.9", i.getString("version")); assertTrue(i.getBoolean("available")); assertFalse(i.getBoolean("files_access"))
    }
}
