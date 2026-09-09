package app.sterna.widget

import androidx.compose.ui.graphics.toArgb
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.components.MONOGRAM_FAMILIES
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import app.sterna.ui.theme.pulledToBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The widget follows the theme the APP is on, not the one the system is on.
 */
class WidgetThemeTest {

    /**
     * The measured defect, 17/08 at the bench: system in dark + app forced to light and the cell
     * stayed dark. That is the `LIGHT` row below, and it is the reason this rule exists at all.
     */
    @Test fun `an app forced to light is light whatever the system is on`() {
        assertEquals(
            "ThemeMode.LIGHT with the system in dark: the app is light, so the widget must be " +
                "light. This is the defect measured at the bench on 17/08 — system in dark + app " +
                "forced to light and the cell stayed dark.",
            false,
            widgetIsDark(ThemeMode.LIGHT, systemNight = true),
        )
        assertEquals(
            "ThemeMode.LIGHT with the system in light: light.",
            false,
            widgetIsDark(ThemeMode.LIGHT, systemNight = false),
        )
    }

    @Test fun `an app forced to dark is dark whatever the system is on`() {
        assertEquals(
            "ThemeMode.DARK with the system in light: the app is dark, so the widget must be " +
                "dark — the mirror image of the bench defect, a light cell under a dark app.",
            true,
            widgetIsDark(ThemeMode.DARK, systemNight = false),
        )
        assertEquals(
            "ThemeMode.DARK with the system in dark: dark.",
            true,
            widgetIsDark(ThemeMode.DARK, systemNight = true),
        )
    }

    /**
     * `SYSTEM` is the only arm that may read the system, and it is where the widget used to be
     * right by accident — for one setting out of three.
     */
    @Test fun `an app following the system takes the system's answer`() {
        assertEquals(
            "ThemeMode.SYSTEM with the system in dark: dark.",
            true,
            widgetIsDark(ThemeMode.SYSTEM, systemNight = true),
        )
        assertEquals(
            "ThemeMode.SYSTEM with the system in light: light.",
            false,
            widgetIsDark(ThemeMode.SYSTEM, systemNight = false),
        )
    }

    @Test fun `the light palette is the Arctic scheme's primary, onSurfaceVariant and onSurface`() {
        assertEquals(
            "in light theme the widget must wear the Arctic scheme: accent = primary (the big " +
                "figure and the unread dot), label = onSurfaceVariant, rowPrimary = onSurface. " +
                "JUnit prints the executed scheme as `expected`.",
            WidgetPalette(
                surface = WidgetSurface.Light,
                accent = ArcticColorScheme.primary.toArgb(),
                label = ArcticColorScheme.onSurfaceVariant.toArgb(),
                rowPrimary = ArcticColorScheme.onSurface.toArgb(),
            ),
            widgetPalette(ArcticColorScheme, dark = false, pureBlack = false),
        )
    }

    @Test fun `the dark palette is the Pelagic scheme's primary, onSurfaceVariant and onSurface`() {
        assertEquals(
            "in dark theme the widget must wear the Pelagic scheme, with the Dark background state.",
            WidgetPalette(
                surface = WidgetSurface.Dark,
                accent = PelagicColorScheme.primary.toArgb(),
                label = PelagicColorScheme.onSurfaceVariant.toArgb(),
                rowPrimary = PelagicColorScheme.onSurface.toArgb(),
            ),
            widgetPalette(PelagicColorScheme, dark = true, pureBlack = false),
        )
    }

    @Test fun `pure black in dark theme gives the Black surface and the compressed scheme's text`() {
        val noir = PelagicColorScheme.pulledToBlack()
        assertEquals(
            "with #117's pure black on, the cell must ask for the Black background state and its " +
                "text must come from the scheme applyPureBlack returns, not from the raw one.",
            WidgetPalette(
                surface = WidgetSurface.Black,
                accent = noir.primary.toArgb(),
                label = noir.onSurfaceVariant.toArgb(),
                rowPrimary = noir.onSurface.toArgb(),
            ),
            widgetPalette(PelagicColorScheme, dark = true, pureBlack = true),
        )
    }

    /** Pure black is a DARK-theme setting; `applyPureBlack` guards that, and the widget inherits the guard. */
    @Test fun `pure black is inert in light theme`() {
        assertEquals(
            "pure black must not touch a light theme: leaving it on and switching the app to " +
                "light would otherwise hand the launcher a black cell full of dark-on-dark text.",
            WidgetPalette(
                surface = WidgetSurface.Light,
                accent = ArcticColorScheme.primary.toArgb(),
                label = ArcticColorScheme.onSurfaceVariant.toArgb(),
                rowPrimary = ArcticColorScheme.onSurface.toArgb(),
            ),
            widgetPalette(ArcticColorScheme, dark = false, pureBlack = true),
        )
    }

    // ---- the tones a home-screen badge is painted in ----

    /**
     * THE BADGE TONES ARE NOT A PALETTE, AND THEY ARE NEVER NULL — the difference that makes
     */
    @Test fun `the badge tones are the light scheme's three accent families`() {
        assertEquals(
            "in light theme a badge must be painted from the Arctic scheme's three container " +
                "families — the same ramps the message list inside the app draws its badges from, " +
                "so one correspondent is one colour on both screens.",
            listOf(
                ToneRamp(ArcticColorScheme.primaryContainer, ArcticColorScheme.onPrimaryContainer),
                ToneRamp(ArcticColorScheme.secondaryContainer, ArcticColorScheme.onSecondaryContainer),
                ToneRamp(ArcticColorScheme.tertiaryContainer, ArcticColorScheme.onTertiaryContainer),
            ),
            widgetMonogramRamps(ArcticColorScheme, dark = false, pureBlack = false),
        )
    }

