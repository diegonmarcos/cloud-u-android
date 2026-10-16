package com.diegonmarcos.superapp.recovery

import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * The recovery surfaces' own palette, as kit roles. Deliberately NOT the launcher palette: the
 * recovery screen and the advisory banner must look the same, and be legible, under every launcher
 * theme and when the theme data is the very thing that is broken. [KitPalette.ok] is the install
 * button, [KitPalette.warn] the "Android will ask" note, [KitPalette.bad] the STUCK accent.
 */
internal object RecoveryPalette {
    val kit = KitPalette(
        surface = Color(0xFF16161A),
        surfaceSelected = Color(0xFF3A3A45),
        textPrimary = Color.White,
        textSecondary = Color(0xFF9A9AA4),
        accent = Color(0xFF3A7BD5),
        hairline = Color(0xFF3A3A45),
        tileInk = Color.White,
        ok = Color(0xFF1F6F43),
        warn = Color(0xFFD08A2A),
        bad = Color(0xFFE0553F),
    )
    /** The page behind the recovery cards. */
    val page = Color(0xFF0A0A0C)
    /** The banner card's fill (95% of near-black). */
    val bannerFill = Color(0xF21A1A1F)
    val muted = Color(0xFFBFBFC8)
    val faint = Color(0xFF7A7A85)
}
