package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import com.diegonmarcos.superapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #877 The declaration this build baked (build.json::ui.mesh_page) is whole and every control is wired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshDeclTest {
    private val d = MeshDecl.baked
    private val ids = MeshStore(FakePort(), d, FakeHost()).engineIds

    @Test fun `the strip has the six pages, the default one among them`() {
        assertEquals(listOf("status", "peers", "profiles", "routes", "controls", "log"), d.pages.map { it.id })
        assertTrue(d.page(d.defaultPage) != null)
        assertEquals(d.defaultPage, d.startPage.id)
    }

    @Test fun `three tabs - Status, Profile, Logs - cover every section exactly once`() {
        assertEquals(listOf("status", "profile", "logs"), d.tabs.map { it.id })
        assertEquals("status", d.startTab.id)
        assertEquals(listOf("status", "routes", "peers"), d.tab("status")!!.sections)
        assertEquals("the profile table comes before every control", listOf("profiles", "controls"), d.tab("profile")!!.sections)
        assertEquals(listOf("log"), d.tab("logs")!!.sections)
        val placed = d.tabs.flatMap { it.sections }
        assertEquals("a section sits in one tab only", placed.size, placed.toSet().size)
        assertEquals("no section is left without a tab", d.pages.map { it.id }.toSet(), placed.toSet())
        for (id in placed) assertTrue("tab names undeclared section $id", d.page(id) != null)
    }

    @Test fun `the guard - every control the page had before is still declared and reachable from a tab`() {
        assertTrue("the guard list is empty", d.previousControls.size >= 37)
        val declared = d.pages.flatMap { it.controls }.map { it.id }.toSet()
        assertEquals("previous controls that are no longer declared", emptyList<String>(), d.previousControls.filter { it !in declared })
        assertEquals("previous controls no tab reaches", emptyList<String>(), d.previousControls.filter { it !in d.reachableControls })
        // every former Controls-page control sits in the Profile tab, below the profile table
        for (c in d.page("controls")!!.controls) assertTrue("${c.id} is not on the Profile tab", c.id in d.tab("profile")!!.sections.flatMap { s -> d.page(s)!!.controls.map { it.id } })
    }

    @Test fun `the guard names the exact controls the page shipped with`() {
        assertEquals(
            listOf("peer_sort", "topology", "peer_add", "peer_remove", "peer_copy"),
            d.previousControls.take(5),
        )
        for (id in listOf("connect", "reconnect", "auto_boot", "auto_wifi", "auto_mobile", "kill_switch", "vpn_settings", "dns_preset", "mtu", "keepalive",
            "tunnel_name", "address", "listen_port", "private_key", "generate_key", "transport_mode", "relay_key", "test_fallbacks", "log_copy", "log_clear", "excluded_apps", "route_filter"))
            assertTrue("$id missing from the guard", id in d.previousControls)
    }

    @Test fun `cloudflare is declared - warp ready, one waiting on the zero trust apply`() {
        val cf = d.cloudflare
        assertEquals("engage.cloudflareclient.com:2408", cf.warp.endpoint)
        assertEquals("diegonmarcos", cf.one.team); assertEquals("diegonmarcos.cloudflareaccess.com", cf.one.loginHost)
        assertTrue("One stays off until #994 is applied", !cf.one.enabled); assertEquals("#994", cf.one.waitingOn)
        for (id in listOf("cf_warp_register", "cf_warp_forget", "cf_one_enrol")) assertTrue(id, d.control(id) != null)
    }

    @Test fun `a control acts through a real engine call or says why it cannot`() {
        for (c in d.pages.flatMap { it.controls }) {
            assertTrue("${c.id} declares neither an engine call nor an unsupported reason", c.engine.isNotBlank() || c.unsupported.isNotBlank())
            if (c.engine.isNotBlank()) assertTrue("${c.id} names engine call ${c.engine}, which the store does not dispatch", c.engine in ids)
        }
    }

    @Test fun `no dispatch entry is orphaned`() {
        val used = d.pages.flatMap { it.controls }.map { it.engine }.toSet()
        assertEquals("engine calls no control names: ${ids - used}", emptySet<String>(), ids - used)
    }

    @Test fun `the unsupported controls are the four the engine cannot honour`() {
        assertEquals(setOf("auto_boot", "auto_wifi", "auto_mobile", "kill_switch"), d.pages.flatMap { it.controls }.filter { !it.honoured }.map { it.id }.toSet())
    }

    @Test fun `defaults resolve from their seeds and stay in range`() {
        val seeds = MeshDecl.seeds()
        val mtu = d.control("mtu")!!
        assertEquals(BuildConfig.UI_WG_INTERFACE_MTU, MeshDecl.defaultOf(mtu, seeds))
        assertTrue(MeshDecl.defaultOf(mtu, seeds).toLong() in mtu.min!!..mtu.max!!)
        assertEquals(BuildConfig.UI_WG_TUNNEL_NAME, MeshDecl.defaultOf(d.control("tunnel_name")!!, seeds))
        assertTrue(MeshDecl.defaultOf(d.control("dns_preset")!!, seeds).isNotBlank())
        val ka = d.control("keepalive")!!
        assertTrue(MeshDecl.defaultOf(ka, seeds).toLong() in ka.min!!..ka.max!!)
        for (c in d.pages.flatMap { it.controls }.filter { it.kind == "choice" && it.choicesFrom.isBlank() })
            assertTrue("${c.id}'s default is not one of its choices", c.default in c.choices)
    }

    @Test fun `every status row is declared once and the thresholds are ordered`() {
        val rows = d.page("status")!!.rows
        assertEquals(rows.size, rows.toSet().size)
        assertTrue(d.handshakeFreshS in 1 until d.handshakeStaleS)
        assertTrue(d.pollMs >= 250)
    }

    @Test fun `parse reads a hand-written declaration`() {
        val p = MeshDecl.parse("""{"default_page":"b","poll_ms":10,"pages":[{"id":"a","label":"A"},{"id":"b","controls":[{"id":"x","kind":"number","min":1,"max":9,"default":5,"engine":"e"}]}]}""")
        assertEquals("b", p.startPage.id); assertEquals(250L, p.pollMs)
        assertEquals("5", p.control("x")!!.default); assertEquals(9L, p.control("x")!!.max)
    }
}
