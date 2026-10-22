package com.diegonmarcos.superapp.fleetendpoints

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * #1093 The blocking exchange Cloud Account's forge client uses, and the per-endpoint check its Status page runs.
 * The public server answers on `127.0.0.1`, the mesh one on `localhost`: two hosts, as in production.
 */
class FleetHttpTest {
    private lateinit var pub: MockWebServer
    private lateinit var mesh: MockWebServer
    private var meshReachable = true
    private val marker = "fleet-SECRET-tok" // gitleaks:allow (a made-up test marker, not a credential)
    private val router = EndpointRouter(probe = { _, _, _ -> meshReachable }, requireHttpsPublic = false)
    private val bearers = FleetBearers()
    private val client = FleetHttp.client(router, bearers)

    @Before fun up() {
        pub = MockWebServer().also { it.start() }
        mesh = MockWebServer().also { it.start() }
        assertTrue(router.set("http://127.0.0.1:${pub.port}", "http://localhost:${mesh.port}", EndpointPolicy.MESH_FIRST))
        bearers.set("127.0.0.1", marker)
    }

    @After fun down() { runCatching { pub.shutdown() }; runCatching { mesh.shutdown() } }

    private fun url(path: String) = "http://127.0.0.1:${pub.port}$path"

    @Test fun `a call to a registered public host goes to the mesh first, with the callers own headers and no bearer`() {
        mesh.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val (status, body) = FleetHttp.call(client, "PUT", url("/api/v1/x"), mapOf("Authorization" to "token t-1"), """{"a":1}""")
        assertEquals(200, status); assertEquals("""{"ok":true}""", body)
        val r = mesh.takeRequest()
        assertEquals("PUT", r.method); assertEquals("/api/v1/x", r.path); assertEquals("""{"a":1}""", r.body.readUtf8())
        assertEquals("token t-1", r.getHeader("Authorization"))
        assertNull(r.getHeader("X-Fleet-Authorization"))
        assertEquals(0, pub.requestCount)
    }

    @Test fun `the same call falls back to public and carries the bearer there`() {
        meshReachable = false
        pub.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val (status, body) = FleetHttp.call(client, "GET", url("/api/v1/y"), emptyMap(), null)
        assertEquals(404, status); assertEquals("nope", body)
        assertEquals("Bearer $marker", pub.takeRequest().getHeader("X-Fleet-Authorization"))
    }

    @Test fun `a body-less POST or DELETE is sent, a GET carries no body`() {
        meshReachable = false
        pub.enqueue(MockResponse().setBody("{}")); pub.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, FleetHttp.call(client, "POST", url("/a"), emptyMap(), null).first)
        assertEquals(200, FleetHttp.call(client, "DELETE", url("/b"), emptyMap(), null).first)
        assertEquals("POST", pub.takeRequest().method); assertEquals("DELETE", pub.takeRequest().method)
    }

    @Test fun `routes tells a fleet host from any other`() {
        assertTrue(FleetHttp.routes(url("/x"), router))
        assertFalse(FleetHttp.routes("https://api.github.com/user", router))
        assertFalse(FleetHttp.routes("not a url", router))
    }

    @Test fun `a transport failure is an IOException whose message names no header or token`() {
        meshReachable = false
        pub.shutdown()
        val e = runCatching { FleetHttp.call(client, "GET", url("/z"), mapOf("Authorization" to "token t-1"), null) }.exceptionOrNull()
        assertTrue(e is IOException)
        assertFalse(e!!.message.orEmpty().contains(marker)); assertFalse(e.message.orEmpty().contains("t-1"))
    }

    // ── the per-endpoint check ───────────────────────────────────────────

    @Test fun `the checker asks the mesh by connection and the public endpoint by request, one at a time`() {
        val probed = mutableListOf<Pair<String, Int>>()
        val checker = EndpointChecker(router, bearers, probe = { h, p, _ -> probed += h to p; meshReachable })
        assertTrue(checker.meshUp("http://localhost:${mesh.port}"))
        assertEquals(listOf("localhost" to mesh.port), probed)
        meshReachable = false
        assertFalse(checker.meshUp("http://localhost:${mesh.port}"))
        assertFalse("an unusable URL is not up", checker.meshUp("nope"))

        pub.enqueue(MockResponse().setResponseCode(401))
        assertEquals("a refusal is still a reachable endpoint", 401, checker.publicStatus("http://127.0.0.1:${pub.port}", "/api/health"))
        assertEquals(0, mesh.requestCount)
        val r = pub.takeRequest()
        assertEquals("/api/health", r.path)
        assertEquals("the check sends what real traffic would", "Bearer $marker", r.getHeader("X-Fleet-Authorization"))
    }

    @Test fun `the check does not follow a redirect and so never carries the bearer off the public host`() {
        val checker = EndpointChecker(router, bearers)
        pub.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${mesh.port}/elsewhere"))
        assertEquals(302, checker.publicStatus("http://127.0.0.1:${pub.port}", "/h"))
        assertEquals(0, mesh.requestCount)
    }

    @Test fun `an unreachable public endpoint is null, not an error`() {
        val checker = EndpointChecker(router, bearers)
        val port = pub.port; pub.shutdown()
        assertNull(checker.publicStatus("http://127.0.0.1:$port", "/h"))
        assertNull(checker.publicStatus("nope", "/h"))
    }

    // ── the registry every app uses ──────────────────────────────────────

    @Test fun `the registry routes a pair, hands the bearer to its public host only and keeps it out of the state`() {
        val r = EndpointRouter(probe = { _, _, _ -> true }, requireHttpsPublic = false); val b = FleetBearers()
        var current: String? = marker
        val n = FleetRegistry.register(listOf(
            EndpointPair("http://127.0.0.1:${pub.port}", "http://localhost:${mesh.port}", "mesh_first"),
            EndpointPair("not a url", "http://localhost:1", "mesh_first")), r, b) { current }
        assertEquals(1, n); assertTrue(r.isRegistered("127.0.0.1"))
        assertEquals(marker, b.forHost("127.0.0.1")); assertNull(b.forHost("localhost")); assertNull(b.forHost("other.example"))
        current = "rotated"; assertEquals("read when asked, never copied", "rotated", b.forHost("127.0.0.1"))
        assertFalse(b.toString().contains("rotated")); assertFalse(r.status.value.toString().contains("rotated"))
    }

    @Test fun `a mesh-only pair never falls back to the public host`() {
        val r = EndpointRouter(probe = { _, _, _ -> false }, requireHttpsPublic = false); val b = FleetBearers()
        FleetRegistry.register(listOf(EndpointPair("http://127.0.0.1:${pub.port}", "http://localhost:${mesh.port}", "mesh_only")), r, b) { marker }
        mesh.shutdown() // the mesh is unreachable
        val c = FleetHttp.client(r, b)
        val e = runCatching { FleetHttp.call(c, "GET", url("/x"), emptyMap(), null) }.exceptionOrNull()
        assertTrue("the request fails (a clear unreachable state)", e is IOException)
        assertEquals("... and is never sent to the public host", 0, pub.requestCount)
    }
}
