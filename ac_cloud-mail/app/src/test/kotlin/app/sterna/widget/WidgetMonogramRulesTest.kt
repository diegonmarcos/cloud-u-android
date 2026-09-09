package app.sterna.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.components.MONOGRAM_FAMILIES
import app.sterna.ui.components.MonogramSlot
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramColor
import app.sterna.ui.components.onAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The badge's two decisions, EXECUTED — not read as text.
 */
class WidgetMonogramRulesTest {

    /** Three ramps in the shape [ColorScheme.monogramRamps] returns: container → onContainer. */
    private val lightRamps = listOf(
        ToneRamp(Color(0xFFCFE8E4), Color(0xFF06201D)),
        ToneRamp(Color(0xFFE8DEF8), Color(0xFF1D192B)),
        ToneRamp(Color(0xFFFFD8E4), Color(0xFF31111D)),
    )

    /** The same three families as a dark scheme states them — the theme the reader switched to. */
    private val darkRamps = listOf(
        ToneRamp(Color(0xFF1F3B38), Color(0xFFCFE8E4)),
        ToneRamp(Color(0xFF362F42), Color(0xFFE8DEF8)),
        ToneRamp(Color(0xFF4A2532), Color(0xFFFFD8E4)),
    )

    private val slots = (0 until MONOGRAM_FAMILIES).flatMap { family ->
        (0 until 6).map { tone -> MonogramSlot(family, tone) }
    }

    /**
     * THE WIDGET DOES NOT OWN A TINT RULE. Every one of the palette's eighteen badges must come
     */
    @Test fun `a badge is painted the colour the app's own list would paint that slot`() {
        assertEquals(
            "widgetMonogramArgb must resolve the row's slot through monogramColor, against the " +
                "ramps handed to it. A tint computed here instead is a copy of the app's rule, " +
                "and it drifts the first time the palette is re-tuned.",
            slots.map { monogramColor(it, lightRamps).toArgb() },
            slots.map { widgetMonogramArgb(RecentRowMonogram("A", it), lightRamps) },
        )
        assertEquals(
            "and the eighteen badges must stay eighteen COLOURS. Collapsing the tone (drawing the " +
                "family's container for all six) leaves three colours on the home screen where " +
                "the list shows eighteen, with every row still drawn and nothing to report.",
            18,
            slots.map { widgetMonogramArgb(RecentRowMonogram("A", it), lightRamps) }.toSet().size,
        )
        assertEquals(
            "the letter must play no part in the colour: the badge answers the ADDRESS, through " +
                "the slot, and two correspondents whose names start alike keep their own colours.",
            widgetMonogramArgb(RecentRowMonogram("A", MonogramSlot(1, 3)), lightRamps),
            widgetMonogramArgb(RecentRowMonogram("Z", MonogramSlot(1, 3)), lightRamps),
        )
    }

    /**
     * BLACK OR WHITE BY LUMINANCE, never a hard-coded white. The badge is drawn on the palest end
     */
    @Test fun `the initial is black or white by luminance, exactly as the in-app badge picks it`() {
        assertEquals(
            "a pale disc must take a BLACK initial. White on the yellow of the account palette is " +
                "1.40:1 — far under AA — and it is what a hard-coded white produces.",
            Color.Black.toArgb(),
            widgetMonogramLetterArgb(Color(0xFFFDD835).toArgb()),
        )
        assertEquals(
            "and a dark disc must take a WHITE one, or the initial disappears the other way in a " +
                "dark theme.",
            Color.White.toArgb(),
            widgetMonogramLetterArgb(Color(0xFF1F3B38).toArgb()),
        )
        assertEquals(
            "and across every badge of both schemes the answer must be onAccentColor's own — the " +
                "same function the list's badge calls. A second luminance rule here would be a " +
                "copy that drifts.",
            slots.flatMap { slot ->
                listOf(
                    onAccentColor(monogramColor(slot, lightRamps)).toArgb(),
                    onAccentColor(monogramColor(slot, darkRamps)).toArgb(),
                )
            },
            slots.flatMap { slot ->
                listOf(
                    widgetMonogramLetterArgb(widgetMonogramArgb(RecentRowMonogram("A", slot), lightRamps)),
                    widgetMonogramLetterArgb(widgetMonogramArgb(RecentRowMonogram("A", slot), darkRamps)),
                )
            },
        )
    }

    /**
     * THE KEY CARRIES THE RESOLVED COLOUR, and this is the rule the whole cache turns on.
     */
    @Test fun `two badges share a bitmap only when letter, resolved colour and side all match`() {
        val slot = MonogramSlot(2, 4)
        val light = widgetMonogramArgb(RecentRowMonogram("M", slot), lightRamps)
        val dark = widgetMonogramArgb(RecentRowMonogram("M", slot), darkRamps)
        assertNotEquals(
            "the fixture itself must hold: one slot resolves to two different colours under two " +
                "schemes, which is the situation the key exists for.",
            light,
            dark,
        )
        assertEquals(
            "the same letter, colour and side must hit the same entry — otherwise there is no " +
                "cache at all and every recycled row re-renders up to 25 bitmaps on a binder " +
                "thread the launcher is waiting on.",
            widgetMonogramKey("M", light, 72),
            widgetMonogramKey("M", light, 72),
        )
        assertNotEquals(
            "⛔ A THEME CHANGE MUST MISS THE CACHE. Same row, same slot, new palette: keyed on the " +
                "slot the launcher is served the previous theme's disc for as long as the process " +
                "lives, and nothing ever invalidates it.",
            widgetMonogramKey("M", light, 72),
            widgetMonogramKey("M", dark, 72),
        )
        assertNotEquals(
            "two initials must not share a bitmap — that is one correspondent wearing another's " +
                "letter.",
            widgetMonogramKey("M", light, 72),
            widgetMonogramKey("N", light, 72),
        )
        assertNotEquals(
            "and neither must two sides: the side is read from the resources, so it differs by " +
                "density, and a shared entry would hand back a disc of the wrong size.",
            widgetMonogramKey("M", light, 72),
            widgetMonogramKey("M", light, 96),
        )
    }
}
