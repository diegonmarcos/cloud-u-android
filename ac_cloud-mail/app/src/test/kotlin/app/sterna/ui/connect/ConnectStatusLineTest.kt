package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the foot of the walk's column says, and whether it has to be read — EXECUTED, over every
 */
class ConnectStatusTest {

    /** The sentence the address step's offline answer carries, as `reportOffline` writes it. */
    private val offline = "You're offline. Adding an account needs a connection, so try again once you're back."

    /** The server's answer on the reported path: this account signs in away from here (#55). */
    private val handover = CredentialsAsk.SignInAway(OAuthGrant.DEVICE_CODE)

    @Test fun `a failure speaks at every step, and carries its own message`() {
        // handoverStarted = FALSE, and it is the whole point of the pair of arguments here:
        // credentialsPane answers SIGN_IN_OFFER for an Error once a hand-over is recorded, and
        // WAITING before one is. Fed `true`, this test would sit on the branch the one-wheel rule
        // cannot reach and would stay green under the very mutation the message below names.
        val said = ConnectStep.entries.map {
            it to statusLine(ConnectState.Error(offline), it, handover, handoverStarted = false)
        }
        assertEquals(
            "⛔ THE DEFECT, IN ITS EXECUTABLE HALF. `reportOffline` writes a ConnectState.Error " +
                "while the walk stands on the ADDRESS step, and that sentence is the whole of the " +
                "answer to a tap on Continue with no network. Condition this branch on the step — " +
                "the neighbouring NeedsServer branch is conditioned, so it is the idiomatic " +
                "one-line mutation — and the walk computes the message, writes it to the state, " +
                "and renders nothing at all. It must speak on EVERY step, and say what the walk " +
                "wrote, word for word.\n" +
                "⛔ AND THE ONE-WHEEL RULE MAY NOT REACH IT (#55): fed here with the ask and the " +
                "hand-over state that make credentialsPane answer WAITING, so that hoisting that " +
                "rule out of the `Working` branch to the whole `when` is RED here. Let it through " +
                "and it swallows the failed sign-in's own message — drawn under the invitation at " +
                "the credentials step, and the only thing that says WHY the button failed.",
            ConnectStep.entries.map { it to StatusLine.Failed(offline) },
            said,
        )
    }

    @Test fun `a running attempt is a wheel at every step, except where the step draws its own`() {
        // EVERY step, still — the expected answers are written out one by one below, so this is
        // the assertion that a step added later cannot slip through unanswered. The list this test
        // walks used to BE `ConnectStep.entries`; spelling it out is what let the credentials step
        // be given a different answer, and this line is what keeps the two in step.
        val steps = listOf(ConnectStep.ADDRESS, ConnectStep.MANUAL, ConnectStep.CREDENTIALS)
        assertEquals(
            "a step added to ConnectStep must be given its answer here, not inherit one by omission.",
            ConnectStep.entries.toSet(), steps.toSet(),
        )
        assertEquals(
            "Connecting and Discovering are attempts still in flight, and the foot of the column " +
                "draws a wheel for them and no sentence — wherever the walk stands EXCEPT at the " +
                "credentials step of a hand-over, where the step above already draws one and the " +
                "two stack up (#55). The pane-owned case is pinned, with its pair, in the test " +
                "below; here the two steps that own no wheel of their own must still keep theirs.",
            listOf(
                ConnectStep.ADDRESS to listOf(StatusLine.Working, StatusLine.Working),
                ConnectStep.MANUAL to listOf(StatusLine.Working, StatusLine.Working),
                ConnectStep.CREDENTIALS to listOf(null, null),
            ),
            steps.map { step ->
                step to listOf(
                    statusLine(ConnectState.Connecting, step, handover, handoverStarted = true),
                    statusLine(ConnectState.Discovering, step, handover, handoverStarted = true),
                )
            },
        )
    }

