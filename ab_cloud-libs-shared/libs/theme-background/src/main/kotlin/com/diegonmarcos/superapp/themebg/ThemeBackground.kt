package com.diegonmarcos.superapp.themebg

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The colour roles of the shared navigation + theme design (#1154), one value per light/dark mode.
 *
 * The page is Cloud Search's darker-gray palette (its slate `bg_start` -> `bg_end`) as a SUBTLE vertical gradient,
 * replacing the flat black the apps drew. [island] is the title island's darker-gray surface, [tabSelected] the
 * rounded-rectangle fill of the selected second-row tab (the SuperApp theme's violet, see [ThemeBackground.TAB_SELECTED]).
 * Every ink/surface pair here is held at WCAG AA or better by ThemeBackgroundTest.
 */
@Immutable
public data class ThemeColors(
    val dark: Boolean,
    val top: Color,
    val bottom: Color,
    val ink: Color,
    val inkDim: Color,
    val island: Color,
    val onIsland: Color,
    val islandBorder: Color,
    val accent: Color,
    val tabSelected: Color,
    val onTabSelected: Color,
    val tabStrip: Color,
)

public object ThemeBackground {
    /**
     * The selected second-row tab's fill: SuperApp's `theme_cloud_surface_selected` / `drawer_avatar` violet
     * (#7C3AED), made OPAQUE. The theme's own value is 0x44-alpha, which over the dark-gray page reads as gray,
     * the owner's complaint; the hue is the token's, only the alpha differs. White on it is 5.7:1.
     */
    public val TAB_SELECTED: Color = Color(0xFF7C3AED)

    /** Cloud Search's `dark_bg_start` / `dark_bg_end` and `light_bg_start` / `light_bg_end`. */
    public fun colors(dark: Boolean): ThemeColors = if (dark) DARK else LIGHT

    /** The page gradient: top -> bottom, subtle (same hue family, a few percent of lightness apart). */
    public fun brush(dark: Boolean): Brush = colors(dark).let { Brush.verticalGradient(listOf(it.top, it.bottom)) }

    /** WCAG 2.x contrast ratio of [a] over [b] (alpha ignored: pass opaque colours). */
    public fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private val DARK = ThemeColors(
        dark = true,
        top = Color(0xFF0F172A), bottom = Color(0xFF1E293B),
        ink = Color(0xFFF8FAFC), inkDim = Color(0xFF94A3B8),
        island = Color(0xFF0A0F1A), onIsland = Color(0xFFFFFFFF), islandBorder = Color(0x1AFFFFFF),
        accent = Color(0xFF0A84FF),
        tabSelected = TAB_SELECTED, onTabSelected = Color(0xFFFFFFFF), tabStrip = Color(0x14FFFFFF),
    )

    private val LIGHT = ThemeColors(
        dark = false,
        top = Color(0xFFE0EAFC), bottom = Color(0xFFCFDEF3),
        ink = Color(0xFF0F172A), inkDim = Color(0xFF475569),
        island = Color(0xFF1F2937), onIsland = Color(0xFFFFFFFF), islandBorder = Color(0x33FFFFFF),
        accent = Color(0xFF007AFF),
        tabSelected = Color(0xFF6D28D9), onTabSelected = Color(0xFFFFFFFF), tabStrip = Color(0x0F000000),
    )
}

/** Paints the shared page gradient behind this element. */
public fun Modifier.themeBackground(dark: Boolean): Modifier = background(ThemeBackground.brush(dark))

/** The shared page background around [content]; [dark] defaults to the system's mode. */
@Composable
public fun ThemedBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().themeBackground(dark)) { content() }
}
