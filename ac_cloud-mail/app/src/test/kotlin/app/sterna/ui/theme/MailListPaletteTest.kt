package app.sterna.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The message-list palette (#472) — the one declaration every list view reads. The whole point of
 * the change is that read and unread are told apart by the TEXT ink, and that a card is a card
 * because it sits on a different-coloured pane. Both must hold in BOTH schemes, or the list falls
 * back to one flat, single-colour sheet.
 */
class MailListPaletteTest {

    // -- read vs unread, the text inks -----------------------------------------------------------

    /** Mutation target 1: make the two inks equal here and every unread row reads as a read one. */
    @Test fun `the dark scheme separates unread text from read text`() {
        val palette = PelagicMailListPalette
        assertNotEquals(
            "unread must be WHITE and read light grey in the dark scheme; equal inks are the " +
                "whole defect this palette exists to kill. unreadText=${palette.unreadText} " +
                "readText=${palette.readText}",
            palette.unreadText,
            palette.readText,
        )
    }

    @Test fun `the light scheme separates unread text from read text`() {
        val palette = ArcticMailListPalette
        assertNotEquals(
            "near-black vs grey in the light scheme, for the same reason as the dark one",
            palette.unreadText,
            palette.readText,
        )
    }

    /** The reader routes through the ONE decision, so it cannot spell one side differently. */
    @Test fun `the text-ink decision returns exactly the palette inks`() {
        assertEquals(PelagicMailListPalette.unreadText, mailListTextInk(unread = true, PelagicMailListPalette).color)
        assertEquals(PelagicMailListPalette.readText, mailListTextInk(unread = false, PelagicMailListPalette).color)
        assertEquals(ArcticMailListPalette.unreadText, mailListTextInk(unread = true, ArcticMailListPalette).color)
        assertEquals(ArcticMailListPalette.readText, mailListTextInk(unread = false, ArcticMailListPalette).color)
    }

    /**
     * The weight axis (#478): the SAME decision carries the bold. Unread rows are BOLD in both
     * schemes, read rows are regular — this is the mutation target 'make unread regular' — and
     * the two must be exact, not merely different (a swap would still differ but would be wrong).
     */
    @Test fun `the text-ink decision weights unread bold and read regular`() {
        assertEquals(FontWeight.Bold, mailListTextInk(unread = true, PelagicMailListPalette).weight)
        assertEquals(FontWeight.Normal, mailListTextInk(unread = false, PelagicMailListPalette).weight)
        assertEquals(FontWeight.Bold, mailListTextInk(unread = true, ArcticMailListPalette).weight)
        assertEquals(FontWeight.Normal, mailListTextInk(unread = false, ArcticMailListPalette).weight)
    }

    // -- card vs pane, the separation ------------------------------------------------------------

    /** Mutation target 2: make the card the same colour as the pane and the cards disappear. */
    @Test fun `the dark card sits on a lighter pane`() {
        val palette = PelagicMailListPalette
        assertNotEquals(
            "a black card needs a visibly lighter dark-grey pane behind it, or the list is one " +
                "flat black sheet. pane=${palette.pane} card=${palette.card}",
            palette.pane,
            palette.card,
        )
    }

    @Test fun `the light card sits on a darker pane`() {
        val palette = ArcticMailListPalette
        assertNotEquals(
            "a white card needs a visibly darker light-grey pane behind it, Gmail-light's deck",
            palette.pane,
            palette.card,
        )
    }

    /** The dark scheme really is the Gmail-dark look Diego asked for: black card, white unread. */
    @Test fun `the dark card is black and unread ink is white`() {
        assertEquals(Color(0xFF000000), PelagicMailListPalette.card)
        assertEquals(Color(0xFFF1F3F5), PelagicMailListPalette.unreadText)
    }

    // -- the OLED pull --------------------------------------------------------------------------

    @Test fun `the OLED pull moves the pane toward black and leaves the inks alone`() {
        val pulled = PelagicMailListPalette.pulledToBlack()
        assertEquals(PelagicMailListPalette.card, pulled.card)
        assertEquals(PelagicMailListPalette.unreadText, pulled.unreadText)
        assertEquals(PelagicMailListPalette.readText, pulled.readText)
        assertNotEquals(
            "the pane must come down toward black with the rest of the OLED scheme",
            PelagicMailListPalette.pane,
            pulled.pane,
        )
    }

    // -- the one dimension declaration ----------------------------------------------------------

    /** A zero gutter is exactly how a card list silently becomes a flat list (#464 follow-up). */
    @Test fun `the card dimensions keep real gutters and a real corner`() {
        assertNotEquals(0.dp, MailListDimens.gutterH)
        assertNotEquals(0.dp, MailListDimens.gutterV)
        assertNotEquals(0.dp, MailListDimens.corner)
    }
}