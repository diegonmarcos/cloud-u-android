package app.sterna.ui.components

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The three-state background of a message row (task #464, #103): selected, current (open in the
 * reading pane), and plain surface. Read and unread rows have the SAME background now — the read
 * state lives in the row's TEXT (bright and bold vs dimmed and regular), never in a row-wide tint,
 * so a filled background stays reserved for selection. Unread could once lift its row to a surface
 * container (#141); that toggle is gone, and this test keeps it gone.
 */
class RowBackgroundTest {

    private val surface = Color(0xFF110000)
    private val secondaryContainer = Color(0xFF002200)
    private val primary = Color(0xFF004444)
    private val primaryContainer = Color(0xFF550055)

    private val scheme = lightColorScheme(
        surface = surface,
        secondaryContainer = secondaryContainer,
        primary = primary,
        primaryContainer = primaryContainer,
    )

    @Test fun `selection wins`() {
        assertEquals(
            secondaryContainer,
            rowBackground(scheme, selected = true, current = false, flash = 0f),
        )
    }

    @Test fun `a selected row keeps the selection colour while it is the current one`() {
        assertEquals(
            "a selected row must keep the selection colour even while it is the one open in the " +
                "pane — selection is the only sign of what the destructive actions will hit",
            secondaryContainer,
            rowBackground(scheme, selected = true, current = true, flash = 0f),
        )
    }

    @Test fun `the current row takes the primary container`() {
        assertEquals(
            "the row the pane is showing must be painted primaryContainer, or nothing on a wide " +
                "window says which message is open on the right",
            primaryContainer,
            rowBackground(scheme, selected = false, current = true, flash = 0f),
        )
    }

    /** Task #464: an ordinary row is plain surface whether the message is read or unread. */
    @Test fun `every ordinary row is plain surface`() {
        assertEquals(
            "an unread row must be painted exactly like a read one — task #464 moved the unread " +
                "signal to the text, so no row carries a background of its own any more",
            surface,
            rowBackground(scheme, selected = false, current = false, flash = 0f),
        )
    }

    /** The flip side of #464's contract: if read and unread no longer differ, selection still must. */
    @Test fun `selection stays distinct from a plain row`() {
        assertNotEquals(
            "selection is the only signal naming the rows the destructive actions will hit; with " +
                "the unread tint gone it must remain visibly distinct from the plain surface row",
            surface,
            rowBackground(scheme, selected = true, current = false, flash = 0f),
        )
    }

    @Test fun `the flash tints the plain base`() {
        assertEquals(
            lerp(surface, primary, 0.14f),
            rowBackground(scheme, selected = false, current = false, flash = 1f),
        )
    }

    @Test fun `the flash tints the selected base`() {
        assertEquals(
            lerp(secondaryContainer, primary, 0.14f),
            rowBackground(scheme, selected = true, current = false, flash = 1f),
        )
    }

    @Test fun `the flash tints the current base`() {
        assertEquals(
            lerp(primaryContainer, primary, 0.14f),
            rowBackground(scheme, selected = false, current = true, flash = 1f),
        )
    }

    /** Half-way through the flash the blend is half as strong. */
    @Test fun `the flash scales with its progress`() {
        assertEquals(
            lerp(surface, primary, 0.07f),
            rowBackground(scheme, selected = false, current = false, flash = 0.5f),
        )
    }

    /** Task #464: read and unread rows share one background. Every call above compiles only
     *  because rowBackground has no unread argument any more — a resurrected tint would fail to
     *  compile, which is the strongest guard this repo has against #141 coming back. */
    @Test fun `the unread tint is gone from the signature`() {
        // No assertion of its own: the shape of the function is fixed by the calls above.
        assertNotEquals(secondaryContainer, rowBackground(scheme, selected = false, current = false, flash = 0f))
    }

    @Test fun `every state is opaque in the sentinel scheme`() {
        assertAllOpaque("sentinel light", scheme)
        assertAllOpaque(
            "sentinel dark",
            darkColorScheme(
                surface = surface,
                secondaryContainer = secondaryContainer,
                primary = primary,
                primaryContainer = primaryContainer,
            ),
        )
    }

    @Test fun `every state is opaque in the app's own schemes`() {
        assertAllOpaque("Arctic", ArcticColorScheme)
        assertAllOpaque("Pelagic", PelagicColorScheme)
    }

    private fun assertAllOpaque(name: String, scheme: androidx.compose.material3.ColorScheme) {
        for (selected in listOf(false, true)) {
            for (current in listOf(false, true)) {
                for (flash in listOf(0f, 0.5f, 1f)) {
                    val color = rowBackground(scheme, selected, current, flash)
                    assertEquals(
                        "$name selected=$selected current=$current flash=$flash gave $color",
                        1f,
                        color.alpha,
                        0f,
                    )
                }
            }
        }
    }
}
