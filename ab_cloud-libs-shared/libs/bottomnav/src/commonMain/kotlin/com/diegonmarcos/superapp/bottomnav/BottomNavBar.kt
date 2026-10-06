package com.diegonmarcos.superapp.bottomnav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/**
 * THE bottom nav of the fleet: one Compose declaration that every app renders (#565).
 *
 * This is a PORT of superapp's View-era CloudBottomNavView + bg_nav_island.xml +
 * bg_bottom_nav_item_checked.xml + color/bottom_nav_content.xml, not a wrapper around them.
 * No AndroidView, no View, no drawable: the old toolkit ends here. The geometry comes from this
 * module's res/values/dimens.xml, which ports the tokens that #462/#473/#477/#498 settled.
 *
 *  - #417 the island is a full pill (radius = height/2), so its ends are true semicircles, and
 *    every item is laid out inside it. Nothing overflows the capsule it sits in.
 *  - #462 the SELECTED item is a pill too. It wraps the icon AND the label (Telegram/Revolut
 *    style), light on the dark bar, and it is the only lit capsule. Selection is shown by that
 *    pill, not by a colour change.
 *  - #473 the end capsules are inset by exactly the capsule's vertical inset, so their arcs are
 *    concentric with the island's. The cells are equal weights, so spacing is even. The label
 *    sits bottom_nav_icon_label_gap below the icon.
 *  - #477 the bottom clearance is ONE dimen plus the live system-bar/display-cutout inset.
 *    The inset is READ (getBottom), never applied through windowInsetsPadding or
 *    consumeWindowInsets, so nothing else in the window loses it.
 *  - #532 [collapsed] drops the labels and leaves an icons-only bar. A Compose shell derives it
 *    from scrolling with [BottomNavCollapse]; a View shell with
 *    [BottomNavIslandView.collapseOnScrollIn], which #673 added after the View shells were found
 *    to be setting [collapsed] nowhere at all, leaving them permanently expanded. Both drivers
 *    decide with [collapseFor], so there is one rule and not one per host style.
 *    The island is fill only, with no stroke layer, so the visible edge IS
 *    the fill's edge. The collapse is ANIMATED by one progress value that shrinks each label's
 *    layout slot, so the bar shrinks with it, and the label carries no clip of its own: the
 *    only thing that hides it is the capsule's pill clip, so the hide line IS the oval edge and
 *    there is no second, rectangular bound for it to vanish at. Under Power Saving or with
 *    animations removed it snaps instead ([barMotionEnabled]).
 *  - #536 the island is bottom_nav_width_fraction (80%) of the width it is given, and it is
 *    centred, so 10% stays clear on each side.
 *
 * The items are INJECTED as [BottomNavEntry]. The bar knows nothing about any app's menu.
 */

/** One nav item as the bar draws it: a stable id, the label (also the collapsed bar's
 *  accessible name), and the glyph. */
public data class BottomNavEntry(val id: String, val label: String, val icon: Painter)

/** The one pill shape. The island and the selected capsule both use it, so the two stadiums
 *  can never drift apart. 50% = half the shorter side, a true semicircle at any size. */
public val bottomNavPillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

internal const val TAG_ISLAND = "bottomnav_island"
internal const val TAG_CONTENT = "bottomnav_content"
internal fun itemTag(id: String) = "bottomnav_item_$id"
internal fun iconTag(id: String) = "bottomnav_icon_$id"
internal fun labelTag(id: String) = "bottomnav_label_$id"

/** The insets the island clears: system bars AND the display cutout (#477). */
@Composable
public fun bottomNavInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * THE island. Its public contract is entries, the selection, the tap callback and (for a Compose
 * shell that scrolls) [collapsed]: nothing else. The colours, the geometry, the typeface, the
 * system-bar clearance, the press feedback and the haptics are all this module's, so an app cannot
 * draw a different island. See [FleetChrome] for the colours and the window.
 */
