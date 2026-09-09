package app.sterna.core.data.unsubscribe

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the one-click unsubscribe puts on the wire, and — more importantly — what it refuses to do.
 */
class UnsubscribeClientTest {
    private lateinit var server: MockWebServer
    private val client = UnsubscribeClient()

    /**
     * Connectivity as the app layer reports it, and a count of when the client asked for it.
     */
    private var online = true
    private var connectivityReads = 0
    private val isOnline: () -> Boolean = {
        connectivityReads++
        online
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `a 2xx answer is a successful unsubscribe`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(UnsubscribeResult.Sent, client.post(server.url("/u/abc").toString(), isOnline))
    }

    @Test fun `the request is a form post carrying RFC 8058's body and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        client.post(server.url("/u/abc").toString(), isOnline)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/u/abc", request.path)
        assertEquals("List-Unsubscribe=One-Click", request.body.readUtf8())
        assertTrue(
            "Content-Type was ${request.getHeader("Content-Type")}",
            request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true,
        )
    }

    /**
     * The URL is used VERBATIM — path and query string, byte for byte (decision D6).
     */
    @Test fun `the whole url is sent, query string included`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client.post(server.url("/u/abc?token=xyz&list=42").toString(), isOnline)

        val request = server.takeRequest()
        assertEquals("/u/abc?token=xyz&list=42", request.path)
        assertEquals("List-Unsubscribe=One-Click", request.body.readUtf8())
    }

    /**
     * The invariant this whole class exists for. The POST goes to a domain named by the sender
     */
    @Test fun `no Authorization header is ever sent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client.post(server.url("/u/abc").toString(), isOnline)

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    /**
     * A 3xx is a FAILURE, and the decisive assertion is the second one: OkHttp follows a
     */
    @Test fun `a redirect is refused and no second request leaves`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/somewhere-else"))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.post(server.url("/u/abc").toString(), isOnline)

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), result)
        assertEquals("the redirect must not be followed", 1, server.requestCount)
    }

    @Test fun `every 3xx is refused, including the permanent ones`() = runBlocking {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/elsewhere"))
            assertEquals(
                "HTTP $code",
                UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT),
                client.post(server.url("/u/$code").toString(), isOnline),
            )
        }
        assertEquals("one request per code, no follow-ups", 5, server.requestCount)
    }

    @Test fun `a server refusal is reported as a refusal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            client.post(server.url("/u/abc").toString(), isOnline),
        )
    }

    // ---- the https-only gate (decision D4), through the public entry point ----

    @Test fun `an http url is refused before any request leaves`() = runBlocking {
        // The mock server speaks plain http, so its own URL is the cleartext case.
        val url = server.url("/u/abc").toString()
        assertTrue("the mock server must be plain http here", url.startsWith("http://"))

        val result = client.oneClick(url, isOnline)

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), result)
        assertEquals("nothing may be sent over cleartext", 0, server.requestCount)
    }

    @Test fun `only https may be posted to`() {
        assertTrue(isPostableUnsubscribeUrl("https://list.example.com/u"))
        assertTrue(isPostableUnsubscribeUrl("HTTPS://list.example.com/u"))
        listOf(
            "http://list.example.com/u",
            "ftp://list.example.com/u",
            "mailto:leave@list.example.com",
            "list.example.com/u",
            "",
        ).forEach { url ->
            assertTrue("\"$url\" must not be POSTable", !isPostableUnsubscribeUrl(url))
        }
    }

    /**
     * The witness that [UnsubscribeClient.oneClick] does not short-circuit its https branch: an
     */
    @Test fun `an https url that cannot be reached is reported as no network`() = runBlocking {
        online = false // the case where "no network" is the true sentence; see the table below
        // Port 1 is privileged and unbound: the connection is refused immediately.
        val result = client.oneClick("https://127.0.0.1:1/u/abc", isOnline)

        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE), result)
    }

    // ---- the two pure mappings, each pinned on its own ----

    @Test fun `status codes map to what the reader is told`() {
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(200))
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(202))
        assertEquals(UnsubscribeResult.Sent, oneClickOutcome(299))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), oneClickOutcome(301))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT), oneClickOutcome(399))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(400))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(500))
        assertEquals(UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED), oneClickOutcome(199))
    }

    /**
     * The transport dying on a device that is really offline is "no network" — word for word the
     * behaviour that shipped; anything else keeps the neutral refusal.
     */
    @Test fun `transport failures are told apart from server refusals`() {
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.UnknownHostException("list.example.com"), online = false),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.SocketTimeoutException("timeout"), online = false),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.net.ConnectException("refused"), online = false),
        )
        // Wrapped, as OkHttp hands them over.
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            oneClickFailure(java.io.IOException("wrapped", java.net.SocketException("down")), online = false),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            oneClickFailure(javax.net.ssl.SSLException("bad certificate"), online = false),
        )
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            oneClickFailure(IllegalStateException("boom"), online = false),
        )
    }

    // ---- who is blamed when the transport dies: the reader's link, or the sender's host ----

    /**
     * THE DECISION TABLE, executed rather than described (F2, found on the bench on 02/08: a POST
     */
    @Test fun `a dead transport blames the sender's host, unless the device really is offline`() {
        val transport = listOf(
            java.net.UnknownHostException("lists.example.com"),
            java.net.SocketException("Network is unreachable"),
            java.net.ConnectException("Connection refused"), // a SocketException
            java.net.SocketTimeoutException("timeout"), // an InterruptedIOException
            java.io.IOException("wrapped", java.net.UnknownHostException("lists.example.com")),
        )
        transport.forEach { t ->
            assertEquals(
                "offline device, ${t.javaClass.simpleName}: the link is the honest culprit",
                UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
                oneClickFailure(t, online = false),
            )
            assertEquals(
                "online device, ${t.javaClass.simpleName}: the sender's host is the culprit, and " +
                    "telling this reader \"no network\" sends them to toggle a working Wi-Fi",
                UnsubscribeResult.Failed(UnsubscribeFailure.UNREACHABLE),
                oneClickFailure(t, online = true),
            )
        }

        // Third row: what did not die on the transport is nobody's connectivity, either way.
        listOf(
            javax.net.ssl.SSLException("bad certificate"),
            IllegalStateException("boom"),
        ).forEach { t ->
            listOf(true, false).forEach { state ->
                assertEquals(
                    "${t.javaClass.simpleName} with online=$state",
                    UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
                    oneClickFailure(t, online = state),
                )
            }
        }
    }

    /**
     * The wiring, as far as a JVM test can follow it: connectivity is asked **at the moment of the
     */
    @Test fun `connectivity is answered at each failure, never captured once`() = runBlocking {
        // Port 1 is privileged and unbound: the connection dies on the transport immediately.
        val dead = "https://127.0.0.1:1/u/abc"

        online = true
        val whileOnline = client.oneClick(dead, isOnline)
        online = false
        val whileOffline = client.oneClick(dead, isOnline)

        assertEquals(
            "the device was online on this attempt",
            UnsubscribeResult.Failed(UnsubscribeFailure.UNREACHABLE),
            whileOnline,
        )
        assertEquals(
            "the device had gone offline by this attempt, on the same client",
            UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE),
            whileOffline,
        )
        assertEquals("one connectivity question per failed attempt", 2, connectivityReads)
    }

    /**
     * And it is asked ONLY on failure: a response that came back is proof the link works, so
     */
    @Test fun `connectivity is not consulted when the server answers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(UnsubscribeResult.Sent, client.post(server.url("/u/ok").toString(), isOnline))
        assertEquals(
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED),
            client.post(server.url("/u/no").toString(), isOnline),
        )

        assertEquals("nothing that answered says anything about the link", 0, connectivityReads)
    }
}
