// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.utils

import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/**
 * The keyboard theme's palette roles as a kit palette: every Compose surface of the IME (clipboard
 * panel, strip, emoji panels) draws under CloudKitTheme(settings.mColors.toKitPalette()), so a
 * theme switch recolours them from the same Colors the key surface uses.
 */
fun Colors.toKitPalette(): KitPalette = KitPalette.fromArgb(
    surface = get(ColorType.STRIP_BACKGROUND),
    surfaceSelected = get(ColorType.KEY_BACKGROUND),
    textPrimary = get(ColorType.KEY_TEXT),
    textSecondary = get(ColorType.KEY_HINT_TEXT),
    accent = get(ColorType.ACTION_KEY_BACKGROUND),
    hairline = (get(ColorType.KEY_HINT_TEXT) and 0x00FFFFFF) or 0x44000000,
    tileInk = get(ColorType.ACTION_KEY_ICON),
)
