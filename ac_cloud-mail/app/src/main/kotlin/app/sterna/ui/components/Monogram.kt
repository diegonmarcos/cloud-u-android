package app.sterna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A coloured monogram avatar — initial + a colour derived from the address.
 * Privacy-first: never loads a remote photo, no network leak (DESIGN.md).
 */
@Composable
fun Monogram(seed: String, label: String, modifier: Modifier = Modifier, color: Color? = null) {
    // Read live rather than remembered: the tones come from MaterialTheme.colorScheme, and a value
    // cached on the seed alone would outlive a theme change and keep painting the old palette.
    val background = color ?: monogramColor(seed, MaterialTheme.colorScheme.monogramRamps())
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(label, seed),
            // Pick black/white by luminance so the initial stays legible on light accent colours.
            color = onAccentColor(background),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** One accent family of the active palette: the tone it fills a container with, and the tone on it. */
internal data class ToneRamp(val container: Color, val onContainer: Color)

/**
 * The tonal ranges badge colours are drawn from — one per accent family of the ACTIVE scheme.
 */
internal fun ColorScheme.monogramRamps(): List<ToneRamp> = listOf(
    ToneRamp(primaryContainer, onPrimaryContainer),
    ToneRamp(secondaryContainer, onSecondaryContainer),
    ToneRamp(tertiaryContainer, onTertiaryContainer),
)

/**
 * Tones taken along each ramp: six, from 0.32 to 0.90 — 18 badges over the three families. Both ends
 */
private const val TONE_COUNT = 6
private const val FIRST_TONE = 0.32f
private const val TONE_GAP = 0.116f

/**
 * A stable badge colour for [seed], expressed in the tones of the palette in use. Trade-off one,
 */
internal fun monogramColor(seed: String, ramps: List<ToneRamp>): Color =
    monogramColor(monogramSlot(seed, ramps.size), ramps)

/** How many accent families the palette offers a badge — [monogramRamps] returns exactly that many. */
internal const val MONOGRAM_FAMILIES = 3

/**
 * Which of the 18 badges the palette offers: an address reduced to two small indices. It exists so
 */
internal data class MonogramSlot(val family: Int, val tone: Int)

/** The half that reads the address: hash, scramble, and cut into a family and a tone index. */
internal fun monogramSlot(seed: String, families: Int = MONOGRAM_FAMILIES): MonogramSlot {
    val scrambled = scramble(seedHash(seed))
    return MonogramSlot(
        family = Math.floorMod(scrambled, families),
        tone = (scrambled ushr 16) % TONE_COUNT,
    )
}

/** The half that reads the palette: step [MonogramSlot.tone] along the family's range. */
internal fun monogramColor(slot: MonogramSlot, ramps: List<ToneRamp>): Color {
    val ramp = ramps[slot.family]
    return blend(ramp.container, ramp.onContainer, FIRST_TONE + TONE_GAP * slot.tone)
}

/** The original seed hash (djb2-style, Int overflow included) — unchanged, so are its collisions. */
private fun seedHash(seed: String): Int {
    var hash = 0
    for (c in seed) hash = c.code + (hash shl 5) - hash
    return hash
}

/** Spread the hash before it is cut into a dozen buckets (the lowbias32 finaliser): its low bits
 *  track the seed's last characters far too closely to be sliced this thin — over a sample of
 *  ordinary addresses the raw hash filled 7 of the 12 slots and crowded five. */
private fun scramble(hash: Int): Int {
    var x = hash
    x = x xor (x ushr 16)
    x *= 0x7feb352d
    x = x xor (x ushr 15)
    x *= 0x846ca68b.toInt()
    x = x xor (x ushr 16)
    return x
}

/** Straight sRGB interpolation, enough to step along a tonal range and free of any dependency. */
private fun blend(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

/** The letter drawn inside a badge: the first letter or digit of the display name, of the address
 *  when there is no name, and `"?"` when neither offers one. Shared with the widget, which has no
 *  composition to call [Monogram] from — hence `internal`. */
internal fun initialOf(label: String, fallback: String): String {
    val source = label.ifBlank { fallback }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
