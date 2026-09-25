package com.diegonmarcos.superapp.profile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #573 — User → identities → peers, read off the per-user config artifact.
 *
 * The fixture is BUILT here from a table and every expectation is computed by
 * walking that table, so no count or name is typed twice. When the cloud-infra
 * checkout sits beside this repository, the REAL emitted artifact is parsed as
 * well and held to the same invariants (one primary identity, one primary
 * peer, every peer's profiles filed under its own id) — the derived value,
 * not a literal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserRegistryTest {

    private val identities = listOf(
        Triple("one@t.test", true, "personal"), Triple("two@t.test", false, "work"), Triple("three@t.test", false, ""),
    )
    /** id → (label, kind, primary, vault_device, wg0 ip, profile ids) */
    private val peers = linkedMapOf(
        "p-a" to listOf("Phone A", "phone", "true", "galaxy", "10.9.9.1", "v4-full,v6-split"),
        "p-b" to listOf("Phone B", "phone", "false", "second", "10.9.9.2", "v4-full,v4-split,v6-full,v6-split"),
        "p-c" to listOf("Laptop", "notebook", "false", "", "10.9.9.3", ""),
    )

    private fun artifact(): JSONObject {
        val root = JSONObject().put("_meta", JSONObject().put("user", "tester"))
        root.put("identities", JSONArray().apply {
            identities.forEach { (e, p, l) -> put(JSONObject().put("email", e).put("primary", p).put("label", l)) }
        })
        root.put("peers", JSONObject().apply {
            peers.forEach { (id, row) ->
                val wg = JSONObject()
                row[5].split(",").filter { it.isNotBlank() }.forEach { pid ->
                    wg.put(pid, JSONObject().put("name", "wg-$pid").put("config_text", "[Interface]\nPrivateKey = <PROVIDED_BY_DEVICE>\nAddress = ${row[4]}/24\n# $id/$pid\n"))
                }
                put(id, JSONObject().put("label", row[0]).put("kind", row[1]).put("primary", row[2].toBoolean())
                    .put("vault_device", row[3]).put("wg0", JSONObject().put("wg_ip", row[4])).put("wireguard", wg))
            }
        })
        root.put("auth_providers", JSONArray(listOf("authelia", "github")))
        return root
    }

    @Test fun `the registry is the artifact, walked`() {
        val reg = UserRegistry.parse(artifact())!!
        assertEquals("tester", reg.user)
        assertEquals(identities.map { it.first }, reg.identities.map { it.email })
        assertEquals(identities.single { it.second }.first, reg.primaryIdentity!!.email)
        assertEquals(peers.keys.toList(), reg.peers.map { it.id })
        assertEquals(peers.entries.single { it.value[2] == "true" }.key, reg.primaryPeer!!.id)
        peers.forEach { (id, row) ->
            val p = reg.peer(id)!!
            assertEquals(row[0], p.label); assertEquals(row[3], p.vaultDevice); assertEquals(row[4], p.wgIp)
            assertEquals(row[5].split(",").filter { it.isNotBlank() }.sorted(), p.profiles)
        }
        assertEquals(listOf("authelia", "github"), reg.authProviders)
    }

    @Test fun `a peer's profiles are its own and nobody else's`() {
        val root = artifact()
        peers.forEach { (id, row) ->
            val got = UserRegistry.peerProfiles(root, id)
            assertEquals(row[5].split(",").filter { it.isNotBlank() }.sorted(), got.keys.toList())
            got.values.forEach { conf -> assertTrue(conf.contains("# $id/")) }
        }
        assertTrue(UserRegistry.peerProfiles(root, "no-such-peer").isEmpty())
    }

    @Test fun `an artifact without a registry parses to null, not to an empty picker`() {
        assertNull(UserRegistry.parse(JSONObject().put("profile", JSONObject())))
    }

    @Test fun `remember keeps the artifact and the registry, in memory`() {
        val root = artifact()
        UserRegistry.remember(root)
        assertTrue(UserRegistry.Current.artifact === root)
        assertEquals(peers.size, UserRegistry.Current.registry!!.peers.size)
    }

    @Test fun `only ids are persisted for the picks`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("", UserRegistry.selectedPeer(ctx))
        UserRegistry.selectPeer(ctx, "p-b"); UserRegistry.selectIdentity(ctx, "two@t.test")
        assertEquals("p-b", UserRegistry.selectedPeer(ctx))
        assertEquals("two@t.test", UserRegistry.selectedIdentity(ctx))
        // The store holds the two ids and nothing else — no address, key or profile text.
        val all = ctx.getSharedPreferences("user_registry", android.content.Context.MODE_PRIVATE).all
        assertEquals(setOf("p-b", "two@t.test"), all.values.map { it.toString() }.toSet())
    }

    @Test fun `the real emitted artifact, when checked out beside this repo, holds the same invariants`() {
        val f = listOf("../../../cloud-infra", "../../cloud-infra", "../cloud-infra")
            .map { File(it, "1_cloud-configs/dist/build-cloud-superapp-diego.json") }.firstOrNull { it.isFile }
        if (f == null) { println("UNVERIFIABLE here: cloud-infra is not checked out beside this repository"); return }
        val root = JSONObject(f.readText())
        val reg = UserRegistry.parse(root) ?: error("${f.path}: the emitted artifact carries no registry")
        assertEquals(1, reg.identities.count { it.primary })
        assertEquals(1, reg.peers.count { it.primary })
        assertTrue(reg.peers.size >= 2)
        reg.peers.forEach { p ->
            // Every profile filed under a peer carries that peer's own address, so a
            // pick can never hand this device another phone's tunnel.
            UserRegistry.peerProfiles(root, p.id).values.forEach { conf ->
                if (p.wgIp.isNotBlank()) assertTrue("${p.id}: profile without its address", conf.contains(p.wgIp))
            }
        }
        // The top-level wireguard block is the PRIMARY peer's set.
        assertEquals(reg.primaryPeer!!.profiles, root.getJSONObject("wireguard").keys().asSequence().sorted().toList())
    }
}
