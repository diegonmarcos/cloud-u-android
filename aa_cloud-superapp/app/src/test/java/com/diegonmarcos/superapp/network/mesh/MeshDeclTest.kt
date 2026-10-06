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
