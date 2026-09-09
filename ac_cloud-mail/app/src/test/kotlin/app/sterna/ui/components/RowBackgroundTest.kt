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
 * The four-state background of a message row (#141, #103): selected, current (open in the reading
 */
class RowBackgroundTest {

    private val surface = Color(0xFF110000)
    private val secondaryContainer = Color(0xFF002200)
    private val containerHighest = Color(0xFF000033)
    private val containerHigh = Color(0xFF444400)
    private val primary = Color(0xFF004444)
    private val primaryContainer = Color(0xFF550055)

    private val scheme = lightColorScheme(
        surface = surface,
        secondaryContainer = secondaryContainer,
        surfaceContainerHighest = containerHighest,
        surfaceContainerHigh = containerHigh,
        primary = primary,
        primaryContainer = primaryContainer,
    )

    @Test fun `selection wins over unread`() {
        assertEquals(
            secondaryContainer,
            rowBackground(scheme, selected = true, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `a selected row looks the same read or unread`() {
        assertEquals(
            rowBackground(scheme, selected = true, current = false, unread = false, unreadTint = true, flash = 0f),
            rowBackground(scheme, selected = true, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `an unread row takes the highest surface container`() {
        assertEquals(
            containerHighest,
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    /** surfaceContainerHigh is the swipe reveal's resting colour: the row must not wear it too. */
    @Test fun `an unread row is not the swipe reveal's neutral`() {
        assertNotEquals(
            containerHigh,
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `a read row stays on plain surface`() {
        assertEquals(
            surface,
            rowBackground(scheme, selected = false, current = false, unread = false, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `read and unread rows differ`() {
        assertNotEquals(
            rowBackground(scheme, selected = false, current = false, unread = false, unreadTint = true, flash = 0f),
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `the flash tints the read base`() {
        assertEquals(
            lerp(surface, primary, 0.14f),
            rowBackground(scheme, selected = false, current = false, unread = false, unreadTint = true, flash = 1f),
        )
    }

    @Test fun `the flash tints the unread base`() {
        assertEquals(
            lerp(containerHighest, primary, 0.14f),
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = true, flash = 1f),
        )
    }

    @Test fun `the flash tints the selected base`() {
        assertEquals(
            lerp(secondaryContainer, primary, 0.14f),
            rowBackground(scheme, selected = true, current = false, unread = true, unreadTint = true, flash = 1f),
        )
    }

    /** Half-way through the flash the blend is half as strong, on the unread base as on any other. */
    @Test fun `the flash scales with its progress`() {
        assertEquals(
            lerp(containerHighest, primary, 0.07f),
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = true, flash = 0.5f),
        )
    }

    // -- the switch off: exactly the list as it was before the branch (#141 follow-up) -----------

    /**
     * The reader who turns the tint off gets the OLD list back, not a third look: an unread row
     */
    @Test fun `with the tint off an unread row is painted like a read one`() {
        assertEquals(
            surface,
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = false, flash = 0f),
        )
        assertEquals(
            rowBackground(scheme, selected = false, current = false, unread = false, unreadTint = false, flash = 0f),
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = false, flash = 0f),
        )
    }

    /**
     * The invariant the switch may never reach: selection is the only signal saying which rows
     * the destructive actions will hit, and it wins with the tint off exactly as it does with it on.
     */
    @Test fun `selection still wins with the tint off`() {
        assertEquals(
            secondaryContainer,
            rowBackground(scheme, selected = true, current = false, unread = true, unreadTint = false, flash = 0f),
        )
        assertEquals(
            secondaryContainer,
            rowBackground(scheme, selected = true, current = false, unread = false, unreadTint = false, flash = 0f),
        )
    }

    /** The flash is not what this setting switches off: it still tints whichever base was retained. */
    @Test fun `the flash still plays with the tint off`() {
        assertEquals(
            lerp(surface, primary, 0.14f),
            rowBackground(scheme, selected = false, current = false, unread = true, unreadTint = false, flash = 1f),
        )
        assertEquals(
            lerp(secondaryContainer, primary, 0.14f),
            rowBackground(scheme, selected = true, current = false, unread = true, unreadTint = false, flash = 1f),
        )
    }

    // -- the current row: the one the reading pane beside the list is showing (#103) --------------

    @Test fun `the current row takes the primary container`() {
        assertEquals(
            "the row the pane is showing must be painted primaryContainer, or nothing on a wide " +
                "window says which message is open on the right",
            primaryContainer,
            rowBackground(scheme, selected = false, current = true, unread = false, unreadTint = true, flash = 0f),
        )
    }

    /** Selection arms the destructive actions: it must stay the one colour that says so. */
    @Test fun `selection wins over current`() {
        assertEquals(
            "a selected row must keep the selection colour even while it is the one open in the " +
                "pane — selection is the only sign of what the destructive actions will hit",
            secondaryContainer,
            rowBackground(scheme, selected = true, current = true, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `current wins over unread`() {
        assertEquals(
            "the open message is marked read on settle a moment later anyway; meanwhile the row " +
                "must already read as the current one, not as one more unread row",
            primaryContainer,
            rowBackground(scheme, selected = false, current = true, unread = true, unreadTint = true, flash = 0f),
        )
    }

    @Test fun `the flash tints the current base`() {
        assertEquals(
            lerp(primaryContainer, primary, 0.14f),
            rowBackground(scheme, selected = false, current = true, unread = false, unreadTint = true, flash = 1f),
        )
    }

    @Test fun `every state is opaque in the sentinel scheme`() {
        assertAllOpaque("sentinel light", scheme)
        assertAllOpaque(
            "sentinel dark",
            darkColorScheme(
                surface = surface,
                secondaryContainer = secondaryContainer,
                surfaceContainerHighest = containerHighest,
                surfaceContainerHigh = containerHigh,
                primary = primary,
                primaryContainer = primaryContainer,
            ),
        )
    }

    @Test fun `every state is opaque in the app's own schemes`() {
        assertAllOpaque("Arctic", ArcticColorScheme)
        assertAllOpaque("Pelagic", PelagicColorScheme)
    }

    /** The point of the change: in the dark scheme an unread row is not the same colour as a read one. */
    @Test fun `the app's dark scheme separates read from unread`() {
        assertNotEquals(
            rowBackground(PelagicColorScheme, selected = false, current = false, unread = false, unreadTint = true, flash = 0f),
            rowBackground(PelagicColorScheme, selected = false, current = false, unread = true, unreadTint = true, flash = 0f),
        )
    }

    private fun assertAllOpaque(name: String, scheme: androidx.compose.material3.ColorScheme) {
        for (selected in listOf(false, true)) {
            for (current in listOf(false, true)) {
                for (unread in listOf(false, true)) {
                    for (unreadTint in listOf(false, true)) {
                        for (flash in listOf(0f, 0.5f, 1f)) {
                            val color = rowBackground(scheme, selected, current, unread, unreadTint, flash)
                            assertEquals(
                                "$name selected=$selected current=$current unread=$unread " +
                                    "unreadTint=$unreadTint flash=$flash gave $color",
                                1f,
                                color.alpha,
                                0f,
                            )
                        }
                    }
                }
            }
        }
    }
}
