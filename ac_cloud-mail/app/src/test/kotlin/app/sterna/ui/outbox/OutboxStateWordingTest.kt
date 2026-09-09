package app.sterna.ui.outbox

import app.sterna.R
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the Outbox screen SAYS about each row, and what the Inbox banner is about.
 */
class OutboxStateWordingTest {

    /** The state the shipped rule parks a save kept on the phone in — asked, never assumed. */
    private val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
        ?: error("a save kept on the device parks nothing at all — see LocalDraftSaveTest")

    // -- executed: the wording, state by state ---------------------------------------------------

    /**
     * Every state's sentence, written out. The pairs are pinned in full rather than checked one
     */
    @Test fun `each outbox state has its own sentence`() {
        assertEquals(
            mapOf(
                OutboxState.HELD to R.string.outbox_state_waiting,
                OutboxState.QUEUED to R.string.outbox_state_waiting,
                OutboxState.SENDING to R.string.outbox_state_sending,
                OutboxState.FAILED to R.string.outbox_state_failed,
                OutboxState.KEPT_AS_DRAFT to R.string.outbox_state_kept_as_draft,
                OutboxState.EDITING to R.string.outbox_state_waiting,
                OutboxState.INTERRUPTED to R.string.outbox_state_interrupted,
            ),
            OutboxState.entries.associateWith { outboxStateLabel(it) },
        )
    }

    /**
     * The frame the waiting row's complement is printed in, EXECUTED — the source lint one
     */
    @Test fun `the waiting row's complement is framed as history, not as a failure`() {
        assertEquals(
            "⛔ the complement's frame must be R.string.outbox_waiting_reason — 'Last error: %1\$s'. " +
                "It says the reason is the LAST one recorded, which is the only true thing that " +
                "can be said: nothing clears lastError when a re-arm succeeds, and an offline row " +
                "carries an online failure's reason for days.",
            R.string.outbox_waiting_reason,
            outboxWaitingReasonLabel(),
        )
    }

    /**
     * THE sentence of this fix. A row saved as a draft on the phone must say so, on its own, and
     */
    @Test fun `the row a kept-on-device save leaves behind says it was saved, not that it failed`() {
        assertEquals(
            "⛔ the parked row's line must be R.string.outbox_state_kept_as_draft, a WHOLE " +
                "sentence printed as it stands (#95)",
            R.string.outbox_state_kept_as_draft,
            outboxStateLabel(parked),
        )
        assertFalse(
            "⛔ and it must take no reason argument: R.string.outbox_state_failed is 'Failed: " +
                "%1\$s', so a state that substitutes into it announces a failure that never " +
                "happened — and with a null lastError it reads 'Failed: couldn't send' over a " +
                "message nobody ever tried to send",
            outboxStateShowsReason(parked),
        )
        assertFalse(
            "⛔ and it must not be painted in the error colour: nothing would ever repaint it, " +
                "there being no deferred upload whose success could take the red back",
            outboxStateIsError(parked),
        )
        assertFalse(
            "⛔ and it must not raise the Inbox's failure banner across the whole list",
            OutboxLogic.needsFailureBanner(parked),
        )
    }

