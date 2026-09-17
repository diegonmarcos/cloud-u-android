package app.sterna.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The message-list look — Gmail's conversation list, one declaration (#472).
 *
 * Every colour and dimension a message list draws comes from here and nowhere else. A view that
 * needs a card colour, a gutter or a text tone reads [LocalMailListPalette] / [MailListDimens];
 * a literal `Color(0xFF…)` or `…dp` inside a list view is the defect this object exists to close.
 *
 * The point of the change is separation: a row must read as ITS OWN RECTANGLE sitting on a
 * different ground. In the dark scheme that is a BLACK card on a dark-grey pane with UNREAD text
 * WHITE and READ text light grey. The light scheme mirrors it (white cards on a light-grey pane,
 * near-black unread, grey read) so the shape is the same wherever the reader is.
 */
internal data class MailListPalette(
    /** The pane the cards sit on — the whole list area behind and between the cards. */
    val pane: Color,
    /** The fill of one message card. */
    val card: Color,
    /** The text ink of an unread card (sender, subject, preview, time). */
    val unreadText: Color,
    /** The text ink of a read card (sender, subject, preview, time). */
    val readText: Color,
) {
    /**
     * The OLED variant: the pane is pulled toward black the same way the raised surface roles are
     * ([ColorScheme.pulledToBlack]) — but only the pane. The card is already black, and the text
     * inks must keep their read/unread difference whatever the pane does.
     */
    fun pulledToBlack(): MailListPalette = copy(pane = lerp(pane, Color.Black, PureBlackPull))
}

/** Light scheme — white cards on a light-grey pane, Gmail-light's conversation list. */
internal val ArcticMailListPalette = MailListPalette(
    pane = Color(0xFFE8EAED),
    card = Color(0xFFFFFFFF),
    unreadText = Color(0xFF202124),
    readText = Color(0xFF5F6368),
)

/** Dark scheme — black cards on a dark-grey pane, Gmail-dark's conversation list. */
internal val PelagicMailListPalette = MailListPalette(
    pane = Color(0xFF1D1F24),
    card = Color(0xFF000000),
    unreadText = Color(0xFFF1F3F5),
    readText = Color(0xFF9AA0A6),
)

/** The active message-list palette, provided by [SternaTheme]. Internal: every reader is in this
 *  module, and a public property may not expose the internal [MailListPalette] type. */
internal val LocalMailListPalette = compositionLocalOf { PelagicMailListPalette }

/**
 * One dimension declaration for the message-list cards (#472): the corner radius and the two
 * gutters that lift a row off its neighbours. A card must show the pane through [gutterV] above
 * and below and [gutterH] on each side, or the list reads as one flat sheet again (#464).
 */
internal object MailListDimens {
    /** Corner radius of a message card. */
    val corner = 12.dp

    /** The card's rounded shape — the one shape every card wears. */
    val shape = RoundedCornerShape(corner)

    /** Pane visible left and right of every card. */
    val gutterH = 8.dp

    /** Pane visible above and below every card. */
    val gutterV = 6.dp
}

/**
 * The TEXT answer a message row wears — colour AND weight in one object (#478). One declaration
 * per fact: a row that reads 'unread' here is BOLD in the unread ink, one that reads 'read' is
 * regular in the read ink. Nothing in a row may spell the read/unread decision a second time.
 */
internal data class MailListTextInk(
    val color: Color,
    val weight: FontWeight,
)

/**
 * The one decision that says how a row's message text reads: unread takes the palette's unread
 * ink AND bold weight, read takes its read ink AND regular weight — never a literal, never a
 * scheme role read here. The two palettes are declared with different inks on purpose (white vs
 * light grey in the dark scheme), so "is this mail unread" is answered by the text even though
 * the card background is the same. The weight rides the SAME branch as the colour (#478): unread
 * is BOLD, read is NOT — one predicate, never a second 'if (unread)' beside this one.
 */
internal fun mailListTextInk(unread: Boolean, palette: MailListPalette): MailListTextInk =
    if (unread) MailListTextInk(palette.readText, FontWeight.Bold)
    else MailListTextInk(palette.unreadText, FontWeight.Normal)
