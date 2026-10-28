package com.diegonmarcos.clouddrive.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The status scheduler's contract after the memory/timeout report: bounded concurrency, cancellation, the size guard, the cheap facts. */
class RepoSchedulerTest {
    private lateinit var tmp: File
    private val release = CountDownLatch(1)

    @Before fun setUp() { tmp = Files.createTempDirectory("repo-scheduler").toFile() }
    @After fun tearDown() { release.countDown(); tmp.deleteRecursively() }

    private fun clone(name: String, onDemand: Boolean = false): GitStatusReader.Target {
        val d = File(tmp, name); File(d, ".git").mkdirs(); return GitStatusReader.Target(name, d, onDemand)
    }
    private fun probe() = GitStatusReader.Probe("main", "origin/main", 0, 0, 0, 0)
    private fun waitFor(what: String, cond: () -> Boolean) { val end = System.currentTimeMillis() + 5_000; while (!cond()) { check(System.currentTimeMillis() < end) { "timed out: $what" }; Thread.sleep(5) } }

    @Test fun neverMoreProbesThanTheDeclaredConcurrency() = runBlocking {
        val now = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val reader = GitStatusReader(
            probe = { calls.incrementAndGet(); val n = now.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; Thread.sleep(30); now.decrementAndGet(); probe() },
            timeoutMs = 5_000, concurrency = 1, ttlMs = 60_000,
        )
        withTimeout(10_000) { reader.request((1..6).map { clone("r$it") }).join() }
        assertEquals(6, calls.get())
        assertEquals("one repository at a time", 1, peak.get())
        assertTrue(reader.statuses.value.values.all { it.state == GitStatusReader.State.CLEAN })
    }

    @Test fun closingTheScreenCancelsTheReadsStillQueued() = runBlocking {
        val started = AtomicInteger()
        val reader = GitStatusReader(probe = { started.incrementAndGet(); release.await(); probe() }, timeoutMs = 30_000, concurrency = 1, ttlMs = 60_000)
        reader.request(listOf(clone("a"), clone("b"), clone("c")))
        waitFor("first probe started") { started.get() == 1 }
        reader.cancelAll()
        release.countDown()
        waitFor("running probe returns") { !reader.busy("a") }
        Thread.sleep(100)
        assertEquals("the queued reads never started", 1, started.get())
        assertFalse(reader.busy("b")); assertFalse(reader.busy("c"))
        // and they can be asked again later: nothing stays claimed
        withTimeout(5_000) { reader.request(listOf(clone("b"))).join() }
        assertEquals(GitStatusReader.State.CLEAN, reader.statuses.value["b"]!!.state)
    }

    @Test fun aLargeRepositoryIsSkippedByThePassAndReadOnDemand() = runBlocking {
        val probed = ConcurrentList()
        val reader = GitStatusReader(probe = { probed.add(it.name); probe() }, timeoutMs = 5_000, concurrency = 1, ttlMs = 60_000, tooLarge = { it.name == "big" })
        withTimeout(5_000) { reader.request(listOf(clone("big"), clone("small"))).join() }
        assertEquals(listOf("small"), probed.items)
        assertEquals(GitStatusReader.State.LARGE, reader.statuses.value["big"]!!.state)
        assertTrue(reader.statuses.value["big"]!!.terminal)
        val g = GitSyncCoordinator.glanceOf(reader.statuses.value["big"]!!)
        assertTrue(g.large && g.read)
        assertEquals(RepoStatus.Kind.LARGE, RepoStatus.kindOf(true, g))
        // tapping "Check working tree": on demand, forced, the guard does not apply
        withTimeout(5_000) { reader.request(listOf(clone("big", onDemand = true)), force = true).join() }
        assertEquals(listOf("small", "big"), probed.items)
        assertEquals(GitStatusReader.State.CLEAN, reader.statuses.value["big"]!!.state)
    }

    private class ConcurrentList { val items = java.util.Collections.synchronizedList(mutableListOf<String>()); fun add(s: String) { items.add(s) } }

    @Test fun theSizeGuardStopsCountingAtTheBound() {
        val d = File(tmp, "w").apply { mkdirs() }
        File(d, ".git").mkdirs(); File(d, ".git/huge").writeText("not counted")
        repeat(5) { File(d, "f$it").writeText("x") }
        File(d, "sub").mkdirs(); repeat(5) { File(d, "sub/g$it").writeText("x") }
        assertFalse(RepoCheap.tooLarge(d, maxFiles = 11, maxMs = 5_000))
        assertTrue(RepoCheap.tooLarge(d, maxFiles = 10, maxMs = 5_000))
        assertTrue("a count that cannot finish in time is large", RepoCheap.tooLarge(d, maxFiles = 1_000, maxMs = 0, clock = object : () -> Long { var t = 0L; override fun invoke() = t++ }))
    }

    @Test fun headAndUpstreamAreReadFromTheGitFilesWithoutTheEngine() {
        val d = File(tmp, "h"); File(d, ".git").mkdirs()
        File(d, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(d, ".git/config").writeText("[core]\n\tbare = false\n[branch \"main\"]\n\tremote = origin\n\tmerge = refs/heads/main\n[branch \"other\"]\n\tremote = up\n\tmerge = refs/heads/x\n")
        assertEquals(RepoCheap.Head("main", "origin/main"), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("ref: refs/heads/other\n")
        assertEquals(RepoCheap.Head("other", "up/x"), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("ref: refs/heads/new\n")
        assertEquals("no upstream configured", RepoCheap.Head("new", null), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        assertEquals("detached", RepoCheap.Head(null, null), RepoCheap.head(d))
        assertNull(RepoCheap.upstreamOf("", "main"))
        assertTrue(RepoCheap.isClone(d)); assertFalse(RepoCheap.isClone(File(tmp, "nope")))
    }
}
