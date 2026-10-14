package app.sterna.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * THE density declaration of Cloud Mail: a dense, data-first reading of the whole UI. Every size on
 * screen is read from here - the type scale ([denseTypography]), icon and touch sizes, the spacing
 * steps and the fixed boxes - and NO screen carries a `…dp` literal of its own (guarded by
 * `test/test-mail-density.sh`). Change the feel of the whole app by editing THIS file only.
 *
 * What this is NOT: no `Density` / `fontScale` override, no `scaleX/scaleY`, no `graphicsLayer`
 * scale, no WebView zoom. Those shrink the picture of the UI and leave every element the wrong size
 * (touch targets under 36dp, clipped text). Here each element is resized on its own: the type
 * styles, the icon box, the row height, each padding step.
 *
 * The number in a step's name is the DESIGN step (the launcher-sized value the screens were written
 * against); the value it holds is the dense size. `s16` is "the 16 step", and it is 13dp.
 *
 * The factor is the fleet's own: the Launcher's Scale "Default" (step 3 = 0.70 + 0.05 * 3 = 0.85,
 * #337/#408) is what the owner reads Cloud Mail at. That scale reached mail only as a DEVICE setting
 * (wm density / font_scale), so any device without it showed mail at launcher size. Here the same
 * proportions are the app's own: type 0.85, spacing/boxes about 0.8, touch floor held at 36dp.
 */
internal object MailMetrics {
    /** Every Material text style is multiplied by this, once, in [denseTypography]. */
    const val TEXT_SCALE = 0.85f

    /** No text style goes below this: dense is not unreadable. */
    const val MIN_TEXT_SP = 10f

    // -- Spacing / box steps. One value per step the screens use; nothing else is allowed. --------
    val hair: Dp = 1.dp
    val s2: Dp = 1.5.dp
    val s3: Dp = 2.5.dp
    val s4: Dp = 3.dp
    val s6: Dp = 5.dp
    val s8: Dp = 6.5.dp
    val s10: Dp = 8.dp
    val s12: Dp = 10.dp
    val s14: Dp = 11.dp
    val s16: Dp = 13.dp
    val s18: Dp = 14.5.dp
    val s20: Dp = 16.dp
    val s22: Dp = 17.5.dp
    val s24: Dp = 19.dp
    val s28: Dp = 22.5.dp
    val s32: Dp = 26.dp
    val s40: Dp = 32.dp
    val s44: Dp = 36.dp
    val s48: Dp = 40.dp
    val s54: Dp = 43.dp
    val s56: Dp = 45.dp
    val s64: Dp = 51.dp
    val s80: Dp = 64.dp
    val s90: Dp = 72.dp
    val s104: Dp = 83.dp
    val s110: Dp = 88.dp
    val s120: Dp = 96.dp
    val s128: Dp = 102.dp
    val s132: Dp = 106.dp
    val s256: Dp = 205.dp
    val s280: Dp = 224.dp

    // -- Named elements ----------------------------------------------------------------------------
    /** An icon glyph, where Material draws 24dp. Applied by [app.sterna.ui.components.Icon]. */
    val icon: Dp = 20.dp

    /** The box of an icon button, where Material draws 40dp. */
    val iconButton: Dp = 36.dp

    /** The touch floor: nothing tappable is reserved smaller than this. Material reserves 48dp. */
    val tap: Dp = 36.dp

    // -- Text tokens for the two places that are not a Material style -------------------------------
    val t13: TextUnit = (13 * TEXT_SCALE).sp
    val t20: TextUnit = (20 * TEXT_SCALE).sp

    // -- Gmail Dark's conversation row ---------------------------------------------------------------
    // The one style that copies another app: these are Gmail's own measurements on Android, NOT steps
    // of the dense ladder above, because "looks exactly like Gmail" is the whole point of that style.
    // Read only by GmailEmailRow; Cloud Dark and Cloud E-ink never see them.
    /** The rounded card's corner. */
    val gmailCardCorner: Dp = 16.dp

    /** Page left visible each side of a card. */
    val gmailCardInsetH: Dp = 8.dp

    /** Half the thin gap between two cards (each card keeps this much above and below). */
    val gmailCardGap: Dp = 1.dp

    /** Card padding: start/end and top/bottom. */
    val gmailRowPadH: Dp = 16.dp
    val gmailRowPadV: Dp = 12.dp

    /** The avatar circle, and the gap between it and the text column. */
    val gmailAvatar: Dp = 40.dp
    val gmailAvatarGap: Dp = 16.dp

    /** The filled unread dot right of the time, and its gap from it. */
    val gmailUnreadDot: Dp = 8.dp
    val gmailDotGap: Dp = 6.dp

    /** The outline star's glyph, and the box it is tapped in. */
    val gmailStar: Dp = 24.dp
    val gmailStarBox: Dp = 40.dp

    /** Attachment-style chips on the fourth line: height, corner, side padding, icon, gaps, outline. */
    val gmailChipHeight: Dp = 32.dp
    val gmailChipCorner: Dp = 16.dp
    val gmailChipPadH: Dp = 12.dp
    val gmailChipIcon: Dp = 18.dp
    val gmailChipGap: Dp = 8.dp
    val gmailChipLineTop: Dp = 8.dp
    val gmailChipBorder: Dp = 1.dp

    /** The label chip at the end of the snippet line. */
    val gmailLabelCorner: Dp = 4.dp
    val gmailLabelPadH: Dp = 6.dp
    val gmailLabelGap: Dp = 8.dp

    /** Small inline gaps: the count after the sender, the importance marker before it. */
    val gmailInlineGap: Dp = 4.dp

    /** Gmail's type sizes: sender; subject and snippet; time, count, labels and chips. */
    val gmailSender: TextUnit = 16.sp
    val gmailSenderLine: TextUnit = 22.sp
    val gmailBody: TextUnit = 14.sp
    val gmailBodyLine: TextUnit = 20.sp
    val gmailSmall: TextUnit = 12.sp
    val gmailSmallLine: TextUnit = 16.sp
}

/** One [TextStyle] with its size, line height and tracking multiplied by [scale]; floor [MailMetrics.MIN_TEXT_SP]. */
private fun TextStyle.dense(scale: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) maxOf(fontSize.value * scale, MailMetrics.MIN_TEXT_SP).sp else fontSize,
    lineHeight = if (lineHeight.isSpecified) (lineHeight.value * scale).sp else lineHeight,
    letterSpacing = if (letterSpacing.isSpecified) (letterSpacing.value * scale).sp else letterSpacing,
)

