package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1090 the mail endpoint contract: both endpoints come out of the one declaration, only fleet accounts get
 * any, a declaration that breaks a rule is dropped rather than guessed at, and no marker survives a toString,
 * a mask or an entry.
 */
class MailEndpointsContractTest {
    private val decl = """{"services":[{"id":"jmap","mesh_dns":"stalwart.app","mesh_port":2443,"mesh_scheme":"https",
        "public_domain":"jmap.example.org","public_scheme":"https","policy":"mesh_first"}]}"""
    private val marker = "tok-SECRET-123456"

    @Test fun bothEndpointsAreDerivedFromTheDeclaration() {
        val s = MailEndpointsContract.parseDecl(decl).single()
        assertEquals("https://stalwart.app:2443", s.meshUrl())
        assertEquals("https://jmap.example.org", s.publicUrl())
    }

    @Test fun theDefaultPortIsNotWrittenOut() {
        val s = MailEndpointsContract.parseDecl(decl.replace("2443", "443")).single()
        assertEquals("https://stalwart.app", s.meshUrl())
    }

    @Test fun aDeclarationThatBreaksARuleIsDropped() {
        fun n(t: String) = MailEndpointsContract.parseDecl(t).size
        assertEquals("a mesh name must be a .app name", 0, n(decl.replace("stalwart.app", "stalwart.example.org")))
        assertEquals("the public URL must be https: the bearer travels on it", 0, n(decl.replace("\"public_scheme\":\"https\"", "\"public_scheme\":\"http\"")))
        assertEquals("a policy outside the set", 0, n(decl.replace("mesh_first", "whatever")))
        assertEquals("a public domain is a host, not a URL", 0, n(decl.replace("jmap.example.org", "jmap.example.org/x")))
        assertEquals("not JSON", 0, n("nope"))
    }

    @Test fun onlyAccountsOnADeclaredServiceAreFleetAccounts() {
        val services = MailEndpointsContract.parseDecl(decl)
        val e = MailEndpointsContract.entries(services, listOf(
            "Me@Example.org" to "jmap.example.org",
            "me@example.org" to "https://jmap.example.org/",
            "plain@gmail.example" to "imap.gmail.example",
            "other@example.org" to "",
        ))
        assertEquals(listOf("me@example.org"), e.map { it.email })
        assertEquals("https://stalwart.app:2443", e.single().mesh)
        assertEquals("https://jmap.example.org", e.single().public)
    }

    @Test fun theDocumentRoundTripsAndTheBearerIsOptional() {
        val e = MailEndpointsContract.entries(MailEndpointsContract.parseDecl(decl), listOf("me@example.org" to "jmap.example.org"))
        val withToken = MailEndpointsContract.parse(MailEndpointsContract.toJson(e, marker))!!
        assertEquals(e, withToken.entries); assertEquals(marker, withToken.bearer)
        val without = MailEndpointsContract.parse(MailEndpointsContract.toJson(e, null))!!
        assertEquals("", without.bearer)
        assertEquals("", MailEndpointsContract.parse(MailEndpointsContract.toJson(e, "  "))!!.bearer)
    }

    @Test fun anEntryWithAnUnusablePublicUrlIsDropped() {
        val doc = """{"v":1,"accounts":[{"email":"a@x.org","service":"j","mesh":"https://m.app","public":"http://plain.example"},
            {"email":"b@x.org","service":"j","mesh":"https://m.app","public":"https://ok.example","policy":"bogus"}]}"""
        val p = MailEndpointsContract.parse(doc)!!
        assertEquals(listOf("b@x.org"), p.entries.map { it.email })
        assertEquals("an unknown policy falls back to mesh first", "mesh_first", p.entries.single().policy)
        assertNull("another version is not read", MailEndpointsContract.parse("""{"v":2,"accounts":[]}"""))
    }

    @Test fun noSecretSurvivesToStringOrAMask() {
        val p = MailEndpointsContract.Published(emptyList(), marker)
        assertFalse(p.toString().contains(marker)); assertTrue(p.toString().contains(MailEndpointsContract.MASK))
        assertEquals("the mask does not reveal the length", MailEndpointsContract.mask("a"), MailEndpointsContract.mask(marker))
        assertEquals("", MailEndpointsContract.mask(""))
        val e = MailEndpointsContract.entries(MailEndpointsContract.parseDecl(decl), listOf("me@example.org" to "jmap.example.org"))
        assertFalse("an entry carries no bearer at all", MailEndpointsContract.toJson(e, null).contains("bearer"))
    }

    @Test fun theHostIsTakenOutOfAnyServerSpelling() {
        assertEquals("jmap.example.org", MailEndpointsContract.hostOf("https://JMAP.example.org:2443/jmap/session"))
        assertEquals("jmap.example.org", MailEndpointsContract.hostOf("jmap.example.org"))
    }
}
