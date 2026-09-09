package app.sterna.core.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapTest {
    @Test
    fun capabilityUrisAreStandard() {
        assertEquals("urn:ietf:params:jmap:core", Jmap.CORE_CAPABILITY)
        assertEquals("urn:ietf:params:jmap:mail", Jmap.MAIL_CAPABILITY)
    }

    @Test
    fun sessionUrlNormalisesInput() {
        val expected = "https://mail.example.com/.well-known/jmap"
        assertEquals(expected, Jmap.sessionUrlFor("mail.example.com"))
        assertEquals(expected, Jmap.sessionUrlFor("https://mail.example.com"))
        assertEquals(expected, Jmap.sessionUrlFor("https://mail.example.com/"))
        assertEquals(expected, Jmap.sessionUrlFor("mail.example.com/.well-known/jmap"))
    }

    @Test
    fun sessionUrlKeepsExplicitSessionUrl() {
        // A pasted session URL (Fastmail's documented endpoint) is used verbatim,
        // not suffixed with the well-known path (issue #54).
        assertEquals(
            "https://api.fastmail.com/jmap/session",
            Jmap.sessionUrlFor("https://api.fastmail.com/jmap/session"),
        )
        assertEquals(
            "https://api.fastmail.com/jmap/session",
            Jmap.sessionUrlFor("https://api.fastmail.com/jmap/session/"),
        )
    }

    @Test
    fun sessionUrlCandidatesForResolvedInputsAreThemselves() {
        assertEquals(
            listOf("https://mail.example.com/.well-known/jmap"),
            Jmap.sessionUrlCandidates("mail.example.com"),
        )
        assertEquals(
            listOf("https://api.fastmail.com/jmap/session"),
            Jmap.sessionUrlCandidates("https://api.fastmail.com/jmap/session"),
        )
        assertTrue(Jmap.sessionUrlCandidates("  ").isEmpty())
    }

    @Test
    fun sessionUrlCandidatesProbePathInputs() {
        // ".../jmap" (the reporter's second input, issue #54): try the session endpoint
        // beneath it, then the path's well-known, then the host root's well-known.
        assertEquals(
            listOf(
                "https://api.fastmail.com/jmap/session",
                "https://api.fastmail.com/jmap/.well-known/jmap",
                "https://api.fastmail.com/.well-known/jmap",
            ),
            Jmap.sessionUrlCandidates("https://api.fastmail.com/jmap"),
        )
    }

    @Test
    fun autodiscoverTriesDomainThenSubdomains() {
        assertEquals(
            listOf("example.com", "mail.example.com", "jmap.example.com", "api.example.com"),
            Jmap.autodiscoverHosts("alice@example.com"),
        )
        // Case and a trailing dot in the domain are normalised away.
        assertEquals(
            listOf("example.com", "mail.example.com", "jmap.example.com", "api.example.com"),
            Jmap.autodiscoverHosts("Alice@Example.com."),
        )
    }

    @Test
    fun oauthMetadataUrlNormalisesInput() {
        val expected = "https://mail.example.com/.well-known/oauth-authorization-server"
        assertEquals(expected, Jmap.oauthMetadataUrlFor("mail.example.com"))
        assertEquals(expected, Jmap.oauthMetadataUrlFor("https://mail.example.com/"))
        // A host given as its JMAP well-known URL still yields the OAuth metadata URL.
        assertEquals(expected, Jmap.oauthMetadataUrlFor("https://mail.example.com/.well-known/jmap"))
    }

    @Test
    fun oauthScopeRequestsJmapAndOfflineAccess() {
        assertTrue(Jmap.OAUTH_SCOPE.contains(Jmap.MAIL_CAPABILITY))
        assertTrue(Jmap.OAUTH_SCOPE.contains("offline_access"))
    }

    @Test
    fun autodiscoverRejectsMalformedAddresses() {
        assertTrue(Jmap.autodiscoverHosts("").isEmpty())
        assertTrue(Jmap.autodiscoverHosts("no-at-sign").isEmpty())
        assertTrue(Jmap.autodiscoverHosts("alice@").isEmpty())
        // A bare hostname with no dot is not a usable mail domain.
        assertTrue(Jmap.autodiscoverHosts("alice@localhost").isEmpty())
    }
}
