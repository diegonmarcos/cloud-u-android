package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.GitAuth
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #850 the ONE status reader, executed: every row must END (clean / ahead / behind /
 * dirty / not cloned / error(reason)), a hanging status must end in error(timeout) without
 * holding the others, the cache must stop re-reads, and an in-flight or stuck probe must
 * never be started twice however often the page asks. The probes are fixtures; the real
 * probe is libs:git-sync's status, exercised by its own suite.
 */
class GitStatusReaderTest {

    private lateinit var tmp: File
    private val release = CountDownLatch(1)

    @Before fun setUp() { tmp = Files.createTempDirectory("git-status-reader").toFile() }
    @After fun tearDown() { release.countDown(); tmp.deleteRecursively() }

    private fun clone(name: String): GitStatusReader.Target {
        val d = File(tmp, name); File(d, ".git").mkdirs(); return GitStatusReader.Target(name, d)
    }

    private fun probe(ahead: Int = 0, behind: Int = 0, changed: Int = 0, conflicts: Int = 0) =
        GitStatusReader.Probe("main", "origin/main", ahead, behind, changed, conflicts)

    @Test fun hangingStatusEndsInTimeoutErrorAndDoesNotHoldTheOthers() = runBlocking {
        val reader = GitStatusReader(
            probe = { dir -> if (dir.name == "huge") { release.await(); probe() } else probe(ahead = 2) },
            timeoutMs = 300, concurrency = 1, ttlMs = 60_000,
        )
        val targets = listOf(clone("huge"), clone("a"), clone("b"))
        withTimeout(5_000) { reader.request(targets).join() }
        val st = reader.statuses.value
        assertEquals(GitStatusReader.State.ERROR, st["huge"]!!.state)
        assertTrue(st["huge"]!!.reason!!.startsWith("timeout"))
        assertEquals("error(timeout after 300ms)", st["huge"]!!.label())
        assertEquals(GitStatusReader.State.AHEAD, st["a"]!!.state)
        assertEquals(GitStatusReader.State.AHEAD, st["b"]!!.state)
        assertTrue("every row ended", st.values.all { it.terminal })
    }

    @Test fun aStuckProbeIsNeverStartedTwice() = runBlocking {
        val calls = AtomicInteger()
        val reader = GitStatusReader(
            probe = { calls.incrementAndGet(); release.await(); probe() },
            timeoutMs = 100, concurrency = 2, ttlMs = 0,
        )
        val t = listOf(clone("stuck"))
        reader.request(t).join()
        repeat(5) { reader.request(t, force = true).join() }
        assertEquals("a probe past its timeout keeps the repository claimed", 1, calls.get())
        assertTrue(reader.busy("stuck"))
        release.countDown()
        val deadline = System.currentTimeMillis() + 3_000
        while (reader.busy("stuck") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        reader.request(t, force = true).join()
        assertEquals("once the thread returned the repository is readable again", 2, calls.get())
    }

    @Test fun recompositionDoesNotRestartAnInFlightRead() = runBlocking {
        val calls = AtomicInteger()
        val gate = CountDownLatch(1)
        val reader = GitStatusReader(probe = { calls.incrementAndGet(); gate.await(2, TimeUnit.SECONDS); probe() }, timeoutMs = 5_000, concurrency = 3, ttlMs = 60_000)
        val t = listOf(clone("r"))
        val first = reader.request(t)
        repeat(10) { reader.request(t) }
        gate.countDown(); first.join()
        assertEquals(1, calls.get())
        assertEquals(GitStatusReader.State.CLEAN, reader.statuses.value["r"]!!.state)
    }

    @Test fun freshEntriesAreCachedUntilForcedOrInvalidated() = runBlocking {
        val calls = AtomicInteger()
        val reader = GitStatusReader(probe = { calls.incrementAndGet(); probe() }, timeoutMs = 2_000, concurrency = 2, ttlMs = 60_000)
        val t = listOf(clone("r"))
        reader.request(t).join(); reader.request(t).join()
        assertEquals(1, calls.get())
        assertTrue(reader.statuses.value["r"]!!.readAtMs > 0)
        reader.request(t, force = true).join()
        assertEquals(2, calls.get())
        reader.invalidate("r"); reader.request(t).join()
        assertEquals(3, calls.get())
    }

    @Test fun concurrencyIsLimited() = runBlocking {
        val now = AtomicInteger(); val peak = AtomicInteger()
        val reader = GitStatusReader(
            probe = { val n = now.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf); Thread.sleep(60); now.decrementAndGet(); probe() },
            timeoutMs = 5_000, concurrency = 2, ttlMs = 0,
        )
        reader.request((1..6).map { clone("r$it") }).join()
        assertTrue("peak ${peak.get()} > 2", peak.get() <= 2)
        assertEquals(6, reader.statuses.value.size)
    }

