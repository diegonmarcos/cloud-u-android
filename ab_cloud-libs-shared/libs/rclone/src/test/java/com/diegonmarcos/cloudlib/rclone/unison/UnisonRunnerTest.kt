package com.diegonmarcos.cloudlib.rclone.unison

import com.diegonmarcos.cloudlib.rclone.RcloneTransport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * #1084 the client half against a FAKE engine: what is sent (generated profile, roots, SSH leg, the scoped
 * flags of a resolution), what is done with the answers (conflicts surfaced, an old engine named, a refusal
 * kept as a sentence) and that keep-both really asks for the copy and reports a failed one.
 */
class UnisonRunnerTest {
    private val pair = UnisonConfig.parse(
        """{"ignore":["Name .DS_Store"],"pairs":[{"id":"docs","root_a":"/storage/emulated/0/Sync/Docs","root_b":{"host":"oci-apps.app","user":"diego","path":"/srv/sync/docs"}}]}"""
    ).pairs.single()
    private val local = UnisonConfig.parse(
        """{"pairs":[{"id":"pics","root_a":"/storage/emulated/0/DCIM","root_b":{"path":"/storage/1234-ABCD/DCIM"}}]}"""
    ).pairs.single()

    private class Fake(
        val unavailable: String? = null,
        val startAnswer: (JSONObject) -> JSONObject = { JSONObject().put("ok", true).put("id", "job-1") },
        val lines: List<String> = emptyList(),
        val rc: Int = 0,
        val rolledBack: Boolean = false,
        val keepCopy: (JSONObject) -> JSONObject = { JSONObject().put("ok", true).put("copied", "todo.conflict-local-x.md") },
    ) : RcloneTransport {
        val sent = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override fun unavailable() = unavailable
        override fun session(): RcloneTransport.Session {
            if (unavailable != null) throw IOException(unavailable)
            return object : RcloneTransport.Session {
                override fun run(request: JSONObject) = JSONObject()
                override fun outChunk(request: JSONObject) = JSONObject()
                override fun jobStart(request: JSONObject) = JSONObject()
                override fun jobPoll(request: JSONObject) = JSONObject()
                override fun jobCancel(request: JSONObject) = JSONObject()
                override fun unisonStart(request: JSONObject): JSONObject { sent += "unison_start" to request; return startAnswer(request) }
                override fun unisonPoll(request: JSONObject): JSONObject {
                    sent += "unison_poll" to request
                    return JSONObject().put("ok", true).put("lines", JSONArray(lines)).put("cursor", lines.size).put("done", true).put("rc", rc).put("rolled_back", rolledBack)
                }
                override fun unisonCancel(request: JSONObject): JSONObject { sent += "unison_cancel" to request; return JSONObject().put("ok", true) }
                override fun unisonKeepCopy(request: JSONObject): JSONObject { sent += "unison_keep_copy" to request; return keepCopy(request) }
                override fun close() {}
            }
        }
    }

    private val access = UnisonRunner.SshAccess(keyPath = "/storage/emulated/0/keys/id_ed25519", passphrase = "pw")

    private fun runSync(t: Fake, p: UnisonConfig.Pair = pair, ssh: (UnisonConfig.Pair) -> UnisonRunner.SshAccess? = { access }): Pair<UnisonOutput.Result, List<String>> {
        val log = CopyOnWriteArrayList<String>()
        val h = UnisonRunner(t, ssh).sync(p, { log += it }, {})
        val r = h.await()
        return r to log
    }

    @Test fun aSyncSendsTheGeneratedProfileTheRootsAndTheSshLegAndSurfacesConflicts() {
        val t = Fake(lines = listOf("Synchronization complete at 19:03:58  (0 items transferred, 1 skipped, 0 failed)", "  skipped: one.txt (contents changed on both sides)"), rc = 1)
        val (r, _) = runSync(t)
        val start = t.sent.first { it.first == "unison_start" }.second
        assertEquals("docs", start.getString("profile"))
        assertEquals(UnisonProfile.generate(pair), start.getString("profile_text"))
        assertEquals(JSONArray(UnisonProfile.roots(pair)).toString(), start.getJSONArray("roots").toString())
        assertEquals(0, start.getJSONArray("extra").length())
        val ssh = start.getJSONObject("ssh")
        assertEquals("oci-apps.app", ssh.getString("host")); assertEquals("diego", ssh.getString("user")); assertEquals(22, ssh.getInt("port"))
        assertEquals("/storage/emulated/0/keys/id_ed25519", ssh.getString("key_path")); assertEquals("pw", ssh.getString("passphrase"))
        assertEquals("unison -server", ssh.getString("server_cmd"))
        assertEquals(listOf("one.txt"), r.conflicts.map { it.path })
        assertFalse(r.ok)
    }

    @Test fun aLocalPairSendsNoSshLeg() {
        val t = Fake(lines = listOf("Synchronization complete at 1:00:00  (0 items transferred, 0 skipped, 0 failed)"))
        val (r, _) = runSync(t, local)
        assertFalse(t.sent.first { it.first == "unison_start" }.second.has("ssh"))
        assertTrue(r.ok)
    }

    @Test fun noncredentialsMeansASentenceAndNoEngineCall() {
        val t = Fake()
        val (r, log) = runSync(t, ssh = { null })
        assertTrue(t.sent.isEmpty())
        assertTrue(log.any { it.contains("no SSH credentials for oci-apps.app") })
        assertEquals(-1, r.rc)
    }

    @Test fun anAbsentEngineIsASentenceNeverAnException() {
        val (r, log) = runSync(Fake(unavailable = "the rclone engine com.x is not installed — install it from Store"))
        assertTrue(log.any { it.contains("not installed") })
        assertEquals(-1, r.rc)
        assertTrue(r.headline().startsWith("fatal"))
    }

