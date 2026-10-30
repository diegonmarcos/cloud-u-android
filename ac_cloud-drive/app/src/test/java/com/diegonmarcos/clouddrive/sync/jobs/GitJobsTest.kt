package com.diegonmarcos.clouddrive.sync.jobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 THE ONE EXECUTOR's contract. Mutations proven red while writing this suite: dropping the Semaphore (atMostWorkersRun
 * observed 5 at once), skipping the lock check (aLockedPathIsSkipped ran both bodies), ignoring `exclusive` in refusal
 * (anExclusiveJobRefusesAnother started the second), recording nothing on failure (everyOutcomeIsAccounted lost the FAIL).
 */
class GitJobsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun jobs() = GitJobs(scope) { System.currentTimeMillis() }
    private fun targets(n: Int, prefix: String = "/s/git/r") = (1..n).map { GitJobs.Target("$prefix$it", "r$it") }
    private fun ok(msg: String = "done") = GitJobs.Outcome(GitJobs.Status.OK, msg)

    @Test fun `at most workers bodies run at once and every target ends`() = runBlocking {
        val j = jobs()
        val inFlight = AtomicInteger(); val peak = AtomicInteger()
        val job = j.submit(GitJobs.Spec("sync_all", "sync", "Sync all", targets(10), workers = 2) { _ ->
            val n = inFlight.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
            delay(30); inFlight.decrementAndGet(); ok()
        })
        assertNotNull(job); job!!.join()
        val s = j.jobs.value.single()
        assertTrue("peak ${peak.get()}", peak.get() <= 2 && peak.get() >= 1)
        assertEquals(10, s.done); assertEquals(10, s.ok.size); assertTrue(s.finished); assertFalse(s.cancelled)
    }

    @Test fun `a locked path is skipped, never worked on twice`() = runBlocking {
        val j = jobs()
        val hold = CountDownLatch(1); val started = CountDownLatch(1)
        val ran = AtomicInteger()
        val a = j.submit(GitJobs.Spec("op@1", "fetch", "fetch", targets(1), exclusive = false) { _ -> started.countDown(); hold.await(5, TimeUnit.SECONDS); ran.incrementAndGet(); ok() })!!
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(j.locked("/s/git/r1")); assertTrue("/s/git/r1" in j.busy.value)
        val b = j.submit(GitJobs.Spec("rename", "rename_to_repo", "Rename", targets(1), exclusive = false) { _ -> ran.incrementAndGet(); ok() })!!
        b.join()
        assertEquals(GitJobs.LOCKED, j.jobs.value.first { it.id == "rename" }.skipped["r1"])
        hold.countDown(); a.join()
        assertEquals("only the first body ran", 1, ran.get())
        assertFalse(j.locked("/s/git/r1")); assertTrue(j.busy.value.isEmpty())
    }

    @Test fun `an exclusive job refuses another exclusive job but not a read`() = runBlocking {
        val j = jobs()
        val hold = CountDownLatch(1); val started = CountDownLatch(1)
        val a = j.submit(GitJobs.Spec("clone_all", "clone_missing", "Clone missing", targets(1), exclusive = true) { _ -> started.countDown(); hold.await(5, TimeUnit.SECONDS); ok() })!!
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = GitJobs.Spec("keep_all", "keep_depth", "Keep depth", targets(1, "/s/git/x")) { _ -> ok() }
        assertNotNull(j.refusal(second)); assertNull(j.submit(second))
        val read = GitJobs.Spec("check", "check_light", "Light check", targets(1, "/s/git/y"), exclusive = false) { _ -> ok() }
        assertNull(j.refusal(read)); assertNotNull(j.submit(read))
        assertNotNull("the same id cannot run twice", j.refusal(GitJobs.Spec("clone_all", "clone_missing", "Clone missing", emptyList()) { _ -> ok() }))
        hold.countDown(); a.join()
        assertNull("free again once ended", j.refusal(second))
    }

    @Test fun `cancel stops what has not started and the started body still lands`() = runBlocking {
        val j = jobs()
        val hold = CountDownLatch(1); val started = CountDownLatch(1)
        val ran = AtomicInteger()
        val job = j.submit(GitJobs.Spec("keep_all", "keep_depth", "Keep depth", targets(6), workers = 1) { _ -> started.countDown(); hold.await(5, TimeUnit.SECONDS); ran.incrementAndGet(); ok("kept") })!!
        assertTrue(started.await(5, TimeUnit.SECONDS))
        j.cancel("keep_all"); hold.countDown(); job.join()
        val s = j.jobs.value.single()
        assertTrue(s.cancelled); assertTrue(s.finished)
        assertTrue("at most the started bodies ran (${ran.get()})", ran.get() in 1..2)
        assertTrue(s.done < 6)
    }

    @Test fun `every outcome is accounted, a throwing body is a FAIL, and onEnd runs once with the final state`() = runBlocking {
        val j = jobs()
        val ends = AtomicInteger(); var last: GitJobs.State? = null
        val job = j.submit(GitJobs.Spec("mixed", "fetch", "Fetch all", targets(4), workers = 4, onEnd = { s -> ends.incrementAndGet(); last = s }) { t ->
            when (t.name) { "r1" -> ok("fetched"); "r2" -> GitJobs.Outcome(GitJobs.Status.SKIP, "dirty"); "r3" -> GitJobs.Outcome(GitJobs.Status.FAIL, "auth"); else -> error("boom") }
        })!!
        job.join()
        val s = j.jobs.value.single()
        assertEquals(mapOf("r1" to "fetched"), s.ok); assertEquals(mapOf("r2" to "dirty"), s.skipped)
        assertEquals(setOf("r3", "r4"), s.failed.keys); assertEquals("boom", s.failed["r4"])
        assertEquals("ok: fetched", s.outcomeOf("r1")); assertEquals("failed: auth", s.outcomeOf("r3")); assertNull(s.outcomeOf("r9"))
        assertEquals(listOf("skipped r2: dirty", "failed r3: auth", "failed r4: boom", "ok r1: fetched"), s.lines())
        assertEquals(1, ends.get()); assertEquals(4, last?.done)
    }

    @Test fun `dismiss drops a finished job, latest picks the running one first`() = runBlocking {
        val j = jobs()
        j.submit(GitJobs.Spec("a", "pull", "Pull all", targets(1)) { _ -> ok() })!!.join()
        assertEquals("a", j.latest("pull")?.id)
        val hold = CountDownLatch(1); val started = CountDownLatch(1)
        val b = j.submit(GitJobs.Spec("b", "pull", "Pull all", targets(1, "/s/git/z"), exclusive = false) { _ -> started.countDown(); hold.await(5, TimeUnit.SECONDS); ok() })!!
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals("b", j.latest("pull")?.id)
        j.dismiss("b"); assertEquals("a running job is never dismissed", 2, j.jobs.value.size)
        j.dismiss("a"); assertEquals(listOf("b"), j.jobs.value.map { it.id })
        hold.countDown(); b.join()
        assertNull(j.latest("depth"))
    }
}
