package app.sterna.ui.connect

import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.OAuthMetadata

// The add-account walk's decisions, beyond what ConnectStep and its transitions
// (stepAfterAddress, stepToRender, stepBack in ConnectPreset.kt) say.

/** A discovery verdict and the address it is a verdict about — a bare `MailAutoconfigResult` would
 *  answer for a domain it never probed, after a rotation or a walk back to another address. */
internal data class DiscoveryVerdict(val email: String, val result: MailAutoconfigResult)

/** The verdict in hand for [email], or null: a verdict about another address is no verdict at all. */
internal fun verdictFor(verdict: DiscoveryVerdict?, email: String): MailAutoconfigResult? =
    verdict?.takeIf { it.email == email.trim() }?.result

/** What a probe that never set out records. Offline it records nothing: a `NotFound` would outlive
 *  the outage and send her to the manual form without probing again. Nothing either over an answer
 *  already held for the same address, or a rotation would demote its own Found. */
internal fun verdictAfterRefusal(
    current: DiscoveryVerdict?,
    email: String,
    online: Boolean,
): DiscoveryVerdict? = when {
    !online -> current
    !canLeaveAddressStep(email) -> current
    current?.email == email.trim() -> current
    else -> DiscoveryVerdict(email.trim(), MailAutoconfigResult.NotFound)
}

/** What the address step's button does when it is tapped. */
internal sealed interface AddressAdvance {
    data object Blocked : AddressAdvance

    data object Waiting : AddressAdvance

    /** Distinct from [Waiting], where the probe waited for cannot set out
     *  ([shouldDiscoverImapSettings]) and the wheel therefore never stops. */
    data object Offline : AddressAdvance

    /** [stepAfterAddress]'s answer, delegated, never recomputed here. */
    data class Go(val step: ConnectStep) : AddressAdvance
}

/** Where a tap on the address step's button leads. A verdict in hand answers whatever the link is
 *  doing, so [AddressAdvance.Go] comes first; with no verdict, offline is answered before waiting,
 *  or the caller spins for a probe that cannot set out. */
internal fun addressAdvance(
    email: String,
    verdict: DiscoveryVerdict?,
    oauth: OAuthDiscovery?,
    online: Boolean,
): AddressAdvance {
    if (!canLeaveAddressStep(email)) return AddressAdvance.Blocked
    val result = verdictFor(verdict, email)
    if (result != null) return AddressAdvance.Go(stepAfterAddress(result, oauth, email))
    return if (online) AddressAdvance.Waiting else AddressAdvance.Offline
}

/** The only ways the address step's wait for a verdict ends. */
internal sealed interface AwaitedAnswer {
    data object Wait : AwaitedAnswer

    data class Leave(val step: ConnectStep) : AwaitedAnswer

    data object SayOffline : AwaitedAnswer
}

/** What to do about a wait for the address step's verdict. Offline an armed wait has no other way
 *  out (it survives process death while the verdict does not). [awaiting] is read first because the
 *  caller's effect also runs with no wait armed, on every keystroke. */
internal fun awaitedAnswer(awaiting: Boolean, advance: AddressAdvance): AwaitedAnswer = when {
    !awaiting -> AwaitedAnswer.Wait
    advance is AddressAdvance.Go -> AwaitedAnswer.Leave(advance.step)
    advance is AddressAdvance.Offline -> AwaitedAnswer.SayOffline
    else -> AwaitedAnswer.Wait
}

/** Whether the address step may offer "Set up manually" — only after a probe for this address came
 *  back with nothing. A drivable OAuth answer counts as one that worked; the door moves to
 *  [CredentialsPane.SIGN_IN_OFFER] rather than disappearing. */
internal fun offersManualSetup(
    verdict: DiscoveryVerdict?,
    oauth: OAuthDiscovery?,
    email: String,
): Boolean = verdictFor(verdict, email) is MailAutoconfigResult.NotFound &&
    !oauthOpensCredentials(oauth, email)

/** Where Back lands, and the address it lands with. */
internal data class WalkBack(val step: ConnectStep, val email: String)

/** Back from [current], carrying [email] through untouched, so the screen cannot quietly drop "Back
 *  keeps what she typed". `null` (from [stepBack]) means Back is not ours: the system pops. */
internal fun stepBackKeeping(current: ConnectStep, email: String): WalkBack? =
    stepBack(current)?.let { WalkBack(it, email) }

/** The quick-setup form to carry into a probe for [email], at the [step] on screen. The cascade's
 *  guard refuses over a non-blank host, and a discovery that worked leaves one behind in a form
 *  the user typed ([PresetForm.discoveredFor]) and on the manual form. */
