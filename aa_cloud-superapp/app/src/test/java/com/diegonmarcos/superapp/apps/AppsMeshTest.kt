package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppsMesh
import com.diegonmarcos.superapp.appstore.AppsMesh.GapKind
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeMesh
import com.diegonmarcos.superapp.kdeconnect.KdePeers
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #733 Apps Mesh: the missing-membership detector, the member actions and the
 * export, on the fleet manifest this build baked and the page declaration it
 * ships (assets/appstore-controls.json, merged into this app). Plus Peer
 * Control's peer list.
 *
 * Every gap is asserted as a PAIR: the defect planted on one installed app
 * must produce exactly that gap, and the same phone with the defect removed
 * must produce none for that app. No member or package is named here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppsMeshTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64
    private val fleetJson = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
    private val fleet = Fleet.parse(b64)
    private val links = StoreMesh.links(fleetJson)
    private val decl = AppsMesh.load(ctx)
    private val apps = fleet.filter { it.kind == "app" }
    private val everyone = fleet.map { it.id }

    /** A phone where every member is installed, a member, reachable, granted,
     *  sees every peer and every engine answers its contract: a full mesh. */
    private fun healthy(
        installed: Collection<String> = everyone,
        peers: Set<String> = everyone.toSet(),
        reachable: Set<String> = everyone.toSet(),
        granted: Set<String> = everyone.toSet(),
        views: Map<String, Set<String>> = everyone.associateWith { everyone.toSet() },
        contracts: Map<String, Int> = links.groupBy { it.key }.mapValues { (_, l) -> l.maxOf { it.minContract } },
    ) = StoreMesh.Live(installed.associateWith { "1.0" }, reachable.associateWith { 38090 }, peers,
        contracts, emptyMap(), granted, views)

    private fun kinds(live: StoreMesh.Live, id: String) =
        AppsMesh.gaps(decl, fleet, links, live).filter { it.app.id == id }.map { it.kind }

    @Test
    fun `the page declaration ships with the four member actions and every gap's words`() {
        val ids = decl.actions.map { it.id }
        for (a in listOf("api", "start", "stop", "open", "details"))
            assertTrue("action $a is not declared in appstore-controls.json::apps_mesh.member_actions: $ids", a in ids)
        for (k in GapKind.values()) {
            val w = decl.gapWords[k.name]
            assertTrue("gap ${k.name} has no label/fix declared", w != null && w.first.isNotBlank() && w.second.isNotBlank())
        }
        // the Store-row action exists only where a Store row does
        assertTrue(AppsMesh.actionsFor(decl, hasStore = true).any { it.id == "store" })
        assertFalse(AppsMesh.actionsFor(decl, hasStore = false).any { it.id == "store" })
        assertEquals(listOf("api", "start", "stop", "open", "details"),
            AppsMesh.actionsFor(decl, hasStore = false).map { it.id })
    }

    @Test
    fun `a healthy full mesh reports no gap at all`() {
        assertTrue("the baked manifest has no apps", apps.size > 1)
        assertEquals(emptyList<GapKind>(), AppsMesh.gaps(decl, fleet, links, healthy()).map { it.kind })
    }

    @Test
    fun `each membership defect is reported on exactly that app, with its fix, and only while it holds`() {
        for (app in apps) {
            val id = app.id
            val noProvider = kinds(healthy(peers = everyone.toSet() - id, reachable = everyone.toSet() - id), id)
            assertEquals("$id without a provider", listOf(GapKind.NO_PROVIDER), noProvider)
            assertEquals("$id asleep-for-good", listOf(GapKind.NO_DEBUG_API), kinds(healthy(reachable = everyone.toSet() - id), id))
            assertEquals("$id without the grant", listOf(GapKind.NO_PERMISSION), kinds(healthy(granted = everyone.toSet() - id), id))
            val other = everyone.first { it != id }
            val blind = AppsMesh.gaps(decl, fleet, links, healthy(views = everyone.associateWith {
                if (it == id) everyone.toSet() - other else everyone.toSet() })).filter { it.app.id == id }
            assertEquals("$id that cannot see $other", listOf(GapKind.PEER_BLIND), blind.map { it.kind })
            val otherLabel = fleet.first { it.id == other }.label
            assertTrue("PEER_BLIND does not name the member it cannot see: ${blind.single().label}",
                blind.single().label.contains(otherLabel))
            // the control: the same app on the healthy phone has nothing to fix
            assertEquals("$id healthy", emptyList<GapKind>(), kinds(healthy(), id))
            // an app that is not on this phone is never reported
            assertEquals("$id absent", emptyList<GapKind>(), kinds(healthy(installed = everyone - id, peers = everyone.toSet() - id,
                reachable = everyone.toSet() - id, granted = everyone.toSet() - id), id))
        }
    }

    @Test
    fun `a broken engine link is a gap on the app that binds it, with the Store fix`() {
        assertTrue("no engine links baked — nothing below would verify anything", links.isNotEmpty())
        for (l in links) {
            val gaps = AppsMesh.gaps(decl, fleet, links, healthy(installed = everyone - l.engine))
                .filter { it.app.id == l.from && it.kind == GapKind.ENGINE_BROKEN }
            if (fleet.first { it.id == l.from }.kind != "app") continue
            assertTrue("${l.from} -> ${l.engine} missing is not an ENGINE_BROKEN gap", gaps.isNotEmpty())
            assertTrue("the gap does not carry the Store fix: ${gaps.map { it.fix }}",
                gaps.any { it.fix.contains(fleet.first { it.id == l.engine }.label) && it.fix.contains("Store") })
            assertFalse("${l.from} healthy still reports a broken engine",
                kinds(healthy(), l.from).contains(GapKind.ENGINE_BROKEN))
        }
    }

    @Test
    fun `the export carries the gap report and every member`() {
        val id = apps.first().id
        val text = AppsMesh.report(decl, fleet, links, healthy(peers = everyone.toSet() - id, reachable = everyone.toSet() - id))
        assertTrue(text, text.contains("Missing membership (1)") && text.contains("[NO_PROVIDER]"))
        for (app in fleet) assertTrue("export misses ${app.id}", text.contains("(${app.id}, "))
        assertTrue(AppsMesh.report(decl, fleet, links, healthy()).contains("Missing membership (0)"))
    }

    @Test
    fun `a member that answers late after a wake is woken ok, and one that never answers is the gap`() {
        // #762 three healthy apps answered ~10 s after their wake; a single short
        // settle reported them as NO_DEBUG_API. The poll must keep sweeping.
        val id = apps.first().id
        var sweeps = 0
        val late = StoreMesh.awaitWoken(emptyMap(), setOf(id), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; if (sweeps >= 10) mapOf(id to 38091) else emptyMap() }, {})
        assertEquals("the member that answers on the 10th sweep was given up on", mapOf(id to 38091), late)
        assertEquals("the poll kept sweeping after the member answered", 10, sweeps)
        // control: a member that never answers stops at the bound, not forever
        sweeps = 0
        val never = StoreMesh.awaitWoken(emptyMap(), setOf(id), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; emptyMap() }, {})
        assertTrue(never.isEmpty())
        assertEquals((StoreMesh.WAKE_TIMEOUT_MS / StoreMesh.WAKE_POLL_MS).toInt(), sweeps)
        // nothing asleep: no extra sweep
        sweeps = 0
        StoreMesh.awaitWoken(mapOf(id to 38090), emptySet(), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; emptyMap() }, {})
        assertEquals(0, sweeps)

        val woke = healthy().let { StoreMesh.Live(it.installed, it.reachable, it.peers, it.contracts, it.shares,
            it.granted, it.peerViews, woken = setOf(id)) }
        assertFalse(kinds(woke, id).contains(GapKind.NO_DEBUG_API))
        val text = AppsMesh.report(decl, fleet, links, woke)
        assertTrue(text, text.contains("(1 woke ok)") && text.contains("was stopped, woke ok"))
        assertFalse(AppsMesh.report(decl, fleet, links, healthy()).contains("was stopped, woke ok"))
    }

    @Test
    fun `Peer Control lists every declared device and every mesh VM once, and remembers the choice`() {
        val devices = KdeConnectConfig.get().devices
        val nodes = KdeMesh.nodes()
        val peers = KdePeers.all()
        assertTrue("no peers at all — the selector would be empty", peers.size > 1)
        for (d in devices) assertTrue("device ${d.id} missing from the selector", peers.any { it.id == d.id })
        for (n in nodes) assertTrue("mesh node ${n.name} (${n.wgIp}) missing", peers.any { it.wgIp == n.wgIp })
        assertEquals("a wg IP is listed twice", peers.size, peers.map { it.wgIp }.toSet().size)
        val pick = peers.last()
        KdePeers.select(ctx, pick.id)
        assertEquals(pick.id, KdePeers.selected(ctx, peers)?.id)
        KdePeers.select(ctx, peers.first().id)
        assertEquals(peers.first().id, KdePeers.selected(ctx, peers)?.id)
    }
}
