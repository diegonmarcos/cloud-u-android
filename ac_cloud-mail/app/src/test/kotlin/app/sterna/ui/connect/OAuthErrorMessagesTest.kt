package app.sterna.ui.connect

import app.sterna.R
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EXECUTES the sign-in failure decision, it does not read the code that holds it.
 */
class OAuthErrorMessagesTest {

    // ---- The witness in the other direction ----
    //
    // "Sign-in was declined or cancelled." is not a wrong sentence, it was a wrong DEFAULT. These
    // two cases are the ones where it is true, and they must keep it.

    @Test fun `access_denied still says the user declined`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_declined),
            oauthFailureSpec(error = "access_denied", description = "", aadstsCode = null),
        )
    }

    @Test fun `authorization_declined still says the user declined`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_declined),
            oauthFailureSpec(error = "authorization_declined", description = "", aadstsCode = null),
        )
    }

    @Test fun `expired_token says the request expired`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_expired),
            oauthFailureSpec(error = "expired_token", description = "", aadstsCode = null),
        )
    }

    @Test fun `an admin-consent refusal points at the admin, not at a code`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_admin_consent),
            oauthFailureSpec(
                error = "invalid_grant",
                description = "AADSTS650051: the application has not been approved.",
                aadstsCode = "AADSTS650051",
            ),
        )
    }

    @Test fun `a plain AADSTS code is shown as the code`() {
        val spec = oauthFailureSpec(
            error = "invalid_grant",
            description = "AADSTS70008: the provided grant has expired.",
            aadstsCode = "AADSTS70008",
        )
        assertEquals(R.string.connect_oauth_error_code, spec.resId)
        assertEquals("AADSTS70008", spec.arg)
    }

    // ---- The change itself: the server's own reason, and only the protocol's word for it ----

    @Test fun `invalid_client shows the protocol error and never the description`() {
        val chatty = "Client authentication failed: client_id 'sterna' is unknown to this tenant, " +
            "trace id 3f9a-BEARER-LOOKING-BLOB, timestamp 2026-08-12"
        val spec = oauthFailureSpec(error = "invalid_client", description = chatty, aadstsCode = null)

        assertEquals(R.string.connect_oauth_error_reason, spec.resId)
        assertEquals("invalid_client", spec.arg)
        assertNotEquals("the server's free text must not become the argument", chatty, spec.arg)
        assertFalse(
            "the argument carried a slice of the server's description: ${spec.arg}",
            spec.arg.orEmpty().contains("trace id"),
        )
        assertFalse(
            "the argument carried a slice of the server's description: ${spec.arg}",
            spec.arg.orEmpty().contains("BEARER-LOOKING-BLOB"),
        )
    }

    /**
     * The admin-consent sentence NAMES MICROSOFT. Until this screen started going through this
     */
    @Test fun `an administrator mentioned by a self-hosted server is not a Microsoft consent refusal`() {
        val spec = oauthFailureSpec(
            error = "invalid_client",
            description = "invalid_client, contact your administrator",
            aadstsCode = null,
        )
        assertNotEquals(
            "a server with no AADSTS code has nothing to do with Microsoft consent",
            R.string.connect_oauth_admin_consent, spec.resId,
        )
        assertEquals(R.string.connect_oauth_error_reason, spec.resId)
        assertEquals("invalid_client", spec.arg)
    }

    /**
     * `error` is `obj["error"]` off the wire: server text of arbitrary length and content, now
     */
    @Test fun `an error field that is not a protocol token is not shown`() {
        val junk = "Bearer eyJhbGciOiJIUzI1NiJ9.SECRET-PAYLOAD.sig " + "x".repeat(4000)
        val spec = oauthFailureSpec(error = junk, description = "", aadstsCode = null)

        assertEquals(OAuthMessage(R.string.connect_oauth_denied), spec)
        assertNull("nothing of the server's text may travel with the message", spec.arg)
        assertFalse(
            "the server's text reached the message: ${spec.arg}",
            spec.arg.orEmpty().contains("SECRET-PAYLOAD"),
        )
    }

    @Test fun `an http status with no protocol error is still named`() {
        val spec = oauthFailureSpec(error = "http_400", description = "", aadstsCode = null)
        assertEquals(R.string.connect_oauth_error_reason, spec.resId)
        assertEquals("http_400", spec.arg)
    }

    @Test fun `a transport failure says the server could not be reached`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_network),
            oauthFailureSpec(error = "network_error", description = "", aadstsCode = null),
        )
    }

    // ---- The fallback, and the fact that the caller chooses it ----

    @Test fun `no error at all falls back to the default`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_denied),
            oauthFailureSpec(error = "", description = "", aadstsCode = null),
        )
    }

    @Test fun `no error at all falls back to the fallback the caller passed`() {
        assertEquals(
            OAuthMessage(R.string.connect_oauth_failed),
            oauthFailureSpec(
                error = "",
                description = "",
                aadstsCode = null,
                fallbackRes = R.string.connect_oauth_failed,
            ),
        )
    }

    // ---- A wait that ran out: WHICH END went quiet (#55) ----

    @Test fun `a deadline reached on answered polls is an expiry`() {
        assertEquals(
            "⛔ Every poll of that wait was answered and the code simply expired: \"The sign-in " +
                "request expired. Try again.\" is the true sentence, and telling this reader to " +
                "check her connection points at the one thing that was working.",
            R.string.connect_oauth_expired, oauthRanOutMessage(everReachedAServer = true),
        )
    }

    @Test fun `a deadline reached without ever reaching a server names the connection`() {
        assertEquals(
            "⛔ #55: nothing answered for the whole life of the device code, so \"expired… try " +
                "again\" sends her back to a server she never spoke to for another thirty minutes. " +
                "The state on screen must be true about what she can act on — her connection.",
            R.string.connect_oauth_network, oauthRanOutMessage(everReachedAServer = false),
        )
    }

    // ---- Discovery: "no OAuth here" and "OAuth, but not this flow" are different facts ----

    @Test fun `no metadata means the server has no OAuth`() {
        assertEquals(R.string.connect_oauth_unsupported, oauthDiscoveryFailure(null))
    }

    /**
     * The fixture lost its authorization endpoint on purpose (#55): a server that publishes one
     */
    @Test fun `metadata with no usable grant means the flow, not OAuth, is missing`() {
        val advertised = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = null,
            tokenEndpoint = "https://idp.example/token",
            deviceAuthorizationEndpoint = null,
        )
        assertEquals(R.string.connect_oauth_flow_unsupported, oauthDiscoveryFailure(advertised))
    }

    /** The point of the change: today both cases render the same sentence, and one of them lies. */
    @Test fun `a server with no OAuth and a server with no device flow are told apart`() {
        val advertised = OAuthMetadata(
            issuer = "https://idp.example",
            tokenEndpoint = "https://idp.example/token",
            deviceAuthorizationEndpoint = null,
        )
        assertNotEquals(
            "a server that advertises OAuth must not be told it has none",
            oauthDiscoveryFailure(null),
            oauthDiscoveryFailure(advertised),
        )
    }

    // ---- Which of the candidate hosts the sign-in goes to ----
    //
    // `Jmap.autodiscoverHosts` tries <domain>, mail.<domain>, jmap.<domain>, api.<domain> — the
    // bare domain FIRST, i.e. the organisation's website. Taking the first host that answers at

    private fun meta(host: String, deviceFlow: Boolean, codeFlow: Boolean = false) = OAuthMetadata(
        issuer = "https://$host",
        authorizationEndpoint = if (codeFlow) "https://$host/authorize" else null,
        tokenEndpoint = "https://$host/token",
        deviceAuthorizationEndpoint = if (deviceFlow) "https://$host/device" else null,
    )

    /** The user the fix broke: the website advertises OAuth, the mail server has the flow. */
    @Test fun `the site answering first does not beat the mail server that has the flow`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false),
            "mail.example.org" to meta("mail.example.org", deviceFlow = true),
        )
        assertEquals("mail.example.org", chooseOAuthHost(discovered)?.first)
    }

    @Test fun `the chosen host is the one with the device flow, not the first one`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false),
            "mail.example.org" to meta("mail.example.org", deviceFlow = true),
            "jmap.example.org" to meta("jmap.example.org", deviceFlow = true),
        )
        val chosen = chooseOAuthHost(discovered)
        assertEquals("mail.example.org", chosen?.first)
        assertEquals("https://mail.example.org/device", chosen?.second?.deviceAuthorizationEndpoint)
    }

    @Test fun `when no candidate speaks the device flow the first responder is kept`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false),
            "mail.example.org" to meta("mail.example.org", deviceFlow = false),
        )
        // Kept so the message can say "OAuth, but not this flow" instead of "no OAuth here".
        assertEquals("example.org", chooseOAuthHost(discovered)?.first)
        assertEquals(
            R.string.connect_oauth_flow_unsupported,
            oauthDiscoveryFailure(chooseOAuthHost(discovered)?.second),
        )
    }

    @Test fun `nothing answered at all is no host`() {
        assertNull(chooseOAuthHost(emptyList()))
    }

    @Test fun `a usable server is no failure at all`() {
        val usable = OAuthMetadata(
            issuer = "https://idp.example",
            tokenEndpoint = "https://idp.example/token",
            deviceAuthorizationEndpoint = "https://idp.example/device",
        )
        assertNull(oauthDiscoveryFailure(usable))
    }

    // ---- WHICH grant is driven (#55) ----
    //
    // The authorization-code grant is the fallback, not the new default. It leaves the app for a
    // browser and comes back through an intent; the device flow does not leave at all, it is what
    // every account signed in so far uses, and it stays first whenever a server offers both.

    /** The witness of non-regression: inverting the two arms of chooseOAuthGrant reddens here. */
    @Test fun `a server that speaks both grants keeps the device flow`() {
        assertEquals(
            OAuthGrant.DEVICE_CODE,
            chooseOAuthGrant(meta("mail.example.org", deviceFlow = true, codeFlow = true)),
        )
    }

    @Test fun `a server with only a device endpoint drives the device flow`() {
        assertEquals(OAuthGrant.DEVICE_CODE, chooseOAuthGrant(meta("mail.example.org", deviceFlow = true)))
    }

    /** The change itself: this server used to be told Sterna could not use its OAuth. */
    @Test fun `a server with only an authorization endpoint drives the code grant`() {
        assertEquals(
            OAuthGrant.AUTHORIZATION_CODE,
            chooseOAuthGrant(meta("mail.example.org", deviceFlow = false, codeFlow = true)),
        )
    }

    @Test fun `a server with neither endpoint drives nothing`() {
        assertEquals(OAuthGrant.NONE, chooseOAuthGrant(meta("mail.example.org", deviceFlow = false)))
    }

    /** A token endpoint is half of both grants: an authorization endpoint alone is not a flow. */
    @Test fun `an authorization endpoint without a token endpoint is not a code grant`() {
        val halfway = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = "https://idp.example/authorize",
            tokenEndpoint = "",
        )
        assertEquals(OAuthGrant.NONE, chooseOAuthGrant(halfway))
    }

    /** Ranking across hosts, where the two grants live on different servers. */
    @Test fun `the code grant does not beat the device flow on another host`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false, codeFlow = true),
            "mail.example.org" to meta("mail.example.org", deviceFlow = true),
        )
        assertEquals("mail.example.org", chooseOAuthHost(discovered)?.first)
    }

    @Test fun `a code-only host beats a host with no usable grant at all`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false),
            "mail.example.org" to meta("mail.example.org", deviceFlow = false, codeFlow = true),
        )
        assertEquals("mail.example.org", chooseOAuthHost(discovered)?.first)
        assertNull(oauthDiscoveryFailure(chooseOAuthHost(discovered)?.second))
    }

    /** Order still decides between equals: the bare domain answered first and has the grant. */
    @Test fun `between two code-only hosts the first responder is kept`() {
        val discovered = listOf(
            "example.org" to meta("example.org", deviceFlow = false, codeFlow = true),
            "mail.example.org" to meta("mail.example.org", deviceFlow = false, codeFlow = true),
        )
        assertEquals("example.org", chooseOAuthHost(discovered)?.first)
    }

    @Test fun `a code-only server is no discovery failure any more`() {
        assertNull(oauthDiscoveryFailure(meta("mail.example.org", deviceFlow = false, codeFlow = true)))
    }

    // ---- WHICH hosts are probed, and which answer ends the search ----
    //
    // The list `chooseOAuthHost` ranks is not given: it is what the probe loop collected, and the
    // loop can make its promise unreachable. `Jmap.autodiscoverHosts` asks the BARE DOMAIN first.

    /** The regression this pins: the website has an authorization endpoint, the mail server has
     *  the device flow, and stopping at the website sends a working sign-in off to a browser. */
    @Test fun `a website publishing an authorization endpoint does not end the search`() = runBlocking {
        val asked = mutableListOf<String>()
        val discovered = collectOAuthHosts(
            listOf("example.org", "mail.example.org", "jmap.example.org"),
        ) { host ->
            asked += host
            when (host) {
                "example.org" -> meta("example.org", deviceFlow = false, codeFlow = true)
                "mail.example.org" -> meta("mail.example.org", deviceFlow = true)
                else -> null
            }
        }
        assertEquals(
            "the mail server must be asked even though the bare domain already answered",
            listOf("example.org", "mail.example.org"), asked,
        )
        assertEquals("mail.example.org", chooseOAuthHost(discovered)?.first)
        assertEquals(OAuthGrant.DEVICE_CODE, chooseOAuthGrant(chooseOAuthHost(discovered)!!.second))
    }

    @Test fun `a device flow ends the search`() = runBlocking {
        val asked = mutableListOf<String>()
        collectOAuthHosts(listOf("mail.example.org", "jmap.example.org", "api.example.org")) { host ->
            asked += host
            meta(host, deviceFlow = true)
        }
        assertEquals(listOf("mail.example.org"), asked)
    }

    @Test fun `with no device flow anywhere every candidate is asked`() = runBlocking {
        val asked = mutableListOf<String>()
        val discovered = collectOAuthHosts(listOf("example.org", "mail.example.org")) { host ->
            asked += host
            meta(host, deviceFlow = false, codeFlow = host == "mail.example.org")
        }
        assertEquals(listOf("example.org", "mail.example.org"), asked)
        // Only NOW does the code grant's rank matter, and it picks the host that has it.
        assertEquals("mail.example.org", chooseOAuthHost(discovered)?.first)
    }

    @Test fun `a host that answers nothing is skipped, not recorded`() = runBlocking {
        val discovered = collectOAuthHosts(listOf("example.org", "mail.example.org")) { host ->
            if (host == "example.org") null else meta(host, deviceFlow = true)
        }
        assertEquals(listOf("mail.example.org"), discovered.map { it.first })
    }

    @Test fun `a host that fails outright does not end the search`() = runBlocking {
        val discovered = collectOAuthHosts(listOf("example.org", "mail.example.org")) { host ->
            if (host == "example.org") error("connection refused") else meta(host, deviceFlow = true)
        }
        assertEquals(listOf("mail.example.org"), discovered.map { it.first })
    }

    @Test fun `a server with OAuth but neither grant is still the flow sentence`() {
        assertEquals(
            R.string.connect_oauth_flow_unsupported,
            oauthDiscoveryFailure(meta("mail.example.org", deviceFlow = false)),
        )
    }

    // ---- Discovery that was REFUSED BY US, not by the server ----
    //
    // `OAuthClient.discoverMetadata` blanks an endpoint a document names off the queried host and
    // off the address domain, and hands the fact back as `endpointsOffDomain`. A document blanked
    // down to nothing then looks EXACTLY like a server with no usable grant, and used to be told
    // "not in a way Sterna can use yet" — the server's OAuth is fine, the refusal is ours.
    //
    // These EXECUTE `oauthDiscoveryFailure` on metadata built by hand and pin the resource it
    // returns; nothing here recomputes the rule, so inverting the order of the two `NONE` arms,
    // or dropping the `&& NONE` conjunction, has to redden.

    /** Built the way `discoverMetadata` returns it: every endpoint refused, so the token
     *  endpoint is BLANK (not null) and the two others are null, with the fact carried alongside. */
    @Test fun `a document blanked down to nothing says the sign-in was handed elsewhere`() {
        val blanked = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = null,
            tokenEndpoint = "",
            deviceAuthorizationEndpoint = null,
            endpointsOffDomain = true,
        )
        assertEquals(R.string.connect_oauth_elsewhere, oauthDiscoveryFailure(blanked))
    }

    /**
     * The witness that keeps the new branch from eating the old one: nothing was refused here,
     */
    @Test fun `a server with no usable grant and nothing refused keeps the flow sentence`() {
        val advertised = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = null,
            tokenEndpoint = "https://idp.example/token",
            deviceAuthorizationEndpoint = null,
            endpointsOffDomain = false,
        )
        assertEquals(R.string.connect_oauth_flow_unsupported, oauthDiscoveryFailure(advertised))
    }

    /** And the same with no endpoint at all: still the server's shortcoming, still that sentence. */
    @Test fun `a document with no endpoint at all and nothing refused keeps the flow sentence`() {
        val empty = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = null,
            tokenEndpoint = "",
            deviceAuthorizationEndpoint = null,
            endpointsOffDomain = false,
        )
        assertEquals(R.string.connect_oauth_flow_unsupported, oauthDiscoveryFailure(empty))
    }

    /**
     * THE WITNESS OF THE CONJUNCTION. Only the device endpoint was off-domain and blanked; the
     */
    @Test fun `a partial refusal that still leaves a usable grant is no failure`() {
        val partly = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = "https://mail.example.org/authorize",
            tokenEndpoint = "https://mail.example.org/token",
            deviceAuthorizationEndpoint = null,
            endpointsOffDomain = true,
        )
        assertEquals(OAuthGrant.AUTHORIZATION_CODE, chooseOAuthGrant(partly))
        assertNull(oauthDiscoveryFailure(partly))
    }

    /** Same, with the device flow itself intact: a blanked authorization endpoint changes nothing. */
    @Test fun `a partial refusal that still leaves the device flow is no failure`() {
        val partly = OAuthMetadata(
            issuer = "https://idp.example",
            authorizationEndpoint = null,
            tokenEndpoint = "https://mail.example.org/token",
            deviceAuthorizationEndpoint = "https://mail.example.org/device",
            endpointsOffDomain = true,
        )
        assertNull(oauthDiscoveryFailure(partly))
    }

    /**
     * THE WITNESS OF THE SHAPE, and it is the one the suite was missing. The token endpoint is
     */
    @Test fun `a token endpoint that survives still says elsewhere when nothing can be driven`() {
        val handed = OAuthMetadata(
            issuer = "https://mail.example.org",
            authorizationEndpoint = null,
            tokenEndpoint = "https://mail.example.org/token",
            deviceAuthorizationEndpoint = null,
            endpointsOffDomain = true,
        )
        assertEquals(OAuthGrant.NONE, chooseOAuthGrant(handed))
        assertEquals(R.string.connect_oauth_elsewhere, oauthDiscoveryFailure(handed))
    }

    /** No metadata at all stays "this server has no OAuth" — see also
     *  `no metadata means the server has no OAuth`; the elsewhere branch must not reach it. */
    @Test fun `nothing discovered is still no OAuth, not a handover`() {
        assertNotEquals(R.string.connect_oauth_elsewhere, oauthDiscoveryFailure(null))
        assertEquals(R.string.connect_oauth_unsupported, oauthDiscoveryFailure(null))
    }
}