internal fun presetForAddress(
    preset: PresetForm,
    verdict: DiscoveryVerdict?,
    email: String,
    step: ConnectStep,
): PresetForm {
    if (step != ConnectStep.ADDRESS) return preset
    val filledFor = preset.discoveredFor ?: return preset
    // A Found, not merely "a verdict": NotFound is not what put hosts in these fields, and
    // reading it as backing them leaves the guard jammed with the very answer that jammed it.
    val backed = filledFor == email.trim() && verdictFor(verdict, email) is MailAutoconfigResult.Found
    if (backed) return preset
    return preset.copy(
        imapHost = PresetForm.NONE.imapHost,
        imapPort = PresetForm.NONE.imapPort,
        imapSecurity = PresetForm.NONE.imapSecurity,
        smtpHost = PresetForm.NONE.smtpHost,
        smtpPort = PresetForm.NONE.smtpPort,
        smtpSecurity = PresetForm.NONE.smtpSecurity,
        discoveredFor = null,
    )
}

/** Whether leaving a step must wipe what the last attempt left in [state]. Finished attempts only:
 *  the running states are what every add route reads to refuse a second submit. */
internal fun clearsAttemptOnLeave(state: ConnectState): Boolean =
    state is ConnectState.NeedsServer || state is ConnectState.Error

/** Whether what a finished attempt left is due to be cleared on arriving at [arriving]; [cleared]
 *  is the step already cleared for, null when nothing has been. A rotation recreates the
 *  composition with the step unmoved, so an effect keyed on the step alone wipes the sentence. */
internal fun clearingIsDue(cleared: ConnectStep?, arriving: ConnectStep): Boolean = cleared != arriving

/** The page where the provider behind [found] has the user create an app-specific password, or
 *  null. No match in [MAIL_PROVIDERS] means no link: a guess sends her to somebody else's page. */
internal fun appPasswordUrlFor(found: MailAutoconfigResult.Found): String? =
    MAIL_PROVIDERS.firstOrNull { it.imapHost.equals(found.incoming.host, ignoreCase = true) }?.appPasswordUrl

/** Whether the credentials step should now sign in over IMAP/SMTP. [ConnectState.NeedsServer] is
 *  the only state that hands over: an [ConnectState.Error] means the server answered and refused,
 *  and replaying the secret elsewhere makes a typo a second no. */
internal fun shouldTryDiscoveredImap(
    step: ConnectStep,
    state: ConnectState,
    found: MailAutoconfigResult.Found?,
): Boolean = step == ConnectStep.CREDENTIALS && state is ConnectState.NeedsServer && found != null

// ---- How the server says this account signs in ------------------------------------------------

/** What the OAuth search found for an address: the host that would sign this account in and what it
 *  advertises, `chosen == null` when nothing answered. The address is in it as in [DiscoveryVerdict]. */
internal data class OAuthDiscovery(val email: String, val chosen: Pair<String, OAuthMetadata>?)

/** What the credentials step asks for — the server's answer, never the user's choice. */
internal sealed interface CredentialsAsk {
    /** No OAuth answer for this address at all. Not reachable at the credentials step, where it
     *  draws a bare wheel for an address that can never be probed again; what keeps it out is that
     *  both answers travel together ([AddressProbe]). */
    data object Awaiting : CredentialsAsk

    data class SignInAway(val grant: OAuthGrant) : CredentialsAsk

    /** No OAuth, or none this app can drive: the password field. */
    data object Password : CredentialsAsk
}

/** What the credentials step must ask for [email]: password field or hand-over, which grant being
 *  [chooseOAuthGrant]'s call. A server advertising OAuth with no drivable grant asks for the
 *  password, and must not be told its server does not support OAuth, because it does. */
internal fun credentialsAsk(discovery: OAuthDiscovery?, email: String): CredentialsAsk {
    val answered = discovery?.takeIf { it.email == email.trim() } ?: return CredentialsAsk.Awaiting
    val chosen = answered.chosen ?: return CredentialsAsk.Password
    return when (val grant = chooseOAuthGrant(chosen.second)) {
        OAuthGrant.DEVICE_CODE, OAuthGrant.AUTHORIZATION_CODE -> CredentialsAsk.SignInAway(grant)
        OAuthGrant.NONE -> CredentialsAsk.Password
    }
}

/** Whether the OAuth answer in hand is one this walk can drive for [email] — the one spelling of
 *  "usable OAuth", so the address step, the manual door and the credentials step cannot disagree.
 *  Not `chosen != null`: a host with no drivable grant yields [CredentialsAsk.Password]. */
internal fun oauthOpensCredentials(discovery: OAuthDiscovery?, email: String): Boolean =
    credentialsAsk(discovery, email) is CredentialsAsk.SignInAway

/** Whether the screen must now start the OAuth sign-in on its own. Gated on the step (an answer
 *  collected at the address step must not launch a browser while she is still typing) and once per
 *  address, since a trigger that fires again reopens the browser the panel's Cancel refused. */
internal fun shouldStartOAuthSignIn(
    step: ConnectStep,
    ask: CredentialsAsk,
    handoverStarted: Boolean,
): Boolean = step == ConnectStep.CREDENTIALS && ask is CredentialsAsk.SignInAway && !handoverStarted

