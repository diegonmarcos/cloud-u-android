// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import com.diegonmarcos.superapp.translate.TranslatePalette
import com.diegonmarcos.superapp.translate.TranslatePalette.Companion.MIN_SECONDARY_CONTRAST
import com.diegonmarcos.superapp.translate.TranslatePalette.Companion.MIN_TEXT_CONTRAST
import com.diegonmarcos.superapp.translate.TranslatePalette.Companion.contrast
import com.diegonmarcos.superapp.translate.TranslatePalette.Companion.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translate bar draws with the KEYBOARD's colours and every word on it is readable.
 *
 * The bar used to be a fixed 95%-opaque near-black slab with dark-grey chips and grey
 * hint text whatever the keyboard theme was: a black box on a light keyboard. These
 * cases hold the replacement to the two things the owner could see go wrong — the bar
 * must look like the keyboard it sits on, and nothing on it may be dark-on-dark (or
 * light-on-light) — for light, dark, image/transparent and deliberately bad themes.
 * Pure JVM: TranslatePalette has no android.* import, so this runs in testDebugUnitTest.
 */
class TranslatePaletteTest {

    // Representative keyboard themes: (MAIN_BACKGROUND, KEY_TEXT, KEY_BACKGROUND, accent).
    private val light = TranslatePalette.fromKeyboard(0xFFE8EAED.toInt(), 0xFF202124.toInt(), 0xFFFFFFFF.toInt(), 0xFF1A73E8.toInt())
    private val dark = TranslatePalette.fromKeyboard(0xFF202124.toInt(), 0xFFE8EAED.toInt(), 0xFF3C4043.toInt(), 0xFF8AB4F8.toInt())

    private fun assertReadable(p: TranslatePalette, label: String) {
        for ((name, fg, bg, min) in listOf(
            Quad("text on background", p.text, p.background, MIN_TEXT_CONTRAST),
            Quad("text on surface", p.text, p.surface, MIN_TEXT_CONTRAST),
            Quad("muted on surface", p.muted, p.surface, MIN_TEXT_CONTRAST),
            Quad("hint on surface", p.hint, p.surface, MIN_SECONDARY_CONTRAST),
            Quad("chip label", p.onChip, p.chip, MIN_TEXT_CONTRAST),
            Quad("primary action label", p.onAccent, p.accent, MIN_TEXT_CONTRAST),
            Quad("accent on background", p.accent, p.background, MIN_SECONDARY_CONTRAST),
        )) {
            val c = contrast(fg, bg)
            assertTrue("$label: $name contrast %.2f < $min".format(c), c >= min)
        }
        assertEquals("$label: background must be opaque", 0xFF, TranslatePalette.alpha(p.background))
        assertEquals("$label: surface must be opaque", 0xFF, TranslatePalette.alpha(p.surface))
    }

    private data class Quad(val name: String, val fg: Int, val bg: Int, val min: Double)

    @Test
    fun lightKeyboardGetsALightBarNotABlackBox() {
        assertFalse("a light keyboard must not get a dark bar", light.isDark)
        assertEquals("the bar is the keyboard's own background", 0xFFE8EAED.toInt(), light.background)
        assertTrue("the bar must not be near-black on a light keyboard", luminance(light.background) > 0.5)
        assertReadable(light, "light")
    }

    @Test
    fun darkKeyboardGetsADarkBarThatStillReads() {
        assertTrue(dark.isDark)
        assertEquals(0xFF202124.toInt(), dark.background)
        assertReadable(dark, "dark")
    }

    @Test
    fun transparentOrImageBackgroundNeverBecomesTransparentBlack() {
        // Image / transparent themes report a see-through MAIN_BACKGROUND. Drawn as-is the
        // bar would be the IME window showing through: black.
        val p = TranslatePalette.fromKeyboard(0x00000000, 0xFF202124.toInt(), 0x00000000, 0xFF1A73E8.toInt())
        assertFalse("dark text means a light bar, not a black one", p.isDark)
        assertReadable(p, "transparent")
    }

    @Test
    fun darkOnDarkThemeColoursAreCorrected() {
        // A custom theme whose key text barely differs from its background.
        val p = TranslatePalette.fromKeyboard(0xFF1B1B20.toInt(), 0xFF34343F.toInt(), 0xFF1B1B20.toInt(), 0xFF22223A.toInt())
        assertReadable(p, "dark-on-dark")
    }

    @Test
    fun everyThemeInASweepIsReadable() {
        val steps = listOf(0x00, 0x33, 0x66, 0x99, 0xCC, 0xFF)
        fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        for (bg in steps) for (fg in steps) for (key in steps) {
            val p = TranslatePalette.fromKeyboard(grey(bg), grey(fg), grey(key), 0xFF3D5AFE.toInt())
            assertReadable(p, "bg=$bg fg=$fg key=$key")
        }
    }
}