    @Test fun `coming back from the approval leaves ONE wheel, not two`() {
        assertEquals(
            "⛔ #55 AS REPORTED, and the promise posted on that thread: coming back from the " +
                "approval must not leave the address step showing two spinners. The pair is what " +
                "is pinned — what step 2 draws AND what the foot of the column draws — because " +
                "either one alone is satisfied by the wrong fix: silence the STEP instead and the " +
                "pane stops being WAITING, which is what credentialsExit reads to open the way " +
                "out of the dead end this same issue is about. So: the pane keeps its wheel, the " +
                "foot of the column says nothing.\n" +
                "⛔ Discovering as much as Connecting: the search for the OAuth server is the " +
                "state the screen is in for the first seconds of the very same wait, and a rule " +
                "written on Connecting alone leaves two wheels on screen for those seconds.",
            listOf(
                CredentialsPane.WAITING to null,
                CredentialsPane.WAITING to null,
                CredentialsPane.WAITING to null,
            ),
            listOf(
                // The token exchange, once the browser/device approval came back — the capture.
                ConnectState.Connecting to true,
                // The OAuth server still being looked for, under the same wheel.
                ConnectState.Discovering to true,
                // And the frame between arriving at step 2 and the trigger firing: the pane draws
                // its wheel before any hand-over is recorded, so the foot must be quiet there too.
                ConnectState.Discovering to false,
            ).map { (state, started) ->
                credentialsPane(handover, state, handoverStarted = started) to
                    statusLine(state, ConnectStep.CREDENTIALS, handover, handoverStarted = started)
            },
        )
    }

    @Test fun `back to the address step keeps the only wheel there is`() {
        assertEquals(
            "⛔ THE OTHER HALF, and the way to break this fix without breaking the test above: " +
                "the system Back during the wait returns to the ADDRESS step and does NOT cancel " +
                "the sign-in (stepBackKeeping, backCancelsHandover false). The step above draws no " +
                "wheel there — its `when (credentialsPane(…))` is inside the CREDENTIALS branch — " +
                "so the foot of the column is the only thing left saying that something is still " +
                "running. Extinguish it on the state instead of on the step and the reader is " +
                "back on an address field with nothing moving at all.",
            listOf(StatusLine.Working, StatusLine.Working),
            listOf(
                statusLine(ConnectState.Connecting, ConnectStep.ADDRESS, handover, handoverStarted = true),
                statusLine(ConnectState.Discovering, ConnectStep.ADDRESS, handover, handoverStarted = true),
            ),
        )
    }

    @Test fun `the password route and the manual form keep both of theirs, untouched`() {
        assertEquals(
            "the majority path: the server wants a password, step 2 draws the FIELD and no wheel " +
                "of its own, and the foot of the column is where 'something is running' is said. " +
                "Write the rule on the step alone — `step == CREDENTIALS` without asking " +
                "credentialsPane — and every password sign-in loses the only progress on screen.",
            listOf(CredentialsPane.PASSWORD to StatusLine.Working),
            listOf(
                credentialsPane(CredentialsAsk.Password, ConnectState.Connecting, handoverStarted = false) to
                    statusLine(
                        ConnectState.Connecting, ConnectStep.CREDENTIALS,
                        CredentialsAsk.Password, handoverStarted = false,
                    ),
            ),
        )
        assertEquals(
            "and the manual form, which goes through these very same two functions: it draws no " +
                "pane of its own, so its wheel is this one.",
            listOf(StatusLine.Working, StatusLine.Working),
            listOf(
                statusLine(ConnectState.Connecting, ConnectStep.MANUAL, CredentialsAsk.Password, handoverStarted = false),
                statusLine(ConnectState.Connecting, ConnectStep.MANUAL, handover, handoverStarted = true),
            ),
        )
    }

    @Test fun `only the manual form is told to fill a server in`() {
        assertEquals(
            "on the manual form this names the field she must now fill.",
            StatusLine.ServerNotFound,
            statusLine(ConnectState.NeedsServer, ConnectStep.MANUAL, handover, handoverStarted = true),
        )
        assertNull(
            "at the credentials step NeedsServer is a transient — the walk is already dialling " +
                "the endpoints the domain published — and 'enter it below' would point at nothing.",
            statusLine(ConnectState.NeedsServer, ConnectStep.CREDENTIALS, handover, handoverStarted = true),
        )
        assertNull(
            "and the address step shows no server field at all.",
            statusLine(ConnectState.NeedsServer, ConnectStep.ADDRESS, handover, handoverStarted = true),
        )
    }

