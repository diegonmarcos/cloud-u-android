package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [oauthServerToStore]: which host an OAuth sign-in writes into the account.
 */
class OAuthServerToStoreTest {

    /** The whole point: a resolution that succeeded outranks the host the tokens came from. */
    @Test fun `a resolved server is what gets stored, not the OAuth document's host`() {
        assertEquals(
            "masto.top",
            oauthServerToStore(MailRepository.DiscoveryResult.Found("masto.top"), "mail.example.com"),
        )
    }

    /**
     * And a silent resolution must not cost the user the account. Autodiscovery can answer
     */
    @Test fun `nothing found falls back on the host the tokens came from`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.NotFound, "mail.example.com"),
        )
    }

    /**
     * A 401/403 from a candidate says nothing about the bearer token this add already validated —
     * the apex is often the organisation's website. Falling back, never failing.
     */
    @Test fun `a credentials rejection during discovery falls back too`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.BadCredentials, "mail.example.com"),
        )
    }

    /**
     * A blank winner is not a host. `Found("")` stored as the server yields an account whose
     */
    @Test fun `a blank resolved server is not a host, and falls back`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.Found(""), "mail.example.com"),
        )
    }
}