    /**
     * THE sentence of the interrupted-send fix. A delivery whose run was killed may ALREADY have
     */
    @Test fun `the row a killed delivery leaves behind says it may already be gone, never that it failed`() {
        assertEquals(
            "⛔ the parked row's line must be R.string.outbox_state_interrupted, a WHOLE sentence " +
                "printed as it stands — 'Sending…' would be a lie (nothing is sending any more) " +
                "and 'Waiting to send' would be worse (nothing is going to send it)",
            R.string.outbox_state_interrupted,
            outboxStateLabel(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must take no reason argument: R.string.outbox_state_failed is 'Failed: " +
                "%1\$s', so a state that substitutes into it announces a failure that never " +
                "happened over a message that may well have gone out",
            outboxStateShowsReason(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must not be painted in the error colour: red says the send went wrong, and " +
                "nothing would ever repaint it",
            outboxStateIsError(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must not raise the Inbox's failure banner across the whole list: 'Some " +
                "messages didn't send' over a message that did send is a claim nothing retracts",
            OutboxLogic.needsFailureBanner(OutboxState.INTERRUPTED),
        )
    }

    /**
     * THE SENTENCE OF THE DESTRUCTIVE ACTION, state by state, written out by hand. The delete
     */
    @Test fun `the row a killed delivery leaves behind is not told it was never sent, when it is deleted`() {
        assertEquals(
            "⛔ only OutboxState.INTERRUPTED may take R.string.outbox_delete_body_interrupted, and " +
                "it must take nothing else: R.string.outbox_delete_body says \"It hasn't been " +
                "sent\", which over an interrupted row is a promise this phone cannot keep — the " +
                "message may be at the recipient already, and the reader is deciding whether to " +
                "destroy her only copy of it",
            mapOf(
                OutboxState.HELD to R.string.outbox_delete_body,
                OutboxState.QUEUED to R.string.outbox_delete_body,
                OutboxState.SENDING to R.string.outbox_delete_body,
                OutboxState.FAILED to R.string.outbox_delete_body,
                OutboxState.KEPT_AS_DRAFT to R.string.outbox_delete_body,
                OutboxState.EDITING to R.string.outbox_delete_body,
                OutboxState.INTERRUPTED to R.string.outbox_delete_body_interrupted,
            ),
            OutboxState.entries.associateWith { outboxDeleteBody(it) },
        )
    }

    /** The two per-state flags, in full — same reason as the sentences above. */
    @Test fun `only a genuine send failure is dressed as one`() {
        assertEquals(
            "the only line that substitutes the row's lastError is the one whose string has a " +
                "placeholder for it",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { outboxStateShowsReason(it) },
        )
        assertEquals(
            "the only line painted in the error colour",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { outboxStateIsError(it) },
        )
        assertEquals(
            "and the only state the Inbox's 'Some messages didn't send' banner is about",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { OutboxLogic.needsFailureBanner(it) },
        )
    }

    /**
     * THE PREDICATE OF THIS FIX, state by state and BOTH ways, written out by hand — never
     */
    @Test fun `only a waiting row that carries a reason says why it is still there`() {
        assertEquals(
            "⛔ with a reason on the row, QUEUED and HELD — and only those two — print it as a " +
                "second line. FAILED already prints it inside its own sentence; KEPT_AS_DRAFT and " +
                "INTERRUPTED would be borrowing a reason that is not about their state; SENDING " +
                "has a delivery in flight.",
            mapOf(
                OutboxState.HELD to true,
                OutboxState.QUEUED to true,
                OutboxState.SENDING to false,
                OutboxState.FAILED to false,
                OutboxState.KEPT_AS_DRAFT to false,
                OutboxState.EDITING to false,
                OutboxState.INTERRUPTED to false,
            ),
            OutboxState.entries.associateWith {
                outboxShowsWaitingReason(it, "550 5.1.1 No such user")
            },
        )
        assertEquals(
            "⛔ with NO reason on the row, nothing prints for any state — including the waiting " +
                "ones. This is the normal case, not an edge: offline the CONNECTED constraint " +
                "(send/Outbox.kt) means no attempt runs, so a QUEUED row has no reason to give, " +
                "and a complement here would be blank or read 'couldn't send' under a message " +
                "nothing has tried to send yet.",
            OutboxState.entries.associateWith { false },
            OutboxState.entries.associateWith { outboxShowsWaitingReason(it, null) },
        )
        assertEquals(
            "⛔ with an EMPTY reason on the row, nothing prints either — and that is the same " +
                "guard of the normal case, not a tidiness check. `OutboxWorker` writes " +
                "`t.message ?: t.javaClass.simpleName`, and a Throwable's message really can be " +
                "the empty string: `unarmedNote` protects itself from exactly that value with " +
                "takeIf { it.isNotBlank() }. Unguarded here the complement becomes its frame " +
                "followed by nothing — 'Last error: ' under 'Waiting to send' — which is the blank " +
                "line the predicate exists to avoid.",
            OutboxState.entries.associateWith { false },
            OutboxState.entries.associateWith { outboxShowsWaitingReason(it, "") },
        )
        assertEquals(
            "⛔ and the same for a reason that is only whitespace: the frame would be printed " +
                "over a blank, which reads as a truncated sentence rather than as no reason.",
            OutboxState.entries.associateWith { false },
            OutboxState.entries.associateWith { outboxShowsWaitingReason(it, "   ") },
        )
    }

    // -- executed: the reason a FAILED row prints -------------------------------------------------

    /**
     * THE SENTENCE OF THE INTERRUPTED-EDIT FIX. A queued row closed over a lost body is parked
     */
    @Test fun `the reason a failed row prints is translated from its sentinel, never stored`() {
        assertEquals(
            "⛔ the sentinel must come out as R.string.outbox_error_edit_interrupted — printed raw " +
                "it reads 'Failed: edit-interrupted' in nine languages",
            OutboxFailureReason.Words(R.string.outbox_error_edit_interrupted),
            outboxFailureReason(OutboxLogic.EDIT_INTERRUPTED),
        )
        assertEquals(
            "⛔ the arming sentinel must come out as R.string.outbox_error_not_armed — printed raw " +
                "it reads 'not-armed' in nine languages, on a row that is merely waiting",
            OutboxFailureReason.Words(R.string.outbox_error_not_armed),
            outboxFailureReason(OutboxLogic.NOT_ARMED),
        )
        assertEquals(
            "a row with no reason keeps the fallback it always had",
            OutboxFailureReason.Words(R.string.outbox_state_failed_unknown),
            outboxFailureReason(null),
        )
        assertEquals(
            "⛔ and the WHOLE ENGLISH SENTENCE 1.5.2 and 1.5.3 wrote into that column must " +
                "decode to the same words. Those two published versions stored `unarmedNote`'s " +
                "diagnostic English on the row instead of the sentinel, and NO migration rewrites " +
                "it — so a row still QUEUED on a phone that ran either of them prints that English " +
                "under 'Waiting to send' in eight locales, which is the whole thing this branch " +
                "exists to stop.",
            OutboxFailureReason.Words(R.string.outbox_error_not_armed),
            outboxFailureReason(
                "${OutboxLogic.NOT_ARMED_LEGACY_PREFIX} WorkManager is not initialised. " +
                    "The message stays in the Outbox.",
            ),
        )
        assertEquals(
            "the server's own words (#183) are printed as they are — another nature of reason, " +
                "and untouched",
            OutboxFailureReason.Text("550 5.1.1 No such user"),
            outboxFailureReason("550 5.1.1 No such user"),
        )
        assertEquals(
            "⛔ and recognising the historic prefix must not start swallowing the server's " +
                "words: a reason that does NOT open with it stays verbatim, even when it opens " +
                "with the same first word",
            OutboxFailureReason.Text("Queued for delivery, 250 2.0.0 Ok"),
            outboxFailureReason("Queued for delivery, 250 2.0.0 Ok"),
        )
    }

    // -- source lint: that the screen and the banner still ask ------------------------------------

    /**
     * SOURCE LINT. `OutboxScreen` is a composable; nothing here can render it. What is pinned is
     */
    @Test fun `the Outbox screen only ever asks those functions about a row's state`() {
        val lines = codeLines(OUTBOX_SCREEN)
        assertEquals(
            "OutboxScreen must consult item.state only through outboxStateShowsReason / " +
                "outboxStateLabel / outboxStateIsError / outboxShowsWaitingReason (and " +
                "OutboxLogic.canEdit for the button). A `when (item.state)` back in the composable " +
                "is unreachable from every test in this module, which is exactly where the " +
                "`else ->` that printed 'Failed: …' over a saved draft lived. The waiting row's " +
                "complement goes through a pure function for that same reason: whether a row that " +
                "waits prints why it is still there is a decision, and it is decided in " +
                "OutboxRowText where a test can run it.",
            listOf(
                "if (outboxStateShowsReason(item.state)) {",
                "outboxStateLabel(item.state),",
                "stringResource(outboxStateLabel(item.state))",
                "color = if (outboxStateIsError(item.state)) {",
                "if (outboxShowsWaitingReason(item.state, item.lastError)) {",
                "if (OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) {",
            ),
            lines.map { it.trim() }.filter { "item.state" in it },
        )
        assertEquals(
            "and every reading of the row's lastError must go through " +
                "outboxFailureReason(item.lastError) — the raw column printed on screen puts " +
                "'edit-interrupted' or 'not-armed' in nine languages. Three lines: the reason the " +
                "FAILED sentence substitutes, the guard that decides whether a WAITING row prints " +
                "one at all, and the reason that complement resolves.",
            listOf(
                "when (val reason = outboxFailureReason(item.lastError)) {",
                "if (outboxShowsWaitingReason(item.state, item.lastError)) {",
                "when (val reason = outboxFailureReason(item.lastError)) {",
            ),
            lines.map { it.trim() }.filter { "item.lastError" in it },
        )
        assertEquals(
            "and it must name no state string of its own: the wording is decided in " +
                "OutboxRowText, where a test can execute it",
            emptyList<String>(),
            lines.map { it.trim() }.filter { "R.string.outbox_state_" in it || "R.string.outbox_error_" in it },
        )
    }

    /**
     * SOURCE LINT, same disclaimer. The COLOUR of the waiting row's complement, pinned as WHOLE
     */
    @Test fun `the waiting row's complement is not painted as a failure`() {
        val lines = codeLines(OUTBOX_SCREEN).map { it.trim() }
        val at = lines.indexOf("if (outboxShowsWaitingReason(item.state, item.lastError)) {")
        check(at >= 0) { "OutboxScreen prints no waiting-reason complement any more" }
        assertEquals(
            "the complement must be a line of its own, FRAMED by outboxWaitingReasonLabel(), " +
                "resolved through outboxFailureReason, and painted onSurfaceVariant. Printed " +
                "BARE the stored reason reads as the reason the row is waiting NOW, which a " +
                "successful re-arm does not clear; colorScheme.error here would say a row waiting " +
                "for the network has failed — red belongs to FAILED alone, and this red would " +
                "never be taken back when the message finally goes out.",
            listOf(
                "if (outboxShowsWaitingReason(item.state, item.lastError)) {",
                "Text(",
                "stringResource(",
                "outboxWaitingReasonLabel(),",
                "when (val reason = outboxFailureReason(item.lastError)) {",
                "is OutboxFailureReason.Words -> stringResource(reason.id)",
                "is OutboxFailureReason.Text -> reason.text",
                "},",
                "),",
                "style = MaterialTheme.typography.bodySmall,",
                "color = MaterialTheme.colorScheme.onSurfaceVariant,",
            ),
            lines.drop(at).take(11),
        )
        // And WHERE it sits, which no assertion above constrains: the block must come BEFORE
        // the row's button Row. Slid inside it, the complement is laid out as a fourth item on
        val buttons = lines.indexOf(
            "Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {",
        )
        check(buttons >= 0) { "OutboxScreen no longer lays the row's buttons out in that Row" }
        assertTrue(
            "the waiting complement must be laid out BEFORE the Retry/Edit/Delete Row (found at " +
                "$at, the Row at $buttons): inside that Row it becomes a fourth end-aligned item " +
                "and pushes the buttons off the card.",
            at < buttons,
        )
    }

    /**
     * SOURCE LINT, same disclaimer as above: the dialog is a composable and nothing here renders
     */
    @Test fun `the delete confirmation asks which body the row awaiting confirmation is owed`() {
        val lines = codeLines(OUTBOX_SCREEN).map { it.trim() }
        val at = lines.indexOf("pendingDelete?.let { id ->")
        check(at >= 0) { "OutboxScreen opens no delete confirmation on 'pendingDelete' any more" }
        assertEquals(
            "the body of the delete dialog must come from outboxDeleteBody(the pending row's " +
                "state), read out of the list the screen already holds — no second trip to the " +
                "repository. A row gone from that list has no state to speak for it and keeps the " +
                "default body; a row that is INTERRUPTED must NOT be told it was never sent.",
            listOf(
                "pendingDelete?.let { id ->",
                "val pendingState = items.firstOrNull { it.id == id }?.state",
                "val deleteBody = pendingState?.let(::outboxDeleteBody) ?: R.string.outbox_delete_body",
                "AlertDialog(",
                "onDismissRequest = { pendingDelete = null },",
                "title = { Text(stringResource(R.string.outbox_delete_title)) },",
                "text = { Text(stringResource(deleteBody)) },",
            ),
            lines.drop(at).take(7),
        )
    }

    /**
     * SOURCE LINT, same disclaimer: `InboxViewModel` is an `AndroidViewModel` and cannot be built
     */
    @Test fun `the Inbox failure banner is raised through the one rule`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
        val at = lines.indexOfFirst { it.startsWith("val outboxHasFailures") }
        check(at >= 0) { "InboxViewModel declares no 'outboxHasFailures' — did it get renamed?" }
        assertEquals(
            "the banner must be raised by OutboxLogic.needsFailureBanner(it.state) and by nothing " +
                "else. Testing OutboxState.FAILED here by hand is how the rule and the screen drift " +
                "apart; adding KEPT_AS_DRAFT to it puts a permanent 'Some messages didn't send' " +
                "banner over a draft saved exactly as asked (#95).",
            listOf(
                "val outboxHasFailures: StateFlow<Boolean> = repo.outboxFlow()",
                ".map { items -> items.any { OutboxLogic.needsFailureBanner(it.state) } }",
            ),
            lines.drop(at).take(2),
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /** The lines of [file] that are code, with whole-line and trailing comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        private const val OUTBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt"
        private const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, OUTBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val OUTBOX_SCREEN: File by lazy { File(root, OUTBOX_SCREEN_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
