package com.diegonmarcos.superapp.translate

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours the translate bar draws with, derived from the KEYBOARD's own theme.
 *
 * WHY THIS EXISTS. The bar used to draw itself with fixed constants — a 95%-opaque
 * near-black (#1B1B20) slab, #34343F chips, grey hint text — whatever the keyboard
 * looked like. Under a light keyboard theme that was a black box glued on top of a
 * white keyboard, with dark-grey chips and grey-on-black hint text the owner could
 * barely read. The keyboard already knows its colours (Settings.mColors); the host
 * hands them in through [fromKeyboard] and the bar keeps no colour of its own.
 *
 * Pure Kotlin on purpose — no android.* import — so the contrast rule is executed by
 * a JVM test (libs:keyboard TranslatePaletteTest) rather than argued about. Colours
 * are ARGB ints, exactly what android.graphics.Color uses.
 */
data class TranslatePalette(
    /** The bar's own background: the keyboard's, fully opaque. */
    val background: Int,
    /** The input box and the result box: a step off the background. */
    val surface: Int,
    /** Body text on [background] and [surface]. */
    val text: Int,
    /** Secondary text (status line, the swap arrow, the close glyph). */
    val muted: Int,
    /** The input box's placeholder. */
    val hint: Int,
    /** Language chips and secondary actions. */
    val chip: Int,
    val onChip: Int,
    /** The primary action (Insert or Replace, whichever Enter runs). */
    val accent: Int,
    val onAccent: Int,
    /** Selection highlight inside the input box (translucent accent). */
    val selection: Int,
    /** Caret colour inside the input box. */
    val caret: Int,
    /** Outline of the input and result boxes (translucent hint colour). */
    val outline: Int,
) {
    val isDark: Boolean get() = luminance(background) < 0.5

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** WCAG AA for body text. */
        const val MIN_TEXT_CONTRAST = 4.5
        /** WCAG AA for large / secondary text and UI components. */
        const val MIN_SECONDARY_CONTRAST = 3.0

        /** Used only when the host has no keyboard colours to give (never on a live keyboard). */
        val DARK = fromKeyboard(background = 0xFF202124.toInt(), keyText = 0xFFE8EAED.toInt(),
            keyBackground = 0xFF3C4043.toInt(), accent = 0xFF8AB4F8.toInt())
        val LIGHT = fromKeyboard(background = 0xFFECEFF1.toInt(), keyText = 0xFF202124.toInt(),
            keyBackground = WHITE, accent = 0xFF1A73E8.toInt())

        /**
         * Build the palette from the keyboard theme's colours (MAIN_BACKGROUND, KEY_TEXT,
         * KEY_BACKGROUND and the accent). Every foreground is checked against what it
         * sits on and replaced by black or white when the theme's own pairing would be
         * unreadable, so no theme — custom ones included — can produce dark-on-dark.
         */
        fun fromKeyboard(background: Int, keyText: Int, keyBackground: Int, accent: Int): TranslatePalette {
            // A theme with an image or a transparent background reports a translucent or
            // fully transparent MAIN_BACKGROUND. Drawn as-is that is the transparent black
            // the bar must never be, so fall back to the key colour, then to the side of
            // the text that reads.
            val bg = when {
                alpha(background) >= 0x80 -> opaque(background)
                alpha(keyBackground) >= 0x80 -> opaque(keyBackground)
                luminance(opaque(keyText)) > 0.5 -> 0xFF202124.toInt()
                else -> 0xFFECEFF1.toInt()
            }
            val text = readableOn(bg, opaque(keyText), MIN_TEXT_CONTRAST)
            // The boxes step AWAY from the text (lighter under dark text, darker under light
            // text), never towards it: that way the text reads on them at least as well as
            // it reads on the bar, whatever mid-tone a theme picks.
            val surface = blend(bg, if (luminance(text) > luminance(bg)) BLACK else WHITE, 0.10)
            val chipBase = if (alpha(keyBackground) >= 0x80 && contrast(opaque(keyBackground), bg) >= 1.15)
                opaque(keyBackground) else blend(bg, text, 0.14)
            val acc = readableOn(bg, opaque(accent), MIN_SECONDARY_CONTRAST)
            val hint = readableOn(surface, blend(text, bg, 0.40), MIN_SECONDARY_CONTRAST)
            return TranslatePalette(
                background = bg,
                surface = surface,
                text = readableOn(surface, text, MIN_TEXT_CONTRAST),
                muted = readableOn(surface, blend(text, bg, 0.25), MIN_TEXT_CONTRAST),
                hint = hint,
                chip = chipBase,
                onChip = readableOn(chipBase, text, MIN_TEXT_CONTRAST),
                accent = acc,
                onAccent = readableOn(acc, WHITE, MIN_TEXT_CONTRAST),
                selection = (acc and 0x00FFFFFF) or 0x66000000,
                caret = acc,
                outline = (hint and 0x00FFFFFF) or 0x80000000.toInt(),
            )
        }

        fun alpha(c: Int): Int = (c ushr 24) and 0xFF
        fun opaque(c: Int): Int = c or 0xFF000000.toInt()

        /** WCAG relative luminance of an (opaque) colour, 0 = black, 1 = white. */
        fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }

        /** WCAG contrast ratio, 1..21. */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** [preferred] when it reads on [bg] at [min], else whichever of black/white reads better. */
        fun readableOn(bg: Int, preferred: Int, min: Double): Int {
            if (contrast(preferred, bg) >= min) return preferred
            return if (contrast(WHITE, bg) >= contrast(BLACK, bg)) WHITE else BLACK
        }

        /** Linear mix of two opaque colours: t = 0 is [a], t = 1 is [b]. */
        fun blend(a: Int, b: Int, t: Double): Int {
            fun mix(sh: Int): Int {
                val x = (a shr sh) and 0xFF; val y = (b shr sh) and 0xFF
                return (x + (y - x) * t).toInt().coerceIn(0, 255) shl sh
            }
            return 0xFF000000.toInt() or mix(16) or mix(8) or mix(0)
        }
    }
}
