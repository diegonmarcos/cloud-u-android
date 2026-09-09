package app.sterna.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #85: contact badges were the one surface that ignored the system palette — a private hue
 */
class MonogramColorTest {

    private val lightRamps = listOf(
        ToneRamp(Color(0xFFEADDFF), Color(0xFF21005D)),
        ToneRamp(Color(0xFFE8DEF8), Color(0xFF1D192B)),
        ToneRamp(Color(0xFFFFD8E4), Color(0xFF31111D)),
    )
    private val lightSurface = Color(0xFFFFFBFE)

    private val darkRamps = listOf(
        ToneRamp(Color(0xFF4F378B), Color(0xFFEADDFF)),
        ToneRamp(Color(0xFF4A4458), Color(0xFFE8DEF8)),
        ToneRamp(Color(0xFF633B48), Color(0xFFFFD8E4)),
    )
    private val darkSurface = Color(0xFF1C1B1F)

    /** A fully achromatic palette — the announced trade-off, kept honest by a test of its own. */
    private val monochromeRamps = listOf(
        ToneRamp(Color(0xFFDDDDDD), Color(0xFF1A1A1A)),
        ToneRamp(Color(0xFFDEDEDE), Color(0xFF1B1B1B)),
        ToneRamp(Color(0xFFDCDCDC), Color(0xFF191919)),
    )
    private val monochromeSurface = Color(0xFFFCFCFC)

    private val addresses = listOf(
        "alex.rivera@masto.top", "jordan.lee@masto.top", "admin@masto.top",
        "anne@example.org", "bob@example.org", "carol@example.com", "dave@example.net",
        "eve@example.io", "frank@example.co", "grace@example.dev", "heidi@example.fr",
        "ivan@example.de", "judy@example.es", "mallory@example.it", "niaj@example.pt",
        "olivia@example.se", "peggy@example.no", "rupert@example.fi", "sybil@example.pl",
        "trent@example.cz", "victor@example.at", "walter@example.ch", "wendy@example.be",
        "zoe@example.lu", "contact@sterna.app", "noreply@codeberg.org", "list@lists.example.org",
        "a.very.long.address.indeed@example.museum", "x@y.zz", "postmaster@example.org",
    )

    // --- the assignment is still per-contact and stable ------------------------------------------

