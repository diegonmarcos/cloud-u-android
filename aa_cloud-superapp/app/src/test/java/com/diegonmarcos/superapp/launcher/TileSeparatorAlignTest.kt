package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1069 — the "|" group separators of the Cloud > Apps strips (Data Apps, Project me) and of the tile grids (Configs) are
 * thin inline dividers: centred on the ICON line, and taking no column (#438), so the icons keep their columns.
 *
 *   S1 strip: separator centre-y == icon centre-y (+-1 dp), and the icons of a row with separators sit at the same x as the
 *      icons of a row without
 *   S2 grid (Configs et al.): the same two checks
 *   S3 no screen draws a separator through the old inline-glyph pattern (a Text/Row child that takes width)
 *
 * Writes app/build/configs-screens/sep1069-*.png (uploaded by configs-screens.yml).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h672dp-450dpi")
class TileSeparatorAlignTest : KitPageHarness() {

    private fun png(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        compose.runOnUiThread {
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            File(out, "sep1069-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
    private fun DpRect.cy() = (top.value + bottom.value) / 2f

    /** Every separator centred on the icon line of the tile before it; returns the left x (dp) of each icon, in order. */
    private fun check(name: String, seps: Map<String, String>) {
        for ((sep, iconTag) in seps) {
            val s = bounds(sep); val i = bounds(iconTag)
            assertEquals("$name: separator $sep centre-y ${s.cy()} dp vs icon centre-y ${i.cy()} dp", i.cy(), s.cy(), 1f)
        }
    }

    private fun strip(id: String, sep: Boolean = false) =
        Sections.AggTile(id = id, label = id, iconName = "settings", target = "page:t/$id", separator = sep)

    @Test
    fun `S1 strip separators sit on the icon line and take no column`() {
        val withSep = listOf(strip("a"), strip("b"), strip("s1", true), strip("c"), strip("d"), strip("s2", true), strip("e"))
        val plain = listOf("f", "g", "h", "i", "j").map { strip(it) }
        showKit { Column { TileStrip(withSep, {}, {}); TileStrip(plain, {}, {}) } }
        png("strip")
        val ic = { t: String -> tileIconTag("page:t/$t") }
        check("strip", mapOf(tileSepTag("s1") to ic("b"), tileSepTag("s2") to ic("d")))
        val a = listOf("a", "b", "c", "d", "e").map { bounds(ic(it)).left.value }
        val b = listOf("f", "g", "h", "i", "j").map { bounds(ic(it)).left.value }
        for (k in a.indices) assertEquals("strip: icon $k of the separated row moved off its column", b[k], a[k], 0.5f)
        // The divider sits between its two neighbours.
        val s = bounds(tileSepTag("s1"))
        assertTrue(bounds(ic("b")).right.value <= s.left.value + 0.5f && s.right.value <= bounds(ic("c")).left.value + 0.5f)
    }

    @Test
    fun `S2 grid separators sit on the icon line and take no column`() {
        val ids = listOf("a", "b", "separator:s1", "c", "d", "e", "f", "g", "h", "i", "j")
        val tiles = ids.map { TileGridFragment.Tile(if (it.startsWith("separator:")) it else "page:t/$it", it.removePrefix("separator:"),
            if (it.startsWith("separator:")) 0 else R.drawable.ic_settings) }
        show(TileGridFragment.newInstance("Configs", tiles))
        png("grid")
        val ic = { t: String -> tileIconTag("page:t/$t") }
        check("grid", mapOf(tileSepTag("separator:s1") to ic("b")))
        // Row 1 = a b | c d e (5 tiles), row 2 = f g h i j.
        val a = listOf("a", "b", "c", "d", "e").map { bounds(ic(it)).left.value }
        val b = listOf("f", "g", "h", "i", "j").map { bounds(ic(it)).left.value }
        for (k in a.indices) assertEquals("grid: icon $k of the separated row moved off its column", b[k], a[k], 0.5f)
    }

    @Test
    fun `S3 no screen draws a separator as a width-taking glyph any more`() {
        val src = File(System.getProperty("user.dir"), "src/main/java")
        val hits = src.walkTopDown().filter { it.extension == "kt" && it.readText().contains("TileSeparator") }.map { it.name }.toList()
        assertFalse("the old TileSeparator glyph is still used in $hits", hits.isNotEmpty())
    }
}
