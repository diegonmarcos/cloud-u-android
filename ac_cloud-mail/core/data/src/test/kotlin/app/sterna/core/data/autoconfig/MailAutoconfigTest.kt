package app.sterna.core.data.autoconfig

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [discoverMailSettings]: which servers a typed email address is turned into, and — the part that
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MailAutoconfigTest {
    private lateinit var server: MockWebServer

    private val email = "jo@masto.top"

    /** A TLS probe that records what it was asked and answers from [alive] — no network. */
    private class ProbeBench(private val alive: (host: String, port: Int) -> Boolean) {
        val asked = mutableListOf<Pair<String, Int>>()
        suspend fun probe(host: String, port: Int): Boolean {
            asked += host to port
            return alive(host, port)
        }
    }

    private fun deadBench() = ProbeBench { _, _ -> false }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** A well-formed Thunderbird-autoconfig document, every knob overridable. */
    private fun publishedConfig(
        root: String = "clientConfig",
        incomingType: String = "imap",
        incomingHost: String = "mx.in.example",
        incomingPort: String = "993",
        incomingSocket: String = "SSL",
        username: String? = "%EMAILADDRESS%",
        withOutgoing: Boolean = true,
        outgoingHost: String = "mx.out.example",
        outgoingPort: String = "465",
        outgoingSocket: String = "SSL",
        extraServers: String = "",
    ): String {
        val usernameLine = username?.let { "<username>$it</username>" }.orEmpty()
        val outgoing = if (withOutgoing) {
            """<outgoingServer type="smtp">
                 <hostname>$outgoingHost</hostname>
                 <port>$outgoingPort</port>
                 <socketType>$outgoingSocket</socketType>
               </outgoingServer>"""
        } else {
            ""
        }
        return """<?xml version="1.0"?>
            <$root version="1.1">
              <emailProvider id="masto.top">
                $extraServers
                <incomingServer type="$incomingType">
                  <hostname>$incomingHost</hostname>
                  <port>$incomingPort</port>
                  <socketType>$incomingSocket</socketType>
                  $usernameLine
                </incomingServer>
                $outgoing
              </emailProvider>
            </$root>"""
    }

    private fun xml(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/xml").setBody(body)

    private fun url(path: String = "/mail/config-v1.1.xml") = server.url(path).toString()

    // ── The two published locations, and nothing else ────────────────────────────────────────

    /**
     * The privacy policy promises the app contacts only the user's own server: no Mozilla ISPDB,
     */
    @Test fun `the two published locations are asked in this order, and nothing else`() {
        assertEquals(
            listOf(
                "https://autoconfig.masto.top/mail/config-v1.1.xml",
                "https://masto.top/.well-known/autoconfig/mail/config-v1.1.xml",
            ),
            autoconfigUrlsFor("masto.top"),
        )
    }

    /**
     * The DEFAULT wiring itself, traversed: the cascade is called the way production calls it —
     */
    @Test fun `the default cascade fetches exactly the two locations on the user's domain`() = runBlocking {
        val asked = mutableListOf<String>()
        val offline = OkHttpClient.Builder()
            .addInterceptor { chain ->
                asked += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val result = discoverMailSettings(email, httpClient = offline, tlsProbe = deadBench()::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
        assertEquals(
            listOf(
                "https://autoconfig.masto.top/mail/config-v1.1.xml",
                "https://masto.top/.well-known/autoconfig/mail/config-v1.1.xml",
            ),
            asked,
        )
    }

    // ── The pure username decision ───────────────────────────────────────────────────────────

    @Test fun `username placeholders are substituted with the pinned address`() {
        assertEquals("jo@masto.top", substitutedUsername("%EMAILADDRESS%", "jo@masto.top"))
        assertEquals("jo", substitutedUsername("%EMAILLOCALPART%", "jo@masto.top"))
        // Absent (the element was missing or empty): the full address.
        assertEquals("jo@masto.top", substitutedUsername("", "jo@masto.top"))
        // A literal username is used verbatim.
        assertEquals("jo.custom", substitutedUsername("jo.custom", "jo@masto.top"))
        // Both placeholders in one value.
        assertEquals(
            "jo+jo@masto.top",
            substitutedUsername("%EMAILLOCALPART%+%EMAILADDRESS%", "jo@masto.top"),
        )
    }

    // ── Reading a published config ───────────────────────────────────────────────────────────

    @Test fun `a published config is read into the stored endpoint types`() = runBlocking {
        server.enqueue(
            xml(
                publishedConfig(
                    incomingHost = "mx.in.example", incomingPort = "993", incomingSocket = "SSL",
                    outgoingHost = "mx.out.example", outgoingPort = "587", outgoingSocket = "STARTTLS",
                ),
            ),
        )
        val bench = deadBench()

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = bench::probe)

        assertEquals(
            MailAutoconfigResult.Found(
                incoming = MailEndpoint("mx.in.example", 993, ConnectionSecurity.TLS),
                outgoing = MailEndpoint("mx.out.example", 587, ConnectionSecurity.STARTTLS),
                username = "jo@masto.top",
            ),
            result,
        )
    }

    /**
     * The order of the tiers is a decision: what the domain PUBLISHED wins over what we would
     */
    @Test fun `the published config wins over a guess that would succeed`() = runBlocking {
        server.enqueue(xml(publishedConfig()))
        val bench = ProbeBench { _, _ -> true }

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = bench::probe)

        assertEquals(
            MailAutoconfigResult.Found(
                incoming = MailEndpoint("mx.in.example", 993, ConnectionSecurity.TLS),
                outgoing = MailEndpoint("mx.out.example", 465, ConnectionSecurity.TLS),
                username = "jo@masto.top",
            ),
            result,
        )
        assertEquals("no guess may be probed once the domain published its config", emptyList<Pair<String, Int>>(), bench.asked)
    }

    @Test fun `the second published location is asked when the first has nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(xml(publishedConfig()))

        val result = discoverMailSettings(
            email,
            autoconfigUrls = listOf(url("/one"), url("/two")),
            tlsProbe = deadBench()::probe,
        )

        assertTrue(result is MailAutoconfigResult.Found)
        assertEquals(2, server.requestCount)
        assertEquals("/one", server.takeRequest().path)
        assertEquals("/two", server.takeRequest().path)
    }

    /**
     * The shipped redirect policy, hop by hop: a same-origin 3xx (a path on the very server this
     */
    @Test fun `a same-origin redirect is walked to the config`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real"))
        server.enqueue(xml(publishedConfig()))

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertTrue("the redirect must be walked to the config", result is MailAutoconfigResult.Found)
        assertEquals(2, server.requestCount)
    }

    @Test fun `the autoconfig client never follows a redirect on its own`() {
        val built = defaultAutoconfigHttpClient()
        assertFalse("every hop must pass through redirectAllowed, none through OkHttp", built.followRedirects)
        assertFalse("an https answer may never send the fetch down to http", built.followSslRedirects)
        assertEquals(15_000, built.connectTimeoutMillis)
        assertEquals(15_000, built.readTimeoutMillis)
    }

    /**
     * The pure decision one hop is judged by, executed with pinned arguments: https on the
     */
    @Test fun `redirectAllowed - https on the domain or under it, and nothing else`() {
        assertTrue(redirectAllowed("https://masto.top/mail/config-v1.1.xml".toHttpUrl(), "masto.top"))
        assertTrue(redirectAllowed("https://mail.masto.top/config".toHttpUrl(), "masto.top"))
        assertTrue(
            "the comparison is case-insensitive",
            redirectAllowed("https://mail.masto.top/config".toHttpUrl(), "MASTO.TOP"),
        )
        assertFalse(
            "a third party is never a place the app may go",
            redirectAllowed("https://autoconfig.thunderbird.net/v1.1/masto.top".toHttpUrl(), "masto.top"),
        )
        assertFalse(
            "the domain itself over cleartext is still a refusal",
            redirectAllowed("http://masto.top/mail/config-v1.1.xml".toHttpUrl(), "masto.top"),
        )
        assertFalse(
            "containing the domain is not being under it",
            redirectAllowed("https://evil-masto.top/config".toHttpUrl(), "masto.top"),
        )
        assertFalse(
            "a foreign registrable domain may end with ours and still be foreign",
            redirectAllowed("https://masto.top.evil.tld/config".toHttpUrl(), "masto.top"),
        )
    }

    /**
     * The live escape this guards: `autoconfig.<domain>` answers 302 towards a third party
     */
    @Test fun `a redirect towards a third party is refused without contacting it`() = runBlocking {
        val thirdParty = MockWebServer()
        thirdParty.start()
        try {
            thirdParty.enqueue(xml(publishedConfig()))
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", thirdParty.url("/mail/config-v1.1.xml").toString()),
            )

            val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

            assertEquals(MailAutoconfigResult.NotFound, result)
            assertEquals("the refused host must receive NO request", 0, thirdParty.requestCount)
            assertEquals(1, server.requestCount)
        } finally {
            thirdParty.shutdown()
        }
    }

    /** The walk is bounded: after [MAX_AUTOCONFIG_REDIRECTS] hops a redirect loop is a dead tier. */
    @Test fun `a redirect loop ends the tier after five hops`() = runBlocking {
        repeat(10) { hop ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop-$hop"))
        }

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
        assertEquals("the first request plus five walked hops", 6, server.requestCount)
    }

    // ── What a published answer must be REFUSED for ──────────────────────────────────────────

    /**
     * An HTTP 200 is not a verdict — the real case this pins: a catch-all reverse proxy
     */
    @Test fun `a 200 with an HTML page on both paths is not a config`() = runBlocking {
        server.enqueue(xml("<!DOCTYPE html><html><body><p>welcome to masto.top</body></html>"))
        server.enqueue(xml("<html><head><title>masto.top</title></head><body><p>hi</p></body></html>"))

        val result = discoverMailSettings(
            email,
            autoconfigUrls = listOf(url("/one"), url("/two")),
            tlsProbe = deadBench()::probe,
        )

        assertEquals(MailAutoconfigResult.NotFound, result)
        assertEquals("both locations must still have been tried", 2, server.requestCount)
    }

    /**
     * The root element IS the verdict: a document whose root is not `clientConfig` is no config,
     */
    @Test fun `an XML answer whose root is not clientConfig is refused, servers inside or not`() = runBlocking {
        server.enqueue(xml(publishedConfig(root = "config")))

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
    }

    @Test fun `a config that only offers pop3 is not an imap config`() = runBlocking {
        server.enqueue(xml(publishedConfig(incomingType = "pop3")))

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
    }

    @Test fun `pop3 and dav servers are skipped, the imap one is the one read`() = runBlocking {
        server.enqueue(
            xml(
                publishedConfig(
                    extraServers = """
                        <incomingServer type="pop3">
                          <hostname>pop.ignored.example</hostname>
                          <port>995</port>
                          <socketType>SSL</socketType>
                        </incomingServer>
                        <incomingServer type="caldav">
                          <hostname>dav.ignored.example</hostname>
                          <port>443</port>
                          <socketType>SSL</socketType>
                        </incomingServer>
                    """,
                ),
            ),
        )

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertEquals(
            MailEndpoint("mx.in.example", 993, ConnectionSecurity.TLS),
            (result as MailAutoconfigResult.Found).incoming,
        )
    }

    /**
     * `plain` — or any socketType we do not positively recognise — rejects the WHOLE tier.
     */
    @Test fun `a socketType of plain never falls back to cleartext`() = runBlocking {
        server.enqueue(xml(publishedConfig(incomingSocket = "plain")))
        assertEquals(
            MailAutoconfigResult.NotFound,
            discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe),
        )

        server.enqueue(xml(publishedConfig(outgoingSocket = "plain")))
        assertEquals(
            "the outgoing side must be held to the same bar",
            MailAutoconfigResult.NotFound,
            discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe),
        )
    }

    @Test fun `an invalid port rejects the tier`() = runBlocking {
        listOf("0", "65536", "not-a-port", "").forEach { port ->
            server.enqueue(xml(publishedConfig(incomingPort = port)))
            assertEquals(
                "port \"$port\"",
                MailAutoconfigResult.NotFound,
                discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe),
            )
        }
    }

    @Test fun `a config without an smtp server rejects the tier`() = runBlocking {
        server.enqueue(xml(publishedConfig(withOutgoing = false)))

        val result = discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
    }

    @Test fun `the username the domain publishes is substituted before it is returned`() = runBlocking {
        server.enqueue(xml(publishedConfig(username = "%EMAILLOCALPART%")))
        assertEquals(
            "jo",
            (
                discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)
                    as MailAutoconfigResult.Found
                ).username,
        )

        server.enqueue(xml(publishedConfig(username = null)))
        assertEquals(
            "no username element: the full address",
            "jo@masto.top",
            (
                discoverMailSettings(email, autoconfigUrls = listOf(url()), tlsProbe = deadBench()::probe)
                    as MailAutoconfigResult.Found
                ).username,
        )
    }

    // ── The guesses, gated by a real handshake ───────────────────────────────────────────────

    /**
     * The hosts and ports a guess may EVER touch, pinned by argument: `imap.<d>:993` then
     */
    @Test fun `guesses are the pinned hosts and ports, first alive one wins`() = runBlocking {
        val bench = ProbeBench { _, _ -> true }

        val result = discoverMailSettings(email, autoconfigUrls = emptyList(), tlsProbe = bench::probe)

        assertEquals(
            MailAutoconfigResult.Found(
                incoming = MailEndpoint("imap.masto.top", 993, ConnectionSecurity.TLS),
                outgoing = MailEndpoint("smtp.masto.top", 465, ConnectionSecurity.TLS),
                username = "jo@masto.top",
            ),
            result,
        )
        assertEquals(listOf("imap.masto.top" to 993, "smtp.masto.top" to 465), bench.asked)
    }

    @Test fun `a guessed host that fails the handshake is not kept`() = runBlocking {
        val bench = ProbeBench { host, _ -> host == "mail.masto.top" }

        val result = discoverMailSettings(email, autoconfigUrls = emptyList(), tlsProbe = bench::probe)

        assertEquals(
            MailAutoconfigResult.Found(
                incoming = MailEndpoint("mail.masto.top", 993, ConnectionSecurity.TLS),
                outgoing = MailEndpoint("mail.masto.top", 465, ConnectionSecurity.TLS),
                username = "jo@masto.top",
            ),
            result,
        )
        assertEquals(
            listOf(
                "imap.masto.top" to 993, "mail.masto.top" to 993,
                "smtp.masto.top" to 465, "mail.masto.top" to 465,
            ),
            bench.asked,
        )
    }

    @Test fun `a guess needs both sides - an incoming server alone is NotFound`() = runBlocking {
        val bench = ProbeBench { _, port -> port == 993 }

        val result = discoverMailSettings(email, autoconfigUrls = emptyList(), tlsProbe = bench::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
    }

    /**
     * No handshake, no guess. A guess is only a NAME; what makes it safe to show — and to send
     */
    @Test fun `when no handshake succeeds nothing is guessed`() = runBlocking {
        val bench = deadBench()

        val result = discoverMailSettings(email, autoconfigUrls = emptyList(), tlsProbe = bench::probe)

        assertEquals(MailAutoconfigResult.NotFound, result)
        assertEquals(
            "with no incoming host verified the answer is already NotFound — no outgoing " +
                "connection leaves for nothing",
            listOf("imap.masto.top" to 993, "mail.masto.top" to 993),
            bench.asked,
        )
    }

    // ── The budget, and cancellation ─────────────────────────────────────────────────────────

    @Test fun `the whole cascade is bounded by one budget`() = runBlocking {
        // A server that answers, but far too late — like a firewalled port that swallows SYNs.
        server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("late"))

        val startedAt = System.nanoTime()
        val result = discoverMailSettings(
            email,
            budgetMs = 250L,
            autoconfigUrls = listOf(url()),
            tlsProbe = deadBench()::probe,
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(MailAutoconfigResult.NotFound, result)
        assertTrue(
            "the caller waited ${elapsedMs} ms — the budget bounds the wait, not just the verdict",
            elapsedMs < 4_000,
        )
    }

    /**
     * The budget production runs on lives in exactly one place, where this pins it; the parameter
     * default keeps it out of every call site (same motif as JMAP_DISCOVERY_BUDGET_MS).
     */
    @Test fun `the budget the app ships with is ten seconds`() {
        assertEquals(10_000L, MAIL_AUTOCONFIG_BUDGET_MS)
    }

    /**
     * Leaving the sign-in screen cancels discovery, and that cancellation must RETRAVERSE — a
     */
    @Test fun `cancelling the caller retraverses, never NotFound`() = runTest {
        var result: MailAutoconfigResult? = null
        val job = launch {
            result = discoverMailSettings(
                email,
                budgetMs = 60_000L,
                autoconfigUrls = emptyList(),
                tlsProbe = { _, _ ->
                    delay(600_000L) // never answers within any budget; cancellable
                    false
                },
            )
        }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull("a swallowed cancellation writes a verdict where none is due", result)
    }
}
