package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.OAuthMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The add-account walk's decisions, EXECUTED with pinned arguments and expected values written out
 */
class ConnectWalkTest {

    private val email = "alex@example.org"

    private val found = MailAutoconfigResult.Found(
        incoming = MailEndpoint("imap.example.org", 143, ConnectionSecurity.STARTTLS),
        outgoing = MailEndpoint("smtp.example.org", 587, ConnectionSecurity.STARTTLS),
        username = "alex@example.org",
    )

    private val foundVerdict = DiscoveryVerdict(email, found)
    private val notFoundVerdict = DiscoveryVerdict(email, MailAutoconfigResult.NotFound)

    /** A server that signs this account in without a password — the drivable answer (#55). */
    private val deviceFlow = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
        deviceAuthorizationEndpoint = "https://example.org/device",
    )

    /** OAuth advertised, and no grant this app can drive: a password field with nothing behind it. */
    private val noUsableGrant = OAuthMetadata(
        issuer = "https://example.org",
        tokenEndpoint = "https://example.org/token",
    )

    private val oauthUsable = OAuthDiscovery(email, "mail.example.org" to deviceFlow)
    private val oauthUndrivable = OAuthDiscovery(email, "mail.example.org" to noUsableGrant)
    private val oauthNothing = OAuthDiscovery(email, null)
    private val oauthElsewhere = OAuthDiscovery("alex@autre.tld", "mail.autre.tld" to deviceFlow)

    // ---- verdictFor: a verdict is about ONE address ----

    @Test fun `a verdict answers for its own address`() {
        assertSame(found, verdictFor(foundVerdict, email))
    }

    @Test fun `a verdict answers for that address however it is spaced`() {
        assertSame(
            "the address field's value reaches this with whatever spacing the keyboard left; the " +
                "probe was launched on the trimmed address, so the lookup trims too.",
            found, verdictFor(foundVerdict, "  alex@example.org "),
        )
    }

    @Test fun `a verdict about another address is no verdict at all`() {
        assertNull(
            "the user walked back and typed a different address: answering with the old domain's " +
                "servers would sign her into somebody else's provider.",
            verdictFor(foundVerdict, "alex@autre.tld"),
        )
    }

    @Test fun `no verdict at all is null`() {
        assertNull(verdictFor(null, email))
    }

    // ---- verdictAfterRefusal: a probe that never set out still has to answer ----

    @Test fun `a refusal with nothing in hand records that nothing was found`() {
        assertEquals(
            "a refusal the user CAN act on — an armed chip, a host she typed herself — leaves the " +
                "address step needing an answer: it must be able to leave, onto the manual form. " +
                "Without this the button spins forever.",
            DiscoveryVerdict(email, MailAutoconfigResult.NotFound),
            verdictAfterRefusal(null, email, online = true),
        )
    }

    @Test fun `a refusal for want of a network records NOTHING`() {
        assertNull(
            "offline nobody asked this domain anything, so NotFound would be a fabricated answer " +
                "— and one that OUTLIVES the outage: held in hand, addressAdvance reads it once " +
                "the link is back and sends her to the manual form, with no probe ever setting " +
                "out for that address again. What answers her offline is AddressAdvance.Offline.",
            verdictAfterRefusal(null, email, online = false),
        )
    }

    @Test fun `offline never demotes a verdict about another address either`() {
        assertSame(
            "the same rule, on the state that made it visible: a Found for another domain must be " +
                "left exactly as it is, not replaced by an offline invention about this one.",
            foundVerdict, verdictAfterRefusal(foundVerdict, "alex@autre.tld", online = false),
        )
    }

    @Test fun `a refusal never demotes an answer already in hand for the same address`() {
        assertSame(
            "the guard refuses over a filled host field — the very state a successful discovery " +
                "leaves behind — so a refusal that overwrote its own Found would send the " +
                "credentials step to the manual form on the next trigger.",
            foundVerdict, verdictAfterRefusal(foundVerdict, email, online = true),
        )
    }

    @Test fun `a refusal about a new address replaces a verdict about the old one`() {
        assertEquals(
            "the verdict in hand is about another domain: it must not answer for this one, and " +
                "the refusal is what the walk has to act on.",
            DiscoveryVerdict("alex@autre.tld", MailAutoconfigResult.NotFound),
            verdictAfterRefusal(foundVerdict, "alex@autre.tld", online = true),
        )
    }

    @Test fun `a refusal about an address the walk cannot act on records nothing`() {
        assertSame(
            "the field is being retyped — mid-edit it is not an address at all. Recording a " +
                "verdict about it would throw away the real one held for what she is retyping.",
            foundVerdict, verdictAfterRefusal(foundVerdict, "", online = true),
        )
        assertNull(verdictAfterRefusal(null, "alex", online = true))
    }

    // ---- addressAdvance: what the address step's button does ----

    @Test fun `an address the shared rule rejects blocks the button`() {
        assertEquals(AddressAdvance.Blocked, addressAdvance("alex", null, null, online = true))
        assertEquals(AddressAdvance.Blocked, addressAdvance("", foundVerdict, null, online = true))
    }

    @Test fun `offline does not enable the button on an address that is not one`() {
        assertEquals(
            "Blocked comes FIRST: offline is a sentence about a probe, and there is no probe to " +
                "speak of for `alex`. Answer Offline here and the button lights up on a half-typed " +
                "address, to say something that is beside the point.",
            AddressAdvance.Blocked, addressAdvance("alex", null, null, online = false),
        )
    }

    @Test fun `a valid address with no verdict yet waits`() {
        assertEquals(
            "the probe is out (or is being asked for): the step must not decide where to go " +
                "before it answers — deciding early is deciding MANUAL, i.e. the protocol question.",
            AddressAdvance.Waiting, addressAdvance(email, null, null, online = true),
        )
    }

    @Test fun `the same address, offline, is answered offline and not asked to wait`() {
        assertEquals(
            "⛔ THE ORDER, on the one pair of calls that can see it: same address, same empty " +
                "hand, only the link differs. Waiting is what re-fires the probe and draws the " +
                "wheel, and offline no probe may set out (shouldDiscoverImapSettings) nor be " +
                "recorded (verdictAfterRefusal) — so 'wait' here is a wheel that turns until the " +
                "app is killed. Both leave her on step 1; only one of them owes her a sentence.",
            AddressAdvance.Offline, addressAdvance(email, null, null, online = false),
        )
        assertEquals(
            "and the witness beside it: online, the very same arguments must still WAIT — an " +
                "offline answer for everyone is not a fix, it is the walk switched off.",
            AddressAdvance.Waiting, addressAdvance(email, null, null, online = true),
        )
    }

    @Test fun `a verdict about another address waits, it does not decide`() {
        assertEquals(
            AddressAdvance.Waiting, addressAdvance("alex@autre.tld", foundVerdict, null, online = true),
        )
    }

    @Test fun `a verdict about another address, offline, is answered offline`() {
        assertEquals(
            "there is no verdict FOR this address, so there is nothing to go on and nothing to " +
                "wait for either: the offline answer is about the probe that would have to run.",
            AddressAdvance.Offline, addressAdvance("alex@autre.tld", foundVerdict, null, online = false),
        )
    }

    @Test fun `a discovered server leads to the credentials step`() {
        assertEquals(
            AddressAdvance.Go(ConnectStep.CREDENTIALS), addressAdvance(email, foundVerdict, null, online = true),
        )
    }

    @Test fun `a domain that published nothing leads to the manual form`() {
        assertEquals(
            AddressAdvance.Go(ConnectStep.MANUAL), addressAdvance(email, notFoundVerdict, null, online = true),
        )
    }

    @Test fun `a verdict in hand is still an answer offline`() {
        assertEquals(
            "the answer was obtained, and the link dropping afterwards does not un-obtain it: the " +
                "walk moves on, and the sign-in beyond has its own offline guard. Refusing here " +
                "would strand her on step 1 holding a verdict she cannot use.",
            AddressAdvance.Go(ConnectStep.CREDENTIALS), addressAdvance(email, foundVerdict, null, online = false),
        )
        assertEquals(
            "and the failure verdict likewise: the manual form is exactly where an offline user " +
                "with a domain that published nothing can still type what she knows.",
            AddressAdvance.Go(ConnectStep.MANUAL), addressAdvance(email, notFoundVerdict, null, online = false),
        )
    }

    // ---- addressAdvance and the OAuth answer: it decides WHERE, never WHETHER (#55) ----

    @Test fun `a domain served over OAuth without autoconfig leads to the credentials step`() {
        assertEquals(
            "⛔ THE reported case (#55). The domain publishes no Thunderbird document, so the " +
                "cascade answers NotFound — but the OAuth search that ran in the same pass found " +
                "the server that signs this account in, and its answer is already in hand. " +
                "Answering MANUAL throws it away and asks the reader for hostnames the app has.",
            AddressAdvance.Go(ConnectStep.CREDENTIALS),
            addressAdvance(email, notFoundVerdict, oauthUsable, online = true),
        )
    }

    @Test fun `a failed cascade with no usable OAuth still leads to the manual form`() {
        assertEquals(
            "the witness beside the case above: no OAuth answer at all, and the shipped walk is " +
                "untouched. Green here for every input is the fix switched off.",
            AddressAdvance.Go(ConnectStep.MANUAL),
            addressAdvance(email, notFoundVerdict, null, online = true),
        )
        assertEquals(
            "the search ran and nothing usable answered it.",
            AddressAdvance.Go(ConnectStep.MANUAL),
            addressAdvance(email, notFoundVerdict, oauthNothing, online = true),
        )
        assertEquals(
            "⛔ OAuth advertised with no grant this app can drive is NOT a way through: step 2 " +
                "would draw a password field, and with no settings verdict behind it that field " +
                "has no server to send anything to — a dead end further from the form than here.",
            AddressAdvance.Go(ConnectStep.MANUAL),
            addressAdvance(email, notFoundVerdict, oauthUndrivable, online = true),
        )
        assertEquals(
            "and an OAuth answer about the address she walked back and replaced decides nothing.",
            AddressAdvance.Go(ConnectStep.MANUAL),
            addressAdvance(email, notFoundVerdict, oauthElsewhere, online = true),
        )
    }

    @Test fun `an OAuth answer on its own does not move the walk off the address step`() {
        assertEquals(
            "⛔ THE ORDER, untouched. The two probes set out together but do not land together: " +
                "answer Go here and the walk decides 'this domain publishes no autoconfig' while " +
                "the cascade is still out — deciding MANUAL seconds early, for everyone.",
            AddressAdvance.Waiting,
            addressAdvance(email, null, oauthUsable, online = true),
        )
        assertEquals(
            "and offline is still answered before waiting: an answer in hand is not a link, and " +
                "no probe may set out to bring the other one back.",
            AddressAdvance.Offline,
            addressAdvance(email, null, oauthUsable, online = false),
        )
    }

    // ---- awaitedAnswer: how the address step's wait ends — and that it ALWAYS can ----

    @Test fun `a verdict landing while nothing is awaited carries nobody off the step`() {
        assertEquals(
            "the effect that reads this re-runs on every keystroke (the address is one of its " +
                "keys), so a verdict already in hand for what she has just finished typing arrives " +
                "here with no wait armed. Answer Leave and the walk jumps to step 2 mid-word — the " +
                "'no automatic jump' the bench signed off on, gone.",
            AwaitedAnswer.Wait,
            awaitedAnswer(awaiting = false, advance = AddressAdvance.Go(ConnectStep.CREDENTIALS)),
        )
    }

    @Test fun `offline says nothing while nothing is awaited`() {
        assertEquals(
            "she has not asked for anything yet: an offline sentence appearing under a field she " +
                "is still typing in accuses the app of a failure nobody attempted.",
            AwaitedAnswer.Wait,
            awaitedAnswer(awaiting = false, advance = AddressAdvance.Offline),
        )
    }

    @Test fun `the awaited verdict ends the wait, for the step it names`() {
        assertEquals(
            "the whole point of arming the wait: leave the moment the probe answers, for exactly " +
                "where addressAdvance says.",
            AwaitedAnswer.Leave(ConnectStep.CREDENTIALS),
            awaitedAnswer(awaiting = true, advance = AddressAdvance.Go(ConnectStep.CREDENTIALS)),
        )
    }

    @Test fun `and for the manual form when that is the step it names`() {
        assertEquals(
            "the step is carried through from the advance, never re-decided here.",
            AwaitedAnswer.Leave(ConnectStep.MANUAL),
            awaitedAnswer(awaiting = true, advance = AddressAdvance.Go(ConnectStep.MANUAL)),
        )
    }

    @Test fun `a wait with no answer coming is ended, and said`() {
        assertEquals(
            "⛔ THE LOCK. Offline no probe sets out and none is recorded (verdictAfterRefusal), so " +
                "nothing will ever land to end this wait: the button stays disabled on 'Finding " +
                "your server…' for ever, and disabled it cannot even be tapped to say why. Answer " +
                "Wait here and the reader's only way out of the screen is to edit her address.",
            AwaitedAnswer.SayOffline,
            awaitedAnswer(awaiting = true, advance = AddressAdvance.Offline),
        )
    }

    @Test fun `a probe still out is still worth waiting for`() {
        assertEquals(
            "online with no verdict yet is the normal second or two after the tap; ending the " +
                "wait here would decide 'manual' before the domain answered.",
            AwaitedAnswer.Wait,
            awaitedAnswer(awaiting = true, advance = AddressAdvance.Waiting),
        )
    }

    @Test fun `an address that is not one ends no wait`() {
        assertEquals(
            "there is no answer to give about an address the walk cannot act on; editing the " +
                "field is what disarms the wait.",
            AwaitedAnswer.Wait,
            awaitedAnswer(awaiting = true, advance = AddressAdvance.Blocked),
        )
    }

    // ---- offersManualSetup: the door to the manual form opens on a failure and nothing else ----

    @Test fun `the manual door opens once the probe came back empty`() {
        assertTrue(offersManualSetup(notFoundVerdict, null, email))
    }

    @Test fun `the manual door stays shut beside a verdict that worked`() {
        assertFalse(
            "offered next to a discovered server, 'Set up manually' puts the protocol question " +
                "back on screen for everyone — the defect this walk exists to remove.",
            offersManualSetup(foundVerdict, null, email),
        )
    }

    @Test fun `the manual door stays shut when the OAuth answer is the way through`() {
        assertFalse(
            "⛔ #55: a drivable OAuth answer IS a verdict that worked, autoconfig or not — the " +
                "walk now leads to the credentials step, so the protocol question offered here " +
                "would stand beside a hand-over about to happen. The door is not withdrawn, it is " +
                "one step along: credentialsExit offers it under the sign-in offer.",
            offersManualSetup(notFoundVerdict, oauthUsable, email),
        )
        assertTrue(
            "and the witness: the same empty cascade with OAuth advertised but not drivable still " +
                "opens the door. Shut it here and that domain can be added by no gesture at all.",
            offersManualSetup(notFoundVerdict, oauthUndrivable, email),
        )
        assertTrue(
            "nor does an OAuth answer about another address take this address' door away.",
            offersManualSetup(notFoundVerdict, oauthElsewhere, email),
        )
    }

    @Test fun `the manual door stays shut while no probe has answered`() {
        assertFalse(
            "before any verdict there is nothing to fall back FROM: the door would invite the " +
                "user to answer the question the probe is about to answer for her.",
            offersManualSetup(null, null, email),
        )
        assertFalse(
            "and a failure about another address is not this address' failure.",
            offersManualSetup(notFoundVerdict, null, "alex@autre.tld"),
        )
    }

    // ---- stepBackKeeping: Back keeps the address ----

    @Test fun `back from the credentials step returns to the address, address kept`() {
        assertEquals(
            "retyping the address at every round trip is how a walk becomes worse than the single " +
                "page it replaced.",
            WalkBack(ConnectStep.ADDRESS, email), stepBackKeeping(ConnectStep.CREDENTIALS, email),
        )
    }

    @Test fun `back from the manual form returns to the address, address kept`() {
        assertEquals(WalkBack(ConnectStep.ADDRESS, email), stepBackKeeping(ConnectStep.MANUAL, email))
    }

    @Test fun `back from the address step is not ours`() {
        assertNull(
            "null is what leaves Back to the system: the screen is popped (or the first-run home " +
                "stays put). Answering it ourselves is how a walk paints a blank screen.",
            stepBackKeeping(ConnectStep.ADDRESS, email),
        )
    }

    // ---- presetForAddress: what a lost or stale verdict invalidates, and what it must not touch ----

    private val filledForA = presetFilledWith(PresetForm.NONE, found, email)

    @Test fun `a discovery whose verdict is GONE does not lock the form for good`() {
        assertEquals(
            "⛔ THE RATCHET. The form survives process death, the verdict does not. Come back with " +
                "the host fields full and nothing backing them and the cascade's guard refuses " +
                "every probe, each refusal records 'nothing published', and that answer then " +
                "sticks to every address typed afterwards: one successful discovery became a " +
                "permanent failure. Blank fields are what let the probe set out again.",
            PresetForm.NONE,
            presetForAddress(filledForA, null, email, ConnectStep.ADDRESS),
        )
    }

    @Test fun `a new address drops what the old one's verdict filled in`() {
        assertEquals(
            "the discovered hosts are the OLD domain's, and kept they are also what makes the " +
                "guard refuse to probe the new one.",
            PresetForm.NONE,
            presetForAddress(filledForA, foundVerdict, "alex@autre.tld", ConnectStep.ADDRESS),
        )
    }

    @Test fun `a verdict still backing the fields keeps them`() {
        assertSame(
            "same address, a Found in hand: the fields say what the probe said, and a needless " +
                "wipe here would send the walk round the cascade again on every recomposition.",
            filledForA, presetForAddress(filledForA, foundVerdict, email, ConnectStep.ADDRESS),
        )
    }

    @Test fun `nothing published cannot back a filled form`() {
        assertEquals(
            "a NotFound verdict is not what put hosts in these fields; treating it as backing " +
                "them would leave the guard jammed with the answer that jammed it.",
            PresetForm.NONE,
            presetForAddress(filledForA, notFoundVerdict, email, ConnectStep.ADDRESS),
        )
    }

    @Test fun `a host the user typed herself is never dropped`() {
        val mine = PresetForm(
            imapHost = "mien.tld", imapPort = "1993", imapSecurity = ConnectionSecurity.TLS,
            smtpHost = "envoi.mien.tld", smtpPort = "2525", smtpSecurity = ConnectionSecurity.TLS,
        )
        assertSame(
            "discoveredFor is unset: nothing here came from a probe, so nothing here is the " +
                "walk's to throw away — not on a stale verdict, and not on no verdict at all.",
            mine, presetForAddress(mine, foundVerdict, "alex@autre.tld", ConnectStep.ADDRESS),
        )
        assertSame(mine, presetForAddress(mine, null, email, ConnectStep.ADDRESS))
    }

    @Test fun `the manual form is never emptied under the user`() {
        assertSame(
            "⛔ on the manual form the fields ARE the work in progress, stale or not: she can see " +
                "them and correct them, and a wipe there costs her a configuration she was in the " +
                "middle of. The drop belongs to the address step, where no host is ever shown.",
            filledForA, presetForAddress(filledForA, null, email, ConnectStep.MANUAL),
        )
        assertSame(filledForA, presetForAddress(filledForA, null, email, ConnectStep.CREDENTIALS))
    }

    @Test fun `a form nothing was discovered into comes back untouched`() {
        assertSame(PresetForm.NONE, presetForAddress(PresetForm.NONE, null, email, ConnectStep.ADDRESS))
    }

    // ---- clearingIsDue: a rotation is not an arrival ----

    @Test fun `a step already cleared for is not cleared again, at every step of the walk`() {
        assertEquals(
            "⛔ THE DEFECT, EXECUTED, measured at the bench (emu, release, airplane mode): the " +
                "reader taps Continue, is told \"You're offline…\", turns the phone " +
                "portrait→landscape→portrait, and the sentence is gone from all three uiautomator " +
                "trees while the address she typed is still there. A rotation RECREATES the " +
                "composition, so the seam keyed on the step runs again on a step it has already " +
                "cleared for and wipes the answer. Every step must answer false to itself — the " +
                "credentials and manual steps rotate too, and `cleared != arriving` inverted, " +
                "widened to `arriving != ConnectStep.ADDRESS`, or replaced by `true` all land here.",
            listOf(
                ConnectStep.ADDRESS to false,
                ConnectStep.CREDENTIALS to false,
                ConnectStep.MANUAL to false,
            ),
            ConnectStep.entries.map { it to clearingIsDue(it, it) },
        )
    }

    @Test fun `a screen that has cleared nothing yet clears, at every step of the walk`() {
        assertEquals(
            "⛔ NULL IS NOT \"THE FIRST STEP\", and this is the half a marker starting at the step " +
                "on screen gets wrong. The view model is NOT new every time this screen appears: " +
                "at first run the screen is composed straight under the activity, which owns it, " +
                "so an attempt that ended before the screen last went away is still in hand when " +
                "it comes back — and would be read out on an add-account screen nobody had used " +
                "yet. An arriving screen has no saved marker; a rotation restores one.",
            listOf(
                ConnectStep.ADDRESS to true,
                ConnectStep.CREDENTIALS to true,
                ConnectStep.MANUAL to true,
            ),
            ConnectStep.entries.map { it to clearingIsDue(null, it) },
        )
    }

    @Test fun `every real change of step still clears`() {
        val moves = ConnectStep.entries
            .flatMap { from -> ConnectStep.entries.map { to -> from to to } }
            .filter { (from, to) -> from != to }
        assertEquals(
            "⛔ the guard is about a step ALREADY CLEARED FOR, never about keeping a finished " +
                "attempt: a stale NeedsServer carried into the credentials step re-fires the IMAP " +
                "hand-over and adds an account on a tap on 'Continue' that nobody meant as a " +
                "sign-in — the one irreversible thing on this screen. All six ordered pairs, " +
                "demotions (process death takes the verdict, not the step) included.",
            moves.associateWith { true },
            moves.associateWith { (from, to) -> clearingIsDue(from, to) },
        )
    }

    // ---- clearsAttemptOnLeave: what a finished attempt leaves behind does not travel ----

    @Test fun `a finished attempt is cleared when the walk moves`() {
        assertTrue(
            "⛔ a stale NeedsServer re-fired the IMAP handover the moment the credentials step " +
                "was re-entered: an account added on a tap on 'Continue' that nobody meant as a " +
                "sign-in.",
            clearsAttemptOnLeave(ConnectState.NeedsServer),
        )
        assertTrue(clearsAttemptOnLeave(ConnectState.Error("nope")))
    }

    @Test fun `an attempt still running is never cleared`() {
        assertFalse(
            "⛔ every add route refuses a second submit by reading exactly this state. Cleared, " +
                "the user gets a second Connect over a coroutine still in flight — two accounts.",
            clearsAttemptOnLeave(ConnectState.Connecting),
        )
        assertFalse(clearsAttemptOnLeave(ConnectState.Discovering))
        assertFalse(
            "the browser flows own their own cancel button; the walk must not disarm them.",
            clearsAttemptOnLeave(ConnectState.AwaitingApproval("ABCD", "https://x", null)),
        )
        assertFalse(clearsAttemptOnLeave(ConnectState.AwaitingBrowser("https://x")))
    }

    @Test fun `nothing to clear is nothing to do`() {
        assertFalse(clearsAttemptOnLeave(ConnectState.Idle))
        assertFalse(
            "Connected is what navigates away; clearing it would strand the user on this screen.",
            clearsAttemptOnLeave(ConnectState.Connected),
        )
    }

    // ---- appPasswordUrlFor: the help link, only where the provider is recognised ----

    @Test fun `a discovered Gmail gets Google's app-password page`() {
        val gmail = found.copy(incoming = MailEndpoint("imap.gmail.com", 993, ConnectionSecurity.TLS))
        assertEquals(
            "Gmail refuses a normal password: without this link the credentials step asks for a " +
                "secret the provider will always reject.",
            "https://myaccount.google.com/apppasswords", appPasswordUrlFor(gmail),
        )
    }

    @Test fun `a provider that needs no app password gets no link`() {
        val fastmail = found.copy(incoming = MailEndpoint("imap.fastmail.com", 993, ConnectionSecurity.TLS))
        assertNull(appPasswordUrlFor(fastmail))
    }

    @Test fun `an unknown host gets no link`() {
        assertNull(
            "a guessed page sends the user to somebody else's account settings.",
            appPasswordUrlFor(found),
        )
    }

    // ---- shouldTryDiscoveredImap: JMAP first, then the published IMAP endpoints ----

    @Test fun `no JMAP server plus published endpoints hands over to IMAP`() {
        assertTrue(
            shouldTryDiscoveredImap(ConnectStep.CREDENTIALS, ConnectState.NeedsServer, found),
        )
    }

    @Test fun `a refused secret is never replayed at another door`() {
        assertFalse(
            "Error means the server answered and said no — a typo, most of the time. Trying IMAP " +
                "with the same secret earns a second rejection and hides the first.",
            shouldTryDiscoveredImap(ConnectStep.CREDENTIALS, ConnectState.Error("nope"), found),
        )
        assertFalse(
            "and nothing hands over while the JMAP attempt is still running.",
            shouldTryDiscoveredImap(ConnectStep.CREDENTIALS, ConnectState.Connecting, found),
        )
        assertFalse(
            shouldTryDiscoveredImap(ConnectStep.CREDENTIALS, ConnectState.Idle, found),
        )
    }

    @Test fun `nothing hands over without endpoints to hand over to`() {
        assertFalse(
            "no published configuration, no host to dial: that address belongs on the manual form.",
            shouldTryDiscoveredImap(ConnectStep.CREDENTIALS, ConnectState.NeedsServer, null),
        )
    }

    @Test fun `the manual form drives itself`() {
        assertFalse(
            "on the manual form NeedsServer is the user's cue to type a JMAP server (the screen " +
                "reveals the field). Signing in over IMAP behind her back there would ignore the " +
                "protocol she chose herself.",
            shouldTryDiscoveredImap(ConnectStep.MANUAL, ConnectState.NeedsServer, found),
        )
        assertFalse(
            shouldTryDiscoveredImap(ConnectStep.ADDRESS, ConnectState.NeedsServer, found),
        )
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ConnectWalkRotationWiringTest {

    @Test fun `the clearing seam runs what it says, and nothing else`() {
        assertSeamBody(
            opener = "    LaunchedEffect(walkStep) {",
            statements = listOf(
                "        if (rememberedStep != walkStep) rememberedStep = walkStep",
                "        if (clearingIsDue(clearedFor, walkStep)) {",
                "            clearedFor = walkStep",
                "            viewModel.clearFinishedAttempt()",
                "        }",
            ),
            why = "⛔ THE WHOLE BODY OF THE SEAM, EVERY STATEMENT, IN THIS ORDER AND AT THIS DEPTH.\n" +
                "  · the opener carries the KEY, and it is the thread the guard hangs from. " +
                "`LaunchedEffect(Unit)` compiles, keeps every line below intact, and passes every " +
                "executed test: the seam then runs once per composition and never again, so a " +
                "real change of step clears nothing. Nothing in this repo pinned the key of an " +
                "effect until one was found killing a whole volet with the suite green;\n" +
                "  · the guard is [clearingIsDue], executed in ConnectWalkTest — not a condition " +
                "spelled out again here, which nothing that runs could ever see;\n" +
                "  · it is asked with `clearedFor`, the step this seam last cleared for. ⛔ NOT " +
                "`rememberedStep`: that one is synchronised with walkStep on the line above, " +
                "before this runs (it differs only on a demotion), so the guard would be true " +
                "almost never and false on the one case that must clear;\n" +
                "  · `clearFinishedAttempt()` is INSIDE the branch. Left outside it, the rotation " +
                "wipes the sentence again and every executed test stays green;\n" +
                "  · the marker is advanced in the SAME branch. Advanced outside the guard it is " +
                "written on every run, which is the same thing as no marker at all; never " +
                "advanced, the seam re-clears every step it comes back to;\n" +
                "  · and NO OTHER STATEMENT. One `return@LaunchedEffect` above the guard — say on " +
                "ConnectState.NeedsServer — and a stale verdict is never cleared again, on any " +
                "path: it re-fires the IMAP hand-over on re-entering the credentials step and " +
                "adds an account on a tap on 'Continue' that nobody meant as a sign-in.",
        )
        assertOccursOnce("viewModel.clearFinishedAttempt()")
        assertOccursOnce("clearingIsDue(")
    }

    @Test fun `the marker is saved, and starts at no step at all`() {
        assertRawRun(
            listOf(
                "    var clearedFor by rememberSaveable(stateSaver = ClearedStepSaver) {",
                "        mutableStateOf<ConnectStep?>(null)",
                "    }",
            ),
            "⛔ THE TWO HALVES OF THE MARKER, AND EACH ONE IS A DEFECT ON ITS OWN.\n" +
                "  · SAVED. A plain `remember` re-initialises on rotation, so the marker would " +
                "read \"nothing cleared yet\" on the very recomposition it exists to recognise, " +
                "and the bench case comes back exactly as it was;\n" +
                "  · and NULL, not `walkStep`, not `ConnectStep.ADDRESS`. Null is what an " +
                "ARRIVING screen holds, and an arrival must still clear: the view model is not " +
                "new every time this screen appears — at first run the screen is composed " +
                "straight under the activity, which owns it — so an attempt that ended before it " +
                "last went away would be read out on an add-account screen nobody had used yet. " +
                "`ConnectStep.ADDRESS` in particular fixes the bench case at the address step and " +
                "leaves every rotation on the credentials and manual steps wiping the sentence as " +
                "before, since it reads \"ADDRESS → CREDENTIALS\" and calls the arrival due.",
        )
        assertRawRun(
            listOf(
                "private val ClearedStepSaver = listSaver<ConnectStep?, String>(",
                "    save = { listOf(it?.name ?: NOTHING_CLEARED) },",
                "    restore = { saved -> saved[0].takeIf { it != NOTHING_CLEARED }?.let(ConnectStep::valueOf) },",
                ")",
            ),
            "⛔ AND WHAT IS SAVED MUST COME BACK THE SAME, both ways. The saver is the only thing " +
                "that makes the marker survive a rotation and stay null on an arrival: a `restore` " +
                "that answers a step for a screen that cleared nothing turns every arrival into a " +
                "rotation (the stale sentence comes back), and one that answers null for a saved " +
                "step turns every rotation into an arrival (the bench case comes back). Nothing " +
                "executes this saver — the module has no instrumented tests — so it is held here, " +
                "raw, with the sentinel it reads.",
        )
    }

    // -- reading the file ---------------------------------------------------------------------

    /**
     * The block opened by [opener] must contain EXACTLY [statements], raw and in order — comments
     */
    private fun assertSeamBody(opener: String, statements: List<String>, why: String) {
        val lines = source().lines()
        val opened = lines.withIndex().filter { it.value == opener }.map { it.index }
        assertEquals(
            "expected exactly one line reading `$opener` in $SCREEN — the seam this test is " +
                "about. Found ${opened.size}. $why",
            1, opened.size,
        )
        val closer = " ".repeat(opener.takeWhile { it == ' ' }.length) + "}"
        val end = (opened[0] + 1 until lines.size).firstOrNull { lines[it] == closer }
            ?: error("no line reading `$closer` closes the block opened at line ${opened[0] + 1}")
        val body = lines.subList(opened[0] + 1, end)
            .filter { it.isNotBlank() && !it.trim().startsWith("//") }
        assertEquals(
            "the body of `$opener` in $SCREEN must be exactly these statements, raw, in order:\n" +
                statements.joinToString("\n") { "|$it" } + "\nand it is:\n" +
                body.joinToString("\n") { "|$it" } + "\n$why",
            statements, body,
        )
    }

    /** [run] must appear in [SCREEN] as consecutive RAW lines (indentation included), exactly once. */
    private fun assertRawRun(run: List<String>, why: String) {
        val lines = source().lines()
        val hits = (0..(lines.size - run.size)).count { lines.subList(it, it + run.size) == run }
        assertEquals(
            "expected exactly one place in $SCREEN where these lines follow one another, with " +
                "exactly this indentation:\n" + run.joinToString("\n") { "|$it" } +
                "\nbut found $hits. A line was rewritten, removed, split, duplicated, reordered, " +
                "re-indented (i.e. wrapped in another block), or something was inserted between " +
                "them. $why",
            1, hits,
        )
    }

    private fun assertOccursOnce(fragment: String) {
        val n = Regex(Regex.escape(fragment)).findAll(source()).count()
        assertEquals(
            "`$fragment` must occur exactly once in $SCREEN — the pinned site above. A second " +
                "occurrence is a second place deciding the same thing, and the one no executed " +
                "test reaches is the one that drifts. Found $n.",
            1, n,
        )
    }

    private fun source(): String =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, SCREEN).isFile }
            ?.let { File(it, SCREEN).readText() }
            ?: error(
                "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                    "source file as text and needs a working directory inside the checkout",
            )

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
    }
}