    @Test fun `the states that have nothing to say say nothing, at every step`() {
        val silent = listOf(
            ConnectState.Idle,
            ConnectState.Connected,
            ConnectState.AwaitingApproval("ABCD-1234", "https://example.org/device", null),
            ConnectState.AwaitingBrowser("https://example.org/authorize"),
        )
        val spoke = silent.flatMap { state ->
            ConnectStep.entries.map { state to statusLine(state, it, handover, handoverStarted = true) }
        }.filter { it.second != null }
        assertEquals(
            "⛔ Idle and Connected are not verdicts, and the two awaiting-approval states own the " +
                "whole screen through their own panels — a line drawn under them would be a " +
                "second sentence about a sign-in that is being reported elsewhere, and (worse) " +
                "one that would scroll the column while a panel is up. Spoke anyway:\n" +
                spoke.joinToString("\n"),
            emptyList<Pair<ConnectState, StatusLine?>>(), spoke,
        )
    }

    @Test fun `sentences must be read, the wheel and the silence must not`() {
        assertTrue(
            "⛔ THE BENCH CASE, end to end: the offline answer at the address step is a sentence, " +
                "and a sentence under the keyboard is a screen that ignored her.",
            statusMustBeRead(
                statusLine(ConnectState.Error(offline), ConnectStep.ADDRESS, handover, handoverStarted = true),
            ),
        )
        assertTrue(
            "the manual form's 'couldn't find your server' is a sentence too, and that form is " +
                "the longest on this screen — it is the surest one to be off the bottom.",
            statusMustBeRead(
                statusLine(ConnectState.NeedsServer, ConnectStep.MANUAL, handover, handoverStarted = true),
            ),
        )
        assertFalse(
            "⛔ NOT the wheel. It is drawn on every connection attempt, from a form she may be in " +
                "the middle of; scrolling to it drags that form out from under her fingers to " +
                "show a spinner. `line != null` is the mutation this refuses.",
            statusMustBeRead(StatusLine.Working),
        )
        assertFalse(
            "and nothing to say is nothing to scroll to — asking anyway moves the column on the " +
                "way OUT of every error, i.e. on the next keystroke. It is also what the " +
                "credentials step of a hand-over now gets (#55): the wheel it does not draw must " +
                "not scroll the column either.",
            statusMustBeRead(null),
        )
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ConnectStatusWiringTest {

    private val lines: List<String> by lazy { source(SCREEN).lines().map { it.trim() } }

    @Test fun `the foot of the column is one value, drawn inside one stable container`() {
        assertRun(
            listOf(
                "val status = statusLine(state, walkStep, ask, handedOver)",
                "Column(modifier = Modifier.bringIntoViewRequester(statusReveal)) {",
                "when (status) {",
            ),
            "⛔ THE THREE LINES, CONSECUTIVE, AND IN THIS ORDER — each of them load-bearing.\n" +
                "  · `statusLine(state, walkStep, ask, handedOver)`: ONE source of truth. Draw the " +
                "block from a `when (state)` and decide separately whether it deserves a scroll, " +
                "and the two " +
                "spellings of the same rule drift apart in silence — the sentence is drawn under " +
                "one and scrolled under the other. Both arguments pinned: drop `walkStep` and the " +
                "credentials step tells the reader to 'enter it below' under a field that is not " +
                "there. And `ask`, which is what [statusLine] asks [credentialsPane] with (#55): " +
                "hard-code it here — `CredentialsAsk.Password` — and the second wheel is back on " +
                "the screen the reporter photographed, with every EXECUTED test still green, " +
                "because they all call the decision directly and none of them composes a screen. " +
                "⚠ `handedOver` is pinned beside it for honesty, not for leverage: under " +
                "Connecting and Discovering credentialsPane answers WAITING either way, so it " +
                "decides nothing HERE — it is passed because [credentialsPane] is ASKED rather " +
                "than restated, and it would start deciding the day that answer widens. Both are " +
                "in scope at this very line (declared beside `verdict` and `handoverFor` above).\n" +
                "  · the Column carrying the requester comes BETWEEN the value and the `when`, so " +
                "the container is composed whether or not there is anything to say. Put the " +
                "requester on the Text instead (or wrap the Column in `if (status != null)`) and " +
                "the node is attached in the same frame the effect fires in — the scroll then " +
                "asks a node that has never been placed, and the sentence stays under the " +
                "keyboard. That is the defect, restored.\n" +
                "  · the `when` is on the VALUE, not on `state`.\n" +
                "Nothing may be inserted between the three: a conditional there is exactly how the " +
                "container stops being stable.",
        )
        assertPinned(
            "val statusReveal = remember { BringIntoViewRequester() }",
            "The requester itself, remembered across recompositions. A fresh one per composition " +
                "is a requester whose node is never the one on screen.",
        )
        assertOnce("statusLine(", "the one seam between the screen and the executed decision")
        assertOnce("bringIntoViewRequester(statusReveal)", "the one container that is brought into view")
        val block = lines.indexOf("val status = statusLine(state, walkStep, ask, handedOver)")
        val steps = lines.indexOf("when (walkStep) {")
        assertTrue(
            "⛔ AND IT STAYS AT THE FOOT of the column: in $SCREEN the status block (line index " +
                "$block) must come AFTER the walk's `when (walkStep) {` (line index $steps). " +
                "Hoisted above the steps the whole thing still compiles, every pin above still " +
                "holds, and the suite stays green — while the answer to a tap on Continue is drawn " +
                "ABOVE the address field, and bringing it into view scrolls the field and the " +
                "button she just used off the top of the screen. Found as a surviving mutation " +
                "while writing this file.",
            steps in 0 until block,
        )
    }

    @Test fun `the block is a direct child of the walk's column, not something's else branch`() {
        // THE HOLE THE COUNTER-AUDIT CAME THROUGH, and no trimmed pin in this repo can see it:
        // wrap the whole foot of the column in `if (walkStep != ConnectStep.ADDRESS) { … }` and
        listOf(
            "val status = statusLine(state, walkStep, ask, handedOver)" to
                "the value the foot of the column draws",
            "LaunchedEffect(status) {" to
                "the effect that scrolls it — wrapped alone, the sentence is drawn and never seen",
            "when (walkStep) {" to
                "the walk's own steps: the reference level. Both must be children of the SAME " +
                    "column, which is what makes 'the block is drawn at every step' true",
        ).forEach { (pinned, what) -> assertIndent(pinned, WALK_COLUMN_INDENT, what) }
    }

    @Test fun `the keyboard shrinks the viewport the scroll aims at`() {
        assertRun(
            listOf(
                ".imePadding()",
                ".verticalScroll(rememberScrollState())",
            ),
            "⛔ THE WHOLE CORRECTION RESTS ON THESE TWO, CONSECUTIVE AND IN THIS ORDER, and until " +
                "now nothing in this module held them (other screens do: ComposeDeleteWiringTest, " +
                "SettingChoiceDialogScrollTest, NotificationsGateWiringTest). imePadding() FIRST " +
                "is what shrinks the scrollable viewport to the space left above the keyboard; " +
                "bringIntoView() then has something to do. Remove it, or swap the two, and the " +
                "viewport is the whole window: the sentence measured at y≈1589 is 'already " +
                "visible' behind a keyboard whose top edge is y≈1500, the scroll is a no-op, and " +
                "the defect is back at 100 % — with every other pin in this file still green. It " +
                "would also kill the #52 guard (the focused field above the keyboard) on the way.",
        )
    }

    @Test fun `a sentence is scrolled into view, and the wheel is not`() {
        assertRun(
            listOf(
                "LaunchedEffect(status) {",
                "if (statusMustBeRead(status)) statusReveal.bringIntoView()",
                "}",
            ),
            "⛔ THE WHOLE EFFECT, closing brace included.\n" +
                "  · keyed on `status`, the value SAID. `LaunchedEffect(Unit)` runs once, at the " +
                "first composition, when there is nothing to say — the sentence is then never " +
                "scrolled to, 100 % of the time, and every executed test stays green because " +
                "nothing else in this repo pins the key of an effect. Keyed on `state` it re-runs " +
                "on states that say nothing at all.\n" +
                "  · guarded by `statusMustBeRead`, executed in ConnectStatusTest. Call " +
                "bringIntoView() unconditionally and every connection attempt drags the form out " +
                "from under her fingers to show a spinner.\n" +
                "  · and it must call bringIntoView() — dropping that call is the defect verbatim.",
        )
        assertOnce("statusMustBeRead(", "the one place the screen asks whether the line must be read")
    }

    @Test fun `each branch draws its own body`() {
        assertRun(
            listOf(
                "StatusLine.Working -> LoadingRing()",
            ),
            "the wheel, on the label's own line — a body cannot be swapped away from a label it " +
                "shares a line with. It is LoadingRing, not a raw CircularProgressIndicator: with " +
                "the system animations off Material 3's indeterminate arc is left on screen as a " +
                "few pixels of stroke and stops reading as \"working\" (#63).",
        )
        assertRun(
            listOf(
                "StatusLine.ServerNotFound -> Text(",
                "text = stringResource(R.string.connect_server_not_found),",
            ),
            "⛔ LABEL AND BODY, ADJACENT. Pinning the two lines separately is blind to the two " +
                "branches' bodies being EXCHANGED: each line would still occur exactly once while " +
                "the manual form said 'Could not connect: …' and a failed sign-in said 'enter it " +
                "below'. The string is an existing one, in all nine locales.",
        )
        assertRun(
            listOf(
                "is StatusLine.Failed -> Text(",
                "text = stringResource(R.string.connect_error, status.message),",
            ),
            "⛔ Same, for the branch that carries the offline sentence — and its ARGUMENT: " +
                "`stringResource(R.string.connect_error)` alone compiles to 'Could not connect: " +
                "%1\$s' with the placeholder left raw, and the reason the walk went to the trouble " +
                "of writing is dropped on the floor.",
        )
    }

    @Test fun `the step condition lives in the decision, not in the screen`() {
        val leaks = lines.withIndex().filter { it.value.contains("walkStep == ConnectStep.MANUAL") }
        assertEquals(
            "⛔ Which steps the 'couldn't find your server' line may speak on is [statusLine]'s " +
                "call, and ConnectStatusTest executes it at every step. Re-stated in $SCREEN it is " +
                "a second copy of the same rule, and the copy nothing can see is the one that " +
                "drifts. Found:\n" + leaks.joinToString("\n"),
            emptyList<IndexedValue<String>>(), leaks,
        )
    }

    // -- reading the file ---------------------------------------------------------------------

    /** [run] must appear in [SCREEN] as consecutive trimmed lines, exactly once. */
    private fun assertRun(run: List<String>, why: String) {
        val hits = (0..(lines.size - run.size)).count { lines.subList(it, it + run.size) == run }
        assertEquals(
            "expected exactly one place in $SCREEN where these lines follow one another, trimmed:\n" +
                run.joinToString("\n") { "    $it" } + "\nbut found $hits. A line was rewritten, " +
                "removed, split, duplicated, reordered, or something was inserted between them — " +
                "this lint compares WHOLE lines, so even a change that only lengthens one lands " +
                "here. $why",
            1, hits,
        )
    }

    private fun assertPinned(pinned: String, why: String) = assertRun(listOf(pinned), why)

    /**
     * The one line of [SCREEN] whose trimmed text is [pinned] must carry exactly [spaces] leading
     */
    private fun assertIndent(pinned: String, spaces: Int, what: String) {
        val raw = source(SCREEN).lines().filter { it.trim() == pinned }
        assertEquals("expected exactly one line of $SCREEN reading `$pinned` — $what", 1, raw.size)
        assertEquals(
            "`$pinned` ($what) must sit at exactly $spaces spaces of indentation in $SCREEN. " +
                "Deeper means something was wrapped around it — a step condition, a null check, a " +
                "panel branch — and a block that is not composed draws nothing while every " +
                "line-based pin in this repo stays green.",
            spaces, raw.single().takeWhile { it == ' ' }.length,
        )
    }

    private fun assertOnce(fragment: String, what: String) {
        val n = Regex(Regex.escape(fragment)).findAll(source(SCREEN)).count()
        assertTrue(
            "`$fragment` must occur exactly once in $SCREEN — $what. A second occurrence is a " +
                "second site deciding the same thing, which is how the drawn line and the " +
                "scrolled line come to disagree. Found $n.",
            n == 1,
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

        /**
         * Direct children of the walk's scrolling column: three levels of `Scaffold { Column {` in,
         * i.e. twelve spaces. The walk's `when (walkStep)` sits there, and so must the status block.
         */
        const val WALK_COLUMN_INDENT = 12
    }
}
