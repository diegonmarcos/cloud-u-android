package app.sterna.ui.components

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the active [androidx.compose.material3.ColorScheme] and the badge colours
 */
class MonogramRampsTest {

    private val primaryContainer = Color(0xFF110000)
    private val onPrimaryContainer = Color(0xFF220000)
    private val secondaryContainer = Color(0xFF003300)
    private val onSecondaryContainer = Color(0xFF004400)
    private val tertiaryContainer = Color(0xFF000055)
    private val onTertiaryContainer = Color(0xFF000066)

    private val scheme = lightColorScheme(
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
    )

    @Test fun `the badge ramps are the scheme's three accent families, in order`() {
        assertEquals(
            listOf(
                ToneRamp(primaryContainer, onPrimaryContainer),
                ToneRamp(secondaryContainer, onSecondaryContainer),
                ToneRamp(tertiaryContainer, onTertiaryContainer),
            ),
            scheme.monogramRamps(),
        )
    }

    /** One role read three times would pass the derivation tests and flatten the badges on screen. */
    @Test fun `the three ramps are distinct from one another`() {
        val ramps = scheme.monogramRamps()
        assertEquals(3, ramps.size)
        assertEquals(3, ramps.distinct().size)
        assertEquals(6, ramps.flatMap { listOf(it.container, it.onContainer) }.distinct().size)
    }

    /**
     * [MONOGRAM_FAMILIES] is not documentation: [monogramSlot] indexes into the ramps with it, and the
     */
    @Test fun `the announced number of families is the number of ramps`() {
        assertEquals(MONOGRAM_FAMILIES, scheme.monogramRamps().size)
        assertEquals(MONOGRAM_FAMILIES, lightColorScheme().monogramRamps().size)
    }

    /** No ramp may be a single point: a container equal to its own "on" colour collapses six tones into one. */
    @Test fun `no ramp collapses to a single colour`() {
        assertTrue(scheme.monogramRamps().none { it.container == it.onContainer })
    }

    /** End to end: a badge built from a real scheme still comes out of that scheme's own tones. */
    @Test fun `a badge derived from a real scheme stays inside it`() {
        val ramps = scheme.monogramRamps()
        val badge = monogramColor("alex.rivera@masto.top", ramps)
        assertTrue("$badge is not a tone of the scheme it came from", ramps.any { within(badge, it) })
    }

    private fun within(color: Color, ramp: ToneRamp): Boolean {
        fun between(c: Float, a: Float, b: Float) =
            c >= minOf(a, b) - EPSILON && c <= maxOf(a, b) + EPSILON
        return between(color.red, ramp.container.red, ramp.onContainer.red) &&
            between(color.green, ramp.container.green, ramp.onContainer.green) &&
            between(color.blue, ramp.container.blue, ramp.onContainer.blue)
    }

    private companion object {
        const val EPSILON = 1f / 255f
    }
}
