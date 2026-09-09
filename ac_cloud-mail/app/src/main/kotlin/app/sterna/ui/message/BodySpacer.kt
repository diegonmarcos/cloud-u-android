package app.sterna.ui.message

import java.util.Locale

/**
 * How much room the document reserves for a Compose layer drawn OVER it, as a CSS length (#171).
 */
internal fun bodySpacerCss(reservedPx: Int, viewportHeightPx: Int): String {
    if (reservedPx <= 0 || viewportHeightPx <= 0) return "0"
    // Locale.ROOT, NOT the device's locale: a comma-decimal locale formats "26,4114vh", which the
    // WebView cannot parse, so the blank falls to zero and the header covers the top of every message.
    return String.format(Locale.ROOT, "%.4f", 100.0 * reservedPx / viewportHeightPx) + "vh"
}