@Composable
public fun BottomNavIsland(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    /** Extra behaviour the HOST hangs on one item's capsule (superapp's long-press fan, #531).
     *  Applied inside the capsule's clip, before its click, so a gesture it consumes wins. The
     *  bar itself stays behaviour-free: an item still does nothing but select on a tap. */
    itemModifier: (BottomNavEntry) -> Modifier = { Modifier },
) {
    BottomNavIslandImpl(entries, selectedId, onSelect, modifier, collapsed, null, itemModifier)
}

/**
 * The one composable every host renders: [BottomNavIsland] (Compose shells), [BottomNavHost] and
 * [BottomNavIslandView] (View shells) all end here, so what they draw is the same tree.
 *
 * [insets] null = clear the live system bars and display cutout through windowInsetsPadding, which
 * leaves out whatever an ancestor already consumed (#477: read, so nothing else loses them). A
 * non-null value is the clearance a View host measured for itself, or a test's injected inset.
 */
@Composable
internal fun BottomNavIslandImpl(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier,
    collapsed: Boolean,
    insets: WindowInsets?,
    itemModifier: (BottomNavEntry) -> Modifier,
) {
    // An app's MaterialTheme must not reach the island: its text style (line height, family,
    // spacing) would leak into the labels through LocalTextStyle, and its colours through the
    // scheme. Both are replaced by the fleet's.
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        IslandContent(entries, selectedId, onSelect, modifier, collapsed, insets, itemModifier)
    }
}

