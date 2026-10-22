package com.diegonmarcos.superapp.fleetendpoints

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * #1090 The fleet transport: a fleet account tries its mesh endpoint first and falls back to the public one,
 * caches the verdict, re-probes when the network changes, sends the bearer on the public path ONLY, and a host
 * that is not a registered fleet endpoint is left alone. The public server answers on `127.0.0.1`, the mesh one on
 * `localhost`: two hosts, as in production.
 */
class EndpointRoutingTest {
    private lateinit var pub: MockWebServer
    private lateinit var mesh: MockWebServer
    private var now = 1_000_000L
    private var meshReachable = true
    private val probed = mutableListOf<Pair<String, Int>>()
    private val bearers = FleetBearers()
    private val marker = "fleet-SECRET-tok" // gitleaks:allow (a made-up test marker, not a credential)

    private fun newRouter(requireHttps: Boolean = false) = EndpointRouter(
        probe = { h, p, _ -> probed += h to p; meshReachable }, clock = { now }, recheckAfterMs = 60_000L, requireHttpsPublic = requireHttps,
    )

    private lateinit var router: EndpointRouter
    private lateinit var http: OkHttpClient

    @Before fun up() {
        pub = MockWebServer().also { it.start() }
        mesh = MockWebServer().also { it.start() }
        router = newRouter()
        // OkHttp's own retry would re-send a dropped GET to the next address of `localhost` (::1 refuses: a ConnectException that
        // proves nothing about the first try); this suite is about the ROUTER's rule, so the client does not retry underneath it.
        http = OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(EndpointRouteInterceptor(router))
            .addNetworkInterceptor(FleetBearerInterceptor(router, bearers))
            .build()
        register(EndpointPolicy.MESH_FIRST)
        bearers.set("127.0.0.1", marker)
    }

    @After fun down() { runCatching { pub.shutdown() }; runCatching { mesh.shutdown() } }

    private fun register(policy: EndpointPolicy) =
        assertTrue(router.set("http://127.0.0.1:${pub.port}", "http://localhost:${mesh.port}", policy))

    private fun get(path: String = "/.well-known/jmap", headers: Map<String, String> = emptyMap()): Int =
        http.newCall(Request.Builder().url("http://127.0.0.1:${pub.port}$path").also { b -> headers.forEach { (k, v) -> b.header(k, v) } }.build())
            .execute().use { it.code }

    // ── order ────────────────────────────────────────────────────────────

