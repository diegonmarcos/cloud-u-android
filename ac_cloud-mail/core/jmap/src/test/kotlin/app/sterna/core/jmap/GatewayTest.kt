package app.sterna.core.jmap

import okhttp3.Dns
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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The edge-gate credentials on the wire: Bearer header, Authelia cookie, scoping, redirect-to-login, no logging. */
class GatewayTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
        .addInterceptor(GatewayRedirectGuard())
        .addNetworkInterceptor(GatewayCookieInterceptor())
        .build()

    @Before fun up() { server = MockWebServer(); server.start(); GatewayCookies.set("jmap.diegonmarcos.com", null) }
    @After fun down() { server.shutdown(); GatewayCookies.set("jmap.diegonmarcos.com", null) }

    private fun get(host: String = "jmap.diegonmarcos.com") =
        http.newCall(Request.Builder().url("http://$host:${server.port}/.well-known/jmap").build()).execute().use { it.code }

    @Test fun `bearer auth is the Authorization header`() {
        assertEquals("Bearer tok", BearerAuth("tok").authorizationHeader())
    }

    @Test fun `the gateway cookie rides every request to the account's domain`() {
        GatewayCookies.set("jmap.diegonmarcos.com", "sess-1")
        server.enqueue(MockResponse().setBody("{}"))
        get("api.diegonmarcos.com")
        assertEquals("authelia_session=sess-1", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `the cookie never goes to another domain`() {
        GatewayCookies.set("jmap.diegonmarcos.com", "sess-1")
        server.enqueue(MockResponse().setBody("{}"))
        get("evil.example.com")
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `no cookie set sends none`() {
        server.enqueue(MockResponse().setBody("{}"))
        get()
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `a redirect to the sign-in portal is a 401 and marks the host rejected`() {
        GatewayCookies.set("jmap.diegonmarcos.com", "dead")
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://auth.diegonmarcos.com:${server.port}/?rd=x"))
        server.enqueue(MockResponse().setBody("login"))
        try { get(); throw AssertionError("expected a sign-in-required failure") } catch (e: JmapException) { assertEquals(401, e.httpCode) }
        assertTrue(GatewayCookies.isRejected("jmap.diegonmarcos.com"))
        GatewayCookies.set("jmap.diegonmarcos.com", "fresh")
        assertFalse("a new sign-in clears the rejection", GatewayCookies.isRejected("jmap.diegonmarcos.com"))
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
