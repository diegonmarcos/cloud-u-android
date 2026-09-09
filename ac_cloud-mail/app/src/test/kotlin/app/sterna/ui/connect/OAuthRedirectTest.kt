package app.sterna.ui.connect

import app.sterna.core.jmap.OAuthMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXECUTES the browser round-trip's decisions; it reads no source file.
 */
class OAuthRedirectTest {

    private val metadata = OAuthMetadata(
        issuer = "https://mail.example.com",
        authorizationEndpoint = "https://mail.example.com/authorize",
        tokenEndpoint = "https://mail.example.com/token",
    )

    private fun request(state: String = "STATE-1", verifier: String = "VERIFIER-1") =
        PendingAuthorization(
            state = state,
            codeVerifier = verifier,
            host = "mail.example.com",
            metadata = metadata,
            email = "user@example.com",
            accountName = "Work",
        )

    // ---- The redirect URI follows the applicationId, it is not a literal ----

    @Test fun `the redirect uri is the application id plus the oauth host`() {
        assertEquals("app.sterna://oauth", oauthRedirectUri("app.sterna"))
    }

    /**
     * The mutation this kills: `"app.sterna://oauth"` written as a constant. The test build is
     */
    @Test fun `the redirect uri follows the package it is asked about`() {
        assertEquals("app.sterna.test://oauth", oauthRedirectUri("app.sterna.test"))
        assertEquals("org.example.fork://oauth", oauthRedirectUri("org.example.fork"))
        assertNotEquals(oauthRedirectUri("app.sterna"), oauthRedirectUri("app.sterna.test"))
    }

    @Test fun `a redirect belongs to us only by our own scheme and host`() {
        assertTrue(isOAuthRedirect("app.sterna.test", "oauth", "app.sterna.test"))
        assertFalse(
            "the test build must not answer for the production scheme",
            isOAuthRedirect("app.sterna", "oauth", "app.sterna.test"),
        )
        assertFalse(isOAuthRedirect("app.sterna", "callback", "app.sterna"))
        assertFalse(isOAuthRedirect("https", "oauth", "app.sterna"))
        assertFalse(isOAuthRedirect("mailto", null, "app.sterna"))
        assertFalse(isOAuthRedirect(null, null, "app.sterna"))
    }

    // ---- The state check, before anything else is read ----

    @Test fun `a state that is not the one we drew exchanges nothing`() {
        assertEquals(
            RedirectVerdict.StateMismatch,
            redirectVerdict(request(), state = "SOMEONE-ELSES-STATE", code = "CODE-42", error = null),
        )
    }

    @Test fun `a redirect carrying no state at all exchanges nothing`() {
        assertEquals(RedirectVerdict.StateMismatch, redirectVerdict(request(), null, "CODE-42", null))
        assertEquals(RedirectVerdict.StateMismatch, redirectVerdict(request(), "", "CODE-42", null))
    }

    /** The order IS the guard: a forged redirect must not even get its `error` rendered. */
    @Test fun `the state is checked before the error field is read`() {
        assertEquals(
            RedirectVerdict.StateMismatch,
            redirectVerdict(request(), state = "FORGED", code = null, error = "access_denied"),
        )
    }

    @Test fun `with nothing in flight there is no exchange`() {
        assertEquals(RedirectVerdict.NoPendingRequest, redirectVerdict(null, "STATE-1", "CODE-42", null))
    }

    @Test fun `our own answer is exchanged with the verifier we drew`() {
        val pending = request(state = "STATE-1", verifier = "VERIFIER-1")
        val verdict = redirectVerdict(pending, "STATE-1", "CODE-42", null)
        assertEquals(RedirectVerdict.Exchange("CODE-42", pending), verdict)
        // The arguments, not just the shape: the code that travels and the verifier that redeems
        // it are the two values the token endpoint is called with.
        val exchange = verdict as RedirectVerdict.Exchange
        assertEquals("CODE-42", exchange.code)
        assertEquals("VERIFIER-1", exchange.request.codeVerifier)
        assertEquals("mail.example.com", exchange.request.host)
        assertEquals("user@example.com", exchange.request.email)
    }

    @Test fun `a refusal is a message, not an exchange`() {
        assertEquals(
            RedirectVerdict.Refused("access_denied"),
            redirectVerdict(request(), "STATE-1", code = null, error = "access_denied"),
        )
    }

    @Test fun `an error wins over a code on our own sign-in`() {
        assertEquals(
            RedirectVerdict.Refused("invalid_scope"),
            redirectVerdict(request(), "STATE-1", code = "CODE-42", error = "invalid_scope"),
        )
    }

