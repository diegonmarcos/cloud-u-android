package app.sterna.core.data.mail

import app.sterna.core.data.account.normalizeEndpoint
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.coroutineContext

/** A session with a mail account, an HTTP 401/403 refusal, or nothing usable. */
sealed interface HostProbe {
    data object Usable : HostProbe
    data object BadCredentials : HostProbe
    data object NoAnswer : HostProbe
}

/** A session WITHOUT a mail account is not a mail server: some servers hand one to anyone (#137). */
fun probeOfSession(session: JmapSession): HostProbe =
    if (session.mailAccountId() != null) HostProbe.Usable else HostProbe.NoAnswer

/**
 * Only the server's own 401/403 says anything about the credentials. A coroutine timeout is not
 * among them: it arrives as a [CancellationException] and rethrows.
 */
fun probeOfFailure(failure: Throwable): HostProbe {
    if (failure is CancellationException) throw failure
    return if (failure is JmapException && (failure.httpCode == 401 || failure.httpCode == 403)) {
        HostProbe.BadCredentials
    } else {
        HostProbe.NoAnswer
    }
}

suspend fun probeHost(fetchSession: suspend () -> JmapSession): HostProbe =
    try {
        probeOfSession(fetchSession())
    } catch (t: Throwable) {
        probeOfFailure(t)
    }

/** Budget for the whole of autodiscovery, all candidates together; not a per-host timeout. */
const val JMAP_DISCOVERY_BUDGET_MS = 10_000L

/**
 * The host of LOWEST RANK that yielded a usable session, not the fastest: the chosen host is
 */
suspend fun discoverJmapAmong(
    hosts: List<String>,
    /** Defaulted so the shipped budget lives in one place; tests pass their own. */
    budgetMs: Long = JMAP_DISCOVERY_BUDGET_MS,
    probe: suspend (String) -> HostProbe,
): MailRepository.DiscoveryResult {
    if (hosts.isEmpty()) return MailRepository.DiscoveryResult.NotFound
    // Atomic: the probes may land on different threads (fetchSession hops to Dispatchers.IO).
    val settled = AtomicReferenceArray<HostProbe>(hosts.size)
    val decided = CompletableDeferred<MailRepository.DiscoveryResult>()
    val probes = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + probes)
    try {
        scope.launch {
            settled.set(0, probe(hosts[0]))
            verdictOnceSettled(hosts, settled)?.let { decided.complete(it); return@launch }
            // Rank 0 has spoken and it is not a mail server: only now may the secret go further.
            for (rank in 1 until hosts.size) {
                launch {
                    settled.set(rank, probe(hosts[rank]))
                    verdictOnceSettled(hosts, settled)?.let { decided.complete(it) }
                }
            }
        }
        return withTimeoutOrNull(budgetMs) { decided.await() } ?: verdictAtBudget(hosts, settled)
    } finally {
        // A request to stop, not a wait for it: the losers die on OkHttp's own timeouts.
        probes.cancel()
    }
}

/**
 * Null while a pending rank could still outrank the best answer. Only RANK 0, the domain the reader
 */
private fun verdictOnceSettled(
    hosts: List<String>,
    settled: AtomicReferenceArray<HostProbe>,
): MailRepository.DiscoveryResult? {
    var typedDomainRejected = false
    for (rank in hosts.indices) {
        when (settled.get(rank)) {
            null -> return null
            HostProbe.Usable -> return MailRepository.DiscoveryResult.Found(hosts[rank])
            HostProbe.BadCredentials -> if (rank == 0) typedDomainRejected = true
            HostProbe.NoAnswer -> {}
        }
    }
    return if (typedDomainRejected) {
        MailRepository.DiscoveryResult.BadCredentials
    } else {
        MailRepository.DiscoveryResult.NotFound
    }
}

/**
 * A probe that finished outranks one still silent. Never
 * [MailRepository.DiscoveryResult.BadCredentials]: a rejection while a rank is silent proves nothing.
 */
private fun verdictAtBudget(
    hosts: List<String>,
    settled: AtomicReferenceArray<HostProbe>,
): MailRepository.DiscoveryResult {
    for (rank in hosts.indices) {
        if (settled.get(rank) == HostProbe.Usable) return MailRepository.DiscoveryResult.Found(hosts[rank])
    }
    return MailRepository.DiscoveryResult.NotFound
}

/**
 * Only ever asked about a host the app guessed ([oauthHostWasGuessed]). The stored host is half of
 */
fun oauthServerToStore(discovered: MailRepository.DiscoveryResult, documentHost: String): String =
    (discovered as? MailRepository.DiscoveryResult.Found)?.server?.takeIf { it.isNotBlank() }
        ?: documentHost

/**
 * The fence around [oauthServerToStore], load-bearing both ways: too wide and the resolution
 */
fun oauthHostWasGuessed(documentHost: String, guessed: List<String>): Boolean {
    val host = normalizeEndpoint(documentHost)
    if (host.isBlank()) return false
    return guessed.any { normalizeEndpoint(it) == host }
}
