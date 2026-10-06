package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density

/*
 * Android's side of the platform seam declared in commonMain/NavPlatform.kt (#876): the
 * resources, the dynamic palette, the animator scale and battery saver, the Vibrator, and the
 * font padding a TextView keeps. Everything the island and the strip draw reaches Android only
 * through here, so the pixels are what they were before the drawing code moved to commonMain.
 */

/** The tokens as Android's resources declare them, with the island's pill and ink taken from
 *  [FleetChrome.scheme] (the dynamic dark palette on Android 12+, the static one below it). */
@Composable
internal fun rememberNavTokens(): NavTokens {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(ctx, density, configuration) { buildNavTokens(ctx, density) }
}

internal fun buildNavTokens(ctx: Context, density: Density): NavTokens {
    val res = ctx.resources
    fun dp(id: Int) = with(density) { res.getDimension(id).toDp() }
    fun sp(id: Int) = with(density) { res.getDimension(id).toSp() }
    fun colour(id: Int) = Color(ctx.getColor(id))
    val scheme = FleetChrome.scheme(ctx)
    return NavTokens(
        pillInset = dp(R.dimen.bottom_nav_pill_inset),
        endInset = dp(R.dimen.bottom_nav_end_inset),
        itemVerticalPad = dp(R.dimen.bottom_nav_item_vertical_pad),
        iconLabelGap = dp(R.dimen.bottom_nav_icon_label_gap),
        iconSize = dp(R.dimen.bottom_nav_icon_size),
        labelTextSize = sp(R.dimen.bottom_nav_label_text_size),
        islandBottomMargin = dp(R.dimen.bottom_nav_island_bottom_margin),
        widthFraction = res.getFraction(R.fraction.bottom_nav_width_fraction, 1, 1),
        collapseMs = res.getInteger(R.integer.bottom_nav_collapse_ms),
        islandFill = colour(R.color.bottom_nav_island_fill),
        pillFill = scheme.inverseSurface,
        pillInk = scheme.inverseOnSurface,
        idleInk = scheme.onSurfaceVariant,
        tabsStripPadding = dp(R.dimen.page_tabs_strip_padding),
        tabsTopInset = dp(R.dimen.page_tabs_top_inset),
        tabsBottomInset = dp(R.dimen.page_tabs_bottom_inset),
        tabsPillPadH = dp(R.dimen.page_tabs_pill_pad_h),
        tabsPillPadV = dp(R.dimen.page_tabs_pill_pad_v),
        tabsPillMargin = dp(R.dimen.page_tabs_pill_margin),
        tabsPillRadius = dp(R.dimen.page_tabs_pill_radius),
        tabsPillStroke = dp(R.dimen.page_tabs_pill_stroke),
        tabsTextSize = sp(R.dimen.page_tabs_text_size),
        tabsTextMinSize = sp(R.dimen.page_tabs_text_min_size),
        tabsDividerPad = dp(R.dimen.page_tabs_divider_pad),
        tabsLetterSpacingMilli = res.getInteger(R.integer.page_tabs_letter_spacing_milli),
        tabsMinChars = res.getInteger(R.integer.page_tabs_min_chars),
        tabsSelectedFill = colour(R.color.page_tabs_selected_fill),
        tabsSelectedStroke = colour(R.color.page_tabs_selected_stroke),
        tabsSelectedText = colour(R.color.page_tabs_selected_text),
        tabsIdleFill = colour(R.color.page_tabs_idle_fill),
        tabsIdleStroke = colour(R.color.page_tabs_idle_stroke),
        tabsIdleText = colour(R.color.page_tabs_idle_text),
    )
}

@Composable
internal fun platformNavTokens(): NavTokens = rememberNavTokens()

/** #532: re-reads Power Saving and the animator scale at every collapse transition ([key]), so a
 *  battery saver switched on while the shell is open holds the very next collapse still. */
@Composable
internal fun platformBarMotion(key: Any?): Boolean {
    val context = LocalContext.current
    return remember(key) {
        barMotionEnabled(
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
            runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
            }.getOrDefault(false),
        )
    }
}

/** SuperApp's section-change rhythm ([FleetHaptics.geminiPattern]) on the view the island sits in. */
@Composable
internal fun platformNavHaptics(): NavHaptics {
    val view = LocalView.current
    return remember(view) {
        object : NavHaptics {
            override fun sectionChange() = FleetHaptics.geminiPattern(view)
        }
    }
}

/** A TextView keeps the font's ascent/descent padding; the strip matches it. */
@Suppress("DEPRECATION")
internal fun TextStyle.withPlatformFontPadding(): TextStyle =
    copy(platformStyle = PlatformTextStyle(includeFontPadding = true))