    @Test fun `neither a code nor an error is nothing usable`() {
        assertEquals(RedirectVerdict.NothingUsable, redirectVerdict(request(), "STATE-1", null, null))
        assertEquals(RedirectVerdict.NothingUsable, redirectVerdict(request(), "STATE-1", "", ""))
    }

    /** No exchange without PKCE, made unconstructible rather than merely avoided. */
    @Test fun `an authorization request cannot exist without a code verifier`() {
        assertThrows(IllegalArgumentException::class.java) { request(verifier = "") }
        assertThrows(IllegalArgumentException::class.java) { request(state = "") }
    }

    // ---- What is POSTed, and where each of the four values comes from ----

    /**
     * The verifier is the one this request drew. A freshly drawn one is non-blank, survives
     */
    @Test fun `the exchange carries the verifier of the request being redeemed`() {
        val verdict = redirectVerdict(request(state = "STATE-1", verifier = "VERIFIER-1"), "STATE-1", "CODE-42", null)
        assertEquals(
            TokenExchange(
                metadata = metadata,
                code = "CODE-42",
                redirectUri = "app.sterna://oauth",
                codeVerifier = "VERIFIER-1",
            ),
            tokenExchangeFor(verdict as RedirectVerdict.Exchange, "app.sterna://oauth"),
        )
    }

    /** Nothing is drawn at exchange time: the same verdict answers the same thing twice. */
    @Test fun `the exchange is derived, never re-drawn`() {
        val verdict = redirectVerdict(request(), "STATE-1", "CODE-42", null) as RedirectVerdict.Exchange
        assertEquals(
            tokenExchangeFor(verdict, "app.sterna.test://oauth"),
            tokenExchangeFor(verdict, "app.sterna.test://oauth"),
        )
    }

    /** The redirect URI posted is the one the browser was sent to — the server compares them. */
    @Test fun `the exchange posts the redirect uri it was given`() {
        val verdict = redirectVerdict(request(), "STATE-1", "CODE-42", null) as RedirectVerdict.Exchange
        assertEquals(
            "app.sterna.test://oauth",
            tokenExchangeFor(verdict, oauthRedirectUri("app.sterna.test")).redirectUri,
        )
    }

    // ---- One redirect, one exchange ----

    @Test fun `the same return twice exchanges once`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        assertTrue(slot.redeem("STATE-1", "CODE-42", null) is RedirectVerdict.Exchange)
        assertEquals(
            "an authorization code is redeemable once; a replayed redirect must find nothing",
            RedirectVerdict.NoPendingRequest,
            slot.redeem("STATE-1", "CODE-42", null),
        )
        assertFalse(slot.isArmed())
    }

    /** A forged redirect must not be able to cancel the sign-in the user is actually doing. */
    @Test fun `a foreign return does not consume the real request`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        assertEquals(RedirectVerdict.StateMismatch, slot.redeem("FORGED", "ATTACKER-CODE", null))
        assertTrue(slot.isArmed())
        val verdict = slot.redeem("STATE-1", "CODE-42", null)
        assertEquals("CODE-42", (verdict as RedirectVerdict.Exchange).code)
    }

    @Test fun `a refusal consumes the request too`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        assertEquals(RedirectVerdict.Refused("access_denied"), slot.redeem("STATE-1", null, "access_denied"))
        assertEquals(RedirectVerdict.NoPendingRequest, slot.redeem("STATE-1", "CODE-42", null))
    }

    @Test fun `cancelling leaves nothing a late redirect could exchange`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        slot.disarm()
        assertFalse(slot.isArmed())
        assertEquals(RedirectVerdict.NoPendingRequest, slot.redeem("STATE-1", "CODE-42", null))
    }

    @Test fun `a fresh slot has nothing in flight`() {
        val slot = AuthorizationRequestSlot()
        assertFalse(slot.isArmed())
        assertEquals(RedirectVerdict.NoPendingRequest, slot.redeem("STATE-1", "CODE-42", null))
    }

    // ---- The state itself ----

    @Test fun `two states drawn in a row differ and are url-safe`() {
        val first = newOAuthState()
        val second = newOAuthState()
        assertNotEquals("a state that repeats matches somebody else's redirect", first, second)
        assertTrue("not URL-safe: $first", URL_SAFE.matches(first))
        assertTrue("not URL-safe: $second", URL_SAFE.matches(second))
    }

    private companion object {
        /** RFC 3986 `unreserved`, and long enough that guessing is not a strategy. */
        val URL_SAFE = Regex("""^[A-Za-z0-9._~-]{43,}$""")
    }
}
