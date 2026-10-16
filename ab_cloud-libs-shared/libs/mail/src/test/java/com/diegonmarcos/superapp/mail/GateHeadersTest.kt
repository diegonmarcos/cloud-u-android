package com.diegonmarcos.superapp.mail

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The edge gate's credential next to the mailbox login: header composition, scoping, and who refused. */
class GateHeadersTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    private val basic = "Basic bWVAeC50ZXN0OnB3"

    private fun bearer(token: String = " fleet-tok ") = MailAuthCreds(mode = MailAuthMode.BEARER, bearer = token)

    @Test fun `bearer mode puts the fleet token in its own header`() {
        val h = GateHeaders.forRequest("jmap.diegonmarcos.com", "jmap.diegonmarcos.com", bearer())
        assertEquals(mapOf("X-Fleet-Authorization" to "Bearer fleet-tok"), h)
        assertTrue("Authorization" !in h.keys)
    }

    @Test fun `no header without a token, in other modes, or for a foreign host`() {
        assertTrue(GateHeaders.forRequest("jmap.diegonmarcos.com", "jmap.diegonmarcos.com", bearer("")).isEmpty())
        assertTrue(GateHeaders.forRequest("jmap.diegonmarcos.com", "jmap.diegonmarcos.com",
            MailAuthCreds(mode = MailAuthMode.AUTHELIA, bearer = "t", sessionCookie = "c")).isEmpty())
        assertTrue(GateHeaders.forRequest("jmap.diegonmarcos.com", "evil.example.org", bearer()).isEmpty())
        assertEquals(1, GateHeaders.forRequest("jmap.diegonmarcos.com", "api.diegonmarcos.com", bearer()).size)
    }

    @Test fun `on the wire the mailbox Authorization is untouched and the fleet header rides next to it`() {
        val host = "127.0.0.1"
        val http = MailHttp.client("http://$host:${server.port}", { bearer() })
        server.enqueue(MockResponse().setBody("{}"))
        http.newCall(Request.Builder().url("http://$host:${server.port}/.well-known/jmap").header("Authorization", basic).build()).execute().close()
        val r = server.takeRequest()
        assertEquals(basic, r.getHeader("Authorization"))
        assertEquals("Bearer fleet-tok", r.getHeader("X-Fleet-Authorization"))
    }

    @Test fun `authelia mode sends the cookie and the mailbox login, no fleet header`() {
        val host = "127.0.0.1"
        val http = MailHttp.client("http://$host:${server.port}", { MailAuthCreds(mode = MailAuthMode.AUTHELIA, sessionCookie = "sess-1") })
        server.enqueue(MockResponse().setBody("{}"))
        http.newCall(Request.Builder().url("http://$host:${server.port}/.well-known/jmap").header("Authorization", basic).build()).execute().close()
        val r = server.takeRequest()
        assertEquals(basic, r.getHeader("Authorization"))
        assertEquals("authelia_session=sess-1", r.getHeader("Cookie"))
        assertNull(r.getHeader("X-Fleet-Authorization"))
    }

    @Test fun `the mailbox 401 is told apart from the gate 401`() {
        val stalwart = listOf("Bearer realm=\"Stalwart Server\"", "Basic realm=\"Stalwart Server\"")
        assertEquals(RejectionLayer.MAILBOX, RejectionLayer.of(stalwart, "application/problem+json"))
        assertEquals(RejectionLayer.MAILBOX, RejectionLayer.of(emptyList(), "application/json"))
        assertEquals(RejectionLayer.GATE, RejectionLayer.of(emptyList(), "text/html; charset=utf-8"))
        assertEquals(RejectionLayer.GATE, RejectionLayer.of(emptyList(), null))
    }

    @Test fun `a gate 401 is reported as the gate, a mailbox 401 as the mailbox`() {
        val host = "127.0.0.1"
        var rejected = 0
        val http = MailHttp.client("http://$host:${server.port}", { bearer() }, onRejected = { rejected++ })
        fun call() = http.newCall(Request.Builder().url("http://$host:${server.port}/.well-known/jmap").header("Authorization", basic).build()).execute()

        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("no"))
        try { call().close(); fail("gate 401 must throw") } catch (e: MailAuthRequiredException) {
            assertEquals(ConnProblem.GATE_REJECTED, ConnErrors.classify(null, e))
        }
        assertEquals(1, rejected)

        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/problem+json")
            .setHeader("WWW-Authenticate", "Basic realm=\"Stalwart Server\"").setBody("{}"))
        call().use { assertEquals(401, it.code) }
        assertEquals(1, rejected)
        assertEquals(ConnProblem.UNAUTHORIZED, ConnErrors.classify(401, null))
    }
}
