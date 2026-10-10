package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every number and colour the fleet's island and page-tab strip are made of, as ONE value, so the
 * drawing code in this directory names no Android resource (#876: this directory also compiles to
 * Kotlin/Wasm).
 *
 * The DEFAULTS are SuperApp's literals, the values `res/values/dimens.xml` and `colors.xml`
 * declare for Android (FleetParityTest pins both sides to the same numbers). The web page uses
 * them as they are. Android resolves the same fields from its resources instead
 * (`rememberNavTokens()` in src/main), which is also where the island's pill and ink pick up the
 * dynamic palette on Android 12+. An app cannot reach this: there is no parameter on the island or
 * the strip that takes it, only [LocalNavTokens] for a host that must override it (a test).
 */
@Immutable
public data class NavTokens(
    // ── the island (bottom_nav_*) ──
    /** The selected capsule's inset from its cell's top and bottom edge. */
    val pillInset: Dp = 6.dp,
    /** The island's end inset: an alias of [pillInset], so the end arcs stay concentric. */
    val endInset: Dp = pillInset,
    val itemVerticalPad: Dp = 6.dp,
    val iconLabelGap: Dp = 8.dp,
    val iconSize: Dp = 24.dp,
    val labelTextSize: TextUnit = 12.sp,
    val islandBottomMargin: Dp = 12.dp,
    /** The island spans this share of the width it is given. */
    val widthFraction: Float = 0.8f,
    /** How long the scroll-collapse takes, milliseconds. */
    val collapseMs: Int = 220,
    /** The accessibility touch floor (Material / Android: 48dp). No island cell is laid out
     *  narrower than this; a bar that would crush its cells below it scrolls instead. */
    val minTouchTarget: Dp = 48.dp,
    /** Above [MAX_BOTTOM] items, the narrowest a cell may get before the island scrolls
     *  horizontally instead of crushing its cells (the option the owner was offered for 6-7 items). */
    val minCellWidth: Dp = 56.dp,
    /** The keyboard / D-pad / switch-access focus ring, drawn in the item's own ink, only while
     *  the item holds focus (touch never focuses an item, so a tapped island looks exactly as before). */
    val focusRingWidth: Dp = 2.dp,
    val islandFill: Color = Color(0xFF140B26),
    val pillFill: Color = Color(0xFFE6E0E9),
    val pillInk: Color = Color(0xFF322F35),
    val idleInk: Color = Color(0xFFCAC4D0),
    // ── the page-tab strip (page_tabs_*) ──
    val tabsStripPadding: Dp = 4.dp,
    val tabsTopInset: Dp = 10.dp,
    val tabsBottomInset: Dp = 10.dp,
    val tabsPillPadH: Dp = 14.dp,
    val tabsPillPadV: Dp = 8.dp,
    val tabsPillMargin: Dp = 3.dp,
    val tabsPillRadius: Dp = 18.dp,
    val tabsPillStroke: Dp = 1.dp,
    val tabsTextSize: TextUnit = 12.sp,
    val tabsTextMinSize: TextUnit = 8.sp,
    val tabsDividerPad: Dp = 4.dp,
    /** Letter spacing in thousandths of an em (0.08em). */
    val tabsLetterSpacingMilli: Int = 80,
    /** The fewest letters of a label ever shown. */
    val tabsMinChars: Int = 7,
    val tabsSelectedFill: Color = Color(0x447C3AED),
    val tabsSelectedStroke: Color = Color(0x66E9D8FD),
    val tabsSelectedText: Color = Color(0xFFFFFFFF),
    val tabsIdleFill: Color = Color(0x22FFFFFF),
    val tabsIdleStroke: Color = Color(0x33FFFFFF),
    val tabsIdleText: Color = Color(0xAAFFFFFF),
)

/** An override of the tokens for everything below it; null (the default) = the platform's own. */
public val LocalNavTokens = compositionLocalOf<NavTokens?> { null }

/** Whether motion is reduced (animations removed, battery saver): the collapse then snaps. null =
 *  ask the platform. */
public val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/** What the island does when a tap moves its pill. Android buzzes SuperApp's rhythm; the web page
 *  has nothing to buzz. */
public interface NavHaptics {
    public fun sectionChange()
}

/** An override of the haptics for everything below it; null (the default) = the platform's own. */
public val LocalFleetHaptics = compositionLocalOf<NavHaptics?> { null }

@Composable
internal fun navTokens(): NavTokens = LocalNavTokens.current ?: platformNavTokens()

/** Whether the collapse may animate this time ([key] re-reads the platform at every transition). */
@Composable
internal fun rememberBarMotion(key: Any?): Boolean =
    LocalReduceMotion.current?.let { !it } ?: platformBarMotion(key)

@Composable
internal fun navHaptics(): NavHaptics = LocalFleetHaptics.current ?: platformNavHaptics()