    @Test fun `the badge tones are the dark scheme's three accent families`() {
        assertEquals(
            "in dark theme they must come from the Pelagic scheme, exactly as the text colours do.",
            listOf(
                ToneRamp(PelagicColorScheme.primaryContainer, PelagicColorScheme.onPrimaryContainer),
                ToneRamp(PelagicColorScheme.secondaryContainer, PelagicColorScheme.onSecondaryContainer),
                ToneRamp(PelagicColorScheme.tertiaryContainer, PelagicColorScheme.onTertiaryContainer),
            ),
            widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = false),
        )
    }

    /**
     * AS MANY RAMPS AS [MONOGRAM_FAMILIES], on every theme. That count is the modulus
     */
    @Test fun `every theme offers a badge exactly as many families as a slot is cut from`() {
        assertEquals(
            "widgetMonogramRamps must return MONOGRAM_FAMILIES ramps whatever the theme: the slot " +
                "carried on a widget row was cut modulo that number, and it will index this list " +
                "in the launcher's process, where an out-of-bounds is a crash nobody can debug.",
            listOf(MONOGRAM_FAMILIES, MONOGRAM_FAMILIES, MONOGRAM_FAMILIES),
            listOf(
                widgetMonogramRamps(ArcticColorScheme, dark = false, pureBlack = false).size,
                widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = false).size,
                widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = true).size,
            ),
        )
    }

    /**
     * The badges must FOLLOW the theme: light and dark cannot hand back the same six colours, or a
     * dark home screen would wear the light scheme's pastel badges.
     */
    @Test fun `a light badge and a dark badge are not the same colour`() {
        assertNotEquals(
            "the two schemes must give different ramps. Equal here means the badge is painted " +
                "from one fixed palette and stops following the theme — the defect (#117) this " +
                "pane fixed for the text, reappearing in a bitmap.",
            widgetMonogramRamps(ArcticColorScheme, dark = false, pureBlack = false),
            widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = false),
        )
    }

    /**
     * PINNED AS A FACT, NOT AS A DESIGN: pure black changes NOTHING about a badge today.
     */
    @Test fun `pure black leaves a badge's tones untouched, because it never touches a container role`() {
        assertEquals(
            "pure black must not silently repaint the badges — and today it does not change them " +
                "at all. If this ever goes red, read the assertion below first: it says whether " +
                "the OLED option started touching the roles a badge is drawn from.",
            widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = false),
            widgetMonogramRamps(PelagicColorScheme, dark = true, pureBlack = true),
        )
        val noir = PelagicColorScheme.pulledToBlack()
        assertEquals(
            "⚠ THE REASON the two above are equal: pulledToBlack pulls background, surface and " +
                "the surfaceContainer* roles to black and leaves the three accent containers " +
                "alone. Change that and the badge tones become pure-black-dependent, which is a " +
                "decision, not a refactor.",
            listOf(
                PelagicColorScheme.primaryContainer,
                PelagicColorScheme.secondaryContainer,
                PelagicColorScheme.tertiaryContainer,
            ),
            listOf(noir.primaryContainer, noir.secondaryContainer, noir.tertiaryContainer),
        )
    }

    /**
     * THE DECISION THIS PANE TURNS ON, executed here on every combination of the three settings.
     */
    @Test fun `only a system theme with no pure black and no Material You lets the resources answer`() {
        val measured = ThemeMode.entries.flatMap { mode ->
            listOf(false, true).flatMap { pureBlack ->
                listOf(false, true).map { dynamicColor ->
                    Triple(mode, pureBlack, dynamicColor) to
                        widgetFollowsResources(mode, pureBlack, dynamicColor)
                }
            }
        }.toMap()
        assertEquals(
            "widgetFollowsResources must answer true on ONE row and one only: ThemeMode.SYSTEM " +
                "with pure black off and Material You off. Answer false there and the palette is " +
                "baked into the RemoteViews as a literal int, with no -night twin behind it and " +
                "no trigger listening for a configuration change: the system goes dark in the " +
                "evening, the app follows, and both home-screen cells stay light until the next " +
                "message arrives — on a quiet mailbox, days. Answer true on any other row and a " +
                "reader who forced the app to light, or switched pure black on, or switched " +
                "Material You off, gets the system's colours back on the home screen, which is " +
                "the defect (#117) this whole pane was opened for. " +
                "JUnit prints the pinned answers as `expected`.",
            mapOf(
                Triple(ThemeMode.SYSTEM, false, false) to true,
                Triple(ThemeMode.SYSTEM, false, true) to false,
                Triple(ThemeMode.SYSTEM, true, false) to false,
                Triple(ThemeMode.SYSTEM, true, true) to false,
                Triple(ThemeMode.LIGHT, false, false) to false,
                Triple(ThemeMode.LIGHT, false, true) to false,
                Triple(ThemeMode.LIGHT, true, false) to false,
                Triple(ThemeMode.LIGHT, true, true) to false,
                Triple(ThemeMode.DARK, false, false) to false,
                Triple(ThemeMode.DARK, false, true) to false,
                Triple(ThemeMode.DARK, true, false) to false,
                Triple(ThemeMode.DARK, true, true) to false,
            ),
            measured,
        )
    }
}
