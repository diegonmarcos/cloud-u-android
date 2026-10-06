package com.diegonmarcos.superapp.bottomnav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * THE page tab strip of the fleet (#868): a section's `pages` (and, one strip lower, a page's own
 * `pages`) as the SuperApp's liquid-glass PILL tabs. A port of superapp's View-era
 * `AppTabsStyle.apply / equalise / inset` + `SectionTabsFragment`'s strip, value for value:
 *
 *  - every pill: monospace bold CAPS, 12sp, 0.08em letter spacing, 14dp x 8dp padding, 3dp margin,
 *    an 18dp-radius rounded rectangle. SELECTED = 0x447C3AED fill, 1dp 0x66E9D8FD stroke, white
 *    text. IDLE = 0x22FFFFFF fill, 1dp 0x33FFFFFF stroke, 0xAAFFFFFF text. (res/values/colors.xml
 *    and dimens.xml `page_tabs_*` are the one declaration; PageTabsTest measures against them.)
 *  - `equalise`: ONE width for every pill, sized to the longest label (floor [min chars]); when N
 *    slots exceed the strip the FONT steps down toward 8sp, then characters are dropped with an
 *    ellipsis, and only if even the floor overflows does the strip scroll. See [planPills].
 *  - `inset`: 4dp strip padding, a 10dp gap above and below, plus - for a strip under the toolbar
 *    island ([underTopChrome]) - the live status-bar / display-cutout inset on top (#477). The
 *    inset is READ, never consumed.
 *  - a LAUNCH page (non-blank [NavPage.action]) wears a pill but is not a destination; the first
 *    one after a destination is preceded by the literal "|" divider the superapp draws.
 *
 * [onSelect] fires for a tap on an unselected pill - including a launch pill, whose [NavPage.action]
 * the host dispatches and which it should NOT make [selectedId] - and [onReselect] for the
 * selected one. [selectedId] only MOVES the pill; it never calls back.
 */
@Composable
public fun PageTabs(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    onReselect: (NavPage) -> Unit = {},
    underTopChrome: Boolean = true,
) {
    PageTabsImpl(pages, selectedId, onSelect, modifier, onReselect, underTopChrome, null)
}

/** The one composable [PageTabs] and [PageTabsView] both render. [insets] is for tests; null = the live window's. */
@Composable
internal fun PageTabsImpl(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier,
    onReselect: (NavPage) -> Unit,
    underTopChrome: Boolean,
    insets: WindowInsets?,
) {
    // An app's MaterialTheme text style (line height, family) must not reach the pills.
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        PageTabsContent(pages, selectedId, onSelect, modifier, onReselect, underTopChrome, insets)
    }
}

@Composable
private fun PageTabsContent(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier,
    onReselect: (NavPage) -> Unit,
    underTopChrome: Boolean,
    insets: WindowInsets?,
) {
    if (pages.isEmpty()) return
    val tokens = navTokens()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // The strip's geometry is laid out in pixels, as it always was; the tokens are dp / sp.
    val stripPad = with(density) { tokens.tabsStripPadding.toPx() }
    val topBase = with(density) { tokens.tabsTopInset.toPx() }
    val bottomGap = with(density) { tokens.tabsBottomInset.toPx() }
    val padH = with(density) { tokens.tabsPillPadH.toPx() }
    val margin = with(density) { tokens.tabsPillMargin.toPx() }
    val startPx = with(density) { tokens.tabsTextSize.toPx() }
    val minPx = with(density) { tokens.tabsTextMinSize.toPx() }
    val dividerPad = with(density) { tokens.tabsDividerPad.toPx() }
    val spacing = tokens.tabsLetterSpacingMilli / 1000f
    val minChars = tokens.tabsMinChars

    val live = insets ?: WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val liveTop = if (underTopChrome) live.getTop(density) else 0
    val divider = pages.indexOfFirst { it.action.isNotBlank() }.takeIf { it > 0 }

    fun styleAt(px: Float, color: Color = Color.Unspecified) = pillStyle(density, px, spacing, color)
    val labels = pages.map { it.label.uppercase() }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(top = with(density) { (topBase + liveTop).toDp() }, bottom = with(density) { bottomGap.toDp() }),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val dividerPx = if (divider == null) 0f else {
            val w = measurer.measure("|", styleAt(startPx)).size.width
            w + 2 * dividerPad
        }
        val avail = widthPx - 2 * stripPad - dividerPx
        val plan = remember(labels, avail, density.density, density.fontScale, spacing) {
            planPills(
                availPx = avail,
                count = pages.size,
                longest = labels.maxOf { it.length },
                padPx = 2 * padH,
                marginPx = 2 * margin,
                startPx = startPx,
                minPx = minPx,
                stepPx = density.density,
                minChars = minChars,
                charWidth = { px -> measurer.measure("M", pillStyle(density, px, spacing, Color.Unspecified)).size.width.toFloat() },
            )
        }
        val slot = avail / pages.size
        val scroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(PageTabsTags.STRIP)
                .then(if (plan.scrollable) Modifier.horizontalScroll(scroll) else Modifier)
                .padding(with(density) { stripPad.toDp() }),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, page ->
                if (i == divider) {
                    Text(
                        "|",
                        Modifier.padding(horizontal = with(density) { dividerPad.toDp() }),
                        style = styleAt(plan.textPx, tokens.tabsIdleText),
                        softWrap = false,
                    )
                }
                val on = page.id == selectedId
                val slotModifier = if (plan.scrollable) Modifier else Modifier.width(with(density) { slot.toDp() })
                Box(slotModifier, contentAlignment = Alignment.Center) {
                    Pill(page, labels[i], on, plan, margin, padH, tokens, { px, c -> styleAt(px, c) }, onSelect, onReselect)
                }
            }
        }
    }
}

