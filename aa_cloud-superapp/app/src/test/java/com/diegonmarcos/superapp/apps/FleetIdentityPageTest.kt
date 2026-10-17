package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.appstore.FleetIdentity
import com.diegonmarcos.superapp.appstore.FleetIdentityFragment
import com.diegonmarcos.superapp.appstore.FleetIdentityPage
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Configs > Network > Apps Mesh > Identity (libs:appstore FleetIdentityPage):
 *
 *   I1 the baked roster is every fleet app of data/ipc-mesh.json, and the page draws a row for each
 *   I2 the shared terminal group (data/fleet-identity.json) is drawn, with cld.termux and cld.termux.nix
 *      in it, and their rows carry the group's sharedUserId
 *   I3 a live uid that disagrees with the declaration is flagged; one that agrees is not
 *   I4 Identity is the Apps Mesh tab beside IPC and builds its host
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetIdentityPageTest : KitPageHarness() {

    private fun declaredPkgs(): Set<String> {
        val m = JSONObject(File("../data/ipc-mesh.json").readText()).getJSONArray("members")
        return (0 until m.length()).map { m.getJSONObject(it).getString("pkg") }.toSet()
    }

    @Test
    fun `I1 the Identity tab renders a row per fleet app`() {
        val reg = FleetIdentity.baked()
        assertEquals(declaredPkgs(), reg.members.map { it.pkg }.toSet())
        compose.setContent { FleetIdentityPage(registry = reg, live = emptyMap()) }
        compose.waitForIdle()
        for (r in FleetIdentity.rows(reg, emptyMap())) compose.onNodeWithTag("kit:listrow:" + r.tag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I2 the shared terminal group is shown`() {
        val reg = FleetIdentity.baked()
        val g = reg.groups.firstOrNull { it.id == "terminal" }
        assertNotNull("data/fleet-identity.json declares the terminal group", g)
        assertEquals(setOf("cld.termux", "cld.termux.nix"), g!!.members.toSet())
        assertNotNull("the terminal group's sharedUserId resolved at bake time", g.sharedUserId)
        val rows = FleetIdentity.rows(reg, emptyMap()).associateBy { it.member.pkg }
        assertEquals(g.sharedUserId, rows.getValue("cld.termux").declared)
        assertEquals(listOf("cld.termux.nix"), rows.getValue("cld.termux").sharesWith)
        assertTrue("no app outside the group is shared", rows.values.filter { it.group == null }.all { it.declared == FleetIdentity.OWN })
        compose.setContent { FleetIdentityPage(registry = reg, live = emptyMap()) }
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_GROUP + "terminal", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I3 a live uid that disagrees is flagged`() {
        val g = FleetIdentity.Group("terminal", "Terminals", "cld.shared", listOf("a.one", "a.two"))
        val reg = FleetIdentity.Registry(listOf(g),
            listOf(FleetIdentity.Member("one", "a.one", "One"), FleetIdentity.Member("two", "a.two", "Two"), FleetIdentity.Member("three", "a.three", "Three")))
        val good = mapOf("a.one" to FleetIdentity.Seen(10100, "cld.shared", "ABCD1234"),
                         "a.two" to FleetIdentity.Seen(10100, "cld.shared", "ABCD1234"),
                         "a.three" to FleetIdentity.Seen(10101, null, "ABCD1234"))
        assertTrue(FleetIdentity.rows(reg, good).all { it.problem == null })
        val split = good + ("a.two" to FleetIdentity.Seen(10102, "cld.shared", null))
        assertNotNull(FleetIdentity.rows(reg, split).first { it.member.pkg == "a.one" }.problem)
        val stray = good + ("a.three" to FleetIdentity.Seen(10100, "cld.shared", null))
        assertNotNull(FleetIdentity.rows(reg, stray).first { it.member.pkg == "a.three" }.problem)
        assertNull(FleetIdentity.rows(reg, emptyMap()).first().problem)
        compose.setContent { FleetIdentityPage(registry = reg, live = stray) }
        compose.waitForIdle()
        compose.onNodeWithTag("kit:listrow:" + FleetIdentity.TAG_ROW + "three", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I4 Identity is the Apps Mesh tab beside IPC and builds its host`() {
        val tabs = Sections.byId("config")!!.pages.first { it.id == "apps-mesh" }.tabs
        assertEquals(tabs.indexOf("ipc") + 1, tabs.indexOf("identity"))
        val page = SectionPages.pagesFor("config", includeHidden = true).first { it.id == "identity" }
        assertEquals("Identity", page.label)
        val f = page.factory()
        assertTrue("Apps Mesh > Identity builds ${f::class.java}", f is FleetIdentityFragment)
        show(f)
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }
}
