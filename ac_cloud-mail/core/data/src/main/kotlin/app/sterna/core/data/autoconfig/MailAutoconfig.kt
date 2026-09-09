package app.sterna.core.data.autoconfig

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.childElements
import app.sterna.core.data.account.childText
import app.sterna.core.data.account.safeXmlDocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/**
 * What discovering IMAP/SMTP settings for an address answered. [Found] carries the STORED types
 * ([MailEndpoint], [ConnectionSecurity]) so the screen can prefill them directly.
 */
sealed interface MailAutoconfigResult {
    data class Found(
        val incoming: MailEndpoint,
        val outgoing: MailEndpoint,
        val username: String,
    ) : MailAutoconfigResult

    data object NotFound : MailAutoconfigResult
}

/**
 * How long the whole cascade may take — the sign-in screen's wait. Same motif as
 * [app.sterna.core.data.mail.JMAP_DISCOVERY_BUDGET_MS]: production passes nothing, a test pins it.
 */
const val MAIL_AUTOCONFIG_BUDGET_MS = 10_000L

/**
 * The two — and ONLY two — URLs a domain may publish its config at (Thunderbird autoconfig
 * format). Both on the user's own domain: no Mozilla ISPDB, no DNS SRV walk, ever.
 */
fun autoconfigUrlsFor(domain: String): List<String> = listOf(
    "https://autoconfig.$domain/mail/config-v1.1.xml",
    "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
)

/**
 * The username a published config means: `%EMAILADDRESS%` full address, `%EMAILLOCALPART%` the
 * part before `@`; empty/absent means the full address.
 */
fun substitutedUsername(raw: String, emailAddress: String): String {
    if (raw.isBlank()) return emailAddress
    return raw
        .replace("%EMAILADDRESS%", emailAddress)
        .replace("%EMAILLOCALPART%", emailAddress.substringBefore('@'))
}

/**
 * The HTTP client the published-config tiers fetch with — NOT the JMAP client (attaches an
 */
internal fun defaultAutoconfigHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** How many 3xx hops one published-config URL may be walked through before its tier gives up. */
internal const val MAX_AUTOCONFIG_REDIRECTS = 5

/**
 * Whether a redirect to [target] is allowed: only https, and only [domain] itself or a
 */
internal fun redirectAllowed(target: HttpUrl, domain: String): Boolean {
    if (target.scheme != "https") return false
    val host = target.host.lowercase()
    val own = domain.lowercase()
    return host == own || host.endsWith(".$own")
}

/** Socket timeout for one guessed-host handshake: a guess is cheap, waiting for one is not. */
private const val TLS_PROBE_TIMEOUT_MS = 5_000

/**
 * TLS probe behind a guessed host: connect, handshake, verify the certificate FOR THAT NAME.
 */
internal suspend fun realTlsProbe(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = TLS_PROBE_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TLS_PROBE_TIMEOUT_MS)
            val params = socket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()
            true
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        false
    }
}

/**
 * The cascade as the UI calls it: address only, shipped budget, production wiring — a separate
 * entry point so callers need not put [OkHttpClient] on their classpath.
 */
suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =
    discoverMailSettings(emailAddress)

/**
 * Discover IMAP/SMTP settings for [emailAddress], first found wins: the domain's PUBLISHED config
 */
suspend fun discoverMailSettings(
    emailAddress: String,
    budgetMs: Long = MAIL_AUTOCONFIG_BUDGET_MS,
    autoconfigUrls: List<String> = autoconfigUrlsFor(emailAddress.substringAfterLast('@')),
    httpClient: OkHttpClient = defaultAutoconfigHttpClient(),
    tlsProbe: suspend (host: String, port: Int) -> Boolean = ::realTlsProbe,
): MailAutoconfigResult {
    val domain = emailAddress.substringAfterLast('@')
    val decided = CompletableDeferred<MailAutoconfigResult>()
    val work = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + work)
    try {
        scope.launch {
            val verdict = try {
                runCascade(emailAddress, domain, autoconfigUrls, httpClient, tlsProbe)
            } catch (t: Throwable) {
                // A crashed cascade is "nothing found", but a CANCELLED one is not: cancellation
                // is the caller leaving, and classifying it as a verdict keeps nobody informed.
                if (t is CancellationException) throw t
                MailAutoconfigResult.NotFound
            }
            decided.complete(verdict)
        }
        return withTimeoutOrNull(budgetMs) { decided.await() } ?: MailAutoconfigResult.NotFound
    } finally {
        // A request to stop, not a wait for it: a blocked fetch dies on its own socket timeout.
        work.cancel()
    }
}

