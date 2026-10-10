package com.diegonmarcos.superapp.search

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.ui.LauncherPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The search results are read over the page they were opened from (the Home sheet, Cloud ▸ Apps,
 * the Home star), so the layer under them is the theme surface at 100% alpha — never the
 * 13%-white `surface` itself, never transparent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchSurfaceOpaqueTest {

    /** A host activity the way ShellActivity is one: the sheet asks it for its surface. */
    class HostActivity : FragmentActivity(), SearchSheet.Host {
        var surface: Int = 0
        override fun hitsFor(scope: SearchScope) = listOf(SearchHit("Mail", "Cloud", scope.id, target = "x"))
        override fun openTarget(target: String) {}
        override fun searchSurfaceColor() = surface
        override fun dismissSearch() {}
    }

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
        // The token itself is see-through (that is the bug this guards against)...
        assertNotEquals(255, Color.alpha(p.surface))
        // ...and the opaque one is that surface laid over the theme's opaque ink, not white.
        val c = LauncherPalette.opaqueSurface(ctx, p)
        assertNotEquals(Color.WHITE, c)
        assertNotEquals(p.surface or (0xFF shl 24), c)
    }

    @Test fun `the sheet container is painted opaque even when the host returns a translucent colour`() {
        val act = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        act.surface = 0x22FFFFFF
        val sheet = SearchSheet.newInstance()
        act.supportFragmentManager.beginTransaction().add(android.R.id.content, sheet).commitNow()
        val bg = sheet.requireView().background as ColorDrawable
        assertEquals(1.0f, Color.alpha(bg.color) / 255f, 0f)
        assertEquals(SearchSheet.opaque(0x22FFFFFF), bg.color)
    }
}
