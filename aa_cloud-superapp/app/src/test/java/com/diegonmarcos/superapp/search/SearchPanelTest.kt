package com.diegonmarcos.superapp.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitSearchTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ONE search component every SuperApp entry shows (search/SearchPanel): the slim kit bar with
 * its clear (×), the scope chips, the results in one section per type in the declared order, the
 * Cloud Search hand-off, the "no Cloud Browser" line, and the results drawn on the opaque theme
 * surface (read back as pixels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchPanelTest : KitPageHarness() {

    private val scopes = listOf(
        SearchScope("cloud-apps", "Cloud-Apps", SearchKinds.CLOUD_APPS, "Cloud apps"),
        SearchScope("phone-apps", "Phone-Apps", SearchKinds.PHONE_APPS, "Phone apps"),
        SearchScope("cloud-configs", "Cloud-Configs", SearchKinds.CLOUD_CONFIGS, "Cloud configs"),
        SearchScope("phone-configs", "Phone-Configs", SearchKinds.PHONE_CONFIGS, "Phone configs"),
        SearchScope("browser-fav", "Browser-Fav", SearchKinds.BROWSER_FAV, "Browser favourites"),
        SearchScope("browser-history", "Browser-History", SearchKinds.BROWSER_HISTORY, "Browser history"),
        SearchScope("browser-web", "Browser-Web", SearchKinds.BROWSER_WEB, "Web"),
    )

    private class Fake(val index: List<SearchHit>) : SearchSource {
        override fun hitsFor(scope: SearchScope) = index.filter { it.source == scope.id }
    }

    private fun hit(label: String, scope: String) = SearchHit(label, "", scope, target = "t:$label")

    private val index = listOf(
        hit("Phone mail settings", "phone-configs"), hit("Mail", "phone-apps"), hit("Cloud Mail", "cloud-apps"),
        hit("mail.diegonmarcos.com", "cloud-configs"), hit("Maildir", "cloud-apps"),
    )
    private val live = listOf(hit("Webmail", "browser-fav"), hit("Gmail", "browser-history"), hit("Search the web for “mail”", "browser-web"))

    private fun controller(entry: SearchEntry = SearchEntry.HOME_STAR) = SearchController(entry, Fake(index), null, scopes)

    private val picked = mutableListOf<String>()
    private val cb = SearchCallbacks(
        onHit = { picked += "hit:${it.label}" },
        onCommand = { picked += "cmd:${it.alias}" },
        onCloudSearch = { picked += "cloud-search:$it" },
    )

    private fun surface() = Color(LauncherPalette.opaqueSurface(compose.activity))

    private fun results(c: SearchController) = showKit {
        Column(Modifier.fillMaxSize()) {
            SearchScopeChips(c)
            SearchResults(c, surface(), cb, Modifier.weight(1f))
        }
    }

    // ── the bar ───────────────────────────────────────────────────────────────

    @Test fun `the bar is the slim kit bar, and its clear appears only while there is text`() {
        val c = controller()
        showKit { SearchBox(c, "Search", onGo = { c.go(cb) }) }
        compose.onNodeWithText("Search").assertExists()
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(0)
        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("ma")
        assertEquals("ma", c.query)
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(1)
        compose.onNodeWithTag(KitSearchTags.CLEAR).performClick()
        assertEquals("", c.query)
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(0)
    }

    @Test fun `Go opens the top result, and with no match hands the text to Cloud Search`() {
        val c = controller(SearchEntry.CLOUD_APPS_PAGE)
        showKit { SearchBox(c, "Search", onGo = { c.go(cb) }) }
        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("mail")
        compose.onNodeWithTag(KitSearchTags.FIELD).performImeAction()
        assertEquals("hit:Cloud Mail", picked.last())
        c.query = "zzzz"
        compose.onNodeWithTag(KitSearchTags.FIELD).performImeAction()
        assertEquals("cloud-search:zzzz", picked.last())
    }

    // ── sections ──────────────────────────────────────────────────────────────

    @Test fun `results are arranged per type, one section per scope, in the declared order`() {
        val c = controller()
        scopes.filter { it.id !in c.selected }.forEach { c.toggle(it.id) }   // every scope on
        c.query = "mail"
        c.setLive("mail", LiveResult(live))
        results(c)
        val tops = scopes.map { s ->
            compose.onNodeWithTag(SearchPanelTags.section(s.id)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("sections out of the declared order: $tops", tops.sorted(), tops)
        compose.onNodeWithText("Cloud apps · 2").assertExists()
        compose.onNodeWithText("Web · 1").assertExists()
        compose.onAllNodesWithTag(SearchPanelTags.row("cloud-apps")).assertCountEquals(2)
    }

    @Test fun `a chip turned off takes its section away, and the defaults are the entry's`() {
        val c = controller(SearchEntry.CLOUD_APPS_PAGE)
        c.query = "mail"
        results(c)
        compose.onNodeWithTag(SearchPanelTags.chip("cloud-apps")).assertIsOn()
        compose.onNodeWithTag(SearchPanelTags.chip("browser-web")).assertIsOff()
        compose.onAllNodesWithTag(SearchPanelTags.section("browser-fav")).assertCountEquals(0)
        compose.onNodeWithTag(SearchPanelTags.section("cloud-apps")).assertExists()
        compose.onNodeWithTag(SearchPanelTags.chip("cloud-apps")).performClick()
        compose.onAllNodesWithTag(SearchPanelTags.section("cloud-apps")).assertCountEquals(0)
        assertFalse("cloud-apps" in c.selected)
    }

    @Test fun `no match offers the text to Cloud Search`() {
        val c = controller()
        c.query = "zzzz"
        results(c)
        compose.onNodeWithTag(SearchPanelTags.CLOUD_SEARCH).performClick()
        assertEquals("cloud-search:zzzz", picked.last())
    }

    @Test fun `no Cloud Browser is said once, not once per browser scope`() {
        val c = controller()   // the Home star: all three browser scopes on
        c.query = "mail"
        c.setLive("mail", LiveResult(emptyList(), listOf(BrowserLookupClient.NOT_AVAILABLE)))
        results(c)
        compose.onAllNodesWithTag(SearchPanelTags.NOTICE).assertCountEquals(1)
        compose.onNodeWithText(BrowserLookupClient.NOT_AVAILABLE).assertExists()
    }

    @Test fun `tapping a row opens that hit`() {
        val c = controller(SearchEntry.CLOUD_APPS_PAGE)
        c.query = "maildir"
        results(c)
        compose.onNodeWithText("Maildir").performClick()
        assertEquals("hit:Maildir", picked.last())
    }

    // ── opaque ────────────────────────────────────────────────────────────────

    @Test fun `the results are drawn on the opaque theme surface, nothing shows through`() {
        val c = controller(SearchEntry.CLOUD_APPS_PAGE)
        c.query = "maildir"
        val expected = surface()
        assertEquals(1.0f, expected.alpha, 0f)
        showKit { Box(Modifier.size(320.dp, 400.dp)) { SearchResults(c, expected, cb, Modifier.fillMaxSize()) } }
        val b = compose.onNodeWithTag(SearchPanelTags.RESULTS).fetchSemanticsNode().boundsInRoot
        // Draw the page view alone (no window background behind it): a see-through list would
        // come out with alpha below 255 here.
        val bmp = compose.runOnUiThread {
            val root = (compose.activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        for ((x, y) in listOf(b.right - 4f to b.bottom - 4f, b.left + 4f to b.bottom - 40f, b.center.x to b.bottom - 8f)) {
            val px = bmp.getPixel(x.toInt(), y.toInt())
            assertEquals("alpha at ($x,$y)", 255, android.graphics.Color.alpha(px))
            assertEquals("colour at ($x,$y)", expected.toArgb(), px)
        }
        assertTrue(bmp.width > 0)
    }
}
