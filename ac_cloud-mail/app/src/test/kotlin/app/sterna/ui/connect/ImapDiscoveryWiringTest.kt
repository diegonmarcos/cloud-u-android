package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two pure decisions behind prefilling the IMAP form from the autoconfig cascade — EXECUTED,
 */
class ImapDiscoveryTest {

    private val email = "user@example.org"

    private val found = MailAutoconfigResult.Found(
        incoming = MailEndpoint("imap.example.org", 143, ConnectionSecurity.STARTTLS),
        outgoing = MailEndpoint("smtp.example.org", 587, ConnectionSecurity.STARTTLS),
        username = "user@example.org",
    )

    // ---- shouldDiscoverImapSettings: when the cascade may set out ----

    @Test fun `all conditions met - the cascade sets out`() {
        assertTrue(
            "IMAP password form, no chip, no OAuth, both hosts untouched, valid address, online: " +
                "this is the one case discovery must run in, or the feature does not exist.",
            shouldDiscoverImapSettings(ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = true),
        )
    }

    @Test fun `offline - the cascade stays home`() {
        assertFalse(
            "offline the cascade must not set out at all — otherwise it fires requests and waits " +
                "in airplane mode, and the screen lies about what is happening.",
            shouldDiscoverImapSettings(ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = false),
        )
    }

