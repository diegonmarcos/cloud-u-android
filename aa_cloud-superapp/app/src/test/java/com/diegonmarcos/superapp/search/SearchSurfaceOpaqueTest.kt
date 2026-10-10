package com.diegonmarcos.superapp.search

import android.app.Application
import android.graphics.Color
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.ui.LauncherPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The search results are read over the page they were opened from (the Home sheet, Cloud ▸ Apps,
 * the Home star), so the layer under them is the theme surface at 100% alpha — never the
 * 13%-white `surface` itself, never transparent. SearchPanelTest reads the drawn pixel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchSurfaceOpaqueTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun `every theme's search surface is fully opaque and not transparent`() {
        for (theme in LauncherTheme.values()) {
            val p = LauncherPalette.forTheme(ctx, theme)
            val c = LauncherPalette.opaqueSurface(ctx, p)
            assertEquals("${theme.id}: alpha", 1.0f, Color.alpha(c) / 255f, 0f)
            assertNotEquals("${theme.id}: transparent", Color.TRANSPARENT, c)
        }
    }

    @Test fun `the Cloud theme's translucent surface is composited, not just re-alpha'd`() {
        val p = LauncherPalette.forTheme(ctx, LauncherTheme.Cloud)
        // The token itself is see-through (that is what made the results hard to read)...
        assertNotEquals(255, Color.alpha(p.surface))
        // ...and the opaque one is that surface laid over the theme's opaque ink, not white.
        assertNotEquals(Color.WHITE, LauncherPalette.opaqueSurface(ctx, p))
    }
}
