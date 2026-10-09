package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.JobBoard.Phase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-job state: no cross-talk between rows, honest overall math, bounded downloads, one install at a time. */
class JobBoardTest {

    private fun twoDownloadsAndAnInstall(): JobBoard = JobBoard().apply {
        queue("a", "A"); queue("b", "B"); queue("c", "C")
        phase("c", Phase.INSTALLING)
    }

    @Test fun `interleaved progress events stay on their own rows`() {
        val b = twoDownloadsAndAnInstall()
        b.download("a", 100, 1000); b.download("b", 900, 1000); b.download("a", 300, 1000); b.download("b", 950, 1000)
        assertEquals(30, b.row("a")!!.percent)
        assertEquals(95, b.row("b")!!.percent)
        assertEquals(Phase.INSTALLING, b.row("c")!!.phase)         // the install is not read as a download
        assertEquals(-1, b.row("c")!!.percent)
        assertEquals("installing", b.row("c")!!.text())
        assertEquals("downloading 30%", b.row("a")!!.text())
    }

    @Test fun `a failure in one row does not touch the others`() {
        val b = twoDownloadsAndAnInstall()
        b.download("a", 500, 1000)
        b.fail("b", "sha256 mismatch")
        assertEquals(Phase.FAILED, b.row("b")!!.phase)
        assertEquals("failed: sha256 mismatch", b.row("b")!!.text())
        assertEquals(Phase.DOWNLOADING, b.row("a")!!.phase)
        assertEquals(50, b.row("a")!!.percent)
        assertEquals(Phase.INSTALLING, b.row("c")!!.phase)
        b.download("b", 999, 1000)                                  // a late event does not revive it
        assertEquals(Phase.FAILED, b.row("b")!!.phase)
    }

    @Test fun `each row is monotonic - a restarted source never moves it back`() {
        val b = JobBoard().apply { queue("a") }
        b.download("a", 600, 1000)
        b.download("a", 0, 0)                                       // next source in the ladder opens at 0
        b.download("a", 100, 1000)
        assertEquals(60, b.row("a")!!.percent)
        assertEquals(600, b.row("a")!!.bytes)
        b.download("a", 800, 1000)
        assertEquals(80, b.row("a")!!.percent)
    }

    @Test fun `unknown total is no percentage`() {
        val b = JobBoard().apply { queue("a") }
        b.download("a", 500, -1)
        assertEquals(-1, b.row("a")!!.percent)
        assertEquals("downloading…", b.row("a")!!.text())
    }

    @Test fun `overall - counts and bytes over active jobs, installs as steps`() {
        val b = JobBoard()
        b.queue("a"); b.queue("b"); b.queue("c"); b.queue("d")
        b.download("a", 500, 1000); b.download("b", 0, 1000)
        b.download("c", 0, 1000); b.download("d", 0, 1000)
        b.phase("c", Phase.QUEUED, "download"); b.phase("d", Phase.QUEUED, "download")
        val o = b.overall()
        assertEquals(2, o.running); assertEquals(2, o.queued); assertTrue(o.multi)
        // a is half way through the 90% download share = 45% of one job's weight, of four equal jobs.
        assertEquals(11, o.percent)
        assertEquals("2 running · 2 queued · 11%", o.text())
    }

    @Test fun `overall does not fall when a job finishes, and reaches 100`() {
        val b = JobBoard()
        b.queue("a"); b.queue("b")
        b.download("a", 1000, 1000); b.download("b", 1000, 1000)
        val before = b.overall().percent
        b.done("a")
        assertTrue(b.overall().percent >= before)
        b.done("b")
        assertEquals(100, b.overall().percent)
        assertEquals(0, b.overall().active)
    }

    @Test fun `a finished session is forgotten when the next job is queued`() {
        val b = JobBoard()
        b.queue("a"); b.done("a")
        b.queue("b")
        assertNull(b.row("a"))
        assertEquals(0, b.overall().percent)
    }

    @Test fun `a nested verb re-announcing the job does not rewind it`() {
        val b = JobBoard()
        b.begin("a", "A"); b.download("a", 700, 1000)
        b.begin("a", "A")
        assertEquals(70, b.row("a")!!.percent)
    }

    @Test fun `single job - the bar speaks for it, not for a batch`() {
        val b = JobBoard().apply { queue("a") }
        assertTrue(!b.overall().multi)
    }

    @Test fun `downloads are bounded to three and installs never overlap, and the rest show queued`() {
        val board = JobBoard()
        val runner = JobRunner(board, maxDownloads = 3)
        val pool = Executors.newFixedThreadPool(8)
        val dl = AtomicInteger(); val maxDl = AtomicInteger()
        val inst = AtomicInteger(); val maxInst = AtomicInteger()
        val gate = CountDownLatch(1)
        val three = CountDownLatch(3)
        (1..5).forEach { board.queue("d$it") }
        val downloads = (1..5).map { i -> pool.submit {
            runner.download("d$i") {
                val n = dl.incrementAndGet(); maxDl.updateAndGet { m -> maxOf(m, n) }
                three.countDown(); gate.await(5, TimeUnit.SECONDS)
                dl.decrementAndGet()
            }
        } }
        assertTrue(three.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(3, board.rows().count { it.phase == Phase.DOWNLOADING })
        assertEquals(2, board.rows().count { it.phase == Phase.QUEUED && it.waitingFor == "download" })
        // an install runs while the downloads are still in flight
        val installs = (1..3).map { i -> pool.submit {
            runner.install("i$i") {
                val n = inst.incrementAndGet(); maxInst.updateAndGet { m -> maxOf(m, n) }
                Thread.sleep(30); inst.decrementAndGet()
            }
        } }
        installs.forEach { it.get(5, TimeUnit.SECONDS) }
        gate.countDown()
        downloads.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(3, maxDl.get())
        assertEquals(1, maxInst.get())
    }
}