    @Test fun `an armed provider chip outranks the cascade`() {
        assertFalse(
            "preset.selected != null means the user picked a hard-coded provider; those come " +
                "BEFORE the cascade, which must not run under them.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm(selected = "Gmail"), email, online = true,
            ),
        )
    }

    @Test fun `an OAuth preset disarms the cascade`() {
        assertFalse(
            "an OAuth chip has no host fields on screen to fill: discovery under it would find " +
                "values nothing displays (WYSIWYG says no).",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm(oauth = true), email, online = true,
            ),
        )
    }

    @Test fun `a hand-typed IMAP host alone disarms the cascade`() {
        assertFalse(
            "one host typed by hand means the user is entering their own settings; the cascade " +
                "must not set out to second-guess them.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm(imapHost = "x"), email, online = true,
            ),
        )
    }

    @Test fun `a hand-typed SMTP host alone disarms the cascade`() {
        assertFalse(
            "same rule on the outgoing side: smtpHost typed, no discovery.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm(smtpHost = "x"), email, online = true,
            ),
        )
    }

    @Test fun `a JMAP route never discovers IMAP settings`() {
        assertFalse(
            "the JMAP path has its own discovery (RFC 8620) and no IMAP fields on screen; this " +
                "cascade is for the IMAP password form only.",
            shouldDiscoverImapSettings(ConnectStep.MANUAL, ConnectRoute.JMAP_AUTODISCOVER, PresetForm.NONE, email, online = true),
        )
    }

    @Test fun `an invalid address keeps the cascade home`() {
        assertFalse(
            "no @, no domain to derive candidate hosts from: nothing to probe, so nothing starts.",
            shouldDiscoverImapSettings(ConnectStep.MANUAL, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, "pas-une-adresse", online = true),
        )
    }

    // ---- shouldDiscoverImapSettings: the address step, where no protocol has been chosen ----

    @Test fun `at the address step the cascade sets out with no protocol chosen`() {
        assertTrue(
            "the address step is the point of the whole walk: the cascade must be able to set " +
                "out there, where the JMAP/IMAP question has NOT been asked. The route is then " +
                "whatever the untouched form defaults to (JMAP autodiscover), and requiring " +
                "IMAP_PASSWORD there is exactly the question this removes.",
            shouldDiscoverImapSettings(
                ConnectStep.ADDRESS, ConnectRoute.JMAP_AUTODISCOVER, PresetForm.NONE, email, online = true,
            ),
        )
    }

    @Test fun `the route condition stays at every step but the first`() {
        assertFalse(
            "in the manual fallback the protocol IS chosen, and a JMAP form has no IMAP fields " +
                "for the verdict to land in: the cascade must stay home there.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.JMAP_AUTODISCOVER, PresetForm.NONE, email, online = true,
            ),
        )
        assertFalse(
            "and the door is open at ADDRESS only, not at 'any step that is not MANUAL'.",
            shouldDiscoverImapSettings(
                ConnectStep.CREDENTIALS, ConnectRoute.JMAP_AUTODISCOVER, PresetForm.NONE, email, online = true,
            ),
        )
        // The other two JMAP routes, spelled out: the condition is "the IMAP form is the one on
        // screen", NOT "the route is not JMAP_AUTODISCOVER". With only the autodiscover route ever
        assertFalse(
            "JMAP with an API token is still a JMAP form: no IMAP fields for the verdict to land in.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.JMAP_TOKEN, PresetForm.NONE, email, online = true,
            ),
        )
        assertFalse(
            "JMAP against a typed server, likewise.",
            shouldDiscoverImapSettings(
                ConnectStep.MANUAL, ConnectRoute.JMAP_SERVER, PresetForm.NONE, email, online = true,
            ),
        )
    }

    @Test fun `the IMAP form opens the door at any step, not only the manual one`() {
        assertTrue(
            "the route condition is a property of the FORM on screen — the IMAP password fields " +
                "are there to receive the verdict — not a whitelist of steps. Narrowing it to " +
                "'IMAP_PASSWORD and MANUAL' would be invisible to every other case here, and it " +
                "would silently disarm the cascade for whatever step the screen volet lands the " +
                "IMAP form on.",
            shouldDiscoverImapSettings(
                ConnectStep.CREDENTIALS, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = true,
            ),
        )
    }

    @Test fun `the address step lifts the route condition and nothing else`() {
        val refusals = listOf(
            "offline: the cascade must not set out at the address step either" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = false,
                ),
            "an armed provider chip still outranks the cascade" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm(selected = "Gmail"), email, online = true,
                ),
            "an OAuth chip still has no host fields to fill" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm(oauth = true), email, online = true,
                ),
            "a hand-typed IMAP host still disarms it" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm(imapHost = "x"), email, online = true,
                ),
            "a hand-typed SMTP host still disarms it" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm(smtpHost = "x"), email, online = true,
                ),
            "an address the shared rule rejects still leaves nothing to probe" to
                shouldDiscoverImapSettings(
                    ConnectStep.ADDRESS, ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, "pas-une-adresse", online = true,
                ),
        )
        refusals.forEach { (why, decided) ->
            assertFalse(
                "opening the door at the address step must lift the ROUTE condition and nothing " +
                    "else — $why.",
                decided,
            )
        }
    }

    // ---- presetFilledWith: what the verdict may write ----

    @Test fun `both sides untouched - all six values land, nothing else moves`() {
        assertEquals(
            "with both host fields untouched the verdict fills BOTH sides — host, port (as text) " +
                "and security, together — and touches nothing else on the form.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "imap.example.org",
                imapPort = "143",
                imapSecurity = ConnectionSecurity.STARTTLS,
                smtpHost = "smtp.example.org",
                smtpPort = "587",
                smtpSecurity = ConnectionSecurity.STARTTLS,
                appPasswordUrl = null,
                discoveredFor = email,
            ),
            presetFilledWith(PresetForm.NONE, found, email),
        )
    }

    @Test fun `a hand-typed IMAP host keeps its whole side - the SMTP side is still written`() {
        val typed = PresetForm(imapHost = "mien.tld", imapPort = "1993", imapSecurity = ConnectionSecurity.TLS)
        assertEquals(
            "the incoming side the user typed must stay STRICTLY intact — host, port AND security: " +
                "a discovered port or security under a hand-typed host is a configuration nobody " +
                "wrote. The untouched outgoing side is still filled.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "mien.tld",
                imapPort = "1993",
                imapSecurity = ConnectionSecurity.TLS,
                smtpHost = "smtp.example.org",
                smtpPort = "587",
                smtpSecurity = ConnectionSecurity.STARTTLS,
                appPasswordUrl = null,
                discoveredFor = email,
            ),
            presetFilledWith(typed, found, email),
        )
    }

    @Test fun `a hand-typed SMTP host keeps its whole side - the IMAP side is still written`() {
        val typed = PresetForm(smtpHost = "envoi.mien.tld", smtpPort = "2525", smtpSecurity = ConnectionSecurity.TLS)
        assertEquals(
            "same rule mirrored: the outgoing side the user typed stays intact, the untouched " +
                "incoming side is filled.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "imap.example.org",
                imapPort = "143",
                imapSecurity = ConnectionSecurity.STARTTLS,
                smtpHost = "envoi.mien.tld",
                smtpPort = "2525",
                smtpSecurity = ConnectionSecurity.TLS,
                appPasswordUrl = null,
                discoveredFor = email,
            ),
            presetFilledWith(typed, found, email),
        )
    }

    @Test fun `both sides typed - the form comes back identical`() {
        val typed = PresetForm(
            imapHost = "mien.tld", imapPort = "1993", imapSecurity = ConnectionSecurity.TLS,
            smtpHost = "envoi.mien.tld", smtpPort = "2525", smtpSecurity = ConnectionSecurity.STARTTLS,
        )
        assertEquals(
            "with both hosts typed the verdict has nowhere to land: the form must come back " +
                "IDENTICAL, not merely with its hosts preserved.",
            typed,
            presetFilledWith(typed, found, email),
        )
    }

    @Test fun `selected and appPasswordUrl are never the cascade's to touch`() {
        val armed = PresetForm(selected = "Gmail", appPasswordUrl = "https://myaccount.google.com/apppasswords")
        val filled = presetFilledWith(armed, found, email)
        assertEquals(
            "presetFilledWith must never touch the chip selection, even on a form it fills — " +
                "which chip is armed is the screen's decision, not the cascade's.",
            "Gmail", filled.selected,
        )
        assertEquals(
            "nor the app-password link that selection carries.",
            "https://myaccount.google.com/apppasswords", filled.appPasswordUrl,
        )
        assertFalse("nor the oauth flag.", filled.oauth)
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ImapDiscoveryWiringTest {

    // ---- the ViewModel: one cascade, two copies of its verdict ----

    @Test
    fun `the ViewModel gates discovery on the executed decision, with real connectivity`() {
        assertPinnedLine(
            VIEW_MODEL,
            "val online = hasUsableNetwork(app)",
            "The link, read from Android ONCE and handed to both decisions below. Replace it with " +
                "a literal — `val online = true` is one keystroke — and the cascade fires requests " +
                "in airplane mode AND records a fabricated 'this domain publishes nothing', while " +
                "every executed test stays green.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "if (!shouldDiscoverImapSettings(step, route, preset, email, online = online)) {",
            "Pinned WITH its online argument: this is the seam between the screen's triggers and " +
                "the decision ImapDiscoveryTest executes.",
        )
        val lines = source(VIEW_MODEL).lines().map { it.trim() }
        assertTrue(
            "the one read of the link must come BEFORE the guard it feeds — and before the " +
                "refusal below, which is the whole reason it is a val: read twice, the guard can " +
                "refuse for want of a network and the refusal then be recorded as though the " +
                "network were there.",
            lines.indexOf("val online = hasUsableNetwork(app)") <
                lines.indexOf("if (!shouldDiscoverImapSettings(step, route, preset, email, online = online)) {"),
        )
    }

    @Test
    fun `a refused probe answers the walk through the executed decision, link included`() {
        assertPinnedLine(
            VIEW_MODEL,
            "_discovery.value = verdictAfterRefusal(_discovery.value, email, online = online)",
            "Without this line a refused probe records nothing at all, and the address step waits " +
                "for a verdict nobody will ever send. Pinned WHOLE, ONLINE ARGUMENT INCLUDED, " +
                "because WHAT it records — and above all what it must NOT record when the refusal " +
                "IS the missing network — is verdictAfterRefusal's call, executed in " +
                "ConnectWalkTest. Drop that argument (or hand it `true`) and the offline refusal " +
                "records a NotFound about a domain nobody asked, which then survives the outage " +
                "and drops the reader into the manual form for good.",
        )
    }

    @Test
    fun `the address step's button reads the executed advance, on the durable verdict and the live link`() {
        assertPinnedLine(
            VIEW_MODEL,
            "addressAdvance(email, _discovery.value, _oauthDiscovery.value, online = hasUsableNetwork(app))",
            "⛔ The link is read HERE, at the tap, not captured when the button was drawn: airplane " +
                "mode is switched on a screen that is not recomposing. And on the DURABLE verdict " +
                "(_discovery), never the one-shot fill signal, which is nulled the moment the " +
                "fields are written. ALL THREE arguments pinned: `online = true` here is the " +
                "reported defect back in full, spinner and all, and handing `null` for the OAuth " +
                "answer (or dropping the argument) sends a domain served over OAuth with no " +
                "autoconfig document to the manual form — #55, verbatim, while the answer that " +
                "would have signed her in sits in _oauthDiscovery.",
        )
        val screen = source(SCREEN).lines().map { it.trim() }
        val offline = screen.indexOf("AddressAdvance.Offline -> {")
        assertEquals(
            "⛔ THE branch, pinned as a WHOLE BLOCK, in order, closing brace included. Offline the " +
                "walk SAYS so and stays on the address step: send her to ConnectStep.MANUAL here " +
                "and the reported defect is back verbatim (the manual form, in silence); fall " +
                "through to the Waiting branch and it is back wearing a spinner that never stops.\n" +
                "⛔ And the SECOND line is not decoration. reportOffline writes the same " +
                "ConnectState.Error every time — a data class into a MutableStateFlow, so an equal " +
                "value emits nothing and recomposes nothing, and LaunchedEffect(status) at the " +
                "foot of the column would not re-run even if it did. Nothing resets the state to " +
                "Idle between two taps (typing does not; clearFinishedAttempt only fires on a " +
                "change of step). Drop this line and the FIRST tap scrolls the sentence into view " +
                "while every tap after it says nothing at all — and one of them always follows, " +
                "because correcting a typo in the address takes the credential block (which sits " +
                "ABOVE this one) back under the keyboard. The gesture asks for its own answer to " +
                "be seen; the effect only covers 'what is said has CHANGED'.",
            listOf(
                "AddressAdvance.Offline -> {",
                "viewModel.reportOffline()",
                "scope.launch { statusReveal.bringIntoView() }",
                "}",
            ),
            if (offline < 0) screen.take(0) else screen.subList(offline, minOf(offline + 4, screen.size)),
        )
        val asked = Regex(Regex.escape("viewModel.advanceFromAddress(username)")).findAll(source(SCREEN)).count()
        assertEquals(
            "advanceFromAddress(username) must occur exactly twice in $SCREEN: the button's " +
                "onClick, and the effect that ends the wait once an answer lands. A site that " +
                "decides the same thing by itself is one no executed test reaches — and a THIRD " +
                "occurrence is most likely the button's `enabled` reading the link again, in " +
                "composition, on every keystroke. Found $asked.",
            2, asked,
        )
    }

    @Test
    fun `the offline sentence has one publisher, and its body is exactly one line`() {
        val lines = source(VIEW_MODEL).lines().map { it.trim() }
        val open = lines.indexOf("internal fun reportOffline() {")
        assertEquals(
            "reportOffline must say exactly what the six sign-in routes' guards say — " +
                "R.string.connect_offline, which already lives in all nine locales — on the " +
                "screen's own error surface, AND NOTHING ELSE. Pinned as the WHOLE body, closing " +
                "brace included: reading only the first line lets a second one be inserted after " +
                "it (`_state.value = ConnectState.Idle` is the obvious one) that puts the sentence " +
                "back out before anything can draw it, with every test green. A new string here " +
                "is an untranslated one, and a state that is not Error is a sentence nothing draws.",
            listOf(
                "internal fun reportOffline() {",
                "_state.value = ConnectState.Error(string(R.string.connect_offline))",
                "}",
            ),
            if (open < 0) lines.take(0) else lines.subList(open, minOf(open + 3, lines.size)),
        )
    }

    @Test
    fun `the button greys out on the address alone, never on a reading of the link`() {
        assertPinnedLine(
            SCREEN,
            "enabled = canLeaveAddressStep(username) && !awaitingVerdict,",
            "⛔ Two facts, and no network. The shared address rule is what greys the button out: " +
                "written `viewModel.advanceFromAddress(username) != AddressAdvance.Offline`, the " +
                "button is dead offline, so the tap that was supposed to SAY 'you are offline' " +
                "never happens and the reader is left with a screen that ignores her — and any " +
                "advance-based spelling reads connectivity through a binder, in composition, once " +
                "per keystroke, to throw the answer away. Drop `!awaitingVerdict` and the probe " +
                "can be re-armed under itself while it is still out.",
        )
    }

    @Test
    fun `the wait for a verdict is ended by the executed decision, wait flag included`() {
        assertPinnedLine(
            SCREEN,
            "when (val answer = awaitedAnswer(awaitingVerdict, viewModel.advanceFromAddress(username))) {",
            "⛔ Pinned WHOLE, with `awaitingVerdict` as its first argument. Decide it here instead " +
                "(`if (advance is AddressAdvance.Go)`, dropping the wait) and this effect — which " +
                "re-runs on every keystroke, the address being one of its keys — carries the " +
                "reader off the address step mid-word, the moment a verdict for what she has " +
                "typed so far lands. ConnectWalkTest executes the decision; it never reads this " +
                "file, so nothing else can see which arguments reach it.",
        )
    }

    @Test
    fun `the offline exit ends the wait AND says so`() {
        val lines = source(SCREEN).lines().map { it.trim() }
        val open = lines.indexOf("AwaitedAnswer.SayOffline -> {")
        assertEquals(
            "⛔ THE ONLY WAY OUT of an armed wait offline: no probe sets out and no verdict is " +
                "recorded, so nothing will ever land to end it — the button stays disabled on " +
                "'Finding your server…' for ever, and disabled it cannot even be tapped to say " +
                "why. Pinned as the WHOLE branch, in order and closing brace included: drop the " +
                "disarm and the button stays dead beside the sentence; drop the report and the " +
                "wait ends in silence, which is the reader watching the spinner vanish with " +
                "nothing said; and this branch removed altogether is the lock itself, restored.",
            listOf(
                "AwaitedAnswer.SayOffline -> {",
                "awaitingVerdict = false",
                "viewModel.reportOffline()",
                "}",
            ),
            if (open < 0) lines.take(0) else lines.subList(open, minOf(open + 4, lines.size)),
        )
    }

    @Test
    fun `the verdict that lands ends the wait and moves the walk, in that order`() {
        val lines = source(SCREEN).lines().map { it.trim() }
        val open = lines.indexOf("is AwaitedAnswer.Leave -> {")
        assertEquals(
            "the wait is disarmed and the step the DECISION named is taken — never a step " +
                "recomputed here. Pinned whole: leave the wait armed and the button is stuck " +
                "disabled on the next step's screen too.",
            listOf(
                "is AwaitedAnswer.Leave -> {",
                "awaitingVerdict = false",
                "rememberedStep = answer.step",
                "}",
            ),
            if (open < 0) lines.take(0) else lines.subList(open, minOf(open + 4, lines.size)),
        )
    }

    @Test
    fun `editing the address disarms the wait`() {
        assertPinnedLine(
            SCREEN,
            "EmailField(username, { username = it; awaitingVerdict = false }, credentialReveal) {",
            "⛔ The disarm is on the SAME line as the write, and pinned with it. Without it the " +
                "button she armed for one address stays disabled on 'Finding your server…' while " +
                "she types another — a wait for a probe that will never be asked for. Nothing " +
                "executed can see this: it is the address step's own field.",
        )
        val says = Regex(Regex.escape("viewModel.reportOffline()")).findAll(source(SCREEN)).count()
        assertEquals(
            "reportOffline() must be called exactly twice in $SCREEN — the address step's button, " +
                "and the effect that ends its wait. Elsewhere it writes an offline error onto a " +
                "step that never asked. Found $says.",
            2, says,
        )
    }

    @Test
    fun `the error the walk writes is actually drawn, at every step`() {
        assertPinnedLine(
            SCREEN,
            "is StatusLine.Failed -> Text(",
            "⛔ THE ONLY THING THAT DRAWS the offline sentence. It used to read `is " +
                "ConnectState.Error -> Text(`, beside a step-conditioned branch, which made `if " +
                "(walkStep != ConnectStep.ADDRESS) Text(` the idiomatic one-line mutation that " +
                "computes the message, writes the state, and renders NOTHING: the reported defect " +
                "back whole with the suite green. ⚠ THE GUARANTEE IS NOT WHAT IT WAS, and it is " +
                "worth being exact about which half moved: that a failure yields a sentence at " +
                "every step is now EXECUTABLE ([ConnectStatusTest] runs statusLine with an Error " +
                "at every ConnectStep and demands a Failed carrying the message), which is a real " +
                "gain. That the sentence is DRAWN is still only what this pin plus the " +
                "indentation pin in ConnectStatusWiringTest say — a line can exist and never be " +
                "composed, and that is exactly how a wrapper around the whole block was found to " +
                "survive the first version of these tests.",
        )
        assertPinnedLine(
            SCREEN,
            "text = stringResource(R.string.connect_error, status.message),",
            "And with the message the walk wrote INTO it: `stringResource(R.string.connect_error)` " +
                "alone still compiles, still draws a red line, and still says nothing — 'Could " +
                "not connect: %1\$s'. That is the whole content of the offline answer.",
        )
    }

    @Test
    fun `the ViewModel launches the real cascade and keeps its WHOLE verdict`() {
        assertPinnedLine(
            VIEW_MODEL,
            "settings = { discoverMailAutoconfig(address) },",
            "Pinned because this is the one call to the real cascade (core:data " +
                "discoverMailAutoconfig — address only, shipped budget). ⛔ It must NOT filter to " +
                "`as? Found` any more: that is what made 'this domain publishes nothing' and 'the " +
                "probe has not answered yet' the same absence of a value, and those two are " +
                "exactly what the address step chooses between. ⚠ It is now an ARGUMENT: the " +
                "cascade runs beside the OAuth probe, under probeAddressTogether, so that the " +
                "address step pays the slower of the two budgets and not their sum — see " +
                "[OAuthHandoverWiringTest] and the executed concurrency test behind it.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "val result = probe.settings",
            "And the cascade's own answer is what the two lines below publish — pinned whole so " +
                "the durable verdict cannot quietly become the OAuth probe's answer, or a literal.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "_discovery.value = DiscoveryVerdict(address, result)",
            "The DURABLE copy, with the address it is about. Pinned whole: dropping the address " +
                "would let a verdict about one domain answer for another, and dropping the line " +
                "sends the walk back to the address step on every rotation.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "_imapDiscovery.value = result as? MailAutoconfigResult.Found",
            "And the one-shot signal BESIDE it, not in place of it: it is what stops a host field " +
                "the user cleared from refilling itself.",
        )
        val calls = Regex(Regex.escape("discoverMailAutoconfig(")).findAll(source(VIEW_MODEL)).count()
        assertEquals(
            "discoverMailAutoconfig( must occur exactly once in $VIEW_MODEL — the pinned line. A " +
                "second call site is one this lint does not read; zero means the cascade is no " +
                "longer wired at all. Found $calls.",
            1, calls,
        )
    }

    @Test
    fun `only the one-shot copy is ever consumed`() {
        assertPinnedLine(
            VIEW_MODEL,
            "_imapDiscovery.value = null",
            "consumeImapDiscovery retires the FILL signal, and only it.",
        )
        val nulled = Regex("""_discovery\.value\s*=\s*null""").findAll(source(VIEW_MODEL)).count()
        assertEquals(
            "nothing may null the durable verdict in $VIEW_MODEL: it is replaced by a later " +
                "probe's verdict, never emptied. Nulled here, the credentials step falls back to " +
                "the address step the instant the fields are written — with the password half " +
                "typed. Found $nulled such assignment(s).",
            0, nulled,
        )
    }

    // ---- the screen: which step is drawn, and from which copy ----

    @Test
    fun `the screen draws the step the DECISION renders, from the DURABLE verdict`() {
        assertPinnedLine(
            SCREEN,
            "val verdict by viewModel.discovery.collectAsStateWithLifecycle()",
            "The walk reads the durable copy. Point it at viewModel.imapDiscovery instead and the " +
                "credentials step collapses back to the address step the moment the fields are " +
                "written, and again after every rotation, throwing away the password being typed.",
        )
        assertPinnedLine(
            SCREEN,
            "val found = verdictFor(verdict, username) as? MailAutoconfigResult.Found",
            "Pinned WHOLE, address argument included: a verdict is about ONE address. Drop the " +
                "verdictFor call and a verdict about the domain she just walked back and replaced " +
                "would still send her to a credentials page for somebody else's server.",
        )
        assertPinnedLine(
            SCREEN,
            "val walkStep = stepToRender(rememberedStep, found, oauthVerdict, username)",
            "⛔ THE line. Render `rememberedStep` raw and, after a process death, the screen draws " +
                "a password field for a server nobody discovered and a button that leads nowhere " +
                "— every executed test stays green, because none of them can see this file. And " +
                "the OAuth answer is an argument HERE too: hand `null` for it and the credentials " +
                "step reached over OAuth alone is bounced back to the address step the frame it " +
                "arrives, which is the walk looping instead of the walk moving.",
        )
        val rendered = Regex(Regex.escape("stepToRender(")).findAll(source(SCREEN)).count()
        assertEquals(
            "stepToRender( must occur exactly once in $SCREEN. Found $rendered.", 1, rendered,
        )
        val oneShot = Regex(Regex.escape("viewModel.imapDiscovery")).findAll(source(SCREEN)).count()
        assertEquals(
            "the one-shot flow may be read exactly once in $SCREEN — to fill the fields. A second " +
                "reader is the walk feeding on the copy that gets consumed. Found $oneShot.",
            1, oneShot,
        )
    }

    @Test
    fun `every discovery trigger passes the step really drawn, through one seam`() {
        assertPinnedLine(
            SCREEN,
            "viewModel.discoverImapSettings(walkStep, route, preset, username)",
            "The ONE line where the triggers agree on their arguments. Hand the guard a literal " +
                "ConnectStep.ADDRESS here and the cascade fires under a JMAP form for every user, " +
                "writing IMAP hosts into a preset the screen does not show (WYSIWYG) — while " +
                "every executed test stays green.",
        )
        assertPinnedLine(
            SCREEN,
            "preset = presetForAddress(preset, verdict, username, walkStep)",
            "And it goes FIRST, WITH the step really drawn: host values nothing backs any more " +
                "are what make the guard refuse, so left in place the walk waits for a probe that " +
                "can never run — and after a process death that refusal poisons every address " +
                "typed afterwards. WHICH values are dropped, and the ⛔ never-on-the-manual-form " +
                "rule, are presetForAddress's call, executed in ConnectWalkTest. Pass a literal " +
                "ConnectStep.ADDRESS here and the manual form is emptied under the user.",
        )
        val calls = Regex(Regex.escape("discoverImapSettings(")).findAll(source(SCREEN)).count()
        assertEquals(
            "discoverImapSettings( must occur exactly once in $SCREEN — inside probeAddress(). A " +
                "second call site is a trigger that can pass whatever it likes. Found $calls.",
            1, calls,
        )
        val triggers = Regex(Regex.escape("probeAddress()")).findAll(source(SCREEN)).count()
        assertEquals(
            "probeAddress() must appear five times in $SCREEN: its declaration and its four " +
                "triggers — arriving at a step, the address field losing focus at EACH of the two " +
                "steps that show one, and the address step's own button, on the ONE branch that " +
                "asks for a probe (Waiting). Found $triggers.",
            5, triggers,
        )
    }

    @Test
    fun `back is the walk's decision, and it carries the address`() {
        assertPinnedLine(
            SCREEN,
            "BackHandler(enabled = stepBackKeeping(walkStep, username) != null) {",
            "Enabled by the decision itself: at the address step it answers null, the handler is " +
                "off, and the SYSTEM pops the screen — a walk that answers Back there paints a " +
                "blank screen of its own making.",
        )
        assertPinnedLine(
            SCREEN,
            "username = back.email",
            "Back carries the address the decision handed back. ⛔ Not `username = \"\"`, and not " +
                "nothing at all: retyping the address at every round trip is how a walk becomes " +
                "worse than the single page it replaced.",
        )
        val assigned = Regex("""\busername = """).findAll(source(SCREEN)).count()
        assertEquals(
            "`username = ` must occur exactly three times in $SCREEN: the two address fields' " +
                "onValueChange, and Back's. A fourth is something else writing the address — " +
                "clearing it on the way back, for instance, which no pinned LINE would ever see " +
                "because it only ADDS one. Found $assigned.",
            3, assigned,
        )
    }

    @Test
    fun `the manual door is offered by the executed decision, never inline`() {
        assertPinnedLine(
            SCREEN,
            "if (offersManualSetup(verdict, oauthVerdict, username)) {",
            "⛔ Offered beside a verdict that worked, 'Set up manually' walks the protocol " +
                "question (JMAP? IMAP/SMTP?) straight back on screen — the defect this whole walk " +
                "removes. Spell the condition out here (`verdict != null`, or nothing at all) and " +
                "ConnectWalkTest, which never reads this file, stays green. The OAuth answer is " +
                "pinned as an argument because a drivable one IS a verdict that worked: withhold " +
                "it and the protocol question is offered beside a hand-over the walk is about to " +
                "make.",
        )
    }

    @Test
    fun `the credentials step hands over to IMAP only where the decision says so`() {
        assertPinnedLine(
            SCREEN,
            "if (!shouldTryDiscoveredImap(walkStep, state, found)) return@LaunchedEffect",
            "JMAP is asked first, on the secret just collected; the published IMAP endpoints only " +
                "when that answers 'no JMAP server here'. Widen this to any failure and a mistyped " +
                "password is replayed at a second door, which earns a second rejection and hides " +
                "the first.",
        )
    }

    @Test
    fun `a demotion sticks, and a finished attempt does not travel with the walk`() {
        assertPinnedLine(
            SCREEN,
            "if (rememberedStep != walkStep) rememberedStep = walkStep",
            "The credentials step falls back to the address step when its verdict is gone " +
                "(process death). Without this write-back the fall is only drawn, not recorded: " +
                "the automatic re-probe succeeds two seconds later and teleports the user forward " +
                "out of the step she is reading — and the bench cannot tell the promised " +
                "'process death lands on step 1' from a flicker.",
        )
        assertPinnedLine(
            SCREEN,
            "viewModel.clearFinishedAttempt()",
            "⛔ Nothing else clears what a finished attempt left in ConnectState. Remove this and " +
                "a NeedsServer from a failed sign-in survives Back: re-entering the credentials " +
                "step re-fires the IMAP handover on its own, adding an account on a tap on " +
                "'Continue' that nobody meant as a sign-in.",
        )
        assertPinnedLine(
            VIEW_MODEL,
            "if (clearsAttemptOnLeave(_state.value)) _state.value = ConnectState.Idle",
            "WHICH states may be cleared is the executed decision's call, not this function's. " +
                "Clear unconditionally and a Connecting is wiped — which is the state every add " +
                "route reads to refuse a second submit, so the next tap adds a second account.",
        )
    }

    @Test
    fun `the endpoints the handover dials are the ones the domain published`() {
        // The guard line alone holds NOTHING about the arguments: shouldTryDiscoveredImap
        // returns a Boolean and never sees an endpoint. Swap incoming/outgoing here and the app
        // fetches mail on the SMTP port; write ConnectionSecurity.NONE and the account is created,
        // and then polled, IN THE CLEAR — credentials included — with every executed test green.
        assertPinnedLine(
            SCREEN,
            "endpoints.incoming.host, endpoints.incoming.port, endpoints.incoming.security,",
            "Incoming trio, whole line: host, port AND the security the domain published.",
        )
        assertPinnedLine(
            SCREEN,
            "endpoints.outgoing.host, endpoints.outgoing.port, endpoints.outgoing.security,",
            "Outgoing trio, whole line, in that order.",
        )
        assertPinnedLine(
            SCREEN,
            "preset.imapHost, preset.imapPort.toInt(), preset.imapSecurity,",
            "The manual route's incoming trio — the fields the user is looking at, and no other " +
                "value (WYSIWYG).",
        )
        assertPinnedLine(
            SCREEN,
            "preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,",
            "The manual route's outgoing trio.",
        )
        // THE PINS ABOVE ARE BLIND TO A SWAP: exchange the two whole lines and each is still
        // present exactly once, while the app fetches mail on the SMTP endpoint and posts it to
        // the IMAP one. Only their ORDER says which argument is which — connectImap takes the
        // incoming trio first, positionally.
        val lines = source(SCREEN).lines().map { it.trim() }
        assertTrue(
            "in $SCREEN the handover's incoming trio must come BEFORE its outgoing one.",
            lines.indexOf("endpoints.incoming.host, endpoints.incoming.port, endpoints.incoming.security,") <
                lines.indexOf("endpoints.outgoing.host, endpoints.outgoing.port, endpoints.outgoing.security,"),
        )
        assertTrue(
            "and the manual route's incoming trio before its outgoing one.",
            lines.indexOf("preset.imapHost, preset.imapPort.toInt(), preset.imapSecurity,") <
                lines.indexOf("preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,"),
        )
        val identity = Regex(Regex.escape("username, password, accountName,")).findAll(source(SCREEN)).count()
        assertEquals(
            "the credentials + label triple must appear exactly twice in $SCREEN — the handover " +
                "and the manual IMAP route — and in that order, since `username` and " +
                "`accountName` are adjacent Strings and a swap compiles into an account whose " +
                "address is its label. Found $identity.",
            2, identity,
        )
    }

    @Test
    fun `the credentials step asks JMAP first, whatever the manual form's chips say`() {
        assertPinnedLine(
            SCREEN,
            "onClick = { viewModel.connectAuto(username, password, accountName) },",
            "⛔ NOT submitConnect(): that reads `protocol`, which is one rememberSaveable for the " +
                "whole screen. One tap on the IMAP/SMTP chip — three steps and a Back ago, for " +
                "another address — and this step would dial IMAP without ever asking JMAP, which " +
                "is a property the walk announces.",
        )
        assertPinnedLine(
            SCREEN,
            "enabled = !busy && credentialsReady,",
            "And its enabled state on the same route: `ready` is computed from the chips too, so " +
                "the button would demand host fields this step does not show.",
        )
        val manual = Regex(Regex.escape("submitConnect()")).findAll(source(SCREEN)).count()
        assertEquals(
            "submitConnect() must appear exactly twice in $SCREEN: its declaration and the manual " +
                "form's button. A third occurrence is the walk handing a step back to the chips. " +
                "Found $manual.",
            2, manual,
        )
    }

    @Test
    fun `the screen writes the verdict through the executed fill, into the visible fields`() {
        assertPinnedLine(
            SCREEN,
            "preset = presetFilledWith(preset, it, username)",
            "Pinned because this is the ONLY way a discovered value may reach the user: written " +
                "into the visible fields' state (`preset`), through the fill ImapDiscoveryTest " +
                "executes. Any other assignment — fields bypassed, Connect reading the verdict " +
                "directly — uses a value the screen never showed (WYSIWYG).",
        )
        val calls = Regex(Regex.escape("presetFilledWith(")).findAll(source(SCREEN)).count()
        assertEquals(
            "presetFilledWith( must occur exactly once in $SCREEN — the pinned line. A second " +
                "call site could write a verdict somewhere this lint does not read. Found $calls.",
            1, calls,
        )
    }

    @Test
    fun `the screen consumes the verdict, and only after writing it`() {
        assertPinnedLine(
            SCREEN,
            "viewModel.consumeImapDiscovery()",
            "Pinned because this is what makes the FILL one-shot: without it the Found stays in " +
                "the StateFlow, every new collector (each rotation) replays it, and a field the " +
                "user cleared by hand refills itself. Nothing above can see that — the fill " +
                "itself is idempotent, so the suite stays green with the call gone.",
        )
        val lines = source(SCREEN).lines().map { it.trim() }
        val write = lines.indexOf("preset = presetFilledWith(preset, it, username)")
        val consume = lines.indexOf("viewModel.consumeImapDiscovery()")
        assertTrue(
            "in $SCREEN the write `preset = presetFilledWith(preset, it, username)` (line index $write) " +
                "must come BEFORE `viewModel.consumeImapDiscovery()` (line index $consume) — " +
                "both pinned unique, so indexOf is exact. Consumed first, the verdict is nulled " +
                "before it lands: a cancellation between the two drops it entirely, and the " +
                "adjacent pair reads as write-then-retire, not the reverse.",
            write in 0 until consume,
        )
    }

    @Test
    fun `core-data's UI entry point is the real cascade, undecorated`() {
        assertPinnedLine(
            CORE_AUTOCONFIG,
            "suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =",
            "Pinned because this signature IS the contract the ViewModel lint above relies on: " +
                "address only, no injected client, no caller-supplied budget. Widen it and every " +
                "call site can hand the cascade something these lints never read.",
        )
        assertPinnedLine(
            CORE_AUTOCONFIG,
            "discoverMailSettings(emailAddress)",
            "Pinned because the delegation must reach the REAL cascade with the address as " +
                "typed: reroute it to a stand-in, or wrap a 60 s budget around it, and " +
                "ImapDiscoveryTest — which never runs this file — stays green.",
        )
        val lines = source(CORE_AUTOCONFIG).lines().map { it.trim() }
        val sig = lines.indexOf("suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =")
        assertEquals(
            "in $CORE_AUTOCONFIG the line after the discoverMailAutoconfig signature must BE the " +
                "delegation — anything inserted between them (a wrapper opening, a budget, a " +
                "cache) would keep both pinned lines intact while changing what the entry point " +
                "does.",
            "discoverMailSettings(emailAddress)",
            lines[sig + 1],
        )
    }

    // -- reading the files -------------------------------------------------------------------------

    /** Exactly one line of [path] whose TRIMMED text equals [pinned] — 0 or 2+ both fail. */
    private fun assertPinnedLine(path: String, pinned: String, why: String) {
        val hits = source(path).lines().count { it.trim() == pinned }
        assertEquals(
            "expected exactly one line of $path whose trimmed text is:\n    $pinned\n" +
                "but found $hits. The line was rewritten, removed, split, or duplicated — this " +
                "lint compares the WHOLE line, so any change to it (even one that only lengthens " +
                "it) lands here. $why",
            1, hits,
        )
    }

    private fun source(path: String): String = locate(path).readText()

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
        const val CORE_AUTOCONFIG = "core/data/src/main/kotlin/app/sterna/core/data/autoconfig/MailAutoconfig.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
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
