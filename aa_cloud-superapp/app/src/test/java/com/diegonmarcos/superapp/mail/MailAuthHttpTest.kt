package com.diegonmarcos.superapp.mail

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

/**
 * Mail account auth on the wire: the interceptor adds the Bearer header, the cookie jar replays the
 * Authelia session, neither leaks past the account's domain, and no secret reaches a log line.
 * Runs against a real local server (MockWebServer); every name resolves to it.
 */
class MailAuthHttpTest {

    private lateinit var server: MockWebServer
    private val toLocal = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
    }).build()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    private fun url(host: String = "jmap.diegonmarcos.com") = "http://$host:${server.port}/.well-known/jmap"
    private fun get(c: OkHttpClient, u: String = url()) = c.newCall(Request.Builder().url(u).build()).execute().use { it.code }

    private fun client(creds: MailAuthCreds, onRefresh: (String, Long) -> Unit = { _, _ -> }, onRejected: () -> Unit = {}) =
        MailHttp.client("https://jmap.diegonmarcos.com", { creds }, onRefresh, onRejected, base = toLocal)

    @Test fun `bearer mode puts the fleet token in X-Fleet-Authorization`() {
        server.enqueue(MockResponse().setBody("{}"))
        get(client(MailAuthCreds(MailAuthMode.BEARER, bearer = " tok-123 ")))
        val r = server.takeRequest()
        assertEquals("Bearer tok-123", r.getHeader("X-Fleet-Authorization"))
        assertNull(r.getHeader("Authorization"))
    }

    @Test fun `bearer keeps the mailbox Basic login in Authorization`() {
        server.enqueue(MockResponse().setBody("{}"))
        val c = client(MailAuthCreds(MailAuthMode.BEARER, bearer = "tok"))
        c.newCall(Request.Builder().url(url()).header("Authorization", "Basic abc").build()).execute().close()
        val r = server.takeRequest()
        assertEquals(listOf("Basic abc"), r.headers.values("Authorization"))
        assertEquals("Bearer tok", r.getHeader("X-Fleet-Authorization"))
    }

    @Test fun `mode none leaves the request as the caller built it`() {
        server.enqueue(MockResponse().setBody("{}"))
        val c = client(MailAuthCreds(MailAuthMode.NONE, bearer = "ignored"))
        c.newCall(Request.Builder().url(url()).header("Authorization", "Basic abc").build()).execute().close()
        val r = server.takeRequest()
        assertEquals("Basic abc", r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
    }

    @Test fun `authelia mode replays the session cookie`() {
        server.enqueue(MockResponse().setBody("{}"))
        get(client(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "sess-9")))
        val r = server.takeRequest()
        assertEquals("authelia_session=sess-9", r.getHeader("Cookie"))
        assertNull("the cookie mode sends no bearer", r.getHeader("Authorization"))
    }

    @Test fun `a rotated session cookie is handed back for persisting`() {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "authelia_session=fresh; Path=/").setBody("{}"))
        var got: String? = null
        get(client(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "old"), onRefresh = { v, _ -> got = v }))
        assertEquals("fresh", got)
    }

    @Test fun `credentials never travel to a foreign host`() {
        server.enqueue(MockResponse().setBody("{}"))
        get(client(MailAuthCreds(MailAuthMode.BEARER, bearer = "tok")), url("evil.example.com"))
        assertNull(server.takeRequest().getHeader("X-Fleet-Authorization"))
        server.enqueue(MockResponse().setBody("{}"))
        get(client(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "s")), url("evil.example.com"))
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertTrue(MailAuthScope.allows("jmap.diegonmarcos.com", "api.diegonmarcos.com"))
        assertFalse(MailAuthScope.allows("jmap.diegonmarcos.com", "diegonmarcos.com.evil.net"))
    }

    @Test fun `a 401 under a gateway mode reports the rejection, under none it does not`() {
        var rejected = 0
        server.enqueue(MockResponse().setResponseCode(401))
        // A bare 401 is the gate's: reported, and surfaced as a sign-in-required failure.
        try { get(client(MailAuthCreds(MailAuthMode.BEARER, bearer = "t"), onRejected = { rejected++ })); throw AssertionError("gate 401 must fail") } catch (e: MailAuthRequiredException) { /* expected */ }
        assertEquals(1, rejected)
        // A mailbox 401 (Stalwart's own problem+json) is the account login: not a gateway rejection.
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/problem+json").setHeader("WWW-Authenticate", "Basic realm=\"Stalwart Server\""))
        assertEquals(401, get(client(MailAuthCreds(MailAuthMode.BEARER, bearer = "t"), onRejected = { rejected++ })))
        assertEquals(1, rejected)
        server.enqueue(MockResponse().setResponseCode(401))
        get(client(MailAuthCreds(MailAuthMode.NONE), onRejected = { rejected++ }))
        assertEquals(1, rejected)
    }

    @Test fun `a redirect that lands on the Authelia portal is a sign-in-required failure`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://auth.diegonmarcos.com:${server.port}/?rd=x"))
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        var rejected = 0
        val c = client(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "dead"), onRejected = { rejected++ })
        try { get(c); throw AssertionError("expected the portal redirect to fail") } catch (e: MailAuthRequiredException) { /* expected */ }
        assertEquals(1, rejected)
        assertEquals(ConnProblem.GATE_REJECTED, ConnErrors.classify(null, MailAuthRequiredException("x")))
    }

    @Test fun `the JMAP client maps the portal redirect to a 401 result`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://auth.diegonmarcos.com:${server.port}/"))
        server.enqueue(MockResponse().setBody("login"))
        val http = client(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "dead"))
        val r = JmapClient("http://jmap.diegonmarcos.com:${server.port}", "a@b.c", "pw", http).discover()
        assertTrue(r is JmapClient.Result.HttpError && r.code == 401)
    }

    // ── secrets never logged ───────────────────────────────────────────────

    @Test fun `credentials print masked`() {
        val c = MailAuthCreds(MailAuthMode.BEARER, bearer = "SECRET-TOKEN", sessionCookie = "SECRET-COOKIE")
        assertFalse(c.toString().contains("SECRET"))
        assertFalse(c.copy(mode = MailAuthMode.AUTHELIA).toString().contains("SECRET"))
    }

    @Test fun `no mail source logs, prints or interpolates a secret`() {
        val dir = File("../../ab_cloud-libs-shared/libs/mail/src/main/java/com/diegonmarcos/superapp/mail")
        val files = dir.listFiles { f -> f.extension == "kt" }!!.toList()
        assertTrue(files.isNotEmpty())
        val sink = Regex("""(Log\.[a-z]\(|println\(|Timber|HttpLoggingInterceptor|printStackTrace)""")
        for (f in files) assertFalse("${f.name} writes to a log", sink.containsMatchIn(f.readText()))
    }

    // ── the failure words ──────────────────────────────────────────────────

    @Test fun `errors are told apart`() {
        assertEquals(ConnProblem.UNAUTHORIZED, ConnErrors.classify(401, null))
        assertEquals(ConnProblem.FORBIDDEN, ConnErrors.classify(403, null))
        assertEquals(ConnProblem.HTTP, ConnErrors.classify(502, null))
        assertEquals(ConnProblem.TLS, ConnErrors.classify(null, SSLHandshakeException("x")))
        assertEquals(ConnProblem.DNS, ConnErrors.classify(null, java.io.IOException("w", UnknownHostException("h"))))
        assertEquals(ConnProblem.TIMEOUT, ConnErrors.classify(null, SocketTimeoutException()))
        assertEquals(ConnProblem.OTHER, ConnErrors.classify(null, IllegalStateException()))
    }

    @Test fun `the status chip follows the credentials`() {
        val now = 1_000L
        fun s(c: MailAuthCreds) = MailAuthStatuses.resolve(c, now)
        assertEquals(MailAuthStatus.CONNECTED, s(MailAuthCreds()))
        assertEquals(MailAuthStatus.TOKEN, s(MailAuthCreds(MailAuthMode.BEARER, bearer = "t")))
        assertEquals(MailAuthStatus.SIGNED_IN, s(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "c")))
        assertEquals(MailAuthStatus.NEEDS_SIGN_IN, s(MailAuthCreds(MailAuthMode.AUTHELIA)))
        assertEquals(MailAuthStatus.NEEDS_SIGN_IN, s(MailAuthCreds(MailAuthMode.AUTHELIA, sessionCookie = "c", cookieExpiresAt = 500)))
        assertEquals(MailAuthStatus.NEEDS_SIGN_IN, s(MailAuthCreds(MailAuthMode.BEARER, bearer = "t", needsSignIn = true)))
        assertEquals(MailAuthStatus.ERROR, s(MailAuthCreds(lastError = "boom")))
    }

    // ── storage ────────────────────────────────────────────────────────────

    private class MapStore : MailSecretStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun `credentials round-trip per account and sign-out keeps the token`() {
        val store = MailAuthStore(MapStore())
        store.save("A@x.com", MailAuthCreds(MailAuthMode.BEARER, bearer = "tok", sessionCookie = "ck", signedInAt = 5))
        assertEquals("tok", store.load("a@x.com").bearer)
        assertEquals(MailAuthCreds(), store.load("other@x.com"))
        store.signOut("a@x.com")
        val after = store.load("a@x.com")
        assertEquals("", after.sessionCookie); assertEquals("tok", after.bearer)
        store.forget("a@x.com")
        assertEquals(MailAuthCreds(), store.load("a@x.com"))
    }

    @Test fun `the session value is read out of a cookie string`() {
        assertEquals("v1", AutheliaPortal.sessionValue("a=b; authelia_session=v1; c=d"))
        assertEquals("", AutheliaPortal.sessionValue("a=b"))
    }
}