/**
 * The whole Material 3 type scale, every one of the 15 styles resized once. The screens read
 * `MaterialTheme.typography.*`, so nothing else needs to change for the type to follow.
 */
internal fun denseTypography(base: Typography = Typography(), scale: Float = MailMetrics.TEXT_SCALE) = Typography(
    displayLarge = base.displayLarge.dense(scale),
    displayMedium = base.displayMedium.dense(scale),
    displaySmall = base.displaySmall.dense(scale),
    headlineLarge = base.headlineLarge.dense(scale),
    headlineMedium = base.headlineMedium.dense(scale),
    headlineSmall = base.headlineSmall.dense(scale),
    titleLarge = base.titleLarge.dense(scale),
    titleMedium = base.titleMedium.dense(scale),
    titleSmall = base.titleSmall.dense(scale),
    bodyLarge = base.bodyLarge.dense(scale),
    bodyMedium = base.bodyMedium.dense(scale),
    bodySmall = base.bodySmall.dense(scale),
    labelLarge = base.labelLarge.dense(scale),
    labelMedium = base.labelMedium.dense(scale),
    labelSmall = base.labelSmall.dense(scale),
)

/**
 * Material reserves 48dp around anything tappable (`minimumInteractiveComponentSize`); in a data-dense
 * list that is a blank band on every row. This lowers the reservation to [MailMetrics.tap] for
 * everything below it in the tree - the touch floor stays, the dead space goes.
 */
@Composable
internal fun ProvideDenseTouchTargets(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MailMetrics.tap, content = content)
}
