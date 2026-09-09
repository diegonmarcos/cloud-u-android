package app.sterna.ui.message

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The reader's blank-space decision (#171), EXECUTED — not re-derived here.
 */
class BodySpacerTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test fun `the reservation is a share of the view, not of one bench device`() {
        // The SAME header height against three different viewports, each pinned as a literal.
        // The bench measured one device; a formula that hard-codes its 2037 px viewport answers
        assertEquals("26.4114vh", bodySpacerCss(538, 2037))
        assertEquals("52.8487vh", bodySpacerCss(538, 1018))
        assertEquals("22.4167vh", bodySpacerCss(538, 2400))
        // And the bottom blank, which is asked for a different height against the same view.
        assertEquals("12.2730vh", bodySpacerCss(250, 2037))
    }

    @Test fun `the length is the share of the view the layer covers`() {
        // What `Nvh` is worth, without a page scale anywhere in the arithmetic: N hundredths of the
        // view. THAT is what this function decides, and it is all it decides.
        //
        // The other half — that the browser paints those hundredths at the same screen size
        // whatever scale the page laid out at — is a property of Blink, NOT of this code, and no
        for ((reserved, viewport) in listOf(538 to 2037, 250 to 2037, 538 to 1018, 96 to 640)) {
            val vh = bodySpacerCss(reserved, viewport).removeSuffix("vh").toDouble()
            assertEquals(
                "a blank of $vh vh must be $reserved of the view's $viewport pixels — any less is " +
                    "that many pixels of the message left under an opaque layer",
                reserved.toDouble(), vh / 100.0 * viewport, 0.5,
            )
        }
    }

    @Test fun `nothing is reserved before anything is measured`() {
        // Both measurements arrive in the layout phase. Until then the reader must behave exactly
        // as it did: a spacer of no height. "0" is a valid CSS length without a unit.
        assertEquals("0", bodySpacerCss(0, 2037))
        assertEquals("0", bodySpacerCss(-1, 2037))
        assertEquals("0", bodySpacerCss(538, 0))
        assertEquals("0", bodySpacerCss(538, -1))
    }

    @Test fun `a comma-decimal locale must not put a comma in the CSS`() {
        // The device locale must not reach the stylesheet. `height:26,4114vh` does not parse: the
        // declaration is dropped, the blank falls to ZERO, and the opaque header covers the top of
        // EVERY message — in French, German, Spanish, Russian, Portuguese…
        Locale.setDefault(Locale.FRANCE)

        val css = bodySpacerCss(538, 2037)

        assertTrue(
            "the CSS length must be formatted with Locale.ROOT: under a comma-decimal locale it " +
                "came out as '$css', which the WebView cannot parse — the blank collapses to zero " +
                "and the header sits on top of the message for every reader in those languages",
            ',' !in css,
        )
        assertEquals("26.4114vh", css)
    }
}
