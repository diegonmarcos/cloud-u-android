package app.sterna.ui.compose

import app.sterna.send.SendOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the composer's top bar calls the message it is holding (#96).
 */
class ComposeTitleTest {
    private fun title(
        draftId: String? = null,
        mode: String? = null,
        replyTo: String? = null,
        restore: Boolean = false,
        editingOutbox: Boolean = false,
    ) = composeTitle(draftId, mode, replyTo, restore, editingOutbox)

    // --- the eight ways a composer opens ---------------------------------------------------------

    @Test fun aComposerThatOpenedNothingIsANewMail() {
        // A blank compose, and equally one prefilled from a mailto: link, from "write to this
        // participant" or from a system Share: fields may be filled in, but no draft, no original
        // and no queued row was opened — so none of them names the screen.
        assertEquals(ComposeTitle.NEW, title())
    }

    @Test fun aReplyIsAReply() {
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1"))
    }

    @Test fun aReplyAllIsAReply() {
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1", mode = "replyAll"))
    }

    @Test fun aForwardIsAForward() {
        // A forward carries the same replyTo as a reply and used to read "Reply" (#96).
        assertEquals(ComposeTitle.FORWARD, title(replyTo = "m1", mode = "forward"))
    }

    @Test fun aReopenedSavedDraftIsADraft() {
        assertEquals(ComposeTitle.DRAFT, title(draftId = "d1"))
    }

    @Test fun aMessageTakenBackOutOfTheOutboxIsAnEdit() {
        assertEquals(ComposeTitle.OUTBOX_EDIT, title(restore = true, editingOutbox = true))
    }

    @Test fun anUndoneSendIsANewMailNotAnEdit() {
        // Intended, and not the same case as the one above: undoing dropped the queued row, so
        // there is no longer anything in the outbox to be editing.
        assertEquals(ComposeTitle.NEW, title(restore = true, editingOutbox = false))
    }

    // --- the reporter's scenario, in plain words -------------------------------------------------

    @Test fun aComposerThatCameBackEmptyAfterTheAppWasKilledIsNotAnEdit() {
        // Kill the app with the composer open on a restored message, then reopen it: the navigation
        // still says restore=true, but the message it referred to died with the process, so nothing
        // is parked behind this screen. The fields are empty; the title must not claim otherwise.
        val restoredDraftSurvived = false
        assertEquals(ComposeTitle.NEW, title(restore = true, editingOutbox = restoredDraftSurvived))
    }

    // --- precedence: the order the cases are tested in is itself the rule ------------------------

    @Test fun aDraftOutranksEverything() {
        assertEquals(
            ComposeTitle.DRAFT,
            title(draftId = "d1", replyTo = "m1", mode = "forward", restore = true, editingOutbox = true),
        )
    }

    @Test fun aForwardAsAttachmentIsAForward() {
        // Same replyTo as a reply, no "forward" literal: read through composeOpening, or the top
        // bar says "Reply" over an empty-bodied "Fwd:" with a .eml attached.
        assertEquals(ComposeTitle.FORWARD, title(replyTo = "m1", mode = "forwardAttachment"))
    }

    @Test fun aForwardOutranksTheReplyItSharesItsArgumentWith() {
        assertEquals(ComposeTitle.FORWARD, title(replyTo = "m1", mode = "forward"))
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1", mode = null))
    }

    @Test fun aHeldOutboxRowOnlyTitlesAComposerThatWasActuallyRestored() {
        // Belt and braces: the flag alone, without the navigation argument, is not an edit.
        assertEquals(ComposeTitle.NEW, title(restore = false, editingOutbox = true))
    }

    // --- where the flag itself comes from --------------------------------------------------------
    //
    // The defect was never in the title's `when`; it was in what the `when` was handed. These cover
    // the input, which the ViewModel now asks [holdsQueuedOutboxRow] for in both places it needs it.

    private fun draft(editingOutboxId: Long?) = SendOutbox.ComposeDraft(
        to = "jordan@example.org",
        cc = "",
        bcc = "",
        subject = "Report",
        body = "Body",
        fromAccountId = "acc1",
        fromIdentityEmail = "alex@example.org",
        attachments = emptyList(),
        inReplyTo = emptyList(),
        references = emptyList(),
        // Edited from a saved draft, with the numbering its id was read under (#99): the field has
        // no default, so every fixture states what the message actually carries.
        draftEmailId = "d1",
        draftUidValidity = 42L,
        draftBodyIsLossy = false,
        editingOutboxId = editingOutboxId,
    )

    @Test fun aRestoreCarryingAQueuedRowHoldsIt() {
        assertTrue(holdsQueuedOutboxRow(restore = true, restored = draft(editingOutboxId = 7L)))
    }

    @Test fun aRestoreThatFoundNothingHoldsNothing() {
        // THE historical defect, at its source: the app was killed, so the argument survived and the
        // handed-over draft did not. `restore=true` on its own must not mean "a row is behind this".
        assertFalse(holdsQueuedOutboxRow(restore = true, restored = null))
    }

    @Test fun anUndoneSendHoldsNothingEither() {
        // A draft is there, but undoing the send already dropped its row: nothing to hand back.
        assertFalse(holdsQueuedOutboxRow(restore = true, restored = draft(editingOutboxId = null)))
    }

    @Test fun aComposerThatIsNotARestoreHoldsNothing() {
        // A draft left unconsumed in the handle must not leak into an ordinary compose — this is
        // what stops the leave dialog describing an outbox row that is not behind this screen.
        assertFalse(holdsQueuedOutboxRow(restore = false, restored = draft(editingOutboxId = 7L)))
    }

    // --- which row a composer rebuilt after a process death takes back -----------------------------
    //
    // The in-memory hand-over dies with the process; the route survives it and now carries the row
    // id. [outboxRowToResume] says which of the two the composer listens to, and it is executed
    // here rather than read: invert its condition and a live reopen would take the row a SECOND
    // time on top of the one `OutboxViewModel.edit` already holds EDITING.

    @Test fun aLiveReopenResumesNothingTheHandOverWinsAndTheRowIsAlreadyHeld() {
        assertNull(outboxRowToResume(restored = draft(editingOutboxId = 7L), outboxId = 7L))
    }

    @Test fun aComposerRebuiltAfterTheAppWasKilledResumesTheRowTheRouteNames() {
        assertEquals(7L, outboxRowToResume(restored = null, outboxId = 7L))
    }

    @Test fun anUndoRouteRebuiltAfterTheAppWasKilledResumesNothing() {
        // `compose?restore=true` with no id: the undone send lived only in memory, there is no row.
        assertNull(outboxRowToResume(restored = null, outboxId = null))
    }

    @Test fun anUndoneSendHandedOverResumesNothingEither() {
        assertNull(outboxRowToResume(restored = draft(editingOutboxId = null), outboxId = null))
    }

    @Test fun theTitleAgreesWithWhatTheComposerActuallyHolds() {
        // The two halves joined the way the ViewModel joins them: the flag the screen is given is
        // this function's answer, so an empty restored composer can no longer read "Edit".
        val killed = holdsQueuedOutboxRow(restore = true, restored = null)
        assertEquals(ComposeTitle.NEW, title(restore = true, editingOutbox = killed))

        val reopened = holdsQueuedOutboxRow(restore = true, restored = draft(editingOutboxId = 7L))
        assertEquals(ComposeTitle.OUTBOX_EDIT, title(restore = true, editingOutbox = reopened))
    }
}
