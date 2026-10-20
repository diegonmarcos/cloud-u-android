package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import kotlin.math.max
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1062 — Cloud ▸ WebApps shows ONLY the web apps (no tab strip, no APK download row) and the 31 tiles FILL one Samsung A37
 * screen (384 x 832 dp, 450 dpi; 384 x 672 dp left between the status strip, top bar and bottom nav): no scrolling, and no
 * more than 15% of the height left unused.
 *
 *   A1 the page lists every web app build.json declares, each on https and resolved from the ONE declaration (webapps_base + path)
 *   A2 fontScale 1.0 / 1.15 on the A37 and display zoom (360 x 600 dp) at 1.3: every tile inside the viewport, the grid reaches
 *      >= 85% of the height, nothing scrolls, no two labels overlap, glyphs >= 24 dp
 *   A3 nothing else is drawn: no web view; the section strip is hidden on WebApps (`hide_tabs`) and still drawn on Cloud ▸ App
 *   A4 the column count is chosen to maximise the cell (autoscale)
 *
 * Each case writes build/configs-screens/webapps-fit@<case>.png (uploaded by configs-screens.yml).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class WebAppsGridFitTest : KitPageHarness() {

    private val page get() = Sections.byId("cloud")!!.pages.first { it.id == "webapps" }

    private fun guard(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        show(SectionPages.webAppsGrid(compose.activity, page))
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        compose.runOnUiThread {
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            File(out, "webapps-fit@$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val viewportDp = root.height / compose.density.density
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        var lowest = 0f
        val labels = mutableListOf<Pair<String, androidx.compose.ui.unit.DpRect>>()
        for (w in page.webapps) {
            val t = compose.onNodeWithTag(tileTag(w.url), useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$name: ${w.id} bottom ${t.bottom} dp is below the viewport $viewportDp dp (the grid scrolls)", t.bottom.value <= viewportDp + 0.5f)
            lowest = max(lowest, t.bottom.value)
            val i = compose.onNodeWithTag(tileIconTag(w.url), useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$name: icon of ${w.id} is ${i.width}, min 24 dp", i.width.value >= 23.9f)
            labels += w.id to compose.onNodeWithTag("tilelabel:${w.url}", useUnmergedTree = true).getBoundsInRoot()
        }
        assertTrue("$name: the grid ends at $lowest dp of $viewportDp dp - more than 15% of the screen is unused", lowest >= viewportDp * 0.85f)
        for (a in labels.indices) for (b in a + 1 until labels.size) {
            val (x, r) = labels[a]; val (y, s) = labels[b]
            val overlap = r.left < s.right - 0.5.dp && s.left < r.right - 0.5.dp && r.top < s.bottom - 0.5.dp && s.top < r.bottom - 0.5.dp
            assertTrue("$name: labels of $x and $y overlap", !overlap)
        }
    }

    @Test
    fun `A1 every declared web app is listed once, on https, from the single declaration`() {
        val apps = page.webapps
        assertTrue("WebApps declares apps", apps.size >= 20)
        assertEquals("duplicate web app ids", apps.size, apps.map { it.id }.toSet().size)
        assertEquals("duplicate urls", apps.size, apps.map { it.url }.toSet().size)
        val base = apps.first().url.substringBefore("/c_")
        for (w in apps) assertTrue("${w.id}: ${w.url} is not https under the one base $base", w.url.startsWith("https://") && w.url.startsWith("$base/"))
    }

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `A2 all web apps fit one A37 screen at fontScale 1_0`() = guard("a37-1.0", 1.0f)

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `A2 all web apps fit one A37 screen at fontScale 1_15`() = guard("a37-1.15", 1.15f)

    @Test @Config(qualifiers = "w360dp-h600dp-480dpi")
    fun `A2 display zoom and fontScale 1_3 still fit`() = guard("zoom-1.3", 1.3f)

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `A3 no web view, and the section tab strip is hidden on WebApps only`() {
        show(SectionPages.webAppsGrid(compose.activity, page))
        fun hasWeb(v: View): Boolean = v is android.webkit.WebView || (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).any { hasWeb(g.getChildAt(it)) } } == true
        assertTrue(!hasWeb(compose.activity.window.decorView))
        assertTrue("WebApps declares hide_tabs", page.hideTabs)
        assertTrue("no other Cloud page hides the strip", Sections.byId("cloud")!!.pages.filter { it.id != "webapps" }.none { it.hideTabs })
    }

    @Test
    fun `A3b the strip is gone on the WebApps tab`() {
        show(SectionTabsFragment.newInstance("cloud", "webapps"))
        compose.onNodeWithTag(PageTabsTags.tab("apps")).assertDoesNotExist()
        compose.onNodeWithTag(PageTabsTags.tab("webapps")).assertDoesNotExist()
    }

    @Test
    fun `A3c the strip is still drawn on Cloud App`() {
        show(SectionTabsFragment.newInstance("cloud", "apps"))
        compose.onNodeWithTag(PageTabsTags.tab("webapps")).assertExists()
    }

    @Test
    fun `A4 the column count maximises the cell`() {
        val (c, r) = WebAppsGridFragment.layout(384f, 672f, 31)
        assertEquals("4 columns x 8 rows beat 5 x 7 on the A37", 4 to 8, c to r)
        val (c2, _) = WebAppsGridFragment.layout(800f, 360f, 31)
        assertTrue("a wide pane uses more columns ($c2)", c2 >= 7)
    }
}
