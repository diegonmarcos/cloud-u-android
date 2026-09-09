package app.sterna.ui.connect

import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OAuth as a RESULT rather than a choice: what the credentials step asks for once the server has
 */
class OAuthHandoverDecisionTest {

    private val email = "user@example.org"

    private val deviceFlow = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        deviceAuthorizationEndpoint = "https://example.org/device",
    )
    private val codeFlow = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        authorizationEndpoint = "https://example.org/authorize",
    )
    private val bothFlows = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        authorizationEndpoint = "https://example.org/authorize",
        deviceAuthorizationEndpoint = "https://example.org/device",
    )
    /** OAuth advertised, and no grant this app can drive — a real case (`connect_oauth_flow_unsupported`). */
    private val noUsableGrant = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
    )

    // ---- credentialsAsk: what step 2 asks for ----

    @Test fun `a device-flow server is signed in away from the app, not asked for a password`() {
        assertEquals(
            "THE case this volet exists for: the server said it signs in through a browser, so " +
                "the step must hand over — never show a password field the server would ignore.",
            CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
            credentialsAsk(OAuthDiscovery(email, "mail.example.org" to deviceFlow), email),
        )
    }

    @Test fun `a code-grant server hands over too`() {
        assertEquals(
            "a server with no device endpoint is signed in by the authorization code grant (#55) " +
                "— still a hand-over, still no password field.",
            CredentialsAsk.SignInAway(OAuthGrant.AUTHORIZATION_CODE),
            credentialsAsk(OAuthDiscovery(email, "mail.example.org" to codeFlow), email),
        )
    }

    @Test fun `a server offering both keeps the device flow`() {
        assertEquals(
            "WHICH grant stays chooseOAuthGrant's call, and it prefers the device flow: that is " +
                "the shipped production path and it never leaves the app. Deciding it again here " +
                "would be a second ranking nothing else can see.",
            CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
            credentialsAsk(OAuthDiscovery(email, "mail.example.org" to bothFlows), email),
        )
    }

    @Test fun `no OAuth anywhere - the password field, exactly as before`() {
        assertEquals(
            "the ordinary case, and the one that must not regress: nothing answered, so step 2 is " +
                "the password field it has always been.",
            CredentialsAsk.Password,
            credentialsAsk(OAuthDiscovery(email, null), email),
        )
    }

    @Test fun `OAuth advertised but not drivable - the password field, and no sentence about it`() {
        assertEquals(
            "a server offering only grants this app cannot drive still has a password door; the " +
                "one thing that must never happen here is telling her the server has no OAuth.",
            CredentialsAsk.Password,
            credentialsAsk(OAuthDiscovery(email, "example.org" to noUsableGrant), email),
        )
    }

    @Test fun `no answer yet asks for NOTHING - never for a password`() {
        assertEquals(
            "⛔ The whole point: a password typed and then dropped in silence because the server " +
                "wanted a browser is the defect. With no answer in hand the step asks for nothing.",
            CredentialsAsk.Awaiting,
            credentialsAsk(null, email),
        )
    }

    @Test fun `an answer about another address is no answer at all`() {
        assertEquals(
            "she walked back and typed a different address: the previous domain's OAuth answer " +
                "must not decide anything for this one.",
            CredentialsAsk.Awaiting,
            credentialsAsk(OAuthDiscovery("someone@autre.tld", "mail.autre.tld" to deviceFlow), email),
        )
    }

    @Test fun `the address is matched trimmed, like every other verdict on this screen`() {
        assertEquals(
            "a trailing space from a paste must not turn a hand-over into a password field.",
            CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
            credentialsAsk(OAuthDiscovery(email, "mail.example.org" to deviceFlow), "  $email  "),
        )
    }

    // ---- oauthOpensCredentials: the one spelling of "usable OAuth" the walk steps on ----

    @Test fun `a device-flow answer opens the credentials step`() {
        assertTrue(
            "⛔ #55: this is what lets a domain served over JMAP + OAuth and publishing no " +
                "Thunderbird autoconfig reach step 2 at all. False here and the address step " +
                "drops her on the manual form holding an answer it already has.",
            oauthOpensCredentials(OAuthDiscovery(email, "mail.example.org" to deviceFlow), email),
        )
    }

    @Test fun `a code-grant answer opens it too`() {
        assertTrue(
            "a server with no device endpoint is still signed in without a password; the walk " +
                "may not treat it as a failure.",
            oauthOpensCredentials(OAuthDiscovery(email, "mail.example.org" to codeFlow), email),
        )
    }

    @Test fun `nothing usable answered the search - the credentials step is not the way`() {
        assertFalse(
            "the ordinary domain: no OAuth anywhere, so the manual form is exactly where the " +
                "walk must still land.",
            oauthOpensCredentials(OAuthDiscovery(email, null), email),
        )
        assertFalse(
            "⛔ NOT `chosen != null`. A host advertising OAuth with no grant this app can drive " +
                "asks for a password (credentialsAsk) — and at a credentials step reached on this " +
                "answer ALONE there is no autoconfig verdict behind that field, so " +
                "shouldTryDiscoveredImap never fires and the button leads nowhere. True here and " +
                "the walk swaps the manual form for a dead end one step further away.",
            oauthOpensCredentials(OAuthDiscovery(email, "example.org" to noUsableGrant), email),
        )
        assertFalse(
            "no answer has come back yet: nothing to open anything with.",
            oauthOpensCredentials(null, email),
        )
    }

    @Test fun `an OAuth answer about another address opens nothing`() {
        assertFalse(
            "she walked back and typed a different domain: signing her in at the previous " +
                "domain's identity provider is somebody else's account.",
            oauthOpensCredentials(
                OAuthDiscovery("someone@autre.tld", "mail.autre.tld" to deviceFlow), email,
            ),
        )
    }

    @Test fun `the OAuth answer opens the step however the address is spaced`() {
        assertTrue(
            "the field's value reaches this with whatever spacing a paste left; the probe was " +
                "launched on the trimmed address, so the match trims too — and a trailing space " +
                "must not be what sends her to the manual form.",
            oauthOpensCredentials(
                OAuthDiscovery(email, "mail.example.org" to deviceFlow), "  $email ",
            ),
        )
    }

    // ---- shouldStartOAuthSignIn: the moved trigger ----

    @Test fun `the sign-in starts at the credentials step, on a hand-over and nowhere else`() {
        assertTrue(
            "the trigger this volet moves: at step 2, with the server asking for a browser, the " +
                "screen starts the sign-in itself.",
            shouldStartOAuthSignIn(
                ConnectStep.CREDENTIALS, CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
                handoverStarted = false,
            ),
        )
        assertTrue(
            "and on the code grant just the same.",
            shouldStartOAuthSignIn(
                ConnectStep.CREDENTIALS, CredentialsAsk.SignInAway(OAuthGrant.AUTHORIZATION_CODE),
                handoverStarted = false,
            ),
        )
        assertFalse(
            "⛔ never while she is still typing her address: the answer lands at the address step, " +
                "and launching a browser from under her hands there is not a walk, it is an ambush.",
            shouldStartOAuthSignIn(
                ConnectStep.ADDRESS, CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
                handoverStarted = false,
            ),
        )
        assertFalse(
            "⛔ nor on the manual fallback, which keeps its own explicit button.",
            shouldStartOAuthSignIn(
                ConnectStep.MANUAL, CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
                handoverStarted = false,
            ),
        )
        assertFalse(
            "a password server is signed in with a password: no browser.",
            shouldStartOAuthSignIn(ConnectStep.CREDENTIALS, CredentialsAsk.Password, handoverStarted = false),
        )
        assertFalse(
            "and nothing starts before the server has answered.",
            shouldStartOAuthSignIn(ConnectStep.CREDENTIALS, CredentialsAsk.Awaiting, handoverStarted = false),
        )
    }

    @Test fun `a hand-over already started for this address does not start itself again`() {
        assertFalse(
            "⛔ THE decision behind Cancel, and it is a decision, not an absence. Cancel puts the " +
                "attempt back to Idle and changes nothing else — same step, same server answer — " +
                "so an unconditional trigger reopens the browser she just refused, for ever. " +
                "Restarting is her gesture (the SIGN_IN_OFFER button), never the screen's.",
            shouldStartOAuthSignIn(
                ConnectStep.CREDENTIALS, CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE),
                handoverStarted = true,
            ),
        )
        assertFalse(
            "and the code grant leaves for a browser just as much: same rule.",
            shouldStartOAuthSignIn(
                ConnectStep.CREDENTIALS, CredentialsAsk.SignInAway(OAuthGrant.AUTHORIZATION_CODE),
                handoverStarted = true,
            ),
        )
    }

    // ---- handoverStarted: for WHICH address a hand-over was started ----

    @Test fun `a hand-over is started for an address, not for the screen`() {
        assertTrue(
            "the address it was started for, as typed: this is what stops the automatic restart.",
            handoverStarted(startedFor = email, email = email),
        )
        assertTrue(
            "trimmed on both sides, like every other address comparison on this walk.",
            handoverStarted(startedFor = email, email = "  $email  "),
        )
        assertFalse(
            "⛔ She walked back and typed ANOTHER address: nothing has been handed over for that " +
                "one, so its sign-in must start by itself like any other. Match on the screen " +
                "instead of the address and the second address is answered by an invitation the " +
                "first address earned.",
            handoverStarted(startedFor = "someone@autre.tld", email = email),
        )
        assertFalse(
            "and nothing started at all is not a hand-over.",
            handoverStarted(startedFor = NO_HANDOVER, email = email),
        )
        assertFalse(
            "⛔ nor is the empty field: NO_HANDOVER is the blank address, so a blank email must " +
                "not read as \"already handed over\".",
            handoverStarted(startedFor = NO_HANDOVER, email = "   "),
        )
    }

    @Test fun `nothing handed over is the blank address, by value`() {
        assertEquals(
            "⛔ ITS VALUE, not only its name — the same thing the counter-audit demanded of " +
                "NO_TYPED_SERVER, which is pinned by value one file away. Every test above reads " +
                "this constant by name and compares it against itself, so any other text is green " +
                "here. It is the blank address on purpose: it is what the screen's saved " +
                "\"handed over for\" starts at, and `handoverStarted` reads it as \"never\" only " +
                "because a blank address is one no reader can type — give it \"none\" and the day " +
                "someone's remembered address is that text, step 2 answers her with an invitation " +
                "for a sign-in it never started.",
            "", NO_HANDOVER,
        )
    }

    // ---- reusableOAuth: the hand-over does not ask twice ----

    @Test fun `the hand-over reuses the answer the address step already has`() {
        val chosen = "mail.example.org" to deviceFlow
        assertEquals(
            "⛔ Searching again, seconds later, on a path the reader did not choose: a host that " +
                "answers once but not twice would be reported as \"this server doesn't support " +
                "OAuth sign-in\", which is false.",
            chosen,
            reusableOAuth(OAuthDiscovery(email, chosen), email, server = ""),
        )
    }

    @Test fun `a server typed by hand is the one host the sign-in must ask`() {
        assertNull(
            "under Advanced she named the server: a domain-derived answer may not stand in for " +
                "the host she typed.",
            reusableOAuth(OAuthDiscovery(email, "mail.example.org" to deviceFlow), email, server = "jmap.autre.tld"),
        )
    }

    @Test fun `an answer about another address is not reused`() {
        assertNull(
            "same rule as everywhere on this screen: a verdict is about ONE address.",
            reusableOAuth(OAuthDiscovery("someone@autre.tld", "mail.autre.tld" to deviceFlow), email, server = ""),
        )
    }

    @Test fun `nothing to reuse means the sign-in searches, as it always did`() {
        assertNull(
            "no answer in hand (the manual button, or a process death): the flow's own search runs.",
            reusableOAuth(null, email, server = ""),
        )
        assertNull(
            "and an answer that found no OAuth is nothing to hand the flow either — it searches, " +
                "and says what it finds.",
            reusableOAuth(OAuthDiscovery(email, null), email, server = ""),
        )
    }
}

