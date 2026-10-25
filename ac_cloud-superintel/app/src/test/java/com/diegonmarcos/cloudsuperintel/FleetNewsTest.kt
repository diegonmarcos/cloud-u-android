package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetNewsTest {
    private val mesh = "http://news-gdelt.mesh.example:3019"
    private val pub = "https://news-gdelt.example"

    @Test fun meshFirstThenPublicWithTheBearer() {
        val r = FleetNews.routes(mesh, pub, "tok")
        assertEquals(listOf(mesh, pub), r.map { it.first })
        assertTrue(r[0].second.isEmpty())
        assertEquals("Bearer tok", r[1].second["Authorization"])
    }

    @Test fun noSignInSkipsThePublicRouteAndNoBlankBaseIsTried() {
        assertEquals(listOf(mesh), FleetNews.routes(mesh, pub, null).map { it.first })
        assertEquals(listOf(pub), FleetNews.routes("", pub, "tok").map { it.first })
        assertTrue(FleetNews.routes("", "", "tok").isEmpty())
    }

    @Test fun fallsBackToPublicWhenTheMeshThrowsOrRefuses() {
        val routes = FleetNews.routes(mesh, pub, "tok")
        val seen = ArrayList<String>()
        val r = FleetNews.first(routes) { base, _ -> seen += base; if (base == mesh) throw java.io.IOException("unreachable") else AiHttp.Response(200, "ok") }
        assertEquals(listOf(mesh, pub), seen)
        assertEquals("ok", r.body)
        assertEquals(502, FleetNews.first(routes) { _, _ -> AiHttp.Response(502, "") }.code)
    }

    @Test fun noRouteIsAnHonestError() {
        val e = runCatching { FleetNews.first(emptyList()) { _, _ -> AiHttp.Response(200, "") } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }
}
