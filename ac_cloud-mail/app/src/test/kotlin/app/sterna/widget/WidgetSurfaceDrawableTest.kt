package app.sterna.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import app.sterna.ui.theme.applyPureBlack
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class WidgetSurfaceDrawableTest {

    @Test fun `the three cell backgrounds carry the app's own surfaces, and the launcher's corner`() {
        val light = applyPureBlack(ArcticColorScheme, darkTheme = false, pureBlack = false).surface
        val dark = applyPureBlack(PelagicColorScheme, darkTheme = true, pureBlack = false).surface
        val black = applyPureBlack(PelagicColorScheme, darkTheme = true, pureBlack = true).surface
        assertEquals(
            "the widget's three backgrounds must be the surface the APP is wearing in each state: " +
                "the Arctic surface when the app is light, the Pelagic surface when it is dark, " +
                "and true black when the reader has the OLED setting on. Get one wrong and the " +
                "cell is a rectangle of the wrong colour next to an app of the right one — and " +
                "with the corner written in place instead of through @dimen/widget_corner_radius, " +
                "that state stops matching the corner the launcher gives its neighbours. JUnit " +
                "prints the schemes' own values as `expected` and what the drawables declare as " +
                "`was`; `$ABSENT` means the file itself is not there.",
            mapOf(
                "widget_surface_light.xml" to shape(light),
                "widget_surface_dark.xml" to shape(dark),
                "widget_surface_black.xml" to shape(black),
            ),
            FILES.associateWith { shapeLinesOf(it) },
        )
    }

    /**
     * EXECUTED, unlike everything above it: [WidgetThemeReader.background] is a plain `when` over an
     */
    @Test fun `each of the three cell states asks for a drawable of its own`() {
        val chosen = WidgetSurface.entries.associateWith { WidgetThemeReader.background(it) }
        assertEquals(
            "the three cell states must resolve to three DIFFERENT drawables: $chosen. Two states " +
                "sharing one file is a home-screen cell wearing the wrong surface — the OLED " +
                "setting on and the cell still navy, or the app dark and the cell still white.",
            WidgetSurface.entries.size,
            chosen.values.toSet().size,
        )
        assertEquals(
            "no cell state may resolve to 0, which is what a dropped or renamed drawable leaves " +
                "behind: setBackgroundResource(0) clears the background outright and the cell " +
                "becomes a hole in the wallpaper with two labels floating on it.",
            emptyList<WidgetSurface>(),
            chosen.filterValues { it == 0 }.keys.toList(),
        )
    }

    /** What the drawable of a cell wearing [surface] must declare, in document order. */
    private fun shape(surface: Color): List<String> =
        listOf(CORNERS, "<solid android:color=\"${hex(surface)}\" />")

    /** The `<corners …>` and `<solid …>` lines of the drawable named [name], whole, in order. */
    private fun shapeLinesOf(name: String): List<String> {
        val file = File(DeclarationSource.ROOT, "app/src/main/res/drawable/$name")
        if (!file.isFile) return listOf(ABSENT)
        return DeclarationSource.codeLines(file)
            .filter { it.startsWith("<corners") || it.startsWith("<solid") }
    }

    /** `#RRGGBB` when the surface is opaque, `#AARRGGBB` when it is not — a see-through cell must redden. */
    private fun hex(c: Color): String {
        val argb = c.toArgb()
        return if ((argb ushr 24) and 0xFF == 0xFF) String.format("#%06X", argb and 0xFFFFFF)
        else String.format("#%08X", argb)
    }

    private companion object {
        const val ABSENT = "(no such file)"

        const val CORNERS = "<corners android:radius=\"@dimen/widget_corner_radius\" />"

        val FILES = listOf(
            "widget_surface_light.xml",
            "widget_surface_dark.xml",
            "widget_surface_black.xml",
        )
    }
}
