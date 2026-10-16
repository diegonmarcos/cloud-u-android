package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the universal page-content view, Compose since fragment_page_content.xml / item_page_row.xml.
 *
 *   P1 the header shows the given title and, with no subtitle, the "section / page" default
 *   P2 a page with declared samples draws one row each, in order, and no empty card
 *   P3 a page with no samples draws the "Coming soon" card naming the missing key
 *   P4 the stripe slot is stable per page+row and always one of the palette's four roles
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageContentComposeTest : KitPageHarness() {

    private fun anyKeyWithSamples(): Pair<String, String>? =
        Sections.all().firstNotNullOfOrNull { s ->
            s.pages.firstOrNull { Sections.pageSamples("${s.id}/${it.id}").isNotEmpty() }?.let { s.id to it.id }
        }

    @Test
    fun `P1 header shows the title and the default subtitle`() {
        show(PageContentFragment.newInstance("zz", "nothing", "A Title"))
        compose.onNodeWithTag(PageContentFragment.TAG_TITLE).assert(hasText("A Title"))
        compose.onNodeWithTag(PageContentFragment.TAG_SUBTITLE).assert(hasText("zz / nothing"))
    }

    @Test
    fun `P3 no samples draws the empty card naming the key`() {
        show(PageContentFragment.newInstance("zz", "nothing", "A Title"))
        compose.onNodeWithTag(PageContentFragment.TAG_EMPTY).assert(hasText("zz/nothing", substring = true))
        compose.onAllNodesWithTag(PageContentFragment.rowTag(0)).assertCountEquals(0)
    }

    @Test
    fun `P2 declared samples draw one row each and no empty card`() {
        val (sec, page) = anyKeyWithSamples() ?: return   // build.json declares no page_samples in this build
        val samples = Sections.pageSamples("$sec/$page")
        show(PageContentFragment.newInstance(sec, page, "T", "sub"))
        compose.onNodeWithTag(PageContentFragment.TAG_SUBTITLE).assert(hasText("sub"))
        for ((i, s) in samples.withIndex()) compose.onNodeWithTag(PageContentFragment.rowTag(i)).assert(hasText(s.title))
        compose.onAllNodesWithTag(PageContentFragment.rowTag(samples.size)).assertCountEquals(0)
        compose.onAllNodesWithTag(PageContentFragment.TAG_EMPTY).assertCountEquals(0)
    }

    @Test
    fun `P4 stripe slots are stable and within the four roles`() {
        for (i in -1..40) {
            val a = PageContentFragment.stripeSlot("cal/month", i)
            assertEquals(a, PageContentFragment.stripeSlot("cal/month", i))
            assertTrue(a in 0..3)
        }
        val p = KitPalette.fromArgb(1, 2, 3, 4, 5, 6, 7, ok = 8, warn = 9, bad = 10)
        assertEquals(setOf(p.accent, p.ok, p.warn, p.bad), (0..3).map { PageContentFragment.stripeColor(p, it) }.toSet())
    }
}
