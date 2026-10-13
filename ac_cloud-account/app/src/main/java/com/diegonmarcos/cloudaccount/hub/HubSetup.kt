package com.diegonmarcos.cloudaccount.hub

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Setup lives inside each app profile and each provider now, and Home has "Setup all". One shape for both:
 * a target runs three steps (login, endpoint, test) and answers one line per step. Lines carry names and
 * states only, never a value: a failure says which step and why in words, never the exception's message.
 */
enum class StepKind(val label: String) { LOGIN("Login"), ENDPOINT("Endpoint"), TEST("Test") }

data class StepResult(val kind: StepKind, val ok: Boolean, val line: String)

data class SetupResult(val id: String, val label: String, val steps: List<StepResult>) {
    val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }
    val line: String get() = steps.firstOrNull { !it.ok }?.let { "${it.kind.label}: ${it.line}" } ?: steps.lastOrNull()?.line.orEmpty()
}

interface SetupTarget {
    val id: String
    val label: String
    /** BLOCKS (network, provider calls): run it off the main thread. */
    fun run(): SetupResult
}

object SetupAll {
    /**
     * Home ▸ Setup all: every target in order, one at a time; a failing or throwing target is recorded and the
     * next one still runs. [each] is called as each finishes, so the page fills row by row.
     */
    fun run(targets: List<SetupTarget>, each: (SetupResult) -> Unit = {}): List<SetupResult> = targets.map { t ->
        runCatching { t.run() }
            .getOrElse { e -> SetupResult(t.id, t.label, listOf(StepResult(StepKind.TEST, false, "stopped (${e.javaClass.simpleName})"))) }
            .also(each)
    }
}

/** A read-only "who am I" call: the HTTP status, or -1 when the call did not complete. */
fun interface Probe {
    fun status(url: String, authorization: String): Int
}

/** The phone's probe: one GET, the credential only in the Authorization header, nothing logged. */
object HttpProbe : Probe {
    override fun status(url: String, authorization: String): Int = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", authorization)
            c.setRequestProperty("Accept", "application/json")
            c.responseCode
        } finally { c.disconnect() }
    }.getOrDefault(-1)
}

/**
 * A provider's Setup: login = the account holds a credential for it; endpoint = where it is checked; test = the
 * credential is not expired and, when the provider has a probe URL, the provider accepts it.
 */
class ProviderSetup(
    private val provider: Provider,
    private val connections: () -> JSONObject,
    private val probe: Probe,
    private val now: () -> Instant = { Instant.now() },
) : SetupTarget {
    override val id: String get() = "provider:${provider.id}"
    override val label: String get() = provider.label

    override fun run(): SetupResult {
        val blob = connections()
        val mine = Providers.secrets(blob, now())[provider].orEmpty()
        val live = mine.filter { !it.expired }
        val steps = ArrayList<StepResult>()
        steps += when {
            live.isNotEmpty() -> StepResult(StepKind.LOGIN, true, "${live.size} credential(s): " + live.joinToString { it.method.label }.take(80))
            mine.isNotEmpty() -> StepResult(StepKind.LOGIN, false, "every credential has expired: re-auth")
            else -> StepResult(StepKind.LOGIN, false, "missing: connect or import a token")
        }
        steps += StepResult(StepKind.ENDPOINT, true, provider.probeUrl?.let { URL(it).host } ?: "no online check for this provider")
        if (live.isEmpty()) { steps += StepResult(StepKind.TEST, false, "skipped: nothing to test"); return SetupResult(id, label, steps) }
        val url = provider.probeUrl
        if (url == null) { steps += StepResult(StepKind.TEST, true, "present and not expired"); return SetupResult(id, label, steps) }
        val pick = live.firstOrNull { it.method == AuthMethod.API_TOKEN } ?: live.firstOrNull { it.method == AuthMethod.WEB_OAUTH }
        if (pick == null) { steps += StepResult(StepKind.TEST, true, "SSH key present (checked on first use)"); return SetupResult(id, label, steps) }
        val value = Providers.leaves(blob)[pick.path]?.toString().orEmpty()
        val code = probe.status(url, "${provider.scheme} $value")
        steps += when {
            code in 200..299 -> StepResult(StepKind.TEST, true, "accepted (${pick.name})")
            code == 401 || code == 403 -> StepResult(StepKind.TEST, false, "refused ($code): rotate or re-auth ${pick.name}")
            code < 0 -> StepResult(StepKind.TEST, false, "no answer (offline?)")
            else -> StepResult(StepKind.TEST, false, "HTTP $code")
        }
        return SetupResult(id, label, steps)
    }
}

/**
 * An app profile's Setup: login = the app is installed (it is signed in through the values Cloud Account pushes);
 * endpoint = the endpoint keys the push carries for it; test = push through the app's setup contract and read
 * back ([push], FleetSetup.runApp on the phone).
 */
class AppSetup(
    override val id: String,
    override val label: String,
    private val installed: () -> Boolean,
    private val endpoints: () -> List<String>,
    private val push: () -> Pair<Boolean, String>,
) : SetupTarget {
    override fun run(): SetupResult {
        if (!installed()) return SetupResult(id, label, listOf(StepResult(StepKind.LOGIN, false, "not installed: install it from Configs ▸ Apps")))
        val steps = arrayListOf(StepResult(StepKind.LOGIN, true, "installed"))
        val e = endpoints()
        steps += StepResult(StepKind.ENDPOINT, true, if (e.isEmpty()) "no endpoint for this app" else e.joinToString().take(80))
        val (ok, line) = push()
        steps += StepResult(StepKind.TEST, ok, line)
        return SetupResult(id, label, steps)
    }
}