    @Test fun everyStateIsReached() = runBlocking {
        val by = ConcurrentHashMap(mapOf(
            "clean" to probe(), "ahead" to probe(ahead = 1), "behind" to probe(behind = 3),
            "dirty" to probe(changed = 1, ahead = 4),
        ))
        val reader = GitStatusReader(
            probe = { d -> by[d.name] ?: throw IllegalStateException("index locked") },
            timeoutMs = 2_000, concurrency = 3, ttlMs = 0,
        )
        val missing = GitStatusReader.Target("gone", File(tmp, "gone"))
        reader.request(listOf(clone("clean"), clone("ahead"), clone("behind"), clone("dirty"), clone("broken"), missing)).join()
        val st = reader.statuses.value
        assertEquals("clean", st["clean"]!!.label())
        assertEquals("ahead 1", st["ahead"]!!.label())
        assertEquals("behind 3", st["behind"]!!.label())
        assertEquals("dirty 1", st["dirty"]!!.label())
        assertEquals("error(index locked)", st["broken"]!!.label())
        assertEquals("not cloned", st["gone"]!!.label())
    }

    @Test fun glanceOfNeverLeavesATerminalStatusReading() {
        GitStatusReader.State.values().filter { it != GitStatusReader.State.READING }.forEach { s ->
            val status = GitStatusReader.Status(s, probe = probe(), reason = "x", readAtMs = 1)
            assertTrue("$s must be read", GitSyncCoordinator.glanceOf(status).read)
        }
        assertEquals(false, GitSyncCoordinator.glanceOf(GitStatusReader.Status(GitStatusReader.State.READING)).read)
        assertEquals("timeout after 20s", GitSyncCoordinator.glanceOf(GitStatusReader.Status(GitStatusReader.State.ERROR, reason = "timeout after 20s")).error)
    }

    @Test fun autoPullOnlyOnWifiWhenOn() {
        assertEquals(GitSyncCoordinator.AUTO_PULL_OFF, GitSyncCoordinator.autoPullDecision(false, true, true, false))
        assertEquals(GitSyncCoordinator.AUTO_PULL_METERED, GitSyncCoordinator.autoPullDecision(true, true, false, false))
        assertEquals(GitSyncCoordinator.AUTO_PULL_GO, GitSyncCoordinator.autoPullDecision(true, false, false, false))
        assertEquals(GitSyncCoordinator.AUTO_PULL_BUSY, GitSyncCoordinator.autoPullDecision(true, true, true, true))
        assertEquals(GitSyncCoordinator.AUTO_PULL_GO, GitSyncCoordinator.autoPullDecision(true, true, true, false))
    }

    @Test fun pullsRideTheOneDeclaredCredential() {
        val none = { GitAuth.None }
        val sess = { GitAuth.Https("session", "s") }
        // a seeded repository holds no secret: the declared token answers
        assertEquals(GitAuth.Https("diegonmarcos", "tok"), GitSyncCoordinator.pickAuth("none", none, sess, { "tok" }, "diegonmarcos", true))
        assertEquals(GitAuth.Https("diegonmarcos", "tok"), GitSyncCoordinator.pickAuth("https", { GitAuth.Https("u", "") }, sess, { "tok" }, "diegonmarcos", true))
        // the repository's own stored secret wins over the declared one
        assertEquals(GitAuth.Https("u", "own"), GitSyncCoordinator.pickAuth("https", { GitAuth.Https("u", "own") }, sess, { "tok" }, "o", true))
        assertEquals(sess(), GitSyncCoordinator.pickAuth(GitSyncCoordinator.AUTH_SESSION, none, sess, { "tok" }, "o", true))
        assertEquals(GitAuth.None, GitSyncCoordinator.pickAuth("none", none, sess, { "" }, "o", true))
        // never presented to a host the credential does not belong to
        assertEquals(GitAuth.None, GitSyncCoordinator.pickAuth("none", none, sess, { "tok" }, "o", false))
    }
}
