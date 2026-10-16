package app.sterna.core.jmap

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The edge-gate credentials on the wire: Bearer header, Authelia cookie, scoping, redirect-to-login, no logging. */
class GatewayTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .addInterceptor(GatewayRedirectGuard())
        .addNetworkInterceptor(GatewayCookieInterceptor())
        .build()

    @Before fun up() { server = MockWebServer(); server.start(); GatewayCookies.set("127.0.0.1", null); GatewayFleetTokens.set("127.0.0.1", null) }
    @After fun down() { server.shutdown(); GatewayCookies.set("127.0.0.1", null); GatewayFleetTokens.set("127.0.0.1", null) }

    private fun get(host: String = "127.0.0.1") =
        http.newCall(Request.Builder().url("http://$host:${server.port}/.well-known/jmap").build()).execute().use { it.code }

    @Test fun `bearer auth is the Authorization header`() {
        assertEquals("Bearer tok", BearerAuth("tok").authorizationHeader())
    }

    @Test fun `bearer mode sends the fleet token in its own header and leaves Authorization to the mailbox`() {
        GatewayFleetTokens.set("127.0.0.1", "fleet-tok")
        server.enqueue(MockResponse().setBody("{}"))
        val basic = BasicAuth("me@x.test", "pw").authorizationHeader()
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/jmap/session").header("Authorization", basic).build()).execute().close()
        val r = server.takeRequest()
        assertEquals("Bearer fleet-tok", r.getHeader("X-Fleet-Authorization"))
        assertEquals(basic, r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
    }

    @Test fun `cookie mode sends the cookie and the mailbox login together, no fleet header`() {
        GatewayCookies.set("127.0.0.1", "sess-1")
        server.enqueue(MockResponse().setBody("{}"))
        val basic = BasicAuth("me@x.test", "pw").authorizationHeader()
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/jmap/session").header("Authorization", basic).build()).execute().close()
        val r = server.takeRequest()
        assertEquals("authelia_session=sess-1", r.getHeader("Cookie"))
        assertEquals(basic, r.getHeader("Authorization"))
        assertNull(r.getHeader("X-Fleet-Authorization"))
    }

    @Test fun `the fleet token never goes to another domain`() {
        GatewayFleetTokens.set("127.0.0.1", "fleet-tok")
        assertTrue(GatewayHeaders.compose("localhost", false, false).isEmpty())
        assertEquals(setOf("X-Fleet-Authorization"), GatewayHeaders.compose("127.0.0.1", false, false).keys)
        assertTrue(GatewayHeaders.compose("127.0.0.1", false, true).isEmpty())
    }

    @Test fun `the gateway cookie rides every request to the account's domain`() {
        GatewayCookies.set("127.0.0.1", "sess-1")
        server.enqueue(MockResponse().setBody("{}"))
        get()
        assertEquals("authelia_session=sess-1", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `the cookie never goes to another domain`() {
        GatewayCookies.set("127.0.0.1", "sess-1")
        server.enqueue(MockResponse().setBody("{}"))
        get("localhost")
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `no cookie set sends none`() {
        server.enqueue(MockResponse().setBody("{}"))
        get()
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `a redirect to the sign-in portal is a 401 and marks the host rejected`() {
        // The terminal interceptor answers as the portal page a followed redirect would have landed on.
        val portal = OkHttpClient.Builder().addInterceptor(GatewayRedirectGuard()).addInterceptor { chain ->
            val first = Request.Builder().url("https://auth.diegonmarcos.com/?rd=x").build()
            val prior = okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(302).message("Found").build()
            okhttp3.Response.Builder().request(first).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").priorResponse(prior).build()
        }.build()
        try {
            portal.newCall(Request.Builder().url("https://jmap.diegonmarcos.com/.well-known/jmap").build()).execute()
            throw AssertionError("expected a sign-in-required failure")
        } catch (e: JmapException) { assertEquals(401, e.httpCode) }
        assertTrue(GatewayCookies.isRejected("jmap.diegonmarcos.com"))
        GatewayCookies.set("jmap.diegonmarcos.com", "fresh")
        assertFalse("a new sign-in clears the rejection", GatewayCookies.isRejected("jmap.diegonmarcos.com"))
        GatewayCookies.set("jmap.diegonmarcos.com", null)
    }

    @Test fun `a gate 401 is the gate, a mailbox 401 is the mailbox`() {
        GatewayFleetTokens.set("127.0.0.1", "fleet-tok")
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("no"))
        try { get(); throw AssertionError("expected the gate refusal") } catch (e: JmapException) {
            assertEquals(GatewayErrors.Kind.GATE, GatewayErrors.kind(e))
            assertTrue(GatewayCookies.isRejected("127.0.0.1"))
        }
        GatewayCookies.set("127.0.0.1", null)
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/problem+json")
            .setHeader("WWW-Authenticate", "Basic realm=\"Stalwart Server\"").setBody("{}"))
        assertEquals(401, get())
        assertFalse("the mailbox login failing must not flag the gate credential", GatewayCookies.isRejected("127.0.0.1"))
        assertEquals(RejectionLayer.MAILBOX, RejectionLayer.of(listOf("Basic realm=\"Stalwart Server\""), null))
        assertEquals(RejectionLayer.GATE, RejectionLayer.of(emptyList(), "text/html"))
        assertTrue(GatewayErrors.describe(JmapException("x", httpCode = 401)).contains("mailbox"))
        assertTrue(GatewayErrors.describe(JmapException("x", httpCode = 401, gate = true)).contains("Gate"))
    }

    @Test fun `failures are told apart`() {
        assertEquals(GatewayErrors.Kind.UNAUTHORIZED, GatewayErrors.kind(JmapException("x", httpCode = 401)))
        assertEquals(GatewayErrors.Kind.FORBIDDEN, GatewayErrors.kind(JmapException("x", httpCode = 403)))
        assertEquals(GatewayErrors.Kind.TLS, GatewayErrors.kind(SSLHandshakeException("x")))
        assertEquals(GatewayErrors.Kind.DNS, GatewayErrors.kind(java.io.IOException("x", UnknownHostException())))
        assertEquals(GatewayErrors.Kind.TIMEOUT, GatewayErrors.kind(SocketTimeoutException()))
        assertEquals(GatewayErrors.Kind.OTHER, GatewayErrors.kind(IllegalStateException("boom")))
        assertFalse(GatewayErrors.describe(JmapException("x", httpCode = 401)).contains("sess"))
    }

    @Test fun `the session value comes out of a cookie string`() {
        assertEquals("v1", GatewayCookies.sessionValue("a=b; authelia_session=v1; c=d"))
        assertEquals("", GatewayCookies.sessionValue("a=b"))
    }

    @Test fun `the gateway code logs nothing`() {
        val src = File("src/main/kotlin/app/sterna/core/jmap/Gateway.kt").readText()
        assertFalse(Regex("""(Log\.|println\(|Timber|HttpLoggingInterceptor|printStackTrace)""").containsMatchIn(src))
    }
}
