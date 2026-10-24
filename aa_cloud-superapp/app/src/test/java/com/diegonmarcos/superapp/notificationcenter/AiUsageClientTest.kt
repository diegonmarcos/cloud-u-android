package com.diegonmarcos.superapp.notificationcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** #1151 mesh first, then public with the bearer: the order of the two legs, and what each failure becomes. */
class AiUsageClientTest {

    private val base = "https://api.example.test/c3-infra-api/"
    private val calls = mutableListOf<String>()

    private fun leg(name: String, reply: () -> AiUsageClient.Reply?) = AiUsageClient.Transport { url, headers ->
        calls += "$name ${url} auth=${headers.containsKey("Authorization")}"
        reply()
    }

    private fun fetch(bearer: String, fleet: AiUsageClient.Transport, plain: AiUsageClient.Transport) =
        AiUsageClient.fetch(base, bearer, fleet, plain)

    @Test fun `the fleet leg answers and the public leg is never touched`() {
        val r = fetch("tok", leg("fleet") { AiUsageClient.Reply(200, "{}") }, leg("plain") { error("must not be called") })
        assertEquals(AiUsageClient.Outcome.Ok("{}", "fleet"), r)
        assertEquals(listOf("fleet https://api.example.test/c3-infra-api/ai/usage auth=false"), calls)
    }

    @Test fun `a host that is not a fleet endpoint goes public with the bearer`() {
        val r = fetch("tok", leg("fleet") { null }, leg("plain") { AiUsageClient.Reply(200, "{\"a\":1}") })
        assertEquals(AiUsageClient.Outcome.Ok("{\"a\":1}", "public"), r)
        assertEquals(listOf("fleet https://api.example.test/c3-infra-api/ai/usage auth=false",
            "plain https://api.example.test/c3-infra-api/ai/usage auth=true"), calls)
    }

    @Test fun `an unreachable fleet leg falls back to public, and a refused one too`() {
        val a = fetch("tok", leg("fleet") { throw IOException("mesh down") }, leg("plain") { AiUsageClient.Reply(200, "{}") })
        assertEquals(AiUsageClient.Outcome.Ok("{}", "public"), a)
        calls.clear()
        val b = fetch("tok", leg("fleet") { AiUsageClient.Reply(401, "") }, leg("plain") { AiUsageClient.Reply(200, "{}") })
        assertEquals(AiUsageClient.Outcome.Ok("{}", "public"), b)
        assertEquals(2, calls.size)
    }

    @Test fun `a server error on the fleet leg is reported and not retried publicly`() {
        val r = fetch("tok", leg("fleet") { AiUsageClient.Reply(502, "") }, leg("plain") { error("must not be called") })
        assertEquals(AiUsageClient.Outcome.Failed("fleet leg: HTTP 502"), r)
    }

    @Test fun `no bearer means no public call, and the reason says so`() {
        val r = fetch("", leg("fleet") { null }, leg("plain") { error("must not be called") }) as AiUsageClient.Outcome.Failed
        assertTrue(r.reason, r.reason.contains("no fleet bearer"))
    }

    @Test fun `public failures are named and carry no credential`() {
        val a = fetch("sekrit-token", leg("fleet") { null }, leg("plain") { AiUsageClient.Reply(403, "sekrit-token") }) as AiUsageClient.Outcome.Failed
        assertEquals("public leg: HTTP 403", a.reason)
        val b = fetch("sekrit-token", leg("fleet") { null }, leg("plain") { throw IOException("sekrit-token") }) as AiUsageClient.Outcome.Failed
        assertEquals("public leg: IOException", b.reason)
        assertTrue(!a.reason.contains("sekrit") && !b.reason.contains("sekrit"))
    }

    @Test fun `an empty base URL is a named failure`() {
        assertEquals(AiUsageClient.Outcome.Failed("no ops endpoint in this build"),
            AiUsageClient.fetch("", "tok", leg("fleet") { null }, leg("plain") { null }))
    }
}
