package com.diegonmarcos.cloudlib.rclone

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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client half of the rclone engine (#871) on the JVM, against a FAKE engine: what the runner
 * sends (the rclone.conf text, the argv), what it does with the answers (a long stdout in chunks, a
 * config rclone rewrote, a refusal, a job polled to its end, a cancel) and what it does with no
 * engine at all (a sentence, never an exception out of start()). The engine's own half is
 * libs:rclone-engine's RcloneExecTest.
 */
class RcloneRunnerTest {
    private lateinit var dir: File
    private lateinit var conf: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("rclone-runner-test").toFile()
        conf = File(dir, "files/rclone/rclone.conf")
        conf.parentFile.mkdirs()
        conf.writeText("[drive]\ntype = drive\n")
    }

    /** A scripted engine: [answer] gets (method, request) and returns the JSON the real engine would. */
    private class Fake(val unavailable: String? = null, val answer: (String, JSONObject) -> JSONObject) : RcloneTransport {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override fun unavailable() = unavailable
        override fun session(): RcloneTransport.Session {
            if (unavailable != null) throw IOException(unavailable)
            return object : RcloneTransport.Session {
                private fun ask(method: String, request: JSONObject): JSONObject { calls += method to request; return answer(method, request) }
                override fun run(request: JSONObject) = ask("run", request)
                override fun outChunk(request: JSONObject) = ask("out_chunk", request)
                override fun jobStart(request: JSONObject) = ask("job_start", request)
                override fun jobPoll(request: JSONObject) = ask("job_poll", request)
                override fun jobCancel(request: JSONObject) = ask("job_cancel", request)
                override fun close() {}
            }
        }
    }

    private fun runner(t: RcloneTransport) = RcloneRunner(t, conf, File(dir, "dl"))
    private fun ok() = JSONObject().put("ok", true)

    @Test fun theRcloneConfTravelsWithTheCallAndAProbeAnswers() {
        val f = Fake { m, _ -> ok().put("rc", 0).put("stdout", "rclone v1.75.1\n- os/version: android\n").put("stderr", "") }
        val r = runner(f)
        assertTrue(r.isAvailable)
        assertEquals("v1.75.1", r.version())
        val sent = f.calls.single().second
        assertEquals("[drive]\ntype = drive\n", sent.getString("config"))
        assertEquals(listOf("version"), (0 until sent.getJSONArray("args").length()).map { sent.getJSONArray("args").getString(it) })
    }

    @Test fun aLongListingIsReassembledFromItsChunksAndParsed() {
        val body = "[" + (1..3000).joinToString(",") { """{"Name":"f$it","Path":"f$it","IsDir":false,"Size":$it,"ModTime":"t","MimeType":""}""" } + "]"
        val cut = body.length / 2
        val f = Fake { m, req -> when (m) {
            "run" -> ok().put("rc", 0).put("stdout", body.substring(0, cut)).put("more", true).put("id", "x1").put("stderr", "")
            else -> { assertEquals("x1", req.getString("id")); assertEquals(cut, req.getInt("offset")); ok().put("stdout", body.substring(cut)).put("more", false) }
        } }
        val list = runner(f).lsjson("drive:").getOrThrow()
        assertEquals(3000, list.size)
        assertEquals(listOf("run", "out_chunk"), f.calls.map { it.first })
    }

    @Test fun aConfigRcloneRewroteIsKeptAndAnUnchangedOneIsNotTouched() {
        val before = conf.lastModified()
        runner(Fake { _, _ -> ok().put("rc", 0).put("stdout", "ok").put("stderr", "") }).capture(listOf("version"))
        assertEquals("nothing came back, nothing written", before, conf.lastModified())
        runner(Fake { _, _ -> ok().put("rc", 0).put("stdout", "[]").put("stderr", "").put("config", "[drive]\ntype = drive\ntoken = refreshed\n") }).lsjson("drive:").getOrThrow()
        assertEquals("[drive]\ntype = drive\ntoken = refreshed\n", conf.readText())
        assertFalse(File(conf.parentFile, "rclone.conf.tmp").exists())
    }

    @Test fun aNonZeroExitSurfacesItsStderrAndAnEngineRefusalItsSentence() {
        val failing = Fake { _, _ -> ok().put("rc", 3).put("stdout", "").put("stderr", "Failed to create file system: bad\n") }
        assertEquals("Failed to create file system: bad\n", runner(failing).lsjson("x:").exceptionOrNull()!!.message)
        val refused = Fake { _, _ -> JSONObject().put("ok", false).put("error", "the rclone engine has no All files access") }
        assertEquals("the rclone engine has no All files access", runner(refused).download("x:a", "a").exceptionOrNull()!!.message)
    }

    @Test fun noEngineInstalledIsASentenceNotACrash() {
        val none = Fake(unavailable = "the rclone engine com.x is not installed — install it from Store") { _, _ -> error("never called") }
        val r = runner(none)
        assertFalse(r.isAvailable); assertEquals("the rclone engine com.x is not installed — install it from Store", r.unavailableReason)
        assertNull(r.version())
        assertTrue(r.lsjson("x:").isFailure)
        val lines = CopyOnWriteArrayList<String>(); val exit = CountDownLatch(1); var code = 99
        val h = r.start(job(), {}, { lines += it }, { code = it; exit.countDown() })
        assertTrue(exit.await(5, TimeUnit.SECONDS)); assertEquals(-1, code); assertEquals(-1, h.waitFor())
        assertTrue(lines.single().contains("not installed"))
    }

    private fun job() = RcloneJob(id = "j", name = "n", op = "copy", source = "drive:a", destination = "/storage/emulated/0/a", flags = listOf("--fast-list"))

    @Test fun aJobIsPolledToItsEndWithStatsLinesExitAndTheRewrittenConfig() {
        val stats = """{"level":"info","msg":"","stats":{"bytes":5,"totalBytes":10,"transfers":1,"totalTransfers":2,"checks":0,"errors":0,"speed":2.5,"eta":2,"elapsedTime":1.0}}"""
        var polls = 0
        val f = Fake { m, req -> when (m) {
            "job_start" -> ok().put("id", "J1")
            else -> {
                polls++
                if (polls == 1) ok().put("lines", JSONArray(listOf(stats, """{"level":"notice","msg":"Copied a.txt"}""", "plain line"))).put("cursor", 3).put("done", false)
                else { assertEquals(3, req.getInt("cursor")); ok().put("lines", JSONArray()).put("cursor", 3).put("done", true).put("rc", 0).put("config", "[drive]\ntype = drive\ntoken = t2\n") }
            }
        } }
        val got = CopyOnWriteArrayList<RcloneStats>(); val lines = CopyOnWriteArrayList<String>(); val exit = CountDownLatch(1); var code = 99
        val h = runner(f).start(job(), { got += it }, { lines += it }, { code = it; exit.countDown() })
        assertTrue(exit.await(10, TimeUnit.SECONDS)); assertEquals(0, code); assertEquals(0, h.waitFor())
        assertEquals(5L, got.single().bytes); assertEquals(0.5f, got.single().fraction, 0.0001f)
        assertEquals(listOf("[notice] Copied a.txt", "plain line"), lines.toList())
        assertEquals("[drive]\ntype = drive\ntoken = t2\n", conf.readText())
        val args = f.calls.first { it.first == "job_start" }.second.getJSONArray("args")
        val argv = (0 until args.length()).map { args.getString(it) }
        assertEquals(listOf("copy", "drive:a", "/storage/emulated/0/a", "--fast-list", "--use-json-log", "--stats", "1s", "--stats-log-level", "NOTICE", "-v"), argv)
    }

    @Test fun cancellingTellsTheEngineAndEndsWithTheCancelledCode() {
        val cancelled = CountDownLatch(1)
        val f = Fake { m, _ -> when (m) {
            "job_start" -> ok().put("id", "J2")
            "job_cancel" -> { cancelled.countDown(); ok() }
            else -> if (cancelled.count == 0L) ok().put("lines", JSONArray()).put("cursor", 0).put("done", true).put("rc", -2)
                    else ok().put("lines", JSONArray()).put("cursor", 0).put("done", false)
        } }
        val exit = CountDownLatch(1); var code = 99
        val h = runner(f).start(job(), {}, {}, { code = it; exit.countDown() })
        h.cancel()
        assertTrue(exit.await(10, TimeUnit.SECONDS)); assertEquals(-2, code)
        assertTrue(f.calls.any { it.first == "job_cancel" && it.second.getString("id") == "J2" })
    }

    @Test fun anEngineThatRefusesAJobEndsItInsteadOfThrowing() {
        val f = Fake { _, _ -> JSONObject().put("ok", false).put("error", "rclone flag '--rc' is not allowed through this engine") }
        val lines = CopyOnWriteArrayList<String>(); val exit = CountDownLatch(1)
        runner(f).start(job(), {}, { lines += it }, { exit.countDown() })
        assertTrue(exit.await(5, TimeUnit.SECONDS)); assertNotNull(lines.singleOrNull { it.contains("not allowed") })
    }

    @Test fun aStagedJobRunsTheStagedArgsAndCopiesBackOnlyAfterAnExitOfZero() {
        val order = CopyOnWriteArrayList<String>()
        val f = Fake { m, _ -> when (m) { "job_start" -> ok().put("id", "J1"); else -> ok().put("lines", JSONArray()).put("cursor", 0).put("done", true).put("rc", 0) } }
        RcloneRunner.legStaging = object : RcloneRunner.LegStaging {
            override fun refusal(args: List<String>): String? = null
            override fun begin(args: List<String>) = RcloneRunner.Staged(listOf("copy", "drive:a", "/staged/dst"), { order += "pull"; null }, { rc -> order += "finish$rc"; null })
        }
        try {
            val exit = CountDownLatch(1); var code = 99
            runner(f).start(job(), {}, {}, { code = it; exit.countDown() })
            assertTrue(exit.await(10, TimeUnit.SECONDS)); assertEquals(0, code)
            assertEquals(listOf("pull", "finish0"), order.toList())
            val args = f.calls.first { it.first == "job_start" }.second.getJSONArray("args")
            assertEquals("/staged/dst", args.getString(2))
        } finally { RcloneRunner.legStaging = null }
    }

    @Test fun aStagingRefusalEndsTheJobWithItsSentenceAndNeverCallsTheEngine() {
        val f = Fake { _, _ -> ok() }
        RcloneRunner.legStaging = object : RcloneRunner.LegStaging {
            override fun refusal(args: List<String>) = "no store here"
            override fun begin(args: List<String>): RcloneRunner.Staged? = null
        }
        try {
            val exit = CountDownLatch(1); var code = 99; val lines = CopyOnWriteArrayList<String>()
            runner(f).start(job(), {}, { lines += it }, { code = it; exit.countDown() })
            assertTrue(exit.await(10, TimeUnit.SECONDS)); assertEquals(-1, code)
            assertEquals(listOf("[engine] no store here"), lines.toList()); assertTrue(f.calls.isEmpty())
        } finally { RcloneRunner.legStaging = null }
    }
}