    @Test fun `mesh first - a reachable mesh answers and the public endpoint is never touched`() {
        mesh.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get())
        assertEquals(1, mesh.requestCount); assertEquals(0, pub.requestCount)
        assertEquals(listOf("localhost" to mesh.port), probed)
        assertEquals(EndpointState.MESH, router.status.value.getValue("127.0.0.1").state())
    }

    @Test fun `mesh first - an unreachable mesh falls back to public and the bearer rides there`() {
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get())
        assertEquals(0, mesh.requestCount); assertEquals(1, pub.requestCount)
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
        assertEquals(EndpointState.PUBLIC, router.status.value.getValue("127.0.0.1").state())
    }

    @Test fun `a mesh that probes up but refuses the connection falls back inside the same request`() {
        mesh.shutdown()
        pub.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get())
        assertEquals(1, pub.requestCount)
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    // ── caching and re-probing ───────────────────────────────────────────

    @Test fun `the verdict is cached - ten requests probe once`() {
        meshReachable = false
        repeat(10) { pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get()) }
        assertEquals(1, probed.size)
        assertEquals(10, pub.requestCount)
    }

    @Test fun `a failed mesh request is cached too - the next request goes straight to public`() {
        mesh.shutdown()
        repeat(2) { pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get()) }
        assertEquals("one probe, then the cached down verdict", 1, probed.size)
        assertEquals(2, pub.requestCount)
    }

    @Test fun `a network change re-probes and the route follows the mesh back`() {
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        meshReachable = true
        pub.enqueue(MockResponse().setBody("{}")); assertEquals("cached until the network changes", 200, get())
        assertEquals(2, pub.requestCount); assertEquals(1, probed.size)
        router.onNetworkChanged()
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertEquals(1, mesh.requestCount); assertEquals(2, probed.size)
    }

    @Test fun `a network change away from the mesh re-probes and falls back`() {
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        meshReachable = false
        router.onNetworkChanged()
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertEquals(1, pub.requestCount)
    }

    @Test fun `the verdict also expires on its own`() {
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}")); get()
        now += 61_000L; meshReachable = true
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertEquals(2, probed.size); assertEquals(1, mesh.requestCount)
    }

    // ── policy ───────────────────────────────────────────────────────────

    @Test fun `public only never probes the mesh`() {
        register(EndpointPolicy.PUBLIC_ONLY)
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertTrue(probed.isEmpty()); assertEquals(0, mesh.requestCount)
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    @Test fun `mesh only has no fallback and never goes public`() {
        register(EndpointPolicy.MESH_ONLY)
        mesh.shutdown()
        try { get(); fail("mesh only must not fall back to public") } catch (e: IOException) { /* expected */ }
        assertEquals(0, pub.requestCount)
        assertEquals(EndpointState.OFFLINE, router.status.value.getValue("127.0.0.1").state())
    }

    @Test fun `the policy can change on a live endpoint`() {
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}")); get()
        router.setPolicy("127.0.0.1", EndpointPolicy.MESH_ONLY)
        assertEquals(EndpointPolicy.MESH_ONLY, router.policyOf("127.0.0.1"))
    }

    // ── the bearer ───────────────────────────────────────────────────────

    @Test fun `no bearer ever reaches the mesh host, even one asked for by the caller`() {
        mesh.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get(headers = mapOf("X-Fleet-Authorization" to "Bearer leaked")))
        assertNull(mesh.takeRequest().getHeader("X-Fleet-Authorization"))
        bearers.set("localhost", marker)
        mesh.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get())
        assertNull("a marker filed under the mesh host is not sent either", mesh.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    @Test fun `the bearer goes to the exact public host and to no sibling`() {
        register(EndpointPolicy.PUBLIC_ONLY)
        bearers.set("127.0.0.1", null); bearers.set("localhost", marker) // a marker for ANOTHER host than the one asked
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertNull(pub.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    @Test fun `a redirect off the public host does not carry the bearer`() {
        register(EndpointPolicy.PUBLIC_ONLY)
        pub.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${mesh.port}/elsewhere"))
        mesh.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, get())
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
        assertNull(mesh.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    @Test fun `a host that is not a fleet endpoint is untouched - no rewrite, no probe, no bearer`() {
        router.remove("127.0.0.1")
        bearers.set("127.0.0.1", marker)
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertEquals(1, pub.requestCount); assertEquals(0, mesh.requestCount); assertTrue(probed.isEmpty())
        assertNull("a plain IMAP/JMAP provider never receives a fleet marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    // ── what is an answer and what is a failure ──────────────────────────

    @Test fun `an HTTP answer from the mesh is an answer - a 401 is not a reason to go public`() {
        mesh.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, get())
        assertEquals(0, pub.requestCount)
        assertEquals(EndpointState.MESH, router.status.value.getValue("127.0.0.1").state())
    }

    @Test fun `a request the mesh may have received is not sent again on public`() {
        mesh.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try { get(); fail("an unanswered request is a failure of this request, not a reason to send it again") } catch (e: IOException) { /* expected */ }
        assertEquals("no second delivery of a request that reached the server", 0, pub.requestCount)
        pub.enqueue(MockResponse().setBody("{}")); assertEquals("but the next one knows the mesh is unreliable", 200, get())
    }

    @Test fun `only a failure that proves the request never arrived allows the other route`() {
        assertTrue(EndpointRouter.neverReached(ConnectException("refused")))
        assertTrue(EndpointRouter.neverReached(UnknownHostException("x.app")))
        assertTrue(EndpointRouter.neverReached(SSLHandshakeException("untrusted")))
        assertTrue(EndpointRouter.neverReached(IOException("wrapped", ConnectException())))
        assertTrue(EndpointRouter.neverReached(SocketTimeoutException("connect timed out")))
        assertTrue("cleartext refused by the platform is refused before anything is sent",
            EndpointRouter.neverReached(java.net.UnknownServiceException("CLEARTEXT communication to gitea.app not permitted by network security policy")))
        assertFalse(EndpointRouter.neverReached(SocketTimeoutException("timeout")))
        assertFalse(EndpointRouter.neverReached(IOException("unexpected end of stream")))
    }

    // ── registration ─────────────────────────────────────────────────────

    @Test fun `urls are kept apart from the rewrite - path and query survive`() {
        mesh.enqueue(MockResponse().setBody("{}"))
        get("/jmap/download/a1/b2?accept=x")
        assertEquals("/jmap/download/a1/b2?accept=x", mesh.takeRequest().path)
    }

    @Test fun `an unusable pair is refused`() {
        val strict = newRouter(requireHttps = true)
        assertFalse("a plain-http public endpoint would carry the bearer in clear", strict.set("http://pub.example", "https://svc.app", EndpointPolicy.MESH_FIRST))
        assertFalse("the same host twice is not two endpoints", strict.set("https://pub.example", "https://pub.example:2443", EndpointPolicy.MESH_FIRST))
        assertFalse("a base URL carries no query string", strict.set("https://pub.example/jmap?x=1", "https://svc.app", EndpointPolicy.MESH_FIRST))
        assertFalse(strict.set("not a url", "https://svc.app", EndpointPolicy.MESH_FIRST))
        assertTrue(strict.set("https://pub.example", "https://svc.app:2443", EndpointPolicy.MESH_FIRST))
    }

    // ── no secrets in what can be logged ─────────────────────────────────

    @Test fun `neither the bearers nor the router state can print a marker`() {
        mesh.enqueue(MockResponse().setBody("{}")); get()
        meshReachable = false; router.onNetworkChanged(); pub.enqueue(MockResponse().setBody("{}")); get()
        assertFalse(bearers.toString().contains(marker))
        assertFalse(router.status.value.toString().contains(marker))
        assertTrue(bearers.toString().contains("127.0.0.1"))
    }

    // ── a bearer taken from a source (Cloud Account reads its vault each time) ──

    @Test fun `a bearer from the source rides the public path only, and never the mesh path`() {
        bearers.set("127.0.0.1", null)
        val asked = mutableListOf<String>()
        bearers.source = { h -> asked += h; marker }
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertNull(mesh.takeRequest().getHeader("X-Fleet-Authorization"))
        meshReachable = false; router.onNetworkChanged()
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
        assertFalse("the source is never asked for the mesh host", "localhost" in asked)
    }

    @Test fun `a source that has nothing, or only blanks, sends no header`() {
        bearers.set("127.0.0.1", null)
        meshReachable = false
        for (v in listOf<String?>(null, "", "   ")) {
            bearers.source = { v }
            pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get())
            assertNull(pub.takeRequest().getHeader("X-Fleet-Authorization"))
        }
    }

    @Test fun `the source's token is not printable through the bearers`() {
        bearers.set("127.0.0.1", null); bearers.source = { marker }
        assertEquals(marker, bearers.forHost("127.0.0.1"))
        assertFalse(bearers.toString().contains(marker))
    }

    // ── a pair under a PATH of a shared host ─────────────────────────────

    @Test fun `a pair under a path swaps the base path and routes only the paths it names`() {
        router.clear()
        assertTrue(router.set("http://127.0.0.1:${pub.port}/pub", "http://localhost:${mesh.port}", EndpointPolicy.MESH_FIRST, listOf("/health", "/analytics")))
        assertTrue(router.isRegistered("127.0.0.1/pub")); assertFalse(router.isRegistered("127.0.0.1"))

        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pub/health"))
        assertEquals("the edge strips /pub: the mesh side serves from its root", "/health", mesh.takeRequest().path)
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pub/analytics/openobserve?x=1"))
        assertEquals("/analytics/openobserve?x=1", mesh.takeRequest().path)
        assertEquals(EndpointState.MESH, router.status.value.getValue("127.0.0.1/pub").state())

        val probes = probed.size
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pub/profile/connect/start"))
        val direct = pub.takeRequest()
        assertEquals("a path the pair does not name stays on the public URL, untouched", "/pub/profile/connect/start", direct.path)
        assertNull("... and is not given the bearer", direct.getHeader("X-Fleet-Authorization"))
        assertEquals(probes, probed.size)

        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pubx/health"))
        assertEquals("a sibling path is not the base path", "/pubx/health", pub.takeRequest().path)
    }

    @Test fun `the fallback of a pair under a path puts the base path back and carries the bearer`() {
        router.clear()
        router.set("http://127.0.0.1:${pub.port}/pub", "http://localhost:${mesh.port}", EndpointPolicy.MESH_FIRST, listOf("/health"))
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pub/health"))
        val r = pub.takeRequest()
        assertEquals("/pub/health", r.path); assertEquals("Bearer $marker", r.getHeader("X-Fleet-Authorization"))
        assertEquals(0, mesh.requestCount)
    }

    @Test fun `a pair with no narrowing routes everything under its base path`() {
        router.clear()
        router.set("http://127.0.0.1:${pub.port}/pub", "http://localhost:${mesh.port}/base", EndpointPolicy.MESH_FIRST)
        mesh.enqueue(MockResponse().setBody("{}")); assertEquals(200, get("/pub/a/b"))
        assertEquals("a mesh side with its own base path gets it", "/base/a/b", mesh.takeRequest().path)
    }
}