@Composable
private fun Pill(
    page: NavPage,
    label: String,
    on: Boolean,
    plan: PillPlan,
    marginPx: Float,
    padHPx: Float,
    tokens: NavTokens,
    style: (Float, Color) -> TextStyle,
    onSelect: (NavPage) -> Unit,
    onReselect: (NavPage) -> Unit,
) {
    val density = LocalDensity.current
    val radius = tokens.tabsPillRadius
    val stroke = tokens.tabsPillStroke
    val padV = tokens.tabsPillPadV
    val shape = RoundedCornerShape(radius)
    val fill = if (on) tokens.tabsSelectedFill else tokens.tabsIdleFill
    val line = if (on) tokens.tabsSelectedStroke else tokens.tabsIdleStroke
    val ink = if (on) tokens.tabsSelectedText else tokens.tabsIdleText
    Box(
        Modifier
            .padding(with(density) { marginPx.toDp() })
            .then(if (plan.pillPx > 0f) Modifier.width(with(density) { plan.pillPx.toDp() }) else Modifier)
            .background(fill, shape)
            .border(stroke, line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = FleetIndication) {
                if (on) onReselect(page) else onSelect(page)
            }
            .semantics { role = Role.Tab; selected = on }
            .padding(horizontal = with(density) { padHPx.toDp() }, vertical = padV)
            .testTag(PageTabsTags.tab(page.id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier.testTag(PageTabsTags.label(page.id)),
            style = style(plan.textPx, ink),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The pill's type: what `AppTabsStyle.makePill` gives the label, at [px] (already scaled). */
private fun pillStyle(density: Density, px: Float, spacing: Float, color: Color): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = with(density) { px.toSp() },
    letterSpacing = spacing.em,
).withPlatformFontPadding() // a TextView keeps the font's ascent/descent padding; matching it keeps the pill height equal

/** How the strip lays its pills out. [pillPx] is the pill's width including its own padding. */
internal data class PillPlan(val textPx: Float, val pillPx: Float, val scrollable: Boolean)

/**
 * `AppTabsStyle.applyEqual`, as a pure function so it is testable without a screen. One width for
 * every pill, sized to the LONGEST label: the font steps down by [stepPx] toward [minPx] until
 * [longest] characters fit one slot's budget; at the floor characters are dropped instead (never
 * below [minChars], plus one for the ellipsis); only if even that overflows the strip does it
 * scroll. In a fixed strip a pill never exceeds its slot - past it the strip would clip.
 */
internal fun planPills(
    availPx: Float,
    count: Int,
    longest: Int,
    padPx: Float,
    marginPx: Float,
    startPx: Float,
    minPx: Float,
    stepPx: Float,
    minChars: Int,
    charWidth: (Float) -> Float,
): PillPlan {
    if (count <= 0 || availPx <= 0f) return PillPlan(startPx, 0f, true)
    val perTab = availPx / count
    val budget = perTab - padPx - marginPx
    if (budget <= 0f) return PillPlan(startPx, 0f, true)
    var size = startPx
    var chars = longest
    while (true) {
        if (chars * charWidth(size) <= budget) break
        if (size > minPx) { size = maxOf(minPx, size - stepPx); continue }
        chars = maxOf(minChars + 1, (budget / charWidth(size)).toInt())
        break
    }
    val pill = chars * charWidth(size) + padPx
    val overflows = pill + marginPx > perTab
    return PillPlan(size, if (overflows) pill else minOf(pill, perTab - marginPx), overflows)
}

/** The strip's test tags, for a HOST's tests that measure the strip it configured. */
public object PageTabsTags {
    public const val STRIP: String = "pagetabs_strip"
    public fun tab(id: String): String = "pagetabs_tab_$id"
    public fun label(id: String): String = "pagetabs_label_$id"
}
