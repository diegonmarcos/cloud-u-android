package app.sterna.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Black or white, whichever has the higher WCAG contrast ratio on [background].
 *
 * Monogram initials and the swatch check-mark sit on arbitrary, user-chosen
 * accent colours (and on hashed avatar colours). A hard-coded white fails AA on
 * the light end of the palette — e.g. white on yellow #FDD835 is only 1.40:1 and
 * white on orange #FB8C00 only 2.37:1, both unreadable. Picking the higher-
 * contrast foreground keeps every avatar legible (black on yellow = 15.05:1).
 * Luminance per WCAG 2.x relative-luminance.
 */
fun onAccentColor(background: Color): Color {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val l = 0.2126f * channel(background.red) +
        0.7152f * channel(background.green) +
        0.0722f * channel(background.blue)
    val whiteContrast = 1.05f / (l + 0.05f)
    val blackContrast = (l + 0.05f) / 0.05f
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

/** Curated accent-colour palette a user can assign to an account. */
object AccountPalette {
    val colors: List<Color> = listOf(
        Color(0xFFE53935), // red
        Color(0xFFFB8C00), // orange
        Color(0xFFFDD835), // yellow
        Color(0xFF43A047), // green
        Color(0xFF00897B), // teal
        Color(0xFF1E88E5), // blue
        Color(0xFF5E35B1), // deep purple
        Color(0xFFD81B60), // pink
    )
}

/** An account's stored accent colour (ARGB) as a Compose [Color], or null for auto. */
fun accountColorOf(argb: Int?): Color? = argb?.let { Color(it) }
