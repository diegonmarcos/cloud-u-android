package app.sterna.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fill behind a row's small rounded chips — the account chip, the [ThreadPill], the
 * draft/not-uploaded chips and the attachment chips (task #464). Since an unread row carries no
 * background of its own any more, there is exactly one answer: [ColorScheme.surfaceVariant], on
 * every row, read or unread, selected or plain. The one-answer shape used to branch on read state
 * (#141) and the chip had to lift itself off the unread tint; with the tint gone the branch is gone.
 */
class ChipFillTest {

    private val surface = Color(0xFF110000)
    private val surfaceVariant = Color(0xFF002200)
    private val secondaryContainer = Color(0xFF004444)

    private val scheme = lightColorScheme(
        surface = surface,
        surfaceVariant = surfaceVariant,
        secondaryContainer = secondaryContainer,
    )

    @Test fun `every chip is the surface variant`() {
        assertEquals(surfaceVariant, chipFill(scheme))
    }

    /** Task #464: read and unread rows are one background, so their chips are one fill. */
    @Test fun `the chip no longer depends on read state`() {
        assertEquals(
            "chipFill takes the scheme only — a read and an unread row sit on the same surface, so " +
                "their chips must too",
            surfaceVariant,
            chipFill(scheme),
        )
    }

    /** A translucent chip would show the row through it, which is the defect by another road. */
    @Test fun `every state is opaque in the sentinel scheme`() {
        assertAllOpaque("sentinel light", scheme)
        assertAllOpaque(
            "sentinel dark",
            darkColorScheme(
                surface = surface,
                surfaceVariant = surfaceVariant,
                secondaryContainer = secondaryContainer,
            ),
        )
    }

    @Test fun `every state is opaque in the app's own schemes`() {
        assertAllOpaque("Arctic", ArcticColorScheme)
        assertAllOpaque("Pelagic", PelagicColorScheme)
    }

    /**
     * A chip stands clear of the row it sits on, in both schemes and on every row a chip can meet:
     * the plain surface row, the selected row, and the current row. Since the chip is a single
     * fixed fill, this is the one contract it has left.
     */
    @Test fun `a chip stands clear of the row it sits on, in both schemes`() {
        for ((name, scheme) in listOf("Arctic" to ArcticColorScheme, "Pelagic" to PelagicColorScheme)) {
            val rowColours = listOf(
                rowBackground(scheme, selected = false, current = false, flash = 0f),
                rowBackground(scheme, selected = true, current = false, flash = 0f),
                rowBackground(scheme, selected = false, current = true, flash = 0f),
            )
            val chip = chipFill(scheme)
            rowColours.forEach { row ->
                val distance = channelDistance(row, chip)
                assertTrue(
                    "$name: the chip ($chip) is $distance/255 from its row ($row). Below 16 the " +
                        "chip's fill is invisible and only its label floats.",
                    distance >= 16,
                )
            }
        }
    }

    private fun channelDistance(a: Color, b: Color): Int = maxOf(
        abs((a.red - b.red) * 255).roundToInt(),
        abs((a.green - b.green) * 255).roundToInt(),
        abs((a.blue - b.blue) * 255).roundToInt(),
    )

    private fun assertAllOpaque(name: String, scheme: ColorScheme) {
        val color = chipFill(scheme)
        assertEquals("$name gave $color", 1f, color.alpha, 0f)
    }
}