/** Whether a hand-over has already been started, on this screen, for the address on screen. One
 *  started for a previous address is no reason to withhold the automatic one. */
internal fun handoverStarted(startedFor: String, email: String): Boolean =
    startedFor != NO_HANDOVER && startedFor == email.trim()

/** No hand-over started yet — the initial value of the screen's "started for" address. */
internal const val NO_HANDOVER = ""

/** What the credentials step draws under the server's answer. */
internal enum class CredentialsPane {
    PASSWORD,

    /** A wheel, and nothing else: something is running that this step is waiting on. */
    WAITING,

    /** The invitation and its button: a hand-over is possible, and none is running. */
    SIGN_IN_OFFER,
}

/** What step 2 draws. [SIGN_IN_OFFER] is a hand-over over without an account: without it, Cancel
 *  comes back to a wheel turning for ever with nothing behind it. */
internal fun credentialsPane(
    ask: CredentialsAsk,
    state: ConnectState,
    handoverStarted: Boolean,
): CredentialsPane = when {
    ask is CredentialsAsk.Password -> CredentialsPane.PASSWORD
    ask !is CredentialsAsk.SignInAway -> CredentialsPane.WAITING
    !handoverStarted -> CredentialsPane.WAITING
    state is ConnectState.Idle || state is ConnectState.Error -> CredentialsPane.SIGN_IN_OFFER
    else -> CredentialsPane.WAITING
}

/** The step the credentials step must offer as a way out under [pane], or null when it owes none.
 *  A discovery that worked must not take a door away (#55): on [CredentialsPane.SIGN_IN_OFFER]
 *  there is no password field, and the address step's door is withheld on a `Found`. */
internal fun credentialsExit(pane: CredentialsPane): ConnectStep? =
    if (pane == CredentialsPane.SIGN_IN_OFFER) ConnectStep.MANUAL else null

/** Whether a hand-over panel (device code, or browser authorization) owns the screen. Those panels
 *  return out of the column early, so a Back handler declared below them is never composed. */
internal fun showsHandoverPanel(state: ConnectState): Boolean =
    state is ConnectState.AwaitingApproval || state is ConnectState.AwaitingBrowser

/** Whether the system Back must cancel the hand-over whose panel is on screen. The state says a
 *  panel is up, never whose: the manual form's Microsoft chip mirrors into the same
 *  [ConnectState.AwaitingApproval] but is app-scoped, and its poll must survive this screen. */
internal fun backCancelsHandover(state: ConnectState, startedByWalk: Boolean): Boolean =
    startedByWalk && showsHandoverPanel(state)

/** The OAuth server already found for [email], reusable by the sign-in itself, or null to search.
 *  A [server] typed by hand under Advanced is the one host sign-in must ask. Reuse at all, because
 *  a second probe would report a host that answered once but not twice as unsupported. */
internal fun reusableOAuth(
    discovery: OAuthDiscovery?,
    email: String,
    server: String,
): Pair<String, OAuthMetadata>? =
    if (server.isBlank()) discovery?.takeIf { it.email == email.trim() }?.chosen else null

// ---- What the foot of the column says, and whether it has to be read ---------------------------

/** What the status block at the foot of the walk has to say. A value before it is a `Text`, because
 *  it is also what a scroll has to aim at: drawn from one reading of [ConnectState] and scrolled
 *  from another, the two spellings drift in silence. */
internal sealed interface StatusLine {
    /** A wheel, not a sentence — see [statusMustBeRead]. */
    data object Working : StatusLine

    data object ServerNotFound : StatusLine

    data class Failed(val message: String) : StatusLine
}

/** What the block says for [state] at [step], or null for the states that say nothing. No step
 *  condition may be added to the failure branch: the address step's own offline answer is a
 *  [ConnectState.Error], so conditioning it there renders nothing. The wheel is drawn once (#55),
 *  and it is this block that goes quiet, never [credentialsPane]'s. */
internal fun statusLine(
    state: ConnectState,
    step: ConnectStep,
    ask: CredentialsAsk,
    handoverStarted: Boolean,
): StatusLine? = when (state) {
    is ConnectState.Connecting, is ConnectState.Discovering ->
        if (step == ConnectStep.CREDENTIALS &&
            credentialsPane(ask, state, handoverStarted) == CredentialsPane.WAITING
        ) {
            null
        } else {
            StatusLine.Working
        }
    is ConnectState.NeedsServer -> if (step == ConnectStep.MANUAL) StatusLine.ServerNotFound else null
    is ConnectState.Error -> StatusLine.Failed(state.message)
    else -> null
}

/** Whether [line] must be brought into view — i.e. whether it is a sentence. Offline at the address
 *  step the whole answer is one line at the foot of a scrolling column, and the IME covers it. Not
 *  the wheel: it is drawn on every attempt, and scrolling to it pulls the form away. */
internal fun statusMustBeRead(line: StatusLine?): Boolean = when (line) {
    is StatusLine.ServerNotFound, is StatusLine.Failed -> true
    is StatusLine.Working, null -> false
}
