package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.StoreSemantics
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #728 Store ▸ Mesh, asserted on what [StoreMesh.Mesh] DRAWS for the fleet
 * manifest this build baked, under a phone state the test invents.
 *
 * Every red assertion has a paired control on the same data: the same link
 * with its engine installed must draw green and carry no fix. An assertion
 * that is also true of the healthy phone would catch nothing, and the pair is
 * what proves it discriminates. No member, engine or package is named here —
 * all of them are read off the baked manifest, and the derivation test adds a
 * member the manifest does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreMeshTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64
    private val fleetJson = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
    private val fleet = Fleet.parse(b64)
    private val links = StoreMesh.links(fleetJson)

    private fun live(
        installed: Collection<String>, contracts: Map<String, Int> = emptyMap(),
    ) = StoreMesh.Live(installed.associateWith { "1.0" }, emptyMap(), emptySet(), contracts, emptyMap(), emptySet())

    /** Draws the mesh and returns the nodes it composed, by test tag. */
    private fun draw(json: JSONObject, fleet: List<Fleet.App>, live: StoreMesh.Live?): List<SemanticsNode> {
        showKit { Column { StoreMesh.Mesh(json, fleet, StoreMesh.links(json), live, onOpen = {}) } }
        return compose.onAllNodes(tagStartsWith("store-mesh-")).fetchSemanticsNodes()
    }

    private fun tags(nodes: List<SemanticsNode>) = nodes.mapNotNull { it.tag() }

    private fun nodeIds(nodes: List<SemanticsNode>) = tags(nodes).filter { it.startsWith(StoreMesh.TAG_NODE) }
        .map { it.removePrefix(StoreMesh.TAG_NODE) }

    private fun linkRows(nodes: List<SemanticsNode>, l: StoreMesh.Link) = nodes.filter {
        it.tag()?.startsWith("${StoreMesh.TAG_LINK}${l.from}>${l.engine}:") == true
    }

    private fun manifestIds(json: JSONObject): List<String> {
        val apps = json.getJSONArray("apps")
        return (0 until apps.length()).map { apps.getJSONObject(it).getString("id") }
    }

    @Test
    fun `every fleet manifest member is drawn exactly once, probing or probed`() {
        val ids = manifestIds(fleetJson)
        assertTrue("the baked manifest has no apps — nothing below would verify anything", ids.size > 1)
        for (live in listOf(null, live(installed = ids))) {
            val drawn = nodeIds(draw(fleetJson, fleet, live))
            assertEquals("nodes drawn (live=${live != null}) differ from the manifest",
                ids.sorted(), drawn.sorted())
        }
    }

    @Test
    fun `the manifest carries the apps' declared engine links`() {
        assertTrue("regen.sh carried no engines onto any fleet row — the mesh would draw no links", links.isNotEmpty())
        val ids = manifestIds(fleetJson).toSet()
        for (l in links) assertTrue("link ${l.from} -> ${l.engine} points at no fleet member", l.engine in ids)
    }

    @Test
    fun `an installed app whose engine is missing shows the red link with the fix, and a present engine does not`() {
        val everyone = manifestIds(fleetJson)
        for (l in links) {
            val engineLabel = fleet.first { it.id == l.engine }.label
            // the defect: the app is installed, its engine is not
            val broken = draw(fleetJson, fleet, live(everyone - l.engine))
            val red = linkRows(broken, l)
            assertTrue("${l.from} -> ${l.engine}: no link row drawn", red.isNotEmpty())
            assertTrue("${l.from} -> ${l.engine} with the engine missing is not flagged ENGINE_MISSING: ${red.map { it.tag() }}",
                red.all { it.tag()!!.endsWith(":ENGINE_MISSING") })
            assertTrue("the broken link does not name its fix: ${red.map { it.text() }}",
                red.all { it.text().contains("install $engineLabel from the Store") })
            assertTrue("the broken link is not drawn red",
                red.all { it.config.getOrNull(StoreSemantics.Ink) == 0xFFF56565.toInt() })

            // the control: same data, engine installed at the contract the app needs
            val healthy = draw(fleetJson, fleet, live(everyone, mapOf(l.key to l.minContract)))
            val green = linkRows(healthy, l)
            assertTrue("${l.from} -> ${l.engine} with its engine present is not OK: ${green.map { it.tag() }}",
                green.isNotEmpty() && green.all { it.tag()!!.endsWith(":OK") })
            assertFalse("a healthy link still names a fix", green.any { it.text().contains("from the Store") })
        }
    }

    @Test
    fun `an engine older than the app's contract is red with update, an absent app is not red`() {
        val everyone = manifestIds(fleetJson)
        for (l in links) {
            val old = linkRows(draw(fleetJson, fleet, live(everyone, mapOf(l.key to l.minContract - 1))), l)
            assertTrue("${l.from} -> ${l.engine} below min_contract is not ENGINE_OLD: ${old.map { it.tag() }}",
                old.isNotEmpty() && old.all { it.tag()!!.endsWith(":ENGINE_OLD") && it.text().contains("update ") })
            // the app itself is not on the phone: nothing binds, nothing is broken
            val absent = draw(fleetJson, fleet, live(everyone - l.from - l.engine))
            assertFalse("a link from an uninstalled app is flagged broken",
                tags(absent).any { it.startsWith("${StoreMesh.TAG_LINK}${l.from}>") &&
                    (it.endsWith(":ENGINE_MISSING") || it.endsWith(":ENGINE_OLD")) })
        }
    }

    @Test
    fun `a member and a binding the manifest does not have are drawn from data alone`() {
        // Copy the baked manifest and add an app in NO group that binds an
        // engine row that is also new. A hand list would draw neither.
        val json = JSONObject(fleetJson.toString())
        val template = json.getJSONArray("apps").getJSONObject(0)
        fun row(id: String) = JSONObject(template.toString()).put("id", id).put("label", "label-$id")
            .put("package", "invented.$id").put("group", "none").apply { remove("engines") }
        val app = row("zz-mesh-app").put("engines", JSONArray().put(JSONObject()
            .put("binding", "zz").put("fleet", "zz-mesh-engine").put("action", "{package}.ENGINE").put("min_contract", 1)))
        json.getJSONArray("apps").put(app).put(row("zz-mesh-engine").put("kind", "lib"))
        val fleet2 = Fleet.parse(java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray()))
        val root = draw(json, fleet2, live(manifestIds(json) - "zz-mesh-engine"))
        assertTrue("the invented members were not drawn", nodeIds(root).containsAll(listOf("zz-mesh-app", "zz-mesh-engine")))
        assertTrue("the invented app's missing engine is not the red link",
            tags(root).contains("${StoreMesh.TAG_LINK}zz-mesh-app>zz-mesh-engine:ENGINE_MISSING"))
    }
}
