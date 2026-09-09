package app.sterna.core.data.mail

import app.sterna.core.data.text.draftHtmlIsLossy
import app.sterna.core.data.text.richBodyFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [reopenedDraftIsLossy], EXECUTED — the verdict the composer computes when it reopens a saved
 */
class ReopenedDraftLossyTest {

    /** The witness: a plain-text draft with nothing this editor drops may be replaced. */
    @Test fun aPlainDraftWithNothingThisEditorDropsIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anHtmlBodyAloneIsLossy() {
        // Flattened to plain text on open: sending and destroying loses the formatting for good.
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = true,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anInlineImageAloneIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 1,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun aCalendarPartAloneIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 1,
                receiptHeader = null,
            ),
        )
    }

    /**
     * The defect this leg closes: the draft asked for a read receipt, the composer's checkbox
     */
    @Test fun aReadReceiptRequestAloneIsLossy() {
        assertTrue(
            "a draft whose Disposition-Notification-To names a destination cannot be reproduced " +
                "by this composer, so it must not be destroyed on send",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "<ann@example.org>",
            ),
        )
    }

    /** The header as senders actually write it: a display name, and several destinations. */
    @Test fun aReadReceiptRequestNamingSeveralDestinationsIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "Ann Lee <ann@example.org>, ops@example.org",
            ),
        )
    }

    /** No header at all: nothing was asked for, so nothing can be lost by this leg. */
    @Test fun noReceiptHeaderIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anEmptyReceiptHeaderIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "",
            ),
        )
    }

    @Test fun aBlankReceiptHeaderIsNotLossy() {
        assertFalse(
            "a header made of whitespace names no destination — keeping every such draft would " +
                "be a duplicate in Drafts on every save, bought with nothing",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "   ",
            ),
        )
    }

    /**
     * A header that is THERE but names no address (`<>`, the shape a server hands back for a
     */
    @Test fun aReceiptHeaderNamingNoAddressIsLossy() {
        assertTrue(
            "a present but unreadable receipt header is an unknown, and an unknown keeps the " +
                "original — the cost is a duplicate in Drafts, which is deletable",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "<>",
            ),
        )
    }

    /** The other unreadable shapes, each one a header the sender did write. */
    @Test fun anUnreadableReceiptHeaderIsLossyWhateverItsShape() {
        for (header in listOf("<>", "Ann Lee <>", "not-an-address", "undisclosed-recipients:;", ",")) {
            assertTrue(
                "receiptHeader = \"$header\" is present but names no address: unknown, keep",
                reopenedDraftIsLossy(
                    hasHtmlBody = false,
                    inlineImageCount = 0,
                    calendarPartCount = 0,
                    receiptHeader = header,
                ),
            )
        }
    }

    // --- the html leg, as the composer now feeds it (#131) --------------------------------------

    /**
     * Since #131 the composer does not hand this leg "there is an html part": it hands it
     */
    @Test fun anHtmlThisEditorCanGiveBackIsNotLossy() {
        assertFalse(
            "a draft this app styled reopens with its spans, so replacing it loses nothing",
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy("<b>hello</b> world"),
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anHtmlWrittenElsewhereIsStillLossy() {
        // The Thunderbird shape: a paragraph with a style attribute. Flattened to text on open.
        assertTrue(
            "an html this editor cannot read back is the loss the whole verdict exists for",
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy("""<p style="font-weight:bold">hello</p>"""),
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    /**
     * **The pair that is a DESTROY, end to end**: a draft whose `text/plain` part has the user's
     */
    @Test fun aDraftWhoseHtmlPartIsEmptyOpensOnItsTextAndKeepsItsOriginal() {
        val html = ""
        val body = richBodyFrom(html) { "see you at six, do not wait for me" }

        assertEquals(
            "the composer must open on the text part — an empty html part is not an empty draft",
            "see you at six, do not wait for me",
            body.text,
        )
        assertTrue(
            "…and the verdict must keep the original: this editor did not read that html part, " +
                "so it cannot answer for it",
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy(html),
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    /** …and a draft with no html part at all is untouched by the change. */
    @Test fun noHtmlPartIsStillNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy(null),
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    /** …but the OTHER legs still decide beside a readable html: fidelity is not one term. */
    @Test fun ourOwnHtmlDoesNotRescueAnInlineImageOrAReceipt() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy("<b>hello</b>"),
                inlineImageCount = 1,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = draftHtmlIsLossy("<b>hello</b>"),
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "ann@example.org",
            ),
        )
    }

    /** Legs are independent: a receipt request stays decisive next to a clean body and back. */
    @Test fun theReceiptLegDecidesOnItsOwnBesideACleanBody() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = true,
                inlineImageCount = 2,
                calendarPartCount = 1,
                receiptHeader = "ann@example.org",
            ),
        )
        // …and a clean body does not rescue an unreadable header: the leg alone decides.
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "not-an-address",
            ),
        )
        // The witness on the other side, so this is not a test that only ever says "lossy":
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "\t \n ",
            ),
        )
    }
}