    @Test fun anEngineThatPredatesUnisonTellsTheUserToUpdateIt() {
        val t = Fake(startAnswer = { JSONObject().put("ok", false).put("error", "unknown method: unison_start") })
        val (_, log) = runSync(t)
        assertTrue(log.any { it.contains("too old for Unison") && it.contains("update") })
    }

    @Test fun anEngineRefusalIsKeptAsItsOwnSentence() {
        val t = Fake(startAnswer = { JSONObject().put("ok", false).put("error", "unison docs is already running") })
        val (r, log) = runSync(t)
        assertTrue(log.any { it.contains("already running") })
        assertEquals(-1, r.rc)
    }

    @Test fun anInterruptedRunReportsThatTheArchivesWereRestored() {
        val t = Fake(lines = listOf("Fatal error: Lost connection with the server"), rc = 3, rolledBack = true)
        val (r, _) = runSync(t)
        assertTrue(r.rolledBack)
        assertEquals("fatal: Lost connection with the server · archives restored", r.headline())
    }

    @Test fun theLogCarriesReadableLinesNotTheProgressTicker() {
        val t = Fake(lines = listOf(" 50%  1/2  (2 B of 4 B)  --:-- ETA", "Reconciling changes", "Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"))
        val (_, log) = runSync(t)
        assertEquals(listOf("Reconciling changes", "Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"), log.toList())
    }

    @Test fun cancelAsksTheEngineToStopTheRunAndTheResultIsMarkedCancelled() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val t = object : RcloneTransport {
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun unavailable(): String? = null
            override fun session() = object : RcloneTransport.Session {
                override fun run(request: JSONObject) = JSONObject()
                override fun outChunk(request: JSONObject) = JSONObject()
                override fun jobStart(request: JSONObject) = JSONObject()
                override fun jobPoll(request: JSONObject) = JSONObject()
                override fun jobCancel(request: JSONObject) = JSONObject()
                override fun unisonStart(request: JSONObject) = JSONObject().put("ok", true).put("id", "j")
                override fun unisonPoll(request: JSONObject): JSONObject {
                    gate.countDown()
                    val done = cancelled.get()
                    return JSONObject().put("ok", true).put("lines", JSONArray()).put("cursor", 0).put("done", done).put("rc", -2).put("rolled_back", true)
                }
                override fun unisonCancel(request: JSONObject): JSONObject { cancelled.set(true); return JSONObject().put("ok", true) }
                override fun close() {}
            }
        }
        val h = UnisonRunner(t, { access }).sync(pair, {}, {})
        assertTrue(gate.await(5, TimeUnit.SECONDS))
        h.cancel()
        val r = h.await()
        assertEquals(-2, r.rc)
        assertTrue(r.headline().startsWith("cancelled"))
    }

    // ── resolution ──────────────────────────────────────────────────────────────────────────
    private fun resolve(t: Fake, c: UnisonResolve.Choice): Pair<UnisonOutput.Result, List<String>> {
        val log = CopyOnWriteArrayList<String>()
        val r = UnisonRunner(t, { access }, clock = { 1_790_000_000_000L }).resolve(pair, "notes/todo.md", c, { log += it }, {}).await()
        return r to log
    }

    @Test fun keepAAndKeepBAreOneScopedRunAndAskForNoCopy() {
        for (c in listOf(UnisonResolve.Choice.KEEP_A, UnisonResolve.Choice.KEEP_B)) {
            val t = Fake(lines = listOf("Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"))
            val (r, _) = resolve(t, c)
            val extra = t.sent.first { it.first == "unison_start" }.second.getJSONArray("extra")
            assertEquals("-path", extra.getString(0)); assertEquals("notes/todo.md", extra.getString(1))
            assertTrue((0 until extra.length()).any { extra.getString(it) == "-backup" })
            assertTrue(t.sent.none { it.first == "unison_keep_copy" })
            assertTrue(r.ok)
        }
    }

    @Test fun keepBothAsksTheEngineToCopyThePhonesVersionBesideTheKeptFile() {
        val t = Fake(lines = listOf("Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"))
        val (r, log) = resolve(t, UnisonResolve.Choice.KEEP_BOTH)
        val copy = t.sent.first { it.first == "unison_keep_copy" }.second
        assertEquals("docs", copy.getString("profile"))
        assertEquals("/storage/emulated/0/Sync/Docs", copy.getString("root"))
        assertEquals("notes/todo.md", copy.getString("path"))
        assertTrue(copy.getString("suffix").startsWith("conflict-local-2026"))
        assertTrue(log.any { it.contains("kept your version as todo.conflict-local-x.md") })
        assertTrue(r.failures.isEmpty())
    }

    @Test fun aFailedKeepBothCopyIsAFailureLineNeverASilentLoss() {
        val t = Fake(lines = listOf("Synchronization complete at 1:00:00  (1 item transferred, 0 skipped, 0 failed)"),
            keepCopy = { JSONObject().put("ok", false).put("error", "no backup of 'notes/todo.md' was kept") })
        val (r, _) = resolve(t, UnisonResolve.Choice.KEEP_BOTH)
        assertEquals(1, r.failures.size)
        assertTrue(r.failures.single().reason.contains("keep both"))
    }

    @Test fun keepBothDoesNotCopyWhenTheScopedRunItselfFailed() {
        val t = Fake(lines = listOf("Fatal error: boom"), rc = 3, rolledBack = true)
        val (r, _) = resolve(t, UnisonResolve.Choice.KEEP_BOTH)
        assertTrue(t.sent.none { it.first == "unison_keep_copy" })
        assertNotNull(r.fatal)
    }
}