    @Test fun `the same address always gets the same badge`() {
        assertEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("alex.rivera@masto.top", lightRamps),
        )
    }

    @Test fun `two addresses get different badges`() {
        assertNotEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("jordan.lee@masto.top", lightRamps),
        )
    }

    /**
     * The size of the palette, pinned exactly rather than bounded loosely.
     */
    @Test fun `the palette offers exactly eighteen badges`() {
        val everything = (0 until 500).map { monogramColor("seed-$it@example.org", lightRamps) }
        assertEquals(18, everything.distinct().size)
    }

    /** Flattening the colours would destroy the point of colouring a badge; this is the floor. */
    @Test fun `a realistic address book spreads over the whole set of badges`() {
        val distinct = addresses.map { monogramColor(it, lightRamps) }.distinct()
        // 15 of the 18 for these 30 addresses. The floor is set above 12 on purpose: it is the most
        // a twelve-slot palette could ever reach, so a narrowing cannot slip past this test.
        assertTrue("only ${distinct.size} distinct badge colours", distinct.size >= 14)
    }

    @Test fun `a blank seed still yields a palette colour`() {
        val badge = monogramColor("", lightRamps)
        assertTrue(lightRamps.any { within(badge, it) })
    }

    // --- and it now comes from the palette -------------------------------------------------------

    /**
     * The one that would not have passed before: the old wheel took no scheme at all, so the same
     * address painted the same colour whatever the system palette was.
     */
    @Test fun `the same address changes colour with the palette`() {
        assertNotEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("alex.rivera@masto.top", darkRamps),
        )
    }

    @Test fun `every badge lies inside one of the palette's ramps`() {
        for (ramps in listOf(lightRamps, darkRamps, monochromeRamps)) {
            for (seed in 0 until 500) {
                val badge = monogramColor("seed-$seed@example.org", ramps)
                assertTrue("$badge fell outside the palette", ramps.any { within(badge, it) })
            }
        }
    }

    /**
     * The trade-off announced on the issue, asserted rather than hoped for: on an achromatic palette
     */
    @Test fun `a monochrome palette gives grey badges that still differ`() {
        val first = monogramColor("alex.rivera@masto.top", monochromeRamps)
        val second = monogramColor("jordan.lee@masto.top", monochromeRamps)
        assertTrue("$first is not grey", first.red == first.green && first.green == first.blue)
        assertNotEquals(first, second)
    }

    // --- and stays legible -----------------------------------------------------------------------

    /**
     * Measured, not hoped for: the worst badge of the 18 reaches 2.47:1 in light, 3.84:1 in dark and
     */
    @Test fun `every badge keeps a visible contrast against the surface it sits on`() {
        val cases = listOf(
            Triple("light", lightRamps, lightSurface),
            Triple("dark", darkRamps, darkSurface),
            Triple("monochrome", monochromeRamps, monochromeSurface),
        )
        for ((name, ramps, surface) in cases) {
            for (seed in 0 until 500) {
                val badge = monogramColor("seed-$seed@example.org", ramps)
                val contrast = contrastRatio(badge, surface)
                assertTrue("$name: $badge only reached $contrast:1", contrast >= 2.4f)
            }
        }
    }

    // --- the two-step path the widget takes ------------------------------------------------------

    /**
     * THIS TEST CANNOT FAIL TODAY, AND THAT IS WHAT IT SAYS. `monogramColor(seed, ramps)` is
     */
    @Test fun `the two entry points agree, for as long as they are two`() {
        for (ramps in listOf(lightRamps, darkRamps)) {
            for (address in addresses) {
                val slot = monogramSlot(address, ramps.size)
                assertEquals(
                    "$address changed colour when routed through $slot",
                    monogramColor(address, ramps),
                    monogramColor(slot, ramps),
                )
            }
        }
    }

    /**
     * The slot is what crosses the process boundary, so its two numbers are pinned as literals —
     */
    @Test fun `known addresses land on known slots`() {
        assertEquals(MonogramSlot(family = 2, tone = 0), monogramSlot("alex.rivera@masto.top"))
        assertEquals(MonogramSlot(family = 1, tone = 4), monogramSlot("jordan.lee@masto.top"))
        assertEquals(MonogramSlot(family = 2, tone = 1), monogramSlot("admin@masto.top"))
        assertEquals(MonogramSlot(family = 0, tone = 0), monogramSlot(""))
    }

    /**
     * AND THE OTHER HALF OF THE DERIVATION, IN LITERALS — the half that reads the PALETTE.
     */
    @Test fun `three known badges come out the exact colours they shipped as`() {
        assertEquals(
            "the colour a slot resolves to is pinned as literal ARGB. Recomputing it here would " +
                "be a copy of the rule and would agree with any rewrite of it: reversing the " +
                "tone order, or moving where the ramp is entered, repaints every badge in the " +
                "app and on the home screen while every other test in this file — distinct " +
                "count, inside-the-ramp, contrast floor — stays green.",
            listOf("0xFFBD98A4", "0xFF494457", "0xFFA77F8C"),
            listOf(
                argb("alex.rivera@masto.top", lightRamps),
                argb("jordan.lee@masto.top", lightRamps),
                argb("admin@masto.top", darkRamps),
            ),
        )
    }

    /** An index out of range is an ArrayIndexOutOfBounds on someone's home screen, not a wrong hue. */
    @Test fun `every slot is inside the palette it indexes`() {
        for (address in addresses) {
            val slot = monogramSlot(address)
            assertTrue("$address gave family ${slot.family}", slot.family in 0 until 3)
            assertTrue("$address gave tone ${slot.tone}", slot.tone in 0 until 6)
        }
    }

    /** Collapsing every address onto one slot would satisfy the two tests above and flatten the list. */
    @Test fun `different addresses do not all share one slot`() {
        assertTrue(addresses.map { monogramSlot(it) }.distinct().size >= 14)
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A badge as it is packed for drawing — the form both the in-app badge and the widget use. */
    private fun argb(seed: String, ramps: List<ToneRamp>): String =
        "0x%08X".format(monogramColor(seed, ramps).toArgb())

    /** Every channel between the ramp's two ends: an interpolation and nothing else. */
    private fun within(color: Color, ramp: ToneRamp): Boolean {
        fun ok(c: Float, a: Float, b: Float) = c >= minOf(a, b) - EPSILON && c <= maxOf(a, b) + EPSILON
        return ok(color.red, ramp.container.red, ramp.onContainer.red) &&
            ok(color.green, ramp.container.green, ramp.onContainer.green) &&
            ok(color.blue, ramp.container.blue, ramp.onContainer.blue)
    }

    /** WCAG 2.x contrast ratio, the same relative luminance [onAccentColor] uses. */
    private fun contrastRatio(a: Color, b: Color): Float {
        fun channel(c: Float) =
            if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        fun luminance(c: Color) =
            0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)

        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private companion object {
        /** One 8-bit step: the badge is quantised to sRGB when it is packed into a [Color]. */
        const val EPSILON = 1f / 255f
    }
}
