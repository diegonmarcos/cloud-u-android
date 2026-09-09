package app.sterna.core.data.mail

import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * [discoverJmapAmong]: what the sign-in screen waits for, which hosts get the password, and which
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryIsBoundedTest {

    /** The real candidate order of `Jmap.autodiscoverHosts("someone@masto.top")`. */
    private val hosts = listOf("masto.top", "mail.masto.top", "jmap.masto.top", "api.masto.top")

    /** A delay no budget in this file survives — the shape of the 200 s the reporter waited. */
    private val hangs = 200_000L

    private val budget = 10_000L

    /**
     * A probe that answers [answers] after the stated virtual delay and records which hosts were
     */
    private class Bench(
        private val answers: Map<String, Pair<Long, HostProbe>>,
        private val deaf: Set<String> = emptySet(),
    ) {
        val asked = mutableListOf<String>()

        suspend fun probe(host: String): HostProbe {
            asked += host
            val (after, answer) = answers.getValue(host)
            if (host in deaf) withContext(NonCancellable) { delay(after) } else delay(after)
            return answer
        }
    }

    // ── The budget bounds the wait, not just the decision ────────────────────────────────────

    @Test
    fun `the sign-in stops at the budget, behind a probe that ignores cancellation`() = runTest {
        val bench = Bench(
            answers = hosts.associateWith { 60_000L to HostProbe.NoAnswer },
            deaf = hosts.toSet(),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun `an answer that landed is not held back by a black hole`() = runTest {
        val bench = Bench(
            answers = mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (300L to HostProbe.Usable),
                "jmap.masto.top" to (60_000L to HostProbe.NoAnswer),
                "api.masto.top" to (60_000L to HostProbe.NoAnswer),
            ),
            deaf = setOf("jmap.masto.top", "api.masto.top"),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("mail.masto.top"), result)
        // 100 ms for rank 0 to answer, then 300 ms for rank 1 — and not a second for the two
        // black holes, which is the whole point: this is the HAPPY path.
        assertEquals(400L, currentTime)
    }

    @Test
    fun `the budget the app ships with is the one that bounds the sign-in`() = runTest {
        val bench = Bench(
            answers = hosts.associateWith { 60_000L to HostProbe.NoAnswer },
            deaf = hosts.toSet(),
        )

        // No budget passed, deliberately: this must exercise the DEFAULT, which is the value
        // production gets. Handing it JMAP_DISCOVERY_BUDGET_MS here would pin the constant while
        // leaving whatever the app actually runs on unmeasured.
        val result = discoverJmapAmong(hosts) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(10_000L, currentTime)
    }

    // ── Who gets the password ────────────────────────────────────────────────────────────────

    @Test
    fun `a usable rank 0 keeps the password away from the guessed subdomains`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (300L to HostProbe.Usable),
                "mail.masto.top" to (100L to HostProbe.Usable),
                "jmap.masto.top" to (100L to HostProbe.Usable),
                "api.masto.top" to (100L to HostProbe.Usable),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("masto.top"), result)
        assertEquals(listOf("masto.top"), bench.asked)
        assertEquals(300L, currentTime)
    }

    @Test
    fun `a rank 0 that is no mail server releases the other candidates`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (200L to HostProbe.NoAnswer),
                "jmap.masto.top" to (200L to HostProbe.NoAnswer),
                "api.masto.top" to (200L to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(hosts, bench.asked)
        // Staggered: 100 ms for rank 0, and only then the 200 ms of the other three, together.
        assertEquals(300L, currentTime)
    }

    @Test
    fun `a rank 0 that rejects the credentials also releases the other candidates`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.BadCredentials),
                "mail.masto.top" to (200L to HostProbe.Usable),
                "jmap.masto.top" to (200L to HostProbe.NoAnswer),
                "api.masto.top" to (200L to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("mail.masto.top"), result)
        assertEquals(hosts, bench.asked)
        assertEquals(300L, currentTime)
    }

    // ── Rank decides, latency does not ───────────────────────────────────────────────────────

    @Test
    fun `the first host of the list wins over a quicker one below it`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (5_000L to HostProbe.Usable),
                "jmap.masto.top" to (1_000L to HostProbe.Usable),
                "api.masto.top" to (hangs to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("mail.masto.top"), result)
        assertEquals(5_100L, currentTime)
    }

    @Test
    fun `at the budget, a session that landed beats a rank that never spoke`() = runTest {
        val bench = Bench(
            answers = mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (60_000L to HostProbe.NoAnswer),
                "jmap.masto.top" to (900L to HostProbe.Usable),
                "api.masto.top" to (60_000L to HostProbe.NoAnswer),
            ),
            deaf = setOf("mail.masto.top", "api.masto.top"),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("jmap.masto.top"), result)
        assertEquals(10_000L, currentTime)
    }

    // ── "Wrong password" is only said when every rank has spoken ─────────────────────────────

    @Test
    fun `a rejection under a rank that has not spoken is not a wrong password`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (200L to HostProbe.BadCredentials),
                // The real mail server, slower than the budget on this day.
                "mail.masto.top" to (12_000L to HostProbe.Usable),
                "jmap.masto.top" to (hangs to HostProbe.NoAnswer),
                "api.masto.top" to (hangs to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun `a rejection is a wrong password once every rank has spoken`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.BadCredentials),
                "mail.masto.top" to (200L to HostProbe.NoAnswer),
                "jmap.masto.top" to (300L to HostProbe.NoAnswer),
                "api.masto.top" to (400L to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.BadCredentials, result)
        assertEquals(500L, currentTime)
    }

    @Test
    fun `no candidate host at all is NotFound, without waiting`() = runTest {
        val result = discoverJmapAmong(emptyList(), budget) { error("no host should be probed") }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(0L, currentTime)
    }

    // ── A guessed rank's rejection is not the reader's password ──────────────────────────────

    @Test
    fun `a rejection from a guessed rank alone is not a wrong password`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (200L to HostProbe.NoAnswer),
                "jmap.masto.top" to (300L to HostProbe.NoAnswer),
                // yandex.ru answers 403 here, and it is not the reader's mail server (#188).
                "api.masto.top" to (400L to HostProbe.BadCredentials),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(hosts, bench.asked)
        assertEquals(500L, currentTime)
    }

    @Test
    fun `a rejection from the domain she typed alone is a wrong password`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (400L to HostProbe.BadCredentials),
                "mail.masto.top" to (300L to HostProbe.NoAnswer),
                "jmap.masto.top" to (200L to HostProbe.NoAnswer),
                "api.masto.top" to (100L to HostProbe.NoAnswer),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.BadCredentials, result)
        assertEquals(hosts, bench.asked)
        assertEquals(700L, currentTime)
    }

    @Test
    fun `a guessed rank's rejection adds nothing to the one from the typed domain`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.BadCredentials),
                "mail.masto.top" to (200L to HostProbe.NoAnswer),
                "jmap.masto.top" to (300L to HostProbe.NoAnswer),
                "api.masto.top" to (400L to HostProbe.BadCredentials),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.BadCredentials, result)
        assertEquals(hosts, bench.asked)
        assertEquals(500L, currentTime)
    }

    /**
     * Every guessed rank at once, so the rule cannot be satisfied by a boundary that merely
     */
    @Test
    fun `no guessed rank is her password, whichever one refuses`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (200L to HostProbe.BadCredentials),
                "jmap.masto.top" to (300L to HostProbe.BadCredentials),
                "api.masto.top" to (400L to HostProbe.BadCredentials),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.NotFound, result)
        assertEquals(hosts, bench.asked)
        assertEquals(500L, currentTime)
    }

    @Test
    fun `a session outranks a guessed rank that rejected the credentials, whatever the latency`() = runTest {
        val bench = Bench(
            mapOf(
                "masto.top" to (100L to HostProbe.NoAnswer),
                "mail.masto.top" to (300L to HostProbe.Usable),
                "jmap.masto.top" to (hangs to HostProbe.NoAnswer),
                // First to answer by far, and the highest rank of all — the rank still decides.
                "api.masto.top" to (50L to HostProbe.BadCredentials),
            ),
        )

        val result = discoverJmapAmong(hosts, budget) { bench.probe(it) }

        assertEquals(MailRepository.DiscoveryResult.Found("mail.masto.top"), result)
        assertEquals(400L, currentTime)
    }

    // ── What an answer MEANS, run rather than read ───────────────────────────────────────────

    @Test
    fun `a session without a mail account is not a mail server`() {
        val anonymous = JmapSession(apiUrl = "https://masto.top/jmap/api/")

        assertEquals(HostProbe.NoAnswer, probeOfSession(anonymous))
    }

    @Test
    fun `a session that carries a mail account is a mail server`() {
        val real = JmapSession(
            apiUrl = "https://masto.top/jmap/api/",
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "u1"),
        )

        assertEquals(HostProbe.Usable, probeOfSession(real))
    }

    @Test
    fun `only the server's own 401 and 403 mean the password is wrong`() {
        assertEquals(HostProbe.BadCredentials, probeOfFailure(JmapException("no", httpCode = 401)))
        assertEquals(HostProbe.BadCredentials, probeOfFailure(JmapException("no", httpCode = 403)))
        assertEquals(HostProbe.NoAnswer, probeOfFailure(JmapException("nope", httpCode = 404)))
        assertEquals(HostProbe.NoAnswer, probeOfFailure(JmapException("boom", httpCode = 500)))
        assertEquals(HostProbe.NoAnswer, probeOfFailure(JmapException("not JMAP")))
        assertEquals(HostProbe.NoAnswer, probeOfFailure(IOException("unreachable")))
    }

    /**
     * The whole of one host's turn, which is what production actually calls: nothing between
     */
    @Test
    fun `one host's answer, failure and cancellation all go through the same door`() = runTest {
        val real = JmapSession(
            apiUrl = "https://masto.top/jmap/api/",
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "u1"),
        )
        val anonymous = JmapSession(apiUrl = "https://masto.top/jmap/api/")

        assertEquals(HostProbe.Usable, probeHost { real })
        assertEquals(HostProbe.NoAnswer, probeHost { anonymous })
        assertEquals(HostProbe.BadCredentials, probeHost { throw JmapException("no", httpCode = 401) })
        assertEquals(HostProbe.NoAnswer, probeHost { throw IOException("unreachable") })

        val cancelled = CancellationException("the user left the screen")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { probeHost { throw cancelled } }
        }
        assertEquals(cancelled, thrown)
    }

    /**
     * Cancellation is not something a host said. Leaving the sign-in screen cancels the probes, and
     */
    @Test
    fun `a cancelled probe is not a host that stayed silent`() {
        val cancelled = CancellationException("the user left the screen")

        val thrown = assertThrows(CancellationException::class.java) { probeOfFailure(cancelled) }

        assertEquals(cancelled, thrown)
    }
}