@Composable
private fun IslandContent(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier,
    collapsed: Boolean,
    insets: WindowInsets?,
    itemModifier: (BottomNavEntry) -> Modifier,
) {
    val density = LocalDensity.current
    val tokens = navTokens()
    val widthFraction = tokens.widthFraction
    // Read, never consumed: getBottom is a plain snapshot read that recomposes when the inset
    // changes. It is the Compose form of a non-consuming insets listener.
    val margin = tokens.islandBottomMargin
    val liveClearance = if (insets == null) Modifier.windowInsetsPadding(bottomNavInsets().only(WindowInsetsSides.Bottom))
    else Modifier.padding(bottom = with(density) { insets.getBottom(this).toDp() })
    val haptics = navHaptics()
    val pillInset = tokens.pillInset
    val pad = tokens.itemVerticalPad
    val gap = tokens.iconLabelGap
    val iconSize = tokens.iconSize
    // Every attribute spelled out, so nothing is inherited from an app's typography.
    val labelStyle = TextStyle(
        fontSize = tokens.labelTextSize,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
    )
    // #532 the collapse animates. remember(collapsed) re-reads Power Saving at every transition, so
    // a battery saver switched on while the shell is open holds the very next collapse still.
    val motion = rememberBarMotion(collapsed)
    val labelShown by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = if (motion) tween(tokens.collapseMs) else snap(),
        label = "bottomnav_label_shown",
    )

    Box(modifier.fillMaxWidth().then(liveClearance).padding(bottom = margin), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .fillMaxWidth(widthFraction)
                .testTag(TAG_ISLAND)
                .clip(bottomNavPillShape)
                .background(tokens.islandFill)
                .padding(horizontal = tokens.endInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                val selected = entry.id == selectedId
                val ink = if (selected) tokens.pillInk else tokens.idleInk
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = pillInset)
                        .testTag(itemTag(entry.id))
                        .clip(bottomNavPillShape)
                        .background(if (selected) tokens.pillFill else Color.Transparent)
                        .then(itemModifier(entry))
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = FleetIndication,
                            role = Role.Tab,
                            onClick = {
                                // SuperApp's section-change haptic rhythm, on a tap that moves the pill.
                                if (!selected) haptics.sectionChange()
                                onSelect(entry)
                            },
                        )
                        .padding(vertical = pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Icon(
                        entry.icon,
                        // With the label hidden, the icon has to carry the item's name.
                        contentDescription = if (collapsed) entry.label else null,
                        tint = ink,
                        modifier = Modifier.size(iconSize).testTag(iconTag(entry.id)),
                    )
                    if (labelShown > 0f) {
                        // Laid out across the whole capsule and centred, not at its own
                        // intrinsic width. A one-line ellipsized paragraph exactly as wide as its
                        // text can round itself into an ellipsis: 'Mail' at 23px came out
                        // ellipsized in a 56px capsule in CI run 36024784089.
                        // The slot (gap + text) shrinks with labelShown and the text is placed at
                        // its top, so the capsule loses height and the label leaves through the
                        // capsule's own pill clip. No clipToBounds here: that would be a
                        // rectangle inside the oval.
                        Text(
                            entry.label,
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, (placeable.height * labelShown).roundToInt()) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .graphicsLayer { alpha = labelShown }
                                .padding(top = gap)
                                .fillMaxWidth()
                                .testTag(labelTag(entry.id)),
                            color = ink,
                            style = labelStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Whether the collapse may animate: off when the reader removed animations (animator duration
 * scale 0) or the device is in the system's battery saver (#501 precedent: animation respects
 * Power Saving). Either alone holds the bar still, and it then snaps between its two shapes.
 */
internal fun barMotionEnabled(animatorDurationScale: Float, powerSaveMode: Boolean): Boolean =
    animatorDurationScale != 0f && !powerSaveMode

/**
 * THE collapse rule of the fleet, one declaration (#673). A downward delta collapses the island to
 * icons, an upward one restores the labels, and no movement changes nothing. Both drivers call
 * this: [BottomNavCollapse] with its nested-scroll delta in a Compose shell, and
 * [BottomNavIslandView.collapseOnScrollIn] with its measured scroll delta in a View shell. A
 * second copy of this `if` is how the two host styles would end up collapsing on different rules.
 */
internal fun collapseFor(collapsed: Boolean, delta: Float): Boolean = when {
    delta > 0f -> true
    delta < 0f -> false
    else -> collapsed
}

/**
 * #532 scroll-collapse for a COMPOSE shell. Attach it with Modifier.nestedScroll(collapse) on the
 * scrolling content and pass [collapsed] to [BottomNavIsland]. Scrolling down the content
 * collapses the bar to icons only, and scrolling back up restores the labels. It only observes: it
 * consumes nothing, so the list still gets every pixel of the scroll.
 *
 * A View-based shell cannot use this — a NestedScrollConnection is reached only from Compose — and
 * uses [BottomNavIslandView.collapseOnScrollIn] instead. Both apply [collapseFor].
 */
public class BottomNavCollapse : NestedScrollConnection {
    public var collapsed: Boolean by mutableStateOf(false)
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Negated: a nested-scroll y goes negative as the content travels down, which is the same
        // gesture a View shell measures as a RISING scroll offset. One rule, [collapseFor], so the
        // Compose and View hosts cannot collapse on different definitions (#673).
        collapsed = collapseFor(collapsed, -available.y)
        return Offset.Zero
    }
}

@Composable
public fun rememberBottomNavCollapse(): BottomNavCollapse = remember { BottomNavCollapse() }

// ── the host: a screen ABOVE the island, scroll-collapsing it (#534) ──

/**
 * The fleet's Compose bottom-nav host: [entries] on the shared island, with the screen's
 * [content] laid out ABOVE it (#534). The content ends where the island's clearance begins, so
 * nothing the screen draws can hide under the bar. Scrolling the content collapses the island to
 * icons (#532). What a tap DOES is the app's: [onSelect] gets the tapped entry. cloud-mail's
 * item table and its launch hand-offs live in cloud-mail (it was the first consumer, #565).
 */
@Composable
public fun BottomNavHost(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The island below already clears the bottom system bar, so the content must not clear it
        // a second time: consumed for the content ONLY. The island still reads it (#477).
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                .nestedScroll(collapse)
                .testTag(TAG_CONTENT),
        ) { content() }
        BottomNavIsland(entries = entries, selectedId = selectedId, onSelect = onSelect, collapsed = collapse.collapsed)
    }
}
