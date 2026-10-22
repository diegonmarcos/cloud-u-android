package com.diegonmarcos.superapp.crash

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1122 persist-first, the local sink over a plain loopback socket, and the offline retry bookkeeping. */
class CrashReportsTest {

    private fun tmp(): File = Files.createTempDirectory("crashes").toFile()
    private val meta = linkedMapOf("version" to "1.2", "device" to "Samsung A37")

    @Test fun persistWritesTheFullStackBeforeAnythingElse() {
        val dir = tmp()
        val f = CrashReports.persist(dir, "startup", CrashReports.trace(IllegalStateException("boom", RuntimeException("root"))), meta, 1_000L)!!
        val t = f.readText()
        assertTrue(t.contains("phase: startup") && t.contains("IllegalStateException: boom") && t.contains("Caused by: java.lang.RuntimeException: root"))
        assertEquals(listOf(f), CrashReports.unseen(dir))
        assertEquals(listOf(f), CrashReports.unsunk(dir))
    }

    @Test fun jsonEscapesWhatATraceContains() {
        val j = CrashReports.json("a.b", "p", "line1\n\t\"q\" \\ end", meta, 5L)
        assertTrue(j.contains("\"trace\":\"line1\\n\\t\\\"q\\\" \\\\ end\""))
        assertTrue(j.startsWith("{\"app\":\"a.b\"") && j.endsWith(",\"time_ms\":5}"))
    }

    @Test fun theLocalSinkIsAPlainLoopbackPostWithTheGuardHeaders() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        var request = ""
        val t = Thread {
            server.accept().use { s ->
                val buf = ByteArray(4096); val n = s.getInputStream().read(buf)
                request = String(buf, 0, n)
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".toByteArray())
            }
        }.also { it.start() }
        val ok = CrashReports.postLocal(server.localPort, CrashReports.SINK_PATH, "{\"app\":\"x\"}")
        t.join(); server.close()
        assertTrue(ok)
        assertTrue(request.startsWith("POST /__app__/api/crashes HTTP/1.1"))
        assertTrue(request.contains("x-c3-write: 1") && request.contains("Content-Type: application/json") && request.endsWith("{\"app\":\"x\"}"))
    }

    @Test fun anUnreachableSinkIsFalseNotAThrow() {
        val free = ServerSocket(0).use { it.localPort }
        assertFalse(CrashReports.postLocal(free, CrashReports.SINK_PATH, "{}"))
        assertFalse("port 0 = no sink was declared at build time", CrashReports.postLocal(0, CrashReports.SINK_PATH, "{}"))
    }

    @Test fun aReportTheSinkMissedIsRetriedUntilMarkedSunk() {
        val dir = tmp()
        val f = CrashReports.persist(dir, "p", "t", meta, 10L)!!
        assertEquals(1, CrashReports.unsunk(dir).size)
        CrashReports.markSunk(f)
        assertTrue(CrashReports.unsunk(dir).isEmpty())
        CrashReports.markAllSeen(dir)
        assertTrue("shown reports stay sunk", CrashReports.unsunk(dir).isEmpty())
        assertTrue(CrashReports.unseen(dir).isEmpty())
    }

    @Test fun oldReportsArePruned() {
        val dir = tmp()
        repeat(30) { CrashReports.persist(dir, "p$it", "t", meta, 1_000L + it) }
        assertEquals(20, dir.listFiles()!!.count { it.name.endsWith(".txt") })
    }

    @Test fun anUnshownReportIsSurfacedUntilMarkedSeen() {
        val dir = tmp()
        CrashReports.persist(dir, "a", "t1", meta, 1_000L)
        CrashReports.persist(dir, "b", "t2", meta, 2_000L)
        assertEquals(2, CrashReports.unseen(dir).size)
        assertTrue(CrashReports.latest(dir)!!.readText().contains("phase: b"))
        CrashReports.markAllSeen(dir)
        assertTrue("shown once, not on every launch", CrashReports.unseen(dir).isEmpty())
        assertTrue("still readable for Copy / Share", CrashReports.shareText(dir).contains("phase: b"))
    }

    @Test fun persistNeverThrowsAndShareTextIsBounded() {
        val blocked = File(tmp(), "file").apply { writeText("x") }
        assertEquals("a dir that cannot be created is swallowed", null, CrashReports.persist(File(blocked, "sub"), "p", "t", meta))
        assertTrue(CrashReports.shareText(tmp()).startsWith("No error"))
        val dir = tmp()
        CrashReports.persist(dir, "big", "x".repeat(200_000), meta, 5L)
        assertTrue(CrashReports.shareText(dir).length < 151_000 && CrashReports.shareText(dir).endsWith("(truncated)"))
    }
}
