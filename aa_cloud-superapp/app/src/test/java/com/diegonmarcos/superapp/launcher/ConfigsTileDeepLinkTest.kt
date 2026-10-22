package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1095 Cloud > Apps > Configs > SuperApp opens the Configs page, not Home. The tile's target is the
 * shell's own `section:config` grammar (ShellActivity.routeTarget: canonicalId -> goSection), so the
 * guard is the declared tile plus the two lookups routeTarget makes on that string: the id resolves
 * to the Configs section, and that section has pages to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigsTileDeepLinkTest {

    private fun superAppTile(): Sections.AggTile {
        val row = Sections.byId("cloud")!!.tileGroups.first { it.title == "Configs" }
        return row.tiles.first { it.id == "cloud-superapp" }
    }

    @Test
    fun `C1 the SuperApp tile in the Configs row targets the Configs section`() {
        assertEquals("section:config", superAppTile().target)
    }

    @Test
    fun `C2 the shell resolves that target to the Configs section with pages`() {
        val id = Sections.canonicalId(superAppTile().target.removePrefix("section:"))
        assertEquals("config", id)
        val section = Sections.byId(id)
        assertNotNull("the shell would open no section", section)
        assertTrue("Configs has no pages to show", section!!.pages.any { !it.hidden })
    }

    @Test
    fun `C3 no Cloud Apps tile relaunches the app at Home any more`() {
        val relaunch = Sections.byId("cloud")!!.tileGroups.flatMap { it.tiles }
            .filter { it.target.startsWith("app://com.diegonmarcos.superapp") }
        assertTrue("tiles still relaunch SuperApp at Home: ${relaunch.map { it.id }}", relaunch.isEmpty())
    }
}
