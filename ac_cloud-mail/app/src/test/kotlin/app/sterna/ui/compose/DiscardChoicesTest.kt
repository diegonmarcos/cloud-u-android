package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which buttons the "you are leaving without saving" dialog offers, and which of them may be tapped
 */
class DiscardChoicesTest {

    /** Just the buttons, for the rules that are about the layout rather than about tappability. */
    private fun buttons(mayKeepDraft: Boolean, hasContent: Boolean = true): List<DiscardChoice> =
        discardChoices(mayKeepDraft, hasContent).map { it.choice }

    /** The Save answer of that layout, or null when it is not offered at all. */
    private fun save(mayKeepDraft: Boolean, hasContent: Boolean): DiscardAnswer? =
        discardChoices(mayKeepDraft, hasContent).firstOrNull { it.choice == DiscardChoice.SAVE_DRAFT }

    @Test fun everyLayoutOffersAWayBackToTheComposer() {
        listOf(true, false).forEach { mayKeepDraft ->
            listOf(true, false).forEach { hasContent ->
                val cancel = discardChoices(mayKeepDraft, hasContent)
                    .firstOrNull { it.choice == DiscardChoice.CANCEL }
                assertTrue(
                    "the leave dialog must always offer Cancel (mayKeepDraft = $mayKeepDraft, " +
                        "hasContent = $hasContent): the message is intact behind it, and a dialog " +
                        "whose every button leaves is a dialog with no way back (#35, #127).",
                    cancel != null && cancel.enabled,
                )
            }
        }
    }

    @Test fun theWayBackIsTheFirstButton() {
        // Leftmost, and away from the destructive one: the same order the settings screens' own
        // three-answer exit uses (Cancel · Discard · Save), so the two do not read differently.
        listOf(true, false).forEach { mayKeepDraft ->
            assertEquals(DiscardChoice.CANCEL, buttons(mayKeepDraft).first())
        }
    }

    @Test fun anEncryptedMessageIsNeverOfferedAWayToBecomeADraft() {
        // The 1.4.3 leak, as a value rather than as the shape of a composable: the toolbar hid
        // Save while encrypting and this dialog uploaded the same plaintext one tap later.
        listOf(true, false).forEach { hasContent ->
            assertTrue(
                "no Save draft may be offered when a draft cannot be kept (hasContent = $hasContent)",
                save(mayKeepDraft = false, hasContent = hasContent) == null,
            )
        }
    }

    @Test fun anEmptiedDraftIsNotOfferedASaveThatWouldDeleteIt() {
        // The #35 defect, run on the values the screen actually holds. Open a saved draft, clear
        // the subject and the body, tap the X, tap "Save draft": ComposeViewModel.saveDraft asks
        // draftHasContent, gets false, and calls repo.discardDraft on the message it was opened
        // with — the copy in Drafts is destroyed by the button that promised to save it.
        val emptied = draftHasContent(
            to = "", cc = "", bcc = "", subject = "", body = "   ", hasAttachment = false,
        )
        assertFalse("an emptied composer holds nothing to save", emptied)
        val offered = save(mayKeepDraft = draftSaveAllowed(PgpMode.OFF), hasContent = emptied)
        assertTrue("the answer stays on the dialog, greyed like the toolbar's icon", offered != null)
        assertFalse(
            "Save draft must not be tappable over an emptied composer: that tap deletes the " +
                "draft on the server (#35).",
            offered!!.enabled,
        )
    }

    @Test fun theDialogOffersASaveExactlyWhenTheToolbarDoes() {
        // The equivalence this test is named after, on BOTH of the toolbar's two conditions, run
        // from the toolbar's own functions rather than restated here:
        val filled = listOf(
            Triple("", "", "hello") to true,
            Triple("bob@example.org", "", "") to true,
            Triple("", "Invoice", "") to true,
            Triple("", "", "") to false,
            Triple("  ", " ", "\n ") to false,
        )
        PgpMode.entries.forEach { mode ->
            filled.forEach { (fields, expectedContent) ->
                val (to, subject, body) = fields
                val hasContent = draftHasContent(
                    to = to, cc = "", bcc = "", subject = subject, body = body, hasAttachment = false,
                )
                assertEquals("draftHasContent($fields)", expectedContent, hasContent)
                val toolbarShowsSave = draftSaveAllowed(mode)
                val answer = save(toolbarShowsSave, hasContent)
                assertEquals(
                    "the dialog must offer Save exactly when the toolbar draws its icon " +
                        "(mode = $mode, fields = $fields)",
                    toolbarShowsSave, answer != null,
                )
                if (answer != null) {
                    assertEquals(
                        "the dialog's Save must be tappable exactly when the toolbar's icon is " +
                            "(mode = $mode, fields = $fields)",
                        hasContent, answer.enabled,
                    )
                }
            }
        }
    }

    @Test fun leavingIsAlwaysOnOffer() {
        // The dialog exists to let the user leave; a layout without Discard would be a trap, and a
        // greyed one is the same trap one step later.
        listOf(true, false).forEach { mayKeepDraft ->
            listOf(true, false).forEach { hasContent ->
                val discard = discardChoices(mayKeepDraft, hasContent)
                    .firstOrNull { it.choice == DiscardChoice.DISCARD }
                assertTrue(
                    "Discard must be offered and tappable (mayKeepDraft = $mayKeepDraft, " +
                        "hasContent = $hasContent)",
                    discard != null && discard.enabled,
                )
            }
        }
    }

    @Test fun theConfirmingAnswerComesLast() {
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD, DiscardChoice.SAVE_DRAFT),
            buttons(mayKeepDraft = true),
        )
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD),
            buttons(mayKeepDraft = false),
        )
        // An empty composer keeps the same three answers, in the same places: the layout does not
        // move under the user's finger between one keystroke and the next.
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD, DiscardChoice.SAVE_DRAFT),
            buttons(mayKeepDraft = true, hasContent = false),
        )
    }

    @Test fun noButtonIsOfferedTwice() {
        listOf(true, false).forEach { mayKeepDraft ->
            listOf(true, false).forEach { hasContent ->
                val choices = buttons(mayKeepDraft, hasContent)
                assertEquals(choices.size, choices.distinct().size)
            }
        }
    }
}
