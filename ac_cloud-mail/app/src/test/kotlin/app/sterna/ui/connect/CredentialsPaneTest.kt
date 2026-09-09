package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Step 2 after a hand-over that ended without an account, and Back while one is on screen.
 */
class CredentialsPaneTest {

    private val handover = CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE)

    // ---- credentialsPane: what step 2 draws ----

    @Test fun `a password server draws its password field, whatever the attempt is doing`() {
        assertEquals(
            "the majority path, untouched: the field is drawn under one answer and one only.",
            CredentialsPane.PASSWORD,
            credentialsPane(CredentialsAsk.Password, ConnectState.Idle, handoverStarted = false),
        )
        assertEquals(
            "and it stays drawn while its own sign-in runs — that button reads \"Connecting…\" " +
                "and must not be replaced by an invitation to open a browser.",
            CredentialsPane.PASSWORD,
            credentialsPane(CredentialsAsk.Password, ConnectState.Connecting, handoverStarted = true),
        )
    }

    @Test fun `a hand-over that is starting draws the wheel`() {
        assertEquals(
            "the frame between arriving at step 2 and the trigger firing: the wheel is TRUE here, " +
                "a sign-in is about to take the whole screen. Draw the invitation instead and it " +
                "flashes under her thumb on every single hand-over.",
            CredentialsPane.WAITING,
            credentialsPane(handover, ConnectState.Idle, handoverStarted = false),
        )
    }

    @Test fun `a hand-over that is running draws the wheel`() {
        assertEquals(
            "the search for the OAuth server: something is really loading.",
            CredentialsPane.WAITING,
            credentialsPane(handover, ConnectState.Discovering, handoverStarted = true),
        )
        assertEquals(
            "the token exchange, once the browser came back.",
            CredentialsPane.WAITING,
            credentialsPane(handover, ConnectState.Connecting, handoverStarted = true),
        )
        assertEquals(
            "behind the device panel, which owns the screen: offering a second sign-in under it " +
                "would start a second approval for the same account.",
            CredentialsPane.WAITING,
            credentialsPane(
                handover,
                ConnectState.AwaitingApproval("ABCD-EFGH", "https://example.org/device", null),
                handoverStarted = true,
            ),
        )
        assertEquals(
            "and behind the browser panel, for the same reason.",
            CredentialsPane.WAITING,
            credentialsPane(
                handover,
                ConnectState.AwaitingBrowser("https://example.org/authorize"),
                handoverStarted = true,
            ),
        )
    }

    @Test fun `a cancelled hand-over draws the invitation, not a wheel`() {
        assertEquals(
            "⛔ DEFECT A. Cancel puts the attempt back to Idle and changes nothing else; the step " +
                "drew a wheel for everything that was not the password field, so it asserted a " +
                "loading that did not exist — for ever, one tap off the default path, with the " +
                "system Back as the only way out. What is true here is that she can sign in and " +
                "nothing is running: the sentence and the button say exactly that.",
            CredentialsPane.SIGN_IN_OFFER,
            credentialsPane(handover, ConnectState.Idle, handoverStarted = true),
        )
    }

    @Test fun `a hand-over that failed draws the invitation under its message`() {
        assertEquals(
            "an attempt that is over is over whichever way it ended: the error text is drawn " +
                "below by the screen, and above it the step must offer the retry rather than spin.",
            CredentialsPane.SIGN_IN_OFFER,
            credentialsPane(handover, ConnectState.Error("Couldn't start OAuth sign-in"), handoverStarted = true),
        )
    }

    @Test fun `a hand-over that succeeded does not offer to start again`() {
        assertEquals(
            "the account is in and the screen is leaving: an invitation to sign in would flash " +
                "over a walk that worked.",
            CredentialsPane.WAITING,
            credentialsPane(handover, ConnectState.Connected, handoverStarted = true),
        )
    }

    @Test fun `an answer that has not come back yet draws the wheel and offers nothing`() {
        assertEquals(
            "nothing has been asked and nothing can be offered: the server has not answered how " +
                "this account signs in.",
            CredentialsPane.WAITING,
            credentialsPane(CredentialsAsk.Awaiting, ConnectState.Idle, handoverStarted = false),
        )
    }

    // ---- credentialsExit: the way OUT of a step that asks nothing and hands over nothing ----

    @Test fun `the invitation carries a way out to the manual form`() {
        assertEquals(
            "⛔ THE DEAD END (#55). A domain that publishes an autoconfig AND an OAuth document " +
                "lands the reader here with the invitation and nothing else: the server refuses " +
                "this app's client id, so the button fails every single time; there is no " +
                "password field to fall back on; and the system Back returns to the address " +
                "step, which offers no manual setup either because its verdict FOUND something. " +
                "Without this exit the account cannot be added at all, by any gesture.",
            ConnectStep.MANUAL,
            credentialsExit(CredentialsPane.SIGN_IN_OFFER),
        )
    }

    @Test fun `no way out is offered while a hand-over is in flight`() {
        assertNull(
            "⛔ The browser is open, or its panel is one frame from taking the screen. A manual " +
                "form offered under a sign-in still running is a second account added behind " +
                "the first, on a walk the reader thinks she left.",
            credentialsExit(CredentialsPane.WAITING),
        )
    }

    @Test fun `beside the password field the way out is the field itself`() {
        assertNull(
            "she can sign in right here, and a 'Set up manually' beside the field is the " +
                "protocol question (JMAP? IMAP/SMTP?) walking back on screen for everyone — the " +
                "one thing this whole walk exists to take off it.",
            credentialsExit(CredentialsPane.PASSWORD),
        )
    }

    @Test fun `the dead end of the report, composed the way the screen composes it`() {
        val browser = CredentialsAsk.SignInAway(OAuthGrant.AUTHORIZATION_CODE)
        assertEquals(
            "⛔ #55 AS REPORTED, end to end: the server answered the hand-over with a refusal " +
                "(unknown client id). Its message is drawn, and under it there must be a way to " +
                "the manual form — otherwise the reader is looking at a button that cannot ever " +
                "work, on a screen nothing leads out of.",
            ConnectStep.MANUAL,
            credentialsExit(
                credentialsPane(browser, ConnectState.Error("invalid_client"), handoverStarted = true),
            ),
        )
        assertEquals(
            "and after Cancel on the panel, the same screen and the same need: the invitation " +
                "is back, no password was ever asked for, and nothing else is on offer.",
            ConnectStep.MANUAL,
            credentialsExit(credentialsPane(browser, ConnectState.Idle, handoverStarted = true)),
        )
        assertNull(
            "but NOT while the browser panel owns the screen: the sign-in she started is still " +
                "running, and a manual form opened over it adds an account behind it.",
            credentialsExit(
                credentialsPane(
                    browser,
                    ConnectState.AwaitingBrowser("https://example.org/authorize"),
                    handoverStarted = true,
                ),
            ),
        )
        assertNull(
            "and not on the majority path, where the password field is already the way through.",
            credentialsExit(
                credentialsPane(CredentialsAsk.Password, ConnectState.Idle, handoverStarted = false),
            ),
        )
    }

    // ---- showsHandoverPanel: which states own the whole screen ----

    @Test fun `the two hand-over panels are the states that own the screen`() {
        assertTrue(
            "the device panel: code, open-browser, waiting line, Cancel.",
            showsHandoverPanel(ConnectState.AwaitingApproval("ABCD-EFGH", "https://example.org/device", null)),
        )
        assertTrue(
            "and the browser panel of the authorization-code grant.",
            showsHandoverPanel(ConnectState.AwaitingBrowser("https://example.org/authorize")),
        )
        assertFalse(
            "⛔ NOT while nothing is up: Back at step 2 belongs to the walk, and it must go back " +
                "to the address step, not cancel a sign-in nobody started.",
            showsHandoverPanel(ConnectState.Idle),
        )
        assertFalse(
            "nor while the OAuth server is being searched for: there is no panel yet, and no " +
                "approval to cancel.",
            showsHandoverPanel(ConnectState.Discovering),
        )
        assertFalse("nor while the token is being exchanged.", showsHandoverPanel(ConnectState.Connecting))
        assertFalse(
            "nor over the imported-accounts list, whose own approval panel is ImportSignIn's and " +
                "has its own cancel (cancelImportOAuth) — its gestures were measured green and " +
                "this must not reach them.",
            showsHandoverPanel(ConnectState.Error("whatever")),
        )
    }

    // ---- backCancelsHandover: WHOSE hand-over Back is allowed to cancel ----

    private val devicePanel = ConnectState.AwaitingApproval("ABCD-EFGH", "https://example.org/device", null)

    @Test fun `back on the device panel of a hand-over this walk started cancels it`() {
        assertTrue(
            "⛔ DEFECT B, and it is what this handler exists for: the walk handed this address " +
                "over itself, its panel owns the screen, and Back must do what that panel's " +
                "Cancel does instead of popping the whole screen with a sign-in in flight.",
            backCancelsHandover(devicePanel, startedByWalk = true),
        )
    }

    @Test fun `and on the browser panel of one this walk started, just the same`() {
        assertTrue(
            "the authorization-code grant shows a different panel and the same gesture applies.",
            backCancelsHandover(ConnectState.AwaitingBrowser("https://example.org/authorize"), startedByWalk = true),
        )
    }

    @Test fun `a sign-in this walk did not start is not this walk's to cancel`() {
        assertFalse(
            "⛔ THE OVERREACH. The manual form's Microsoft/Outlook chip is mirrored into this very " +
                "same AwaitingApproval, so a handler armed on the STATE intercepts it too — and " +
                "that flow is built to survive the screen disappearing: connectOutlookOAuth hands " +
                "the poll to the app-scoped OutlookSignIn so the token exchange outlives the round " +
                "trip to the browser, this screen being popped included. Before the Back handler " +
                "existed, Back there popped the screen and the account was added anyway when the " +
                "browser came back; cancelling it kills that. Only the hand-over the walk started " +
                "itself is answered here, and the walk is the only path that records one.",
            backCancelsHandover(devicePanel, startedByWalk = false),
        )
    }

    @Test fun `with no panel on screen, back belongs to the walk`() {
        assertFalse(
            "⛔ Back at step 2 with nothing up must return to the address step, not cancel a " +
                "sign-in nobody is running — including right after Cancel, where the walk has " +
                "started a hand-over for this address and it is over.",
            backCancelsHandover(ConnectState.Idle, startedByWalk = true),
        )
    }

    @Test fun `a hand-over still looking for its server has no panel to cancel`() {
        assertFalse(
            "no panel owns the screen yet, so the walk's own Back is composed and answers.",
            backCancelsHandover(ConnectState.Discovering, startedByWalk = true),
        )
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [OAuthHandoverWiringTest]:
 */
class HandoverBackWiringTest {

    private val lines: List<String> by lazy { source(SCREEN).lines().map { it.trim() } }

    @Test fun `back on a hand-over panel cancels the hand-over`() {
        assertPinned("BackHandler(enabled = backCancelsHandover(state, handedOver)) { viewModel.cancelOAuth() }")
        assertPinned("BackHandler(enabled = stepBackKeeping(walkStep, username) != null) {")
    }

    @Test fun `and it is composed while the panel it answers for is on screen`() {
        val back = lines.indexOf("BackHandler(enabled = backCancelsHandover(state, handedOver)) { viewModel.cancelOAuth() }")
        val device = lines.indexOf("DeviceApprovalPanel(awaiting, onCancel = viewModel::cancelOAuth)")
        val browser = lines.indexOf("BrowserAuthorizationPanel(awaitingBrowser, onCancel = viewModel::cancelOAuth)")
        val firstReturn = lines.indexOf("return@Column")
        assertTrue(
            "⛔ DEFECT B, and it is a question of ORDER, not of existence. In $SCREEN the " +
                "hand-over Back handler (line index $back) must come BEFORE the first " +
                "`return@Column` (line index $firstReturn) — those returns hand the whole column " +
                "to a panel, so anything below them is not composed while one is up, no handler " +
                "answers Back, and the system pops the screen: the user lands in the launcher " +
                "with a sign-in still in flight. Swap these two lines and every pinned-line " +
                "assertion in this repo stays green.",
            back in 0 until firstReturn,
        )
        assertTrue(
            "and before both panels it answers for (device $device, browser $browser).",
            back < device && back < browser,
        )
    }

    @Test fun `the walk's own back stays below, where the panels have already returned`() {
        val handover = lines.indexOf("BackHandler(enabled = backCancelsHandover(state, handedOver)) { viewModel.cancelOAuth() }")
        val walk = lines.indexOf("BackHandler(enabled = stepBackKeeping(walkStep, username) != null) {")
        val lastReturn = lines.indexOfLast { it == "return@Column" }
        assertTrue(
            "in $SCREEN the walk's Back (line index $walk) must stay AFTER the last " +
                "`return@Column` (line index $lastReturn), i.e. below the imported-accounts " +
                "listing: hoisting it above them is the other way to fix defect B, and it would " +
                "change the listing's Back — whose gestures were measured green at the bench and " +
                "are out of scope. It must also stay after the hand-over handler ($handover), " +
                "which is what makes the two never argue.",
            lastReturn in 0 until walk && handover < walk,
        )
    }

    @Test fun `the offer draws the two strings that already exist, and starts the hand-over`() {
        assertPinned(
            "Text(stringResource(R.string.connect_oauth_code_step1), style = MaterialTheme.typography.bodyMedium)",
        )
        assertPinned("Text(stringResource(R.string.connect_oauth_open_browser))")
        assertPinned("CredentialsPane.SIGN_IN_OFFER -> {")
        assertPinned("onClick = { startHandover() },")
        assertPinned("fun startHandover() {")
        assertPinned("handoverFor = username.trim()")
        assertPinned("val handedOver = handoverStarted(handoverFor, username)")
        // rememberSaveable, not remember: a plain remember is lost on rotation, the trigger
        // fires again on the recomposed screen, and the browser she cancelled reopens itself the
        // moment she turns the phone. Nothing executed can see that difference.
        assertPinned("var handoverFor by rememberSaveable { mutableStateOf(NO_HANDOVER) }")
        val offer = lines.indexOf("CredentialsPane.SIGN_IN_OFFER -> {")
        val button = lines.indexOf("onClick = { startHandover() },")
        val label = lines.indexOf("Text(stringResource(R.string.connect_oauth_open_browser))")
        assertTrue(
            "in $SCREEN the invitation's button (line index $button, labelled at $label) must sit " +
                "inside the SIGN_IN_OFFER branch (line index $offer): drawn anywhere else it is a " +
                "second way to start a sign-in, on a step where one may already be running.",
            offer in 0 until button && button < label,
        )
    }

    @Test fun `the wheel is what WAITING draws, and it is drawn on the label's own line`() {
        // LoadingRing, not a raw CircularProgressIndicator: with the system animations off,
        // Material 3's indeterminate arc is left on screen as a few pixels of stroke and stops
        // reading as "waiting" at all (#63).
        assertPinned("CredentialsPane.WAITING -> LoadingRing()")
    }

    @Test fun `the credentials step has three branches, and they bound each other`() {
        assertEquals(
            "the lint below bounds one branch by the NEXT branch label, so the three labels of " +
                "the `when (credentialsPane(…))` must be found in $SCREEN, once each. Found: " +
                branchLabels,
            listOf("CredentialsPane.PASSWORD", "CredentialsPane.SIGN_IN_OFFER", "CredentialsPane.WAITING"),
            branchLabels.map { it.first }.sorted(),
        )
    }

    @Test fun `the invitation is drawn inside the SIGN_IN_OFFER branch and nowhere else`() {
        val offer = branchLabels.first { it.first == "CredentialsPane.SIGN_IN_OFFER" }.second
        val next = branchLabels.map { it.second }.filter { it > offer }.minOrNull() ?: lines.size
        val outside = listOf(
            "Text(stringResource(R.string.connect_oauth_code_step1), style = MaterialTheme.typography.bodyMedium)",
            "onClick = { startHandover() },",
            "Text(stringResource(R.string.connect_oauth_open_browser))",
        ).map { it to lines.indexOf(it) }.filterNot { it.second in (offer + 1) until next }
        assertEquals(
            "⛔ A BODY MUST BELONG TO ITS LABEL, and nothing else in this repo holds that. Every " +
                "other pin here is an existence check or an order check, and both survive the " +
                "bodies of two branches being EXCHANGED: give the wheel to SIGN_IN_OFFER and the " +
                "invitation to WAITING, leave the labels where they are, and each pinned line " +
                "still exists exactly once and both order constraints still hold — while step 2 " +
                "draws a bare wheel after Cancel again (the whole defect) and offers a browser " +
                "button, refused by the double-submit guard, at the moment a sign-in is really " +
                "starting. WAITING is held by the pinned line above, its body being on the label's " +
                "own line; SIGN_IN_OFFER is held here. In $SCREEN each line of the invitation must " +
                "sit strictly between the SIGN_IN_OFFER label (line index $offer) and the next " +
                "branch label (line index $next). Outside it:",
            emptyList<Pair<String, Int>>(), outside,
        )
    }

    @Test fun `the way out of the dead end is drawn under the whole guard, not inside a branch`() {
        assertPinned("credentialsExit(credentialsPane(ask, state, handedOver))?.let { exit ->")
        assertPinned("onClick = { rememberedStep = exit },")
        assertPinned("Text(stringResource(R.string.connect_step_manual))")
        val exit = lines.indexOf("credentialsExit(credentialsPane(ask, state, handedOver))?.let { exit ->")
        val lastBranch = branchLabels.maxOf { it.second }
        val manualStep = lines.indexOf("ConnectStep.MANUAL -> {")
        assertTrue(
            "⛔ #55, AND WHERE IT IS DRAWN IS THE WHOLE OF IT. In $SCREEN the exit (line index " +
                "$exit) must sit AFTER the last `CredentialsPane.` branch label (line index " +
                "$lastBranch), i.e. under the closed `when`, and BEFORE the manual step's own " +
                "branch (line index $manualStep), i.e. still inside the credentials step. Drop " +
                "the whole block inside WAITING or PASSWORD instead — indentation and all, not " +
                "one character of any line changed — and the guard above answers null in that " +
                "branch, so the button is never drawn at all: the dead end of #55 is back, the " +
                "executed tests below stay green because the decision itself is untouched, and " +
                "every other pinned line in this repo stays green too. Inside SIGN_IN_OFFER it " +
                "draws, but the offer's own branch then owns a decision that is made twice.",
            lastBranch in 0 until exit && exit < manualStep,
        )
        val label = lines.indexOf("Text(stringResource(R.string.connect_step_manual))")
        assertTrue(
            "and the button (labelled at line index $label) belongs to that exit, not to some " +
                "other block further down the column.",
            exit < label && label < manualStep,
        )
        // ORDER IS NOT ENOUGH, and this is where the mutation went through: PASSWORD is the
        // LAST branch label of the guard, so the whole block dropped INSIDE it still sits after
        val depth = indent(exit)
        val guardDepth = indent(lines.indexOf("when (credentialsPane(ask, state, handedOver)) {"))
        assertEquals(
            "the exit must stand at exactly the indentation of the `when` it follows " +
                "($guardDepth spaces) in $SCREEN, and it stands at $depth. Deeper means it is " +
                "inside a branch — inside PASSWORD or WAITING the decision answers null there and " +
                "the button is never drawn, with every executed test and every other pin green.",
            guardDepth, depth,
        )
    }

    /** The leading spaces of the RAW line at [index] — trimmed lines cannot see a nesting change. */
    private fun indent(index: Int): Int =
        source(SCREEN).lines()[index].takeWhile { it == ' ' }.length

    /** The `when (credentialsPane(…))` branch labels of [SCREEN], as (label, line index). */
    private val branchLabels: List<Pair<String, Int>> by lazy {
        lines.withIndex()
            .filter { it.value.startsWith("CredentialsPane.") && it.value.contains(" -> ") }
            .map { it.value.substringBefore(" -> ") to it.index }
    }

    private fun assertPinned(pinned: String) {
        val hits = lines.count { it == pinned }
        assertEquals(
            "expected exactly one line of $SCREEN whose trimmed text is:\n    $pinned\nbut found " +
                "$hits. The line was rewritten, removed, split or duplicated — this lint compares " +
                "the WHOLE line, so any change to it (even one that only lengthens it) lands here.",
            1, hits,
        )
    }

    private fun source(path: String): String =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?.let { File(it, path).readText() }
            ?: error(
                "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                    "source file as text and needs a working directory inside the checkout",
            )

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
    }
}
