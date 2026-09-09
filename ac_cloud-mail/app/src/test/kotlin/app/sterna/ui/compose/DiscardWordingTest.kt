package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "you are leaving without saving" dialog is allowed to claim (#70/#35).
 */
class DiscardWordingTest {
    @Test fun anOrdinaryComposerSaysTheOrdinaryThing() {
        assertEquals(DiscardWording.PLAIN, discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false))
    }

    @Test fun anEncryptedMessageExplainsWhyItCannotBecomeADraft() {
        assertEquals(DiscardWording.ENCRYPTED, discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = false))
    }

    @Test fun aMessageNeverSavedIsTheOneThatCanReallyBeDiscarded() {
        // The title "Discard message?" is TRUE here and stays: nothing of this message exists
        // anywhere else, so leaving really does destroy it.
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false),
        )
    }

    @Test fun aDraftAlreadyOnTheServerIsNotDiscardedByLeaving() {
        // #35/#127: reopen a saved draft, type, tap the close button. The dialog said "Discard
        // message?" while its own body said "changes" — and leaving destroys nothing on the server
        // (cancel() → abandon(), a no-op outside the outbox). The draft is still in Drafts
        // afterwards, exactly as it was before this composer opened.
        assertEquals(
            DiscardWording.DRAFT,
            discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = true),
        )
    }

    @Test fun aDraftBeingEncryptedStillExplainsWhyItCannotBeSaved() {
        // Reached by closing the padlock by hand on a reopened draft. Both halves are owed at once:
        // the reason the Save button is gone (a draft is uploaded exactly as typed), AND the fact
        // that the copy in Drafts survives. The plain ENCRYPTED wording ends on "Leaving now
        // discards the message", which is false here — that was the last title still lying.
        assertEquals(
            DiscardWording.ENCRYPTED_DRAFT,
            discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = true),
        )
        assertTrue(
            "an encrypted draft keeps its message, so it may not be announced as destroyed",
            discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = true).keepsMessage,
        )
    }

    @Test fun theEncryptedDraftStillOffersNoWayIntoDrafts() {
        // 1.4.3's guarantee, checked on the new variant rather than assumed to be inherited: the
        // buttons are decided by mayKeepDraft alone, so the sixth wording cannot have reopened the
        // plaintext path.
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD),
            discardChoices(mayKeepDraft = false, hasContent = true).map { it.choice },
        )
    }

    @Test fun aQueuedMessageOutranksADraftBehindTheSameScreen() {
        // A send queued from a reopened draft carries both. Where the MESSAGE is outranks what the
        // screen was opened from: it is in the outbox, and the outbox wording is the one that says
        // so (#70).
        assertEquals(
            DiscardWording.OUTBOX,
            discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = true),
        )
        assertEquals(
            DiscardWording.OUTBOX_ENCRYPTED,
            discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = true),
        )
    }

    @Test fun onlyAMessageThatExistsNowhereElseMayBeAnnouncedAsDestroyed() {
        // The rule stated once over the whole input space, so a later branch cannot quietly widen
        // the one title that claims destruction. PLAIN is that title; everything with a copy
        listOf(true, false).forEach { mayKeepDraft ->
            listOf(true, false).forEach { editingOutbox ->
                listOf(true, false).forEach { editingDraft ->
                    val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft)
                    val existsElsewhere = editingOutbox || editingDraft
                    assertTrue(
                        "discardWording($editingOutbox, $mayKeepDraft, $editingDraft) = $wording " +
                            "may not be PLAIN: a copy of this message exists off this screen, so " +
                            "\"Discard message?\" is false.",
                        !existsElsewhere || wording != DiscardWording.PLAIN,
                    )
                    assertEquals(
                        "discardWording($editingOutbox, $mayKeepDraft, $editingDraft) = $wording " +
                            "must report keepsMessage = $existsElsewhere: that flag is what the " +
                            "title and the discard button key on, so a variant that gets it wrong " +
                            "reaches the screen saying the opposite of what it decided.",
                        existsElsewhere, wording.keepsMessage,
                    )
                }
            }
        }
    }

    // -- where `editingDraft` comes from, which is where the last lie was -----------------------

    @Test fun aComposerOpenedOnASavedDraftHasOneBehindIt() {
        assertTrue(savedDraftBehindScreen(draftId = "d1", restoredDraftId = null))
    }

    @Test fun anUndoneSendStartedFromADraftStillHasThatDraftBehindIt() {
        // THE hole, and it is the reported symptom reached by another path. A send started from a
        // draft leaves that draft in Drafts until the message is DELIVERED; Undo only removes the
        assertTrue(savedDraftBehindScreen(draftId = null, restoredDraftId = "d1"))
    }

    @Test fun anUndoneSendOfANewMailHasNothingBehindIt() {
        // The message really does exist nowhere else here — it was never a draft — so this is the
        // case where "Discard message?" is the true title, and it must stay reachable.
        assertFalse(savedDraftBehindScreen(draftId = null, restoredDraftId = null))
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(
                editingOutbox = false,
                mayKeepDraft = true,
                editingDraft = savedDraftBehindScreen(draftId = null, restoredDraftId = null),
            ),
        )
    }

    @Test fun aDraftReopenedOfflineIsStillADraftOnTheServer() {
        // The navigation argument, not whether the draft could be READ: an offline reopen whose
        // fetch failed has its draft sitting in Drafts, untouched.
        assertEquals(
            DiscardWording.DRAFT,
            discardWording(
                editingOutbox = false,
                mayKeepDraft = true,
                editingDraft = savedDraftBehindScreen(draftId = "d1", restoredDraftId = null),
            ),
        )
    }

    @Test fun aMessageTakenBackOutOfTheOutboxIsNeverAnnouncedAsDestroyed() {
        // The reporter's scenario: reopen a queued message, change something, tap the close button.
        // Only the changes go; the message is still in the outbox afterwards.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false))
    }

    @Test fun whereTheMessageIsOutranksWhyItCannotBeADraft() {
        // Reached by closing the padlock BY HAND on a message reopened from the outbox — an already
        // encrypted row cannot be reopened at all (OutboxLogic.canEdit refuses it, its ciphertext
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false).fromOutbox)
    }

    @Test fun theOutboxWordingSplitsOnWhetherSaveDraftIsOnOffer() {
        // The body has to be true of BOTH buttons in front of the user, and "Save draft" is the one
        // that takes the message OUT of the outbox (it consumes the queued row). So the sentence
        // that mentions drafts is used only where that button exists; the other layout, which can
        // only leave, gets the shorter sentence and may point at the outbox's own delete.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false))
        assertEquals(
            DiscardWording.OUTBOX_ENCRYPTED,
            discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false),
        )
    }

    @Test fun bothOutboxLayoutsShareTheirTitleAndTheirDiscardButton() {
        // Only the body differs between the two: [fromOutbox] is what the title and the button key
        // on, so they cannot drift apart as the body splits.
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false).fromOutbox)
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = false).fromOutbox)
    }

    @Test fun theDialogAndTheToolbarAgreeOnWhatMayBecomeADraft() {
        // The wording of "you can't save this" must follow the same rule as the hidden Save button,
        // or the dialog would explain an absence the toolbar does not have (#35).
        assertEquals(
            DiscardWording.ENCRYPTED,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.ENCRYPT), editingDraft = false),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.SIGN), editingDraft = false),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.OFF), editingDraft = false),
        )
    }
}