/**
 * The OAuth search under a budget, and the address step's two probes under one clock.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OAuthDiscoveryIsBoundedTest {

    /** The real candidate order of `Jmap.autodiscoverHosts("someone@example.org")`. */
    private val hosts = listOf("example.org", "mail.example.org", "jmap.example.org", "api.example.org")

    /** A delay no budget here survives — the shape of the ~200 s four silent hosts cost. */
    private val hangs = 200_000L

    private val budget = 10_000L

    private val deviceFlow = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        deviceAuthorizationEndpoint = "https://example.org/device",
    )
    private val codeFlow = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        authorizationEndpoint = "https://example.org/authorize",
    )

    /**
     * Answers [answers] after the stated virtual delay, and records which hosts were ASKED. Hosts in
     * [deaf] ignore cancellation while they wait, like the blocking call the real probe makes.
     */
    private class Bench(
        private val answers: Map<String, Pair<Long, OAuthMetadata?>>,
        private val deaf: Set<String> = emptySet(),
    ) {
        val asked = mutableListOf<String>()

        suspend fun probe(host: String): OAuthMetadata? {
            asked += host
            val (after, answer) = answers.getValue(host)
            if (host in deaf) withContext(NonCancellable) { delay(after) } else delay(after)
            return answer
        }
    }

    @Test fun `four silent hosts cost the budget, not two hundred seconds`() = runTest {
        val bench = Bench(hosts.associateWith { hangs to null }, deaf = hosts.toSet())

        val chosen = discoverOAuthAmong(hosts, budget) { bench.probe(it) }

        assertNull("nothing answered in time: no host to sign into.", chosen)
        assertEquals(
            "⛔ The address step must stop at its budget. Without one this is the ~200 s wheel, " +
                "now on the path every account added goes through.",
            10_000L, currentTime,
        )
    }

    @Test fun `the budget the app ships with is the one the address step waits under`() = runTest {
        val bench = Bench(hosts.associateWith { hangs to null }, deaf = hosts.toSet())

        // No budget passed, deliberately: production passes none either, so this is the only
        // test that measures what the app actually runs on.
        val chosen = discoverOAuthAmong(hosts) { bench.probe(it) }

        assertNull(chosen)
        assertEquals(10_000L, currentTime)
    }

    @Test fun `two hosts answered - the device flow wins, not the first responder`() = runTest {
        val bench = Bench(
            answers = mapOf(
                // The organisation's website, behind an SSO portal: it publishes an OAuth document,
                // and it is the FIRST candidate. This is the production case chooseOAuthHost exists
                // for, and the one the budget must not quietly undo.
                "example.org" to (100L to codeFlow),
                "mail.example.org" to (300L to deviceFlow),
                "jmap.example.org" to (hangs to null),
                "api.example.org" to (hangs to null),
            ),
            deaf = setOf("jmap.example.org", "api.example.org"),
        )

        val chosen = discoverOAuthAmong(hosts, budget) { bench.probe(it) }

        assertEquals(
            "⛔ TWO answers landed, of different ranks: the verdict must be chooseOAuthHost's — a " +
                "device flow on the mail host beats a code grant on the bare domain — and not " +
                "the host that answered first. Taking the first responder sends someone who signed " +
                "in yesterday without leaving the app to a browser, at the WEBSITE's identity " +
                "provider, which issues no token for her mail server.",
            "mail.example.org", chosen?.first,
        )
        assertEquals(deviceFlow, chosen?.second)
        assertEquals(
            "the happy path pays 100 ms + 300 ms and not one second more: the search stops on a " +
                "device flow, and the two black holes are never asked.",
            400L, currentTime,
        )
        assertEquals(listOf("example.org", "mail.example.org"), bench.asked)
    }

    @Test fun `what landed before the budget still decides, and by rank`() = runTest {
        val bench = Bench(
            answers = mapOf(
                "example.org" to (100L to codeFlow),
                "mail.example.org" to (hangs to deviceFlow),
                "jmap.example.org" to (hangs to null),
                "api.example.org" to (hangs to null),
            ),
            deaf = hosts.toSet(),
        )

        val chosen = discoverOAuthAmong(hosts, budget) { bench.probe(it) }

        assertEquals(
            "the bare domain answered a code grant; the mail host that would have outranked it " +
                "never spoke. An answer in hand beats one that is still silent — dropping it " +
                "would say \"no OAuth here\" about a server that plainly has some.",
            "example.org", chosen?.first,
        )
        assertEquals(10_000L, currentTime)
    }

    @Test fun `an address with no domain probes nothing`() = runTest {
        val chosen = discoverOAuthAmong(emptyList()) { error("no host may be probed") }

        assertNull(chosen)
        assertEquals(0L, currentTime)
    }

    // ---- the two probes of the address step, on one clock ----

    @Test fun `the address step's two probes run at the same time, not one after the other`() = runTest {
        val found = MailAutoconfigResult.NotFound

        val probe = probeAddressTogether(
            settings = { delay(10_000); found },
            oauth = { delay(10_000); OAuthDiscovery("user@example.org", null) },
        )

        assertEquals(
            "⛔ Chained, the address step costs BOTH budgets — 20 s — and it costs them to " +
                "everyone, including the majority whose server has no OAuth at all. Concurrent, it " +
                "is bounded by the slower of the two.",
            10_000L, currentTime,
        )
        assertEquals(found, probe.settings)
        assertEquals(OAuthDiscovery("user@example.org", null), probe.oauth)
    }

    @Test fun `and both answers come back, neither dropped`() = runTest {
        val settings = MailAutoconfigResult.NotFound
        val oauth = OAuthDiscovery("user@example.org", "mail.example.org" to deviceFlow)

        val probe = probeAddressTogether(
            settings = { delay(4_000); settings },
            oauth = { delay(1_000); oauth },
        )

        assertEquals(settings, probe.settings)
        assertEquals(
            "the OAuth answer must not be dropped because the settings cascade was slower.",
            oauth, probe.oauth,
        )
        assertEquals(4_000L, currentTime)
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class OAuthHandoverWiringTest {

    @Test fun `the screen reads the server's answer and asks the executed decision what to do`() {
        assertPinnedLine(
            SCREEN,
            "val oauthVerdict by viewModel.oauthDiscovery.collectAsStateWithLifecycle()",
            "The screen must read the durable OAuth answer the address step recorded.",
        )
        assertPinnedLine(
            SCREEN,
            "val ask = credentialsAsk(oauthVerdict, username)",
            "Pinned WITH its address argument: an answer about the domain she walked back and " +
                "replaced must not decide anything for the one on screen. Spell the condition out " +
                "here instead and OAuthHandoverDecisionTest, which never reads this file, stays green.",
        )
    }

    @Test fun `the sign-in starts where the executed decision says, and nowhere else`() {
        assertPinnedLine(
            SCREEN,
            "LaunchedEffect(walkStep, ask) {",
            "⛔ THE KEY, and the thread the whole volet hangs from. `LaunchedEffect(Unit)` runs " +
                "once, at the first composition, where the walk is still on the address step — the " +
                "hand-over is then dead 100 % of the time, and every other test in this file stays " +
                "green, because nothing else in the repo pins the key of an effect. It must re-run " +
                "when the step moves AND when the server's answer changes.",
        )
        assertPinnedLine(
            SCREEN,
            "if (!shouldStartOAuthSignIn(walkStep, ask, handedOver)) return@LaunchedEffect",
            "⛔ THE moved trigger. Drop the step argument and an answer landing at the address " +
                "step launches a browser while she is still typing; drop the hand-over argument " +
                "and the trigger fires again the moment Cancel closes the panel, which is the " +
                "browser she just refused reopening itself.",
        )
        assertPinnedLine(
            SCREEN,
            "startHandover()",
            "⛔ Through the ONE seam that records the hand-over as started. Call connectOAuth " +
                "directly here and nothing records it: the step then cannot tell \"not started " +
                "yet\" from \"cancelled\", which is exactly the wheel this defect is.",
        )
        assertPinnedLine(
            SCREEN,
            "viewModel.connectOAuth(username, NO_TYPED_SERVER, accountName)",
            "⛔ NO_TYPED_SERVER, never `server`: that field is the manual form's, nothing blanks " +
                "it, and it is not on screen here. Typed for one address and carried to another, " +
                "it makes this sign-in ask that host ALONE — which answers \"this server doesn't " +
                "support OAuth sign-in\", a false sentence, under a wheel, with no password field " +
                "beside it. And the address and the label are adjacent Strings: a swap compiles " +
                "into an account whose address is its label.",
        )
        assertPinnedLine(
            SCREEN,
            "private const val NO_TYPED_SERVER = \"\"",
            "⛔ ITS VALUE, not only its name. Pinning the call above and not this leaves the whole " +
                "hand-over hanging on a constant nothing reads: give it any non-blank text and " +
                "`reusableOAuth` stops reusing the answer already found, the sign-in probes that " +
                "one made-up host alone, and the credentials step tells a reader whose server does " +
                "OAuth perfectly well that it does not. Green suite, dead feature. Found by the " +
                "counter-audit as a surviving mutation.",
        )
        val leaks = source(SCREEN).lines().map { it.trim() }
            .filter { it.startsWith("viewModel.connectOAuth(") && it != "viewModel.connectOAuth(username, NO_TYPED_SERVER, accountName)" }
        assertEquals(
            "the walk's own sign-in is the only bare connectOAuth call in $SCREEN; the manual " +
                "form's is inside its button's onClick and reads `server` on purpose, because " +
                "that form SHOWS the field. Found:\n" + leaks.joinToString("\n"),
            emptyList<String>(), leaks,
        )
    }

    @Test fun `the password field is drawn under one answer only`() {
        assertPinnedLine(
            SCREEN,
            "when (credentialsPane(ask, state, handedOver)) {",
            "⛔ The guard the whole volet comes down to, now with the two facts that also tell a " +
                "wheel from an invitation. Spell the branches out here instead — `if (ask !is " +
                "CredentialsAsk.Password)` — and CredentialsPaneTest, which never reads this file, " +
                "stays green while step 2 spins for ever after Cancel. Drop `state` and no attempt " +
                "can ever be seen to have ended; drop `handedOver` and the offer is drawn before " +
                "anything was ever started.",
        )
        val lines = source(SCREEN).lines().map { it.trim() }
        val guard = lines.indexOf("when (credentialsPane(ask, state, handedOver)) {")
        val password = lines.indexOf("CredentialsPane.PASSWORD -> {")
        val field = lines.indexOf("label = stringResource(R.string.connect_password),")
        assertTrue(
            "in $SCREEN the credentials step's password field (line index $field) must sit AFTER " +
                "the PASSWORD branch (line index $password) of the guard (line index $guard). All " +
                "three are pinned unique, so indexOf is exact. Drawn outside that branch, the " +
                "field is on screen whatever the server said.",
            guard in 0 until password && password < field,
        )
    }

    @Test fun `the ViewModel runs the two probes together, under the executed helper`() {
        assertPinnedLine(
            VIEW_MODEL,
            "val probe = probeAddressTogether(",
            "⛔ The concurrency is executed by a test on a virtual clock; nothing in this file " +
                "can be. Chain the two calls here instead and the address step costs 20 s to " +
                "everyone, with every executed test still green.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "settings = { discoverMailAutoconfig(address) },",
            "The real cascade, on the address as typed — the line the settings lint follows.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "oauth = { probeOAuthFor(address) },",
            "⛔ And the OAuth probe beside it, on EVERY pass — no step argument, because gating it " +
                "on the step is exactly what let a settings verdict be recorded with no OAuth " +
                "answer beside it, and the credentials step then drew a bare wheel for an address " +
                "that can never be probed again.",
        )
    }

    @Test fun `the OAuth answer is published before the verdict the walk steps on`() {
        assertPinnedLine(
            VIEW_MODEL,
            "_oauthDiscovery.value = probe.oauth",
            "⛔ UNCONDITIONAL, and beside the settings verdict below: the two answers of one pass " +
                "are published together or the credentials step can be entered with only one of " +
                "them — a bare wheel that asks nothing, offers nothing and starts nothing. Put a " +
                "condition back in front of this write and that pit reopens.",
        )
        val lines = source(VIEW_MODEL).lines().map { it.trim() }
        val oauth = lines.indexOf("_oauthDiscovery.value = probe.oauth")
        val walk = lines.indexOf("_discovery.value = DiscoveryVerdict(address, result)")
        assertTrue(
            "in $VIEW_MODEL the OAuth answer (line index $oauth) must be published BEFORE the " +
                "verdict the walk steps on (line index $walk): the credentials step is drawn the " +
                "instant that verdict lands, and drawn in the other order it flashes the password " +
                "field of a server that wants a browser.",
            oauth in 0 until walk,
        )
    }

    @Test fun `the OAuth probe asks the mail candidates, credentials-free, under the budget`() {
        assertPinnedLine(
            VIEW_MODEL,
            "val chosen = discoverOAuthAmong(mailOAuthCandidates(address)) {",
            "⛔ Pinned WHOLE: the budgeted search, on the MAIL-NAMED candidates only — the bare " +
                "domain is the organisation's website and its OAuth document is not about the " +
                "mail ([mailOAuthCandidates], executed in MailOAuthCandidatesTest) — and with NO " +
                "budget argument, the default being the one place the shipped value lives and the " +
                "only one a test measures. Write `discoverOAuthAmong(hosts, 200_000L)` here and " +
                "the wheel is back with every executed test green.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "container.mailRepository.discoverOAuth(it, addressDomain)",
            "And the probe is the repository's credentials-free metadata GET — which is what " +
                "makes it safe to run before any secret has been typed. ⛔ Pinned WITH the " +
                "address domain: it is what bounds the hosts the document may name (executed in " +
                "`OAuthClientTest.oauthEndpointAllowed_*`), and dropping the argument here " +
                "would leave the guard with the queried host alone — the bench's own deployment, " +
                "which serves OAuth from a sibling host, becomes unaddable.",
        )
        val calls = Regex(Regex.escape("discoverOAuthAmong(")).findAll(source(VIEW_MODEL)).count()
        assertEquals(
            "discoverOAuthAmong( must occur exactly once in $VIEW_MODEL — the pinned line. Found $calls.",
            1, calls,
        )
    }

    @Test fun `the sign-in reuses the answer instead of asking a second time`() {
        assertPinnedLine(
            VIEW_MODEL,
            "val discovered = reused?.let { listOf(it) }",
            "⛔ The USE of the reused answer, pinned as well as its computation: " +
                "`reused?.let { emptyList() }` compiles, keeps the line above intact, and turns " +
                "every hand-over into \"this server doesn't support OAuth sign-in\" with the whole " +
                "suite green.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "val reused = reusableOAuth(_oauthDiscovery.value, emailTrim, server)",
            "⛔ Pinned WITH the server field: a host typed under Advanced is the one host the " +
                "sign-in must ask, and reusing a domain-derived answer over it signs into the " +
                "wrong server. Delete the line and the hand-over searches again seconds after the " +
                "address step did — a host that answers once but not twice is then told to the " +
                "reader as \"this server doesn't support OAuth sign-in\", which is false.",
        )
    }

    // -- reading the files -------------------------------------------------------------------------

    /** Exactly one line of [path] whose TRIMMED text equals [pinned] — 0 or 2+ both fail. */
    private fun assertPinnedLine(path: String, pinned: String, why: String) {
        val hits = source(path).lines().count { it.trim() == pinned }
        assertEquals(
            "expected exactly one line of $path whose trimmed text is:\n    $pinned\n" +
                "but found $hits. The line was rewritten, removed, split or duplicated — this lint " +
                "compares the WHOLE line, so any change to it (even one that only lengthens it) " +
                "lands here. $why",
            1, hits,
        )
    }

    private fun source(path: String): String = locate(path).readText()

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        fun locate(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
    }
}
