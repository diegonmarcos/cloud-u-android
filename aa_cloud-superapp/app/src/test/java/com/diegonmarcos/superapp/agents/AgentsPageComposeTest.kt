package com.diegonmarcos.superapp.agents

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Configs ▸ Apps ▸ Agents.
 *
 *   G1 the baked registry is data/fleet-agents.json, every agent present
 *   G2 Configs: Agents is in the Apps group, after Peer Control and before About, over two tabs
 *   G3 SuperApp agents shows only this app's rows; Fleet agents shows every app's
 *   G4 a tap opens the details with the Open action
 *   G5 empty and error states
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentsPageComposeTest : KitPageHarness() {

    private fun committedIds(): List<String> {
        val arr = JSONObject(File("../data/fleet-agents.json").readText()).getJSONArray("agents")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    @Test
    fun `G1 the baked registry carries every declared agent`() {
        val baked = FleetAgents.all().map { it.id }
        assertEquals(committedIds(), baked)
        for (id in listOf("cloud-drive/screenshots", "cloud-tasks/screenshot-events", "cloud-code/claude",
                "cloud-code/hermes", "cloud-code/goose", "cloud-code/deepseek", "cloud-code/openclaw",
                "cloud-superapp/auto-update"))
            assertTrue("$id missing", id in baked)
    }

    @Test
    fun `G2 Agents sits in Apps before About and owns two tabs`() {
        val section = Sections.byId("config")!!
        val ids = section.pages.map { it.id }
        assertTrue(ids.indexOf("kde") < ids.indexOf("agents"))
        assertTrue(ids.indexOf("agents") < ids.indexOf("about"))
        val agents = section.allPages.first { it.id == "agents" }
        assertEquals("Apps", agents.group)
        assertEquals(listOf("agents-superapp", "agents-fleet"), Sections.tabPagesOf("config", agents).map { it.id })
    }

    @Test
    fun `G3 each tab shows its own scope`() {
        val all = FleetAgents.all()
        val mine = FleetAgents.forScope(all, AgentsFragment.SCOPE_SUPERAPP)
        assertTrue(mine.isNotEmpty() && mine.all { it.isSelf })
        assertEquals(all.size, FleetAgents.forScope(all, AgentsFragment.SCOPE_FLEET).size)

        show(AgentsFragment.newInstance(AgentsFragment.SCOPE_SUPERAPP))
        compose.onNodeWithTag("agents:list").assertExists()
        compose.onNodeWithText(mine.first().name).assertExists()
    }

    @Test
    fun `G4 a tap shows the details and the Open action`() {
        val f = show(AgentsFragment.newInstance(AgentsFragment.SCOPE_FLEET))
        val first = FleetAgents.all().first()
        compose.onNodeWithText(first.name).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agent:details:${first.id}").assertExists()
        compose.onNodeWithTag("agent:open:${first.id}").assertExists()
        assertTrue(f.ui is AgentsFragment.Ui.Loaded)
    }

    @Test
    fun `G5 empty and error states`() {
        val empty = AgentsFragment.newInstance(AgentsFragment.SCOPE_FLEET).apply { source = { emptyList() } }
        show(empty)
        compose.onNodeWithTag("agents:empty").assertExists()

        compose.runOnUiThread { empty.source = { error("bad registry") }; empty.load() }
        compose.waitForIdle()
        compose.onNodeWithTag("agents:error").assertExists()
    }

    @Test
    fun `G6 a provider row reads as a word`() {
        assertEquals("3 pending", AgentStatusReader.providerLive(mapOf("pending" to "3", "last_scan_ms" to "1")).word)
        assertEquals("up to date", AgentStatusReader.providerLive(mapOf("pending" to "0", "last_scan_ms" to "1700000000000")).word)
        assertEquals("no runs yet", AgentStatusReader.providerLive(emptyMap()).word)
    }
}
