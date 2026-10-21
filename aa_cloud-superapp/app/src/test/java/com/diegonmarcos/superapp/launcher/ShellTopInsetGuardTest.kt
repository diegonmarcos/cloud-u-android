package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellTopInset
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * #1080 GUARD: no page's content starts under the shell's top row, at ANY height the row settles at.
 *
 * The row (toolbar_island) is not at a fixed offset: in launcher mode ShellActivity seats it under the status strip
 * ([StripIslandGap]: strip height + a gap), so its bottom edge is 90 dp only by luck. This test stands up the shell's real
 * parts (view_content_panes, the island at ?attr/actionBarSize high, StripIslandGap, [ShellTopInset]) in a frame padded
 * like shell_linear, moves the strip through every height below, and for each page the shell registers (Configs, Configs
 * with its search open, ADB Shell, and every native Configs page) hosted in the REAL container:
 *
 *   G1  the container is padded to at least the row's bottom edge
 *   G2  every node the page draws (Compose semantics, and every View) starts at or below that edge
 *
 * Evidence: app/build/configs-screens/top-inset-<page>@<strip>.png (the row outlined in red, its bottom edge as a line).
 * [ShellTopInsetStaticGuardTest] holds the same rule for the rest of the registry without rendering it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellTopInsetGuardTest(private val key: String) : KitPageHarness() {

    companion object {
        /** The strip heights the row is seated under (dp): none (the other modes: 6 dp under the system bar) up to a tall one. */
        val STRIPS = listOf(0, 24, 48, 72, 120)
        /** The tallest-but-real one gets the screenshot. */
        const val SHOT_STRIP = 72
        const val GAP_DP = 6
        /** The pages the shell must render here; every other page is rendered when it can be and recorded when it cannot. */
        val MUST = setOf("configs", "configs-search", "config/adb-shell", "config/about", "config/perms")

        val OUT = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }

        /**
         * Configs, Configs with its search open, and every page under it that ConfigsScreensTest renders (the same list: it is the
         * registry's natively hosted Configs pages, ADB Shell among them). Every other registered page is held by
         * [ShellTopInsetStaticGuardTest]: all of them reach the screen through the one padded container.
         */
        fun keys(): List<String> =
            listOf("configs", "configs-search") + com.diegonmarcos.superapp.configs.ConfigsScreensTest.SCREENS.filter { it != "grid" }.map { "config/$it" }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = keys().map { arrayOf<Any>(it) }
    }

    private lateinit var shell: FrameLayout
    private lateinit var island: FrameLayout
    private lateinit var strip: View
    private var toolbarPx = 0

    private fun px(dp: Int) = (dp * compose.density.density).toInt()

    private fun pageFor(k: String): Fragment = when (k) {
        "configs", "configs-search" -> ConfigsFragment.newInstance(ConfigsPageFixture.tiles(compose.activity))
        else -> {
            val id = k.substringAfter("/")
            SectionPages.pagesFor("config", includeHidden = true).first { it.id == id }.factory()
        }
    }

    /** The shell's own parts, wired as activity_main wires them: a padded frame, the page container, the strip, the island. */
    private fun buildShell() {
        compose.runOnUiThread {
            val a = compose.activity
            val themed = ContextThemeWrapper(a, R.style.Theme_Superapp)
            val tv = android.util.TypedValue()
            check(themed.theme.resolveAttribute(themed.resources.getIdentifier("actionBarSize", "attr", themed.packageName), tv, true))
            toolbarPx = tv.getDimension(themed.resources.displayMetrics).toInt()
            shell = FrameLayout(a).apply { id = R.id.shell_linear; clipChildren = false; clipToPadding = false; setPadding(0, px(24), 0, 0) }
            LayoutInflater.from(a).inflate(R.layout.view_content_panes, shell, true)
            shell.addView(FrameLayout(a).apply { id = R.id.overlay_container }, FrameLayout.LayoutParams(-1, -1))
            strip = View(a).apply { setBackgroundColor(0x33FFFFFF) }
            shell.addView(strip, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
            island = FrameLayout(a).apply {
                id = R.id.toolbar_island
                setBackgroundColor(0x33FF0000)
                addView(View(a), FrameLayout.LayoutParams(-1, toolbarPx))
            }
            shell.addView(island, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(GAP_DP); marginStart = px(12); marginEnd = px(12) })
            StripIslandGap.follow(strip, island, px(GAP_DP))
            a.setContentView(shell)
            ShellTopInset.install(shell)
        }
        compose.waitForIdle()
    }

    private fun host(page: Fragment) {
        compose.runOnUiThread {
            compose.activity.supportFragmentManager.beginTransaction().replace(R.id.fragment_container, page).commitNow()
        }
        compose.waitForIdle()
    }

    /** Seat the strip at [dp], lay the shell out until it stops moving, and let the page load. */
    private fun settle(stripDp: Int) {
        compose.runOnUiThread {
            (strip.layoutParams as FrameLayout.LayoutParams).height = px(stripDp)
            strip.requestLayout()
        }
        repeat(4) {
            compose.runOnUiThread {
                val m = compose.activity.resources.displayMetrics
                shell.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY))
                shell.layout(0, 0, m.widthPixels, m.heightPixels)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            }
            compose.waitForIdle()
        }
    }

    private fun windowTop(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[1]

    /** Every View under [v] that is shown and has an area, with its top edge in the window. */
    private fun viewTops(v: View, out: MutableList<Pair<String, Int>> = mutableListOf()): List<Pair<String, Int>> {
        if (v.isShown && v.width > 0 && v.height > 0 && v.javaClass.name != "androidx.compose.ui.platform.AndroidComposeView") out += v.javaClass.simpleName to windowTop(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) viewTops(v.getChildAt(i), out)
        return out
    }

    /** The views the page drew: everything under the page container (the container itself is the host, padded, not content). */
    private fun pageViewTops(): List<Pair<String, Int>> = compose.runOnUiThread {
        val c = shell.findViewById<ViewGroup>(R.id.fragment_container)
        (0 until c.childCount).flatMap { viewTops(c.getChildAt(it)) }
    }

    private fun composeTops(): List<Pair<String, Float>> =
        runCatching {
            compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(false)
        }.getOrDefault(emptyList()).filter { it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f }
            .map { (it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "node#${it.id}") to it.boundsInWindow.top }

    private fun shot(name: String, limitPx: Float) {
        val bmp = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).also { c ->
                    c.drawColor(android.graphics.Color.BLACK); root.draw(c)
                    val red = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
                    c.drawRect(island.left.toFloat(), windowTop(island).toFloat(), island.right.toFloat(), windowTop(island) + island.height.toFloat(), red)
                    c.drawLine(0f, limitPx, b.width.toFloat(), limitPx, red)
                }
            }
        }
        File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `no page starts under the top row at any strip height`() {
        RuntimeEnvironment.setFontScale(1.0f)
        val file = key.replace('/', '-')
        val problems = mutableListOf<String>()
        try {
            buildShell()
            host(pageFor(key))
            if (key == "configs-search") {
                compose.onNode(hasSetTextAction()).performTextInput("adb")
                compose.waitForIdle()
            }
            for (stripDp in STRIPS) {
                settle(stripDp)
                val containerPad = compose.runOnUiThread { shell.findViewById<View>(R.id.fragment_container).paddingTop }
                val islandBottom = compose.runOnUiThread { island.top + island.height }
                val containerTop = compose.runOnUiThread { shell.findViewById<View>(R.id.fragment_container).top }
                val limitPx = (windowTop(shell) + islandBottom).toFloat()
                if (containerTop + containerPad < islandBottom)
                    problems += "strip ${stripDp}dp: the page container starts content at ${containerTop + containerPad}px, the top row ends at ${islandBottom}px"
                if (containerPad < px(90) - 1) problems += "strip ${stripDp}dp: container padding $containerPad px is under the declared shell_top_inset"
                val tops = composeTops().filter { it.second < limitPx - 1f } + pageViewTops().filter { it.second < limitPx - 1 }.map { it.first to it.second.toFloat() }
                if (tops.isNotEmpty()) problems += "strip ${stripDp}dp: ${tops.size} node(s) start above the top row's bottom edge ($limitPx px): " + tops.take(5).joinToString { "${it.first}@${it.second}" }
                if (stripDp == SHOT_STRIP) shot("top-inset-$file@$stripDp", limitPx)
            }
            assertTrue("$key: no page content drawn", composeTops().isNotEmpty() || pageViewTops().size > 1 || key !in MUST)
        } catch (t: Throwable) {
            File(OUT, "top-inset-$file.err").writeText("${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(300)}\n")
            assertTrue("$key did not render: ${t.javaClass.simpleName}: ${t.message?.take(300)}", key !in MUST)
            return
        }
        assertTrue("$key starts under the top row:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
