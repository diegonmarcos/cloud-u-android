package com.diegonmarcos.superapp.profile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.network.WireGuardPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #570 — Configs ▸ Profile ▸ Fleet: the vault bundle as the fleet configurator.
 *
 * Every expectation is computed from the fixture by a walk of its own, or read
 * back from the store an apply wrote — never typed twice. The device is chosen,
 * and its mesh identity derives from the vault: a profile is the device's when
 * the profile's own Address line carries the device's declared wg address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultCockpitTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `devices are the electronics entries that carry a wg_peer, pending ones excluded`() {
        val b = bundle()
        val got = VaultCockpit.devices(b)
        // Independent walk: every group → entry with a non-pending wg_peer.wg_ip.
        val want = mutableListOf<Pair<String, String>>()
        val e = b.getJSONObject("electronics")
        e.keys().forEach { g ->
            val group = e.optJSONObject(g) ?: return@forEach
            group.keys().forEach { id ->
                val peer = group.optJSONObject(id)?.optJSONObject("wg_peer") ?: return@forEach
                if (!peer.optBoolean("pending") && peer.optString("wg_ip").isNotBlank())
                    want += id to peer.getString("wg_ip")
            }
        }
        assertTrue("fixture must have a pending group", e.has("watches"))
        assertEquals(want.toSet(), got.map { it.id to it.wgIp }.toSet())
        assertEquals("termux-galaxy", got.first { it.id == "galaxy" }.label)
    }

    @Test fun `a mesh profile belongs to the device whose declared address it carries`() {
        val b = bundle()
        val devices = VaultCockpit.devices(b).associateBy { it.id }
        val galaxy = VaultCockpit.meshProfiles(b, devices.getValue("galaxy"))
        val surface = VaultCockpit.meshProfiles(b, devices.getValue("surface"))
        assertEquals(setOf("config-v4-split", "config-v6-full"), galaxy.keys)
        assertTrue("the surface has no profile in this export", surface.isEmpty())
        // Ownership came from the Address line, not the file name.
        galaxy.values.forEach { conf ->
            assertTrue(conf.lineSequence().any { it.startsWith("Address") && it.contains(devices.getValue("galaxy").wgIp) })
        }
    }

    @Test fun `applying a profile writes the tunnel through the WireGuard parser, and then it matches`() {
        val b = bundle()
        val galaxy = VaultCockpit.devices(b).first { it.id == "galaxy" }
        val prefs = WireGuardPrefs(ctx)
        val before = VaultCockpit.meshRows(b, galaxy, VaultCockpit.tunnelState(prefs))
        assertTrue(before.isNotEmpty())
        assertTrue("nothing applied yet", before.all { it.state == VaultCockpit.State.DIFFERS })

        val (name, conf) = VaultCockpit.meshProfiles(b, galaxy).entries.first()
        val line = VaultCockpit.applyMesh(prefs, name, conf)
        assertTrue(line, line.startsWith("✓"))

        // Read back from the store, compare with what the parser derives from the
        // conf's own Address line (the JVM spells IPv6 long-form; both sides go
        // through the same parser, so the comparison is about the value, not spelling).
        val parsed = com.wireguard.config.Config.parse(java.io.BufferedReader(java.io.StringReader(conf)))
        assertEquals(parsed.getInterface().addresses.joinToString(", "), prefs.interfaceAddress)
        assertTrue(prefs.interfaceAddress.contains("10.0.0.9/24"))
        val wantKeys = conf.lineSequence().filter { it.startsWith("PublicKey") }.map { it.substringAfter("=").trim() }.toSet()
        assertEquals(wantKeys, prefs.peers().map { it.publicKey }.toSet())
        assertEquals(WireGuardPrefs.PROVIDER_CUSTOM, prefs.configProvider)

        val after = VaultCockpit.meshRows(b, galaxy, VaultCockpit.tunnelState(prefs))
        assertEquals(VaultCockpit.State.MATCH, after.first { it.label == name }.state)
        assertTrue(after.filter { it.label != name }.all { it.state == VaultCockpit.State.DIFFERS })
    }

    @Test fun `a conf the parser rejects writes nothing`() {
        val prefs = WireGuardPrefs(ctx)
        val addressBefore = prefs.interfaceAddress
        val line = VaultCockpit.applyMesh(prefs, "broken", "[Interface]\nAddress = not-an-address\n")
        assertTrue(line, line.startsWith("✗"))
        assertEquals(addressBefore, prefs.interfaceAddress)
    }

    @Test fun `the mail account is found by the local part and its password by pass_env`() {
        val b = bundle()
        val d = VaultCockpit.mailDeclared(b, "me@example.org")!!
        val accounts = b.getJSONObject("mail").getJSONObject("accounts")
        val key = accounts.keys().asSequence().first { accounts.getJSONObject(it).getString("name") == "me" }
        assertEquals(key, d.account)
        assertEquals(b.getJSONObject("mail").getJSONObject("passwords").getString(accounts.getJSONObject(key).getString("pass_env")), d.password)
        assertEquals(b.getJSONObject("mail").getJSONObject("endpoints").getString("domain"), d.domain)
        assertNull("no such account", VaultCockpit.mailDeclared(b, "nobody@example.org"))
        assertNull("a pending password is no password", VaultCockpit.mailDeclared(b, "yo@example.org")!!.password)
    }

    @Test fun `ai rows map each vault token to the device provider the layout names`() {
        val b = bundle()
        val layout = VaultCockpit.parseLayout(JSONObject("""{"sections":[],"ai_tokens":{"openrouter_my_ai_api":"openrouter"}}"""))
        val token = b.getJSONObject("ai").getJSONObject("tokens").getString("openrouter_my_ai_api")
        val present = VaultCockpit.aiState("""{"providers":[{"id":"openrouter","key_present":true,"key_hint":"${token.takeLast(4)}"}]}""")
        val rows = VaultCockpit.aiRows(b, layout, present, "down", "unmapped").associateBy { it.label }
        assertEquals(VaultCockpit.State.MATCH, rows.getValue("openrouter_my_ai_api").state)
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("openrouter_3").state)
        assertEquals("unmapped", rows.getValue("openrouter_hermes_agent").device)
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("claude").state)

        val other = VaultCockpit.aiState("""{"providers":[{"id":"openrouter","key_present":true,"key_hint":"zzzz"}]}""")
        assertEquals(VaultCockpit.State.DIFFERS, VaultCockpit.aiRows(b, layout, other, "down", "u").first { it.label == "openrouter_my_ai_api" }.state)
        assertEquals("down", VaultCockpit.aiRows(b, layout, null, "down", "u").first { it.label == "openrouter_my_ai_api" }.device)
        // Every row carries a value length, never the token itself.
        VaultCockpit.aiRows(b, layout, present, "d", "u").forEach { assertTrue(it.declared, !it.declared.contains(token)) }
    }

    @Test fun `the declared app set merges fleet packages and an inventory, pending contributes nothing`() {
        val b = bundle()
        val galaxy = VaultCockpit.devices(b).first { it.id == "galaxy" }
        val fleet = setOf("com.x.a")
        val got = VaultCockpit.appsDeclared(b, galaxy, fleet)
        // Independent walk: every "package" under apps.devices.galaxy.
        val want = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(b.getJSONObject("apps").getJSONObject("devices").getJSONObject("galaxy").toString())
            .map { it.groupValues[1] }.toSet()
        assertTrue(want.size >= 3)
        assertEquals(want, got.map { it.pkg }.toSet())
        assertTrue(got.first { it.pkg == "com.x.a" }.ours)
        assertTrue(!got.first { it.pkg == "com.x.b" }.ours)
        assertTrue(VaultCockpit.appsDeclared(b, VaultCockpit.devices(b).first { it.id == "surface" }, fleet).isEmpty())
    }

    @Test fun `keyboard rows show a pending list as pending and an exported one by size`() {
        val rows = VaultCockpit.keyboardRows(bundle(), "kb").associateBy { it.label }
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("personal_data").state)
        assertTrue(rows.getValue("personal_data").declared.contains("pending-phone-export"))
        assertEquals("2 entries", rows.getValue("urls_pub").declared)
        assertTrue(rows.values.all { it.device == "kb" })
    }

    @Test fun `a schema version this build does not know is refused`() {
        val known = setOf(1)
        assertNull(VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{"schema_version":1}}"""), known))
        assertEquals(2, VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{"schema_version":2}}"""), known))
        assertEquals(0, VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{}}"""), known))
        assertTrue("the baked list is non-empty", VaultConnect.knownSchemaVersions.isNotEmpty())
    }

    @Test fun `the baked layout has sections, each fed by at least one vault section`() {
        val layout = VaultCockpit.layout
        assertTrue(layout.sections.isNotEmpty())
        layout.sections.forEach { s ->
            assertTrue(s.id, s.vault.isNotEmpty())
            assertTrue(s.id, s.label.isNotBlank())
        }
        assertEquals(layout.sections.size, layout.sections.map { it.id }.distinct().size)
        assertTrue(VaultCockpit.consumed(layout).containsAll(layout.sections.flatMap { it.vault }))
    }

    @Test fun `the chosen device is the only thing stored`() {
        VaultCockpit.selectDevice(ctx, "galaxy")
        assertEquals("galaxy", VaultCockpit.selectedDevice(ctx))
        val all = ctx.getSharedPreferences("vault_cockpit", Context.MODE_PRIVATE).all
        assertEquals(mapOf("device_id" to "galaxy"), all)
    }

    // ── fixture: the bundle shape configs/emit.py produces (schema v1 + apps) ──

    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val peerA = "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc="
    private val peerB = "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc="

    private fun conf(address: String, vararg peers: String) = buildString {
        appendLine("# fixture"); appendLine("[Interface]")
        appendLine("PrivateKey = $zeroKey"); appendLine("Address = $address"); appendLine("DNS = 10.0.0.1")
        peers.forEach { appendLine(); appendLine("[Peer]"); appendLine("PublicKey = $it"); appendLine("Endpoint = 1.2.3.4:51820"); appendLine("AllowedIPs = 10.0.0.0/24") }
    }

    private fun bundle() = JSONObject().apply {
        put("schema_version", 1)
        put("mesh", JSONObject().put("profiles", JSONObject()
            .put("config-v4-split", conf("10.0.0.9/24, fd0c:1d00::9/64", peerA, peerB))
            .put("config-v6-full", conf("fd0c:1d00::9/64", peerA))
            .put("config-other", conf("10.0.0.77/24", peerA)))
            .put("public_key", "PUB"))
        put("mail", JSONObject()
            .put("endpoints", JSONObject().put("domain", "jmap.example.org"))
            .put("accounts", JSONObject()
                .put("admin", JSONObject().put("name", "me").put("pass_env", "ME_PASSWORD"))
                .put("yo", JSONObject().put("name", "yo").put("pass_env", "YO_PASSWORD")))
            .put("passwords", JSONObject().put("ME_PASSWORD", "pw-one")
                .put("YO_PASSWORD", JSONObject().put("pending", true).put("source", "x").put("reason", "y"))))
        put("ai", JSONObject().put("tokens", JSONObject()
            .put("openrouter_my_ai_api", "sk-or-abcd1234")
            .put("openrouter_hermes_agent", "sk-or-efgh5678")
            .put("openrouter_3", JSONObject().put("pending", true).put("source", "no-store").put("reason", "r"))
            .put("claude", JSONObject().put("plan", "subscription").put("token", JSONObject.NULL))))
        put("git", JSONObject().put("github_token", "ghp_x").put("ssh_private_key", "KEY").put("repos", org.json.JSONArray(listOf("a", "b"))))
        put("autocomplete", JSONObject()
            .put("personal_data", JSONObject().put("pending", true).put("source", "pending-phone-export").put("reason", "Personal Data"))
            .put("urls_pub", org.json.JSONArray(listOf("https://a", "https://b"))))
        put("electronics", JSONObject()
            .put("computers", JSONObject().put("surface", JSONObject()
                .put("type", "notebook")
                .put("wg_peer", JSONObject().put("wg_ip", "10.0.0.5").put("wg_ipv6", "fd0c:1d00::5").put("name", "desktop-nixos"))
                .put("mac", JSONObject().put("pending", true).put("source", "o").put("reason", "r"))))
            .put("phones", JSONObject().put("galaxy", JSONObject()
                .put("type", "phone")
                .put("wg_peer", JSONObject().put("wg_ip", "10.0.0.9").put("wg_ipv6", "fd0c:1d00::9").put("name", "termux-galaxy"))))
            .put("watches", JSONObject().put("pending", true).put("source", "o").put("reason", "r"))
            .put("kde_connect", org.json.JSONArray()))
        put("apps", JSONObject().put("devices", JSONObject().put("galaxy", JSONObject()
            .put("fleet", org.json.JSONArray().put(JSONObject().put("package", "com.x.a").put("label", "A")).put(JSONObject().put("package", "com.x.b")))
            .put("foreign", JSONObject().put("pending", true).put("source", "pending-phone-export").put("reason", "r"))
            .put("inventory", JSONObject().put("kind", "cloud-sa.app-inventory").put("schema", 1)
                .put("apps", org.json.JSONArray().put(JSONObject().put("package", "com.y.c").put("version_name", "1").put("version_code", 1)
                    .put("origin_store", JSONObject.NULL).put("ours", false).put("category", JSONObject.NULL)))))))
        put("_generated", JSONObject().put("tree_sha256", "abc"))
    }
}
