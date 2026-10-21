package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1075 — Configs and Cloud ▸ Apps are ONE design. They draw the same components with the same size tokens, so a change to
 * the Apps tile changes Configs with it:
 *
 *   D1 source: both pages call TileStrip (-> TileCell), GroupTitle and TILE_PAGE_PADDING; Configs holds no tile code or
 *      size literal of its own, and the separate compact tile is not used by it
 *   D2 drawn: a Configs tile and an Apps tile measure the same (cell width, glyph size, cell height)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
class ConfigsSharesAppsDesignTest : KitPageHarness() {

    private val src = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/launcher")

    private fun code(name: String) = File(src, name).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)//.*$"), "")

    @Test
    fun `D1 both pages are built from the same shared components and tokens`() {
        val configs = code("ConfigsFragment.kt")
        val apps = code("GroupedTilesFragment.kt")
        for (shared in listOf("TileStrip(", "GroupTitle(", "TILE_PAGE_PADDING")) {
            assertTrue("Configs does not use $shared", configs.contains(shared))
            assertTrue("Cloud > Apps does not use $shared", apps.contains(shared))
        }
        assertTrue("TileStrip draws TileCell", Regex("TileCell\\(tile").containsMatchIn(apps))
        assertTrue("Configs draws a tile of its own", !Regex("CompactTile|TileCell\\(|Icon\\(|Text\\(").containsMatchIn(configs))
        assertTrue("Configs states a size of its own", !Regex("\\d\\s*\\.(dp|sp)\\b").containsMatchIn(configs))
        assertTrue("the Apps cell width is one token", Regex("TILE_CELL_WIDTH").findAll(apps).count() >= 3)
    }

    @Test
    fun `D2 a Configs tile and an Apps tile are the same size`() {
        val tiles = ConfigsPageFixture.tiles(compose.activity)
        val cfgId = tiles.first { !it.id.startsWith(TileGridFragment.SEPARATOR_PREFIX) }.id
        show(ConfigsFragment.newInstance(tiles))
        val cfg = compose.onNodeWithTag(GroupedTilesFragment.tileTag(cfgId), useUnmergedTree = true).getBoundsInRoot()
        val cfgIcon = compose.onNodeWithTag(tileIconTag(cfgId), useUnmergedTree = true).getBoundsInRoot()

        compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(
            compose.activity.supportFragmentManager.fragments.first()).commitNow() }
        show(GroupedTilesFragment.newInstance("cloud"))
        compose.waitForIdle()
        val first = compose.onAllNodesWithTag(GroupedTilesFragment.TAG_LIST).fetchSemanticsNodes().isNotEmpty()
        assertTrue("Cloud > Apps drew", first)
        val appTile = Sections.byId("cloud")!!.tileGroups.first { g -> g.tiles.any { !it.separator && it.children.isEmpty() } }
            .tiles.first { !it.separator && it.children.isEmpty() }
        val app = compose.onNodeWithTag(GroupedTilesFragment.tileTag(appTile.target), useUnmergedTree = true).getBoundsInRoot()
        val appIcon = compose.onNodeWithTag(tileIconTag(appTile.target), useUnmergedTree = true).getBoundsInRoot()

        assertEquals("cell width", app.width.value, cfg.width.value, 0.5f)
        assertEquals("glyph width", appIcon.width.value, cfgIcon.width.value, 0.5f)
        assertEquals("glyph height", appIcon.height.value, cfgIcon.height.value, 0.5f)
        assertEquals("cell width is the token", TILE_CELL_WIDTH.value, cfg.width.value, 0.5f)
    }
}
