package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #573 — every tab strip's top/bottom spacing is ONE declaration.
 *
 * Diego: the top-nav bar's tabs must sit with the same top and bottom spacing
 * as every page. Until #573 only the section strip read the top base and
 * added the live status inset; Profile's and Launcher's strips took neither.
 * This reads the two dimens back off a strip styled by [AppTabsStyle.apply]
 * — the numbers come from res/values/dimens.xml, never from this file — and
 * dispatches a status-bar inset to prove it is added on top of the base for
 * a strip under the top chrome and NOT for one in a sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppTabsStyleTest {

    private val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Superapp)
    private val top get() = ctx.resources.getDimensionPixelSize(R.dimen.tab_strip_top_inset)
    private val bottom get() = ctx.resources.getDimensionPixelSize(R.dimen.tab_strip_bottom_inset)

    private fun strip(): TabLayout = TabLayout(ctx).apply {
        addTab(newTab().setText("One")); addTab(newTab().setText("Two"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun margins(t: TabLayout) = (t.layoutParams as ViewGroup.MarginLayoutParams).let { it.topMargin to it.bottomMargin }

    private fun statusBar(px: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0)).build()

    @Test fun `a styled strip wears the declared top base and bottom gap`() {
        val t = strip()
        AppTabsStyle.apply(t)
        assertEquals(top to bottom, margins(t))
    }

    @Test fun `under the top chrome the live status inset is added to the base, and the insets are not consumed`() {
        val t = strip()
        AppTabsStyle.apply(t)
        val out = ViewCompat.dispatchApplyWindowInsets(t, statusBar(48))
        assertEquals(top + 48 to bottom, margins(t))
        assertEquals("the listener hands the insets on unchanged", 48, out.getInsets(WindowInsetsCompat.Type.statusBars()).top)
    }

    @Test fun `a strip in a sheet keeps the base only, whatever the window says`() {
        val t = strip()
        AppTabsStyle.apply(t, underTopChrome = false)
        ViewCompat.dispatchApplyWindowInsets(t, statusBar(48))
        assertEquals(top to bottom, margins(t))
    }
}
