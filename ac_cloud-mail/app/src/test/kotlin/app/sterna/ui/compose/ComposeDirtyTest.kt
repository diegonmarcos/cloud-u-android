package app.sterna.ui.compose

import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unsaved-changes guard must fire on a real edit and stay quiet on a close that changed nothing
 */
class ComposeDirtyTest {
    private fun dirty(
        onlyCopy: Boolean = false,
        to: String = "", initialTo: String = "",
        cc: String = "", initialCc: String = "",
        bcc: String = "", initialBcc: String = "",
        subject: String = "", initialSubject: String = "",
        body: RichBody = RichBody.plain(""), initialBody: RichBody = RichBody.plain(""),
        requestReceipt: Boolean = false, initialRequestReceipt: Boolean = false,
        attachmentsTouched: Boolean = false,
    ) = ComposeDirty.isDirty(
        onlyCopy, to, initialTo, cc, initialCc, bcc, initialBcc,
        subject, initialSubject, body, initialBody,
        requestReceipt, initialRequestReceipt, attachmentsTouched,
    )

    @Test fun anUntouchedReopenedMessageIsNotDirty() {
        // Reopened from the outbox with everything pre-filled — nothing changed since.
        assertFalse(
            dirty(
                to = "jordan@example.org, ", initialTo = "jordan@example.org, ",
                cc = "cc@example.org, ", initialCc = "cc@example.org, ",
                bcc = "bcc@example.org, ", initialBcc = "bcc@example.org, ",
                subject = "Report", initialSubject = "Report",
                body = RichBody.plain("Body"), initialBody = RichBody.plain("Body"),
            ),
        )
    }

    @Test fun reorderingRecipientChipsIsNotAnEdit() {
        // Tapping a chip to edit it reorders the field; the set of addresses is the same (#94).
        assertFalse(
            dirty(
                to = "b@example.org, a@example.org", initialTo = "a@example.org, b@example.org",
            ),
        )
    }

    @Test fun caseAndSpacingDifferencesInRecipientsAreNotAnEdit() {
        assertFalse(dirty(to = "  Alex@Example.org ", initialTo = "alex@example.org"))
    }

    @Test fun addingARecipientIsDirty() {
        assertTrue(dirty(to = "a@example.org, c@example.org", initialTo = "a@example.org"))
    }

    @Test fun editingSubjectOrBodyIsDirty() {
        assertTrue(dirty(subject = "New", initialSubject = "Old"))
        assertTrue(dirty(body = RichBody.plain("New"), initialBody = RichBody.plain("Old")))
    }

    // --- the styling is part of the body (#131) -----------------------------------------------

    @Test fun boldingAWordWithoutChangingTheTextIsDirty() {
        assertTrue(
            "the same text with one span more is an edit: a draft closed here would lose the bold " +
                "the user just applied, without a word",
            dirty(
                body = RichBody("Body text", mapOf(Inline.BOLD to listOf(Span(0, 4)))),
                initialBody = RichBody.plain("Body text"),
            ),
        )
    }

    @Test fun aReopenedStyledDraftIsNotDirtyUntouched() {
        val styled = mapOf(Inline.BOLD to listOf(Span(0, 4)), Inline.ITALIC to listOf(Span(5, 9)))
        assertFalse(
            "same text, same spans: reopening a styled draft and closing it changed nothing",
            dirty(body = RichBody("Body text", styled), initialBody = RichBody("Body text", styled)),
        )
    }

    @Test fun turningTwoLinesIntoAListWithoutChangingTheTextIsDirty() {
        // #131 lists. The markers are DRAWN, never typed, so the text is byte for byte what it was:
        // if the blocks were not compared, tapping "bulleted list" and leaving would discard the
        // list without a word, on a screen that showed it a second earlier.
        assertTrue(
            dirty(
                body = RichBody("milk\neggs", emptyMap(), listOf(Block(BlockKind.BULLET, 0..1))),
                initialBody = RichBody.plain("milk\neggs"),
            ),
        )
    }

    @Test fun aReopenedDraftHoldingAListIsNotDirtyUntouched() {
        val blocks = listOf(Block(BlockKind.NUMBER, 0..1))
        assertFalse(
            "same text, same blocks: reopening a draft that held a list and closing it changed nothing",
            dirty(
                body = RichBody("milk\neggs", emptyMap(), blocks),
                initialBody = RichBody("milk\neggs", emptyMap(), blocks),
            ),
        )
    }

    @Test fun touchingAttachmentsIsDirtyButMerelyHavingThemIsNot() {
        assertTrue(dirty(attachmentsTouched = true))
        assertFalse(dirty(attachmentsTouched = false))
    }

    @Test fun anUndoneSendIsAlwaysGuardedEvenUntouched() {
        assertTrue(dirty(onlyCopy = true))
    }

    // --- the read-receipt box, both senses ------------------------------------------------------
    //
    // The grave one is CLEARING it. On a message reopened from the outbox the leave path is
    // `cancel()`, which hands the queued row back exactly as it was: an unseen clear means the
    // message goes out still asking for a receipt while the screen had just said it would not.

    @Test fun clearingTheReadReceiptBoxIsDirty() {
        assertTrue(
            "unticking \"ask for a read receipt\" and leaving must raise the discard dialogue: the " +
                "queued message is handed back unchanged, so a silent close SENDS the request the " +
                "user just withdrew",
            dirty(requestReceipt = false, initialRequestReceipt = true),
        )
    }

    @Test fun tickingTheReadReceiptBoxIsDirty() {
        assertTrue(
            "ticking it and leaving must raise the dialogue too, or the request is dropped with " +
                "the screen still showing it as asked for",
            dirty(requestReceipt = true, initialRequestReceipt = false),
        )
    }

    @Test fun aReadReceiptBoxThatOpenedTickedIsNotAnEdit() {
        // The witness (#70 BLOCKER 3): a reopened outbox message opens with the box already ticked.
        // Counting its mere PRESENCE would fire the discard dialogue on a reopen-and-close where
        // nothing was touched, which is the very mistake this file exists to prevent.
        assertFalse(dirty(requestReceipt = true, initialRequestReceipt = true))
        assertFalse(dirty(requestReceipt = false, initialRequestReceipt = false))
    }
}
