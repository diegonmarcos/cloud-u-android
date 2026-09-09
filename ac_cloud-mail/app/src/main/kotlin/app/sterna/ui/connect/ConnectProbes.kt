package app.sterna.ui.connect

import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

// How the address step asks: the OAuth search's budget, and its two probes running at once.

/** How long the OAuth search may take, all candidates together. Not a per-host timeout, and not
 *  `OAuthClient`'s business: its 20 s connect + 30 s read also serve the token polls, where waiting
 *  is right. Four candidates asked one after another cost up to 200 s. */
const val OAUTH_DISCOVERY_BUDGET_MS = 10_000L

/** Which hosts the default path may ask how the mail signs in: the mail-named hosts of the address'
 *  domain, never the bare domain. The bare domain also serves the organisation's website, whose
 *  not recognised here, and the manual form's button keeps all four. */
fun mailOAuthCandidates(email: String): List<String> {
    val hosts = Jmap.autodiscoverHosts(email)
    val bareDomain = hosts.firstOrNull() ?: return emptyList()
    return hosts.filter { it.endsWith(".$bareDomain") }
}

/** Search [candidates] for an OAuth server with [discover], under one global [budgetMs], and answer
 *  with [chooseOAuthHost]'s pick of what answered in time. The search runs in a child job of the
 *  caller but is never joined, because a real probe sits in a blocking OkHttp `execute()` that
 *  ignores cancellation until its socket gives up. */
suspend fun discoverOAuthAmong(
    candidates: List<String>,
    /** Defaulted on purpose, and production passes nothing, so the shipped budget lives in one
     *  place — [OAUTH_DISCOVERY_BUDGET_MS]. */
    budgetMs: Long = OAUTH_DISCOVERY_BUDGET_MS,
    discover: suspend (String) -> OAuthMetadata?,
): Pair<String, OAuthMetadata>? {
    if (candidates.isEmpty()) return null
    // Written as each host answers, read when the budget is up: the search's own return value is
    // out of reach precisely in the case this exists for.
    val answered = CopyOnWriteArrayList<Pair<String, OAuthMetadata>>()
    val searched = CompletableDeferred<Unit>()
    val probes = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + probes)
    try {
        scope.launch {
            collectOAuthHosts(candidates) { host -> discover(host)?.also { answered += host to it } }
            searched.complete(Unit)
        }
        withTimeoutOrNull(budgetMs) { searched.await() }
        return chooseOAuthHost(answered.toList())
    } finally {
        // A request to stop, not a wait for it: a deaf probe dies on OkHttp's own timeouts.
        probes.cancel()
    }
}

/** What one pass over an address learned. [oauth] is not nullable: nullable, a settings verdict
 *  could be recorded with no OAuth answer beside it, which draws neither a password field nor a
 *  hand-over for an address refused for ever after. "Found no OAuth" is [OAuthDiscovery.chosen]. */
internal data class AddressProbe(
    val settings: MailAutoconfigResult,
    val oauth: OAuthDiscovery,
)

/** Ask [settings] and [oauth] concurrently, and answer once both have. Each side carries its own
 *  10 s budget, so chaining them costs the address step 20 s. And it waits for both: a credentials
 *  step drawn before the OAuth answer landed is a password field for a server that wanted none. */
internal suspend fun probeAddressTogether(
    settings: suspend () -> MailAutoconfigResult,
    oauth: suspend () -> OAuthDiscovery,
): AddressProbe = coroutineScope {
    // Both started before either is awaited: `async { … }.await()` twice in a row, or a plain
    // `settings()` then `oauth()`, is one keystroke away and costs both budgets.
    val settingsAnswer = async { settings() }
    val oauthAnswer = async { oauth() }
    AddressProbe(settingsAnswer.await(), oauthAnswer.await())
}
