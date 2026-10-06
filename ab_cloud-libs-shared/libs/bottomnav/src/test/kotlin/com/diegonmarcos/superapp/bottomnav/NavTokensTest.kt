package com.diegonmarcos.superapp.bottomnav

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #876: the wasm page draws NavTokens' DEFAULTS; Android draws what its resources declare. They
 * are the same island only while the two agree, which is what this holds: at mdpi (1dp == 1px)
 * the tokens read from res/values are exactly the literals the web page uses. The island's pill
 * and ink are the one exception on purpose: Android 12+ swaps in the wallpaper-derived palette
 * (FleetChrome), so they are compared on the static scheme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
class NavTokensTest {

    @Test
    fun `the web page's literals are the Android resources`() {
        val ctx = RuntimeEnvironment.getApplication()
        val fromRes = buildNavTokens(ctx, Density(1f, 1f))
        val web = NavTokens()
        assertEquals(
            web,
            fromRes.copy(pillFill = web.pillFill, pillInk = web.pillInk, idleInk = web.idleInk, widthFraction = web.widthFraction),
        )
        // "80%" parses to 0.79999995f; the literal is 0.8f.
        assertEquals(web.widthFraction, fromRes.widthFraction, 1e-6f)
        val statik = FleetChrome.staticScheme(ctx)
        assertEquals(web.pillFill, statik.inverseSurface)
        assertEquals(web.pillInk, statik.inverseOnSurface)
        assertEquals(web.idleInk, statik.onSurfaceVariant)
    }
}