/** The tiers, in the order that IS the decision: published config first, guesses last. */
private suspend fun runCascade(
    emailAddress: String,
    domain: String,
    autoconfigUrls: List<String>,
    httpClient: OkHttpClient,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): MailAutoconfigResult {
    for (url in autoconfigUrls) {
        fetchPublishedConfig(httpClient, url, domain, emailAddress)?.let { return it }
    }
    return guessedSettings(domain, emailAddress, tlsProbe)
}

/**
 * Fetch ONE published-config URL, or null for "this tier said nothing". HTTP 200 is NOT a
 */
private suspend fun fetchPublishedConfig(
    httpClient: OkHttpClient,
    url: String,
    domain: String,
    emailAddress: String,
): MailAutoconfigResult.Found? = withContext(Dispatchers.IO) {
    try {
        var current = url.toHttpUrl()
        repeat(MAX_AUTOCONFIG_REDIRECTS + 1) {
            val next = httpClient.newCall(Request.Builder().url(current).build()).execute()
                .use { response ->
                    if (!response.isRedirect) {
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        return@withContext parsePublishedConfig(body.byteStream(), emailAddress)
                    }
                    val target = response.header("Location")?.let { current.resolve(it) }
                        ?: return@withContext null
                    val sameOrigin = target.scheme == current.scheme &&
                        target.host == current.host && target.port == current.port
                    if (!sameOrigin && !redirectAllowed(target, domain)) return@withContext null
                    target
                }
            current = next
        }
        null // Still a redirect after MAX_AUTOCONFIG_REDIRECTS hops: the tier gives up.
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }
}

/**
 * Read a Thunderbird-autoconfig document (hardened like the K-9 import), or null to reject the
 */
internal fun parsePublishedConfig(body: InputStream, emailAddress: String): MailAutoconfigResult.Found? {
    val document = try {
        safeXmlDocumentBuilderFactory().newDocumentBuilder().parse(body)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        return null // Not XML at all — an HTML page, a truncated body, a doctype.
    }
    val root = document.documentElement ?: return null
    if (root.tagName != "clientConfig") return null
    val provider = root.childElements().firstOrNull { it.tagName == "emailProvider" } ?: return null
    val incomingEl = provider.childElements().firstOrNull {
        it.tagName == "incomingServer" && it.getAttribute("type").equals("imap", ignoreCase = true)
    } ?: return null
    val outgoingEl = provider.childElements().firstOrNull {
        it.tagName == "outgoingServer" && it.getAttribute("type").equals("smtp", ignoreCase = true)
    } ?: return null
    val incoming = endpointOf(incomingEl) ?: return null
    val outgoing = endpointOf(outgoingEl) ?: return null
    return MailAutoconfigResult.Found(
        incoming = incoming,
        outgoing = outgoing,
        username = substitutedUsername(incomingEl.childText("username"), emailAddress),
    )
}

/**
 * One server element as a stored endpoint, or null to reject it and the tier. `socketType` is
 */
private fun endpointOf(server: Element): MailEndpoint? {
    val host = server.childText("hostname")
    if (host.isBlank()) return null
    val port = server.childText("port").toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val security = when (server.childText("socketType").uppercase()) {
        "SSL" -> ConnectionSecurity.TLS
        "STARTTLS" -> ConnectionSecurity.STARTTLS
        else -> return null
    }
    return MailEndpoint(host, port, security)
}

/**
 * The guessed tier: `imap.`/`mail.` at 993, `smtp.`/`mail.` at 465 — implicit TLS only, so a kept
 */
private suspend fun guessedSettings(
    domain: String,
    emailAddress: String,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): MailAutoconfigResult {
    val incomingHost = firstVerifiedHost(listOf("imap.$domain", "mail.$domain"), 993, tlsProbe)
        ?: return MailAutoconfigResult.NotFound
    val outgoingHost = firstVerifiedHost(listOf("smtp.$domain", "mail.$domain"), 465, tlsProbe)
        ?: return MailAutoconfigResult.NotFound
    return MailAutoconfigResult.Found(
        incoming = MailEndpoint(incomingHost, 993, ConnectionSecurity.TLS),
        outgoing = MailEndpoint(outgoingHost, 465, ConnectionSecurity.TLS),
        username = emailAddress,
    )
}

/** The first of [hosts] that passes the handshake on [port], probed one at a time, in order. */
private suspend fun firstVerifiedHost(
    hosts: List<String>,
    port: Int,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): String? = hosts.firstOrNull { tlsProbe(it, port) }
