package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.download.DlBackoff
import com.diegonmarcos.superapp.browser.download.DlCodec
import com.diegonmarcos.superapp.browser.download.DlControl
import com.diegonmarcos.superapp.browser.download.DlFormat
import com.diegonmarcos.superapp.browser.download.DlHttpEngine
import com.diegonmarcos.superapp.browser.download.DlJob
import com.diegonmarcos.superapp.browser.download.DlOutcome
import com.diegonmarcos.superapp.browser.download.DlRunner
import com.diegonmarcos.superapp.browser.download.DlStop
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * The download engine's range / resume rules against a real HTTP server (MockWebServer):
 * pause -> resume continues with Range + If-Range; a server without range support restarts
 * cleanly; a changed ETag restarts; a connection dropped mid-body is retried and resumed;
 * segmented transfers reassemble the exact bytes; size and checksum are verified.
 */
class DownloadEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val body = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** What the fake origin does: range support, its current ETag, and drops to inject. */
    private class Origin(var ranges: Boolean = true, var etag: String = "\"v1\"", var content: ByteArray = ByteArray(0),
                         var dropFirstBodyAfter: Int = -1)

    private val origin = Origin()

    @Before fun up() {
        origin.content = body
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val c = origin.content
                val range = request.getHeader("Range")
                val ifRange = request.getHeader("If-Range")
                val honour = origin.ranges && range != null && (ifRange == null || ifRange == origin.etag)
                val r = MockResponse().addHeader("ETag", origin.etag)
                if (origin.ranges) r.addHeader("Accept-Ranges", "bytes")
                if (!honour) {
                    if (origin.dropFirstBodyAfter > 0) {
                        val cut = origin.dropFirstBodyAfter; origin.dropFirstBodyAfter = -1
                        // Declares the full length, sends part of it, then the socket closes.
                        return r.setResponseCode(200).setBody(Buffer().write(c, 0, cut))
                            .setHeader("Content-Length", c.size).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                    return r.setResponseCode(200).setBody(Buffer().write(c))
                }
                val m = Regex("""bytes=(\d+)-(\d*)""").find(range!!)!!
                val from = m.groupValues[1].toInt()
                val to = m.groupValues[2].toIntOrNull() ?: (c.size - 1)
                if (from >= c.size) return r.setResponseCode(416).addHeader("Content-Range", "bytes */${c.size}")
                return r.setResponseCode(206).addHeader("Content-Range", "bytes $from-$to/${c.size}")
                    .setBody(Buffer().write(c, from, to - from + 1))
            }
        }
        server.start()
    }

    @After fun down() = server.shutdown()

    private fun job(path: String = "/f.bin", checksum: String? = null) =
        DlJob(id = "t1", url = server.url(path).toString(), fileName = "f.bin", userAgent = "UA/1", cookie = "sid=abc",
            referer = "https://page.example/", checksum = checksum)

    private fun engine(segmentThreshold: Long = Long.MAX_VALUE) =
        DlHttpEngine(segmentThreshold = segmentThreshold, progressEveryBytes = 64 * 1024, disk = { Long.MAX_VALUE / 4 })

    private fun out() = File(tmp.root, "dl/f.bin")

    @Test fun `a plain download carries the page's cookie, agent and referer and lands byte-exact`() {
        val o = engine().run(job(), out(), 1, DlControl()) {}
        assertTrue(o.toString(), o is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
        val r = requests.first()
        assertEquals("sid=abc", r.getHeader("Cookie"))
        assertEquals("UA/1", r.getHeader("User-Agent"))
        assertEquals("https://page.example/", r.getHeader("Referer"))
        assertEquals("identity", r.getHeader("Accept-Encoding"))
        assertNull(r.getHeader("Range"))
    }

    @Test fun `pause then resume continues with Range and If-Range from the exact byte`() {
        val control = DlControl()
        val first = engine().run(job(), out(), 1, control) { p -> if (p.downloaded >= 1024 * 1024) control.request(DlStop.PAUSE) }
        assertTrue(first.toString(), first is DlOutcome.Stopped && first.reason == DlStop.PAUSE)
        val paused = first.job
        val have = out().length()
        assertTrue("paused part way ($have)", have in 1 until body.size)
        assertEquals(have, paused.downloaded)
        assertEquals("\"v1\"", paused.etag)
        // persisted and read back, as after a process death
        val restored = DlCodec.fromJson(DlCodec.toJson(listOf(paused))).single()
        val second = engine().run(restored, out(), 1, DlControl()) {}
        assertTrue(second.toString(), second is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
        val resume = requests.last()
        assertEquals("bytes=$have-", resume.getHeader("Range"))
        assertEquals("\"v1\"", resume.getHeader("If-Range"))
    }

    @Test fun `a server without range support restarts from zero and still ends byte-exact`() {
        origin.ranges = false
        val control = DlControl()
        val first = engine().run(job(), out(), 1, control) { p -> if (p.downloaded >= 512 * 1024) control.request(DlStop.PAUSE) }
        assertTrue(first is DlOutcome.Stopped)
        assertEquals(false, first.job.acceptRanges)
        val second = engine().run(first.job, out(), 1, DlControl()) {}
        assertTrue(second.toString(), second is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
        assertEquals(body.size.toLong(), second.job.total)
    }

    @Test fun `an ETag that changed while paused restarts with the new file`() {
        val control = DlControl()
        val first = engine().run(job(), out(), 1, control) { p -> if (p.downloaded >= 512 * 1024) control.request(DlStop.PAUSE) }
        assertTrue(first is DlOutcome.Stopped)
        val v2 = Random(99).nextBytes(2 * 1024 * 1024)
        origin.content = v2; origin.etag = "\"v2\""
        val second = engine().run(first.job, out(), 1, DlControl()) {}
        assertTrue(second.toString(), second is DlOutcome.Completed)
        assertArrayEquals(v2, out().readBytes())
        assertEquals("\"v2\"", second.job.etag)
        assertEquals("\"v1\"", requests.last().getHeader("If-Range"))   // asked with the old validator, got the new file
    }

    @Test fun `a connection dropped mid-body is retried and resumed with a range`() {
        origin.dropFirstBodyAfter = 700 * 1024
        val waits = mutableListOf<Long>()
        val runner = DlRunner(engine(), sleeper = { waits += it }, backoff = { 10L })
        var retried = 0
        val o = runner.run(job(), out(), 1, DlControl(), onRetry = { _, _ -> retried++ }) {}
        assertTrue(o.toString(), o is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
        assertEquals(1, retried)
        assertTrue(waits.isNotEmpty())
        val resume = requests.last()
        val from = Regex("""bytes=(\d+)-""").find(resume.getHeader("Range")!!)!!.groupValues[1].toLong()
        assertTrue("resumed from the bytes already on disk ($from)", from in 1..(700L * 1024))
    }

    @Test fun `a 404 is final and a 503 is retried`() {
        server.dispatcher = object : Dispatcher() {
            var n = 0
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/gone" -> MockResponse().setResponseCode(404)
                n++ == 0 -> MockResponse().setResponseCode(503)
                else -> MockResponse().setResponseCode(200).setBody(Buffer().write(body))
            }
        }
        val gone = DlRunner(engine(), sleeper = {}, backoff = { 1L }).run(job("/gone"), out(), 1, DlControl()) {}
        assertTrue(gone is DlOutcome.Failed && !gone.retryable)
        val ok = DlRunner(engine(), sleeper = {}, backoff = { 1L }).run(job("/x"), File(tmp.root, "dl/x.bin"), 1, DlControl()) {}
        assertTrue(ok.toString(), ok is DlOutcome.Completed)
        assertTrue(DlBackoff.retryable(503) && DlBackoff.retryable(429) && !DlBackoff.retryable(404))
        assertTrue(DlBackoff.delayMs(0, 0.0) < DlBackoff.delayMs(3, 0.0) && DlBackoff.delayMs(30, 0.0) <= 300_000)
    }

    @Test fun `a large file on a range server is fetched over several connections and reassembled`() {
        val o = engine(segmentThreshold = 1024 * 1024).run(job(), out(), 4, DlControl()) {}
        assertTrue(o.toString(), o is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
        val ranged = requests.mapNotNull { it.getHeader("Range") }
        assertEquals("a 3 MiB file at a 1 MiB minimum makes 3 segments, one ranged request each", 3, ranged.distinct().size)
        assertTrue("every segment carried If-Range", requests.filter { it.getHeader("Range") != null }.all { it.getHeader("If-Range") == "\"v1\"" })
    }

    @Test fun `a segmented download paused mid-way resumes each segment`() {
        val control = DlControl()
        val e = engine(segmentThreshold = 1024 * 1024)
        val first = e.run(job(), out(), 3, control) { p -> if (p.downloaded >= 1024 * 1024) control.request(DlStop.PAUSE) }
        assertTrue(first.toString(), first is DlOutcome.Stopped)
        assertEquals(3, first.job.segments.size)
        val restored = DlCodec.fromJson(DlCodec.toJson(listOf(first.job))).single()
        assertEquals(first.job.segments, restored.segments)
        val second = e.run(restored, out(), 3, DlControl()) {}
        assertTrue(second.toString(), second is DlOutcome.Completed)
        assertArrayEquals(body, out().readBytes())
    }

    @Test fun `the checksum is verified at the end`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        assertTrue(engine().run(job(checksum = "sha256:$sha"), out(), 1, DlControl()) {} is DlOutcome.Completed)
        out().delete()
        val bad = engine().run(job(checksum = "sha256:" + "0".repeat(64)), out(), 1, DlControl()) {}
        assertTrue(bad is DlOutcome.Failed && !bad.retryable)
    }

    @Test fun `not enough free space fails before writing`() {
        val e = DlHttpEngine(disk = { 10L * 1024 })
        val o = e.run(job(), out(), 1, DlControl()) {}
        assertTrue(o.toString(), o is DlOutcome.Failed && o.error.startsWith("Not enough free space"))
    }

    @Test fun `content-disposition names and plans`() {
        assertEquals("report 2026.pdf", DlFormat.dispositionName("attachment; filename=\"report 2026.pdf\""))
        assertEquals("naïve.txt", DlFormat.dispositionName("attachment; filename=\"x.txt\"; filename*=UTF-8''na%C3%AFve.txt"))
        assertEquals("passwd", DlFormat.dispositionName("attachment; filename=\"../../etc/passwd\""))
        assertNull(DlFormat.dispositionName("inline"))
        assertEquals("a (2).txt", DlFormat.unique("a.txt") { it == "a.txt" })
        val plan = DlHttpEngine.plan(10L * 1024 * 1024, 4)
        assertEquals(4, plan.size); assertEquals(0L, plan.first().start); assertEquals(10L * 1024 * 1024 - 1, plan.last().end)
        assertEquals(Triple(100L, 199L, 1000L), DlHttpEngine.parseContentRange("bytes 100-199/1000"))
    }
}
