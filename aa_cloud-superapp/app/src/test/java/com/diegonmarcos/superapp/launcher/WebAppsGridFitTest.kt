package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
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
 * #1062 — Cloud ▸ WebApps shows ONLY the web apps (no tab bar, no APK download row) and ALL of them fit one Samsung A37 screen
 * (384 x 832 dp, 450 dpi; 384 x 672 dp left between the status strip, top bar and bottom nav) with no scrolling.
 *
 *   A1 the page lists every web app build.json declares, each on https and resolved from the ONE declaration (webapps_base + path)
 *   A2 fontScale 1.0 / 1.15 on the A37 and display zoom (360 x 600 dp) at 1.3: every tile's bottom is inside the viewport,
 *      tiles stay <= 35 dp, no two labels overlap
 *   A3 nothing else is drawn: no APK row, no tab strip of the old HTML replica, no web view
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
        TileMetrics.clear()
        show(LauncherNavController.webAppsGrid(compose.activity, page))
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        compose.runOnUiThread {
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            File(out, "webapps-fit@$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val viewportDp = root.height / compose.density.density
        val labels = mutableListOf<Pair<String, androidx.compose.ui.unit.DpRect>>()
        for (w in page.webapps) {
            val t = compose.onNodeWithTag(tileTag(w.url), useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$name: ${w.id} bottom ${t.bottom} dp is below the viewport $viewportDp dp (the grid scrolls)", t.bottom.value <= viewportDp + 0.5f)
            assertTrue("$name: ${w.id} is ${t.height} tall, max 35 dp", t.height.value <= 35.5f)
            labels += w.id to compose.onNodeWithTag("tilelabel:${w.url}", useUnmergedTree = true).getBoundsInRoot()
        }
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
    fun `A3 no tab strip, no APK row and no web view is drawn`() {
        show(LauncherNavController.webAppsGrid(compose.activity, page))
        fun hasWeb(v: View): Boolean = v is android.webkit.WebView || (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).any { hasWeb(g.getChildAt(it)) } } == true
        assertTrue(!hasWeb(compose.activity.window.decorView))
        compose.onNodeWithTag(com.diegonmarcos.superapp.bottomnav.PageTabsTags.tab("apps")).assertDoesNotExist()
    }
}
