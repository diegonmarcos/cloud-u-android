package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1184 the fleet routes: the declared policy decides which address is tried, and the bearer goes to a public origin only. */
class EndpointsTest {
    private val both = FleetEndpoint("svc", "http://svc.mesh:3000/", "https://pub.example/svc/", "mesh_first")

    @Test fun meshFirstThenPublicWithTheBearer() {
        val (routes, skipped) = both.plan("tok")
        assertEquals(listOf("mesh", "public"), routes.map { it.label })
        assertEquals("a trailing slash never doubles", "http://svc.mesh:3000", routes[0].base)
        assertEquals("https://pub.example/svc", routes[1].base)
        assertTrue("the mesh gets no bearer", routes[0].headers.isEmpty())
        assertEquals("Bearer tok", routes[1].headers["Authorization"])
        assertTrue(skipped.isEmpty())
    }

    @Test fun withoutASignInThePublicRouteIsSkippedAndSaysWhy() {
        val (routes, skipped) = both.plan(null)
        assertEquals(listOf("mesh"), routes.map { it.label })
        assertEquals(listOf("public route needs the fleet sign-in of Cloud Account"), skipped)
    }

    @Test fun meshOnlyNeverTriesPublicAndPublicOnlyNeverTriesMesh() {
        assertEquals(listOf("mesh"), both.copy(policy = "mesh_only").plan("tok").first.map { it.label })
        assertEquals(listOf("public"), both.copy(policy = "public_only").plan("tok").first.map { it.label })
        assertTrue(both.copy(policy = "public_only").plan("tok").second.isEmpty())
    }

    @Test fun anUndeclaredAddressIsReportedNotInvented() {
        val noPublic = FleetEndpoint("p", "http://p.mesh:1", "", "mesh_first")
        assertEquals(listOf("p has no public address"), noPublic.plan("tok").second)
        val noMesh = FleetEndpoint("m", "", "https://m.example", "mesh_first")
        assertEquals(listOf("m has no mesh address"), noMesh.plan("tok").second)
        assertEquals(listOf("public"), noMesh.plan("tok").first.map { it.label })
    }

    @Test fun theFirstRouteThatAnswersWinsAndAFailureFallsThrough() {
        val tried = mutableListOf<String>()
        val ok = both.attempt("tok") { r -> tried += r.label; if (r.label == "mesh") error("connection refused") else "answer" }
        assertEquals(Outcome.Ok("answer", "public"), ok)
        assertEquals(listOf("mesh", "public"), tried)
        tried.clear()
        assertEquals(Outcome.Ok("m", "mesh"), both.attempt("tok") { r -> tried += r.label; "m" })
        assertEquals(listOf("mesh"), tried)
    }

    @Test fun whenEveryRouteFailsEveryReasonIsKept() {
        val down = both.attempt(null) { error("timed out") }
        assertEquals(Outcome.Down(listOf("public route needs the fleet sign-in of Cloud Account", "mesh: timed out")), down)
        val nameless = both.attempt("tok") { throw java.io.IOException() }
        assertEquals(Outcome.Down(listOf("mesh: IOException", "public: IOException")), nameless)
    }

    @Test fun theBakedDeclarationIsReadAndAGarbledOneIsNone() {
        val m = FleetEndpoint.parse("""[{"id":"a","mesh_url":"http://a:1","public_url":"https://a.example","policy":"mesh_only"},{"id":"b"}]""")
        assertEquals(FleetEndpoint("a", "http://a:1", "https://a.example", "mesh_only"), m["a"])
        assertEquals("policy defaults to mesh_first", FleetEndpoint("b", "", "", "mesh_first"), m["b"])
        assertTrue(FleetEndpoint.parse("not json").isEmpty())
    }
}
