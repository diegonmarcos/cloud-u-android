package com.diegonmarcos.superapp.fleetconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1093 The fleet endpoint contract: both endpoints of a service come out of its one declaration, a service that
 * breaks a rule is dropped (never guessed at), a service with no public URL can only be mesh-only, and the document
 * Cloud Account publishes round-trips with the bearer optional and masked wherever it could be printed.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEndpointsContractTest {
    private val marker = "tok-SECRET-123456" // gitleaks:allow (a made-up test marker, not a credential)
    private val decl = """{"services":[
        {"id":"jmap","kind":"mail","mesh_dns":"stalwart.app","mesh_port":2443,"mesh_scheme":"https","public_domain":"jmap.example.org","policy":"mesh_first","health":"/.well-known/jmap"},
        {"id":"gitea","kind":"git","mesh_dns":"gitea.app","mesh_port":3002,"mesh_scheme":"http","public_domain":"git.example.org","policy":"mesh_first","health":"/api/healthz"},
        {"id":"me-api","kind":"data","mesh_dns":"cloud-me-api.app","mesh_port":8124,"mesh_scheme":"http","policy":"mesh_only","health":"/me/health"}]}"""

    @Test fun bothEndpointsAreDerivedFromTheDeclaration() {
        val s = FleetEndpointsContract.parseDecl(decl).associateBy { it.id }
        assertEquals(listOf("jmap", "gitea", "me-api"), FleetEndpointsContract.parseDecl(decl).map { it.id })
        assertEquals("https://stalwart.app:2443", s.getValue("jmap").meshUrl())
        assertEquals("https://jmap.example.org", s.getValue("jmap").publicUrl())
        assertEquals("http://gitea.app:3002", s.getValue("gitea").meshUrl())
        assertEquals("a mesh-only service has no public URL", "", s.getValue("me-api").publicUrl())
        assertFalse(s.getValue("me-api").hasPublic)
    }

    @Test fun theDefaultPortIsNotWrittenOut() {
        assertEquals("https://stalwart.app", FleetEndpointsContract.parseDecl(decl.replace("2443", "443")).first().meshUrl())
        assertEquals("http://gitea.app", FleetEndpointsContract.parseDecl(decl.replace("3002", "80")).first { it.id == "gitea" }.meshUrl())
    }

    @Test fun aDeclarationThatBreaksARuleIsDropped() {
        fun n(t: String) = FleetEndpointsContract.parseDecl(t).size
        assertEquals("a mesh name must be a .app name", 2, n(decl.replace("stalwart.app", "stalwart.example.org")))
        assertEquals("a public URL must be https: the bearer travels on it", 2, n(decl.replace("\"public_domain\":\"jmap.example.org\"", "\"public_domain\":\"jmap.example.org\",\"public_scheme\":\"http\"")))
        assertEquals("a policy outside the set", 2, n(decl.replace("\"mesh_first\",\"health\":\"/.well", "\"whatever\",\"health\":\"/.well")))
        assertEquals("a public domain is a host, not a URL", 2, n(decl.replace("jmap.example.org", "jmap.example.org/x")))
        assertEquals("a service with no public URL is mesh-only", 2, n(decl.replace("\"mesh_only\"", "\"mesh_first\"")))
        assertEquals("a health path starts with a slash", 2, n(decl.replace("/me/health", "me/health")))
        assertEquals("not JSON", 0, n("nope"))
        assertEquals("no services", 0, n("{}"))
    }

    @Test fun aServiceIdIsDeclaredOnce() {
        val twice = decl.replace("\"id\":\"gitea\"", "\"id\":\"jmap\"")
        assertEquals(listOf("jmap", "me-api"), FleetEndpointsContract.parseDecl(twice).map { it.id })
    }

    @Test fun aKindNarrowsTheDeclarationAndStaysADeclaration() {
        val mail = FleetEndpointsContract.declOfKind(decl, "mail")
        assertEquals(listOf("jmap"), FleetEndpointsContract.parseDecl(mail).map { it.id })
        assertEquals(listOf("jmap"), MailEndpointsContract.parseDecl(mail).map { it.id })
        assertEquals("", FleetEndpointsContract.declOfKind("nope", "mail"))
        assertTrue(FleetEndpointsContract.parseDecl(FleetEndpointsContract.declOfKind(decl, "absent")).isEmpty())
    }

    @Test fun theMailReaderSeesOnlyServicesWithAPublicUrl() {
        assertEquals(listOf("jmap", "gitea"), MailEndpointsContract.parseDecl(decl).map { it.id })
    }

    @Test fun theDocumentRoundTripsAndTheBearerIsOptional() {
        val e = FleetEndpointsContract.entries(FleetEndpointsContract.parseDecl(decl))
        val withToken = FleetEndpointsContract.parse(FleetEndpointsContract.toJson(e, marker))!!
        assertEquals(e, withToken.entries); assertEquals(marker, withToken.bearer)
        val without = FleetEndpointsContract.parse(FleetEndpointsContract.toJson(e, null))!!
        assertEquals("", without.bearer)
        assertEquals("", FleetEndpointsContract.parse(FleetEndpointsContract.toJson(e, "  "))!!.bearer)
        assertEquals("", withToken.entries.first { it.service == "me-api" }.public)
    }

    @Test fun theBearerNeverPrintsAndTheMaskHidesTheLength() {
        val p = FleetEndpointsContract.parse(FleetEndpointsContract.toJson(FleetEndpointsContract.entries(FleetEndpointsContract.parseDecl(decl)), marker))!!
        assertFalse(p.toString().contains(marker)); assertTrue(p.toString().contains(FleetEndpointsContract.MASK))
        assertEquals(FleetEndpointsContract.mask("a"), FleetEndpointsContract.mask("a-much-longer-secret"))
        assertEquals("", FleetEndpointsContract.mask(""))
        assertFalse("no entry carries the bearer", FleetEndpointsContract.toJson(FleetEndpointsContract.entries(FleetEndpointsContract.parseDecl(decl)), null).contains(marker))
    }

    @Test fun aDocumentOfAnotherVersionOrWithAnUnusableUrlIsRefusedOrDropped() {
        assertNull(FleetEndpointsContract.parse("""{"v":2,"services":[]}"""))
        assertNull(FleetEndpointsContract.parse("nope"))
        val p = FleetEndpointsContract.parse("""{"v":1,"services":[
            {"service":"a","mesh":"http://a.app:1","public":"http://insecure.example.org"},
            {"service":"b","mesh":"ftp://b.app","public":""},
            {"service":"c","mesh":"http://c.app:1","public":"https://c.example.org","policy":"x"}]}""")!!
        assertEquals(listOf("c"), p.entries.map { it.service })
        assertEquals("an unknown policy falls back to the default", FleetEndpointsContract.POLICY_MESH_FIRST, p.entries.single().policy)
    }

    @Test fun theHostIsTakenOutOfAnyServerSpelling() {
        assertEquals("jmap.example.org", FleetEndpointsContract.hostOf("https://Jmap.Example.org:8443/x"))
        assertEquals("jmap.example.org", FleetEndpointsContract.hostOf("jmap.example.org"))
    }

    @Test fun aServiceUnderAPathOfASharedHostCarriesItsBasePathAndTheRoutesItMayTake() {
        val d = """{"services":[{"id":"api","kind":"api","mesh_dns":"c3-public-api.app","mesh_port":8087,"mesh_scheme":"http",
            "public_domain":"api.example.org","public_path":"/pub/","route_paths":["/health","/analytics"],"policy":"mesh_first","health":"/health"}]}"""
        val s = FleetEndpointsContract.parseDecl(d).single()
        assertEquals("https://api.example.org/pub", s.publicUrl()); assertEquals("http://c3-public-api.app:8087", s.meshUrl())
        assertEquals(listOf("/health", "/analytics"), s.routePaths)
        val e = FleetEndpointsContract.entries(listOf(s))
        assertEquals(e, FleetEndpointsContract.parse(FleetEndpointsContract.toJson(e, null))!!.entries)
        assertEquals(listOf("/health", "/analytics"), e.single().only)
    }

    @Test fun aBadPathIsDroppedAndAMeshOnlyServiceHasNoPublicPath() {
        val d = """{"services":[{"id":"api","mesh_dns":"x.app","public_domain":"a.example.org","PATHS":1,"policy":"mesh_first"}]}"""
        fun n(extra: String) = FleetEndpointsContract.parseDecl(d.replace("\"PATHS\":1", extra)).size
        assertEquals(1, n("\"public_path\":\"/pub\""))
        assertEquals("a path starts with a slash", 0, n("\"public_path\":\"pub\""))
        assertEquals("no query in a path", 0, n("\"public_path\":\"/pub?x\""))
        assertEquals("an empty route path", 0, n("\"route_paths\":[\"\"]"))
        assertEquals("a route path starts with a slash", 0, n("\"route_paths\":[\"health\"]"))
        val meshOnly = """{"services":[{"id":"m","mesh_dns":"m.app","policy":"mesh_only","public_path":"/pub"}]}"""
        assertEquals("a path of a public host it does not have", 0, FleetEndpointsContract.parseDecl(meshOnly).size)
    }
}
