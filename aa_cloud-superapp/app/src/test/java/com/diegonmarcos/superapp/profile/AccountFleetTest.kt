package com.diegonmarcos.superapp.profile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.FleetConfig
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
 * #783 — the fleet in Account: the path layout R/S/L share (`settings › app › file › key`), the
 * grouped server → runtime bodies, the secret mask, the drift's app ownership and the new-phone
 * plan (install-then-apply / apply / done / nothing), on the real manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountFleetTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val m: FleetConfig.Manifest by lazy { FleetConfig.manifest(ctx) }
    private val S = AccountDrift.SEP

    private val export = JSONObject().put("schema_version", 1).put("stores", JSONObject()
        .put("cloud_calc_jev", JSONObject().put("model_route", "openai/gpt").put("limit", 5)
            .put(FleetConfig.TYPES, JSONObject().put("limit", "l"))))

    @Test fun `an export is filed at settings › app › file › key, its types beside it, its schema on record`() {
        val v = AccountFleet.flatten("calc", export)
        assertEquals("openai/gpt", v["settings${S}calc${S}cloud_calc_jev${S}model_route"])
        assertEquals("l", v["settings${S}calc${S}cloud_calc_jev${S}_types${S}limit"])
        assertEquals(1, v["settings${S}calc${S}_schema"])
    }

    @Test fun `a declared subtree is the wire format again, and the round trip is exact`() {
        val body = JSONObject()
        AccountFleet.flatten("calc", export).forEach { (p, x) -> AccountDrift.put(body, p, x!!) }
        val back = AccountFleet.body(body.getJSONObject("settings").getJSONObject("calc"))
        assertEquals(AccountDrift.canonical(export), AccountDrift.canonical(back))
    }

    @Test fun `server → runtime groups one body per app and carries each value's declared type`() {
        val server = JSONObject().put("settings", JSONObject().put("calc", JSONObject().put("_schema", 1)
            .put("cloud_calc_jev", JSONObject().put("limit", 9).put(FleetConfig.TYPES, JSONObject().put("limit", "l")))))
        val b = AccountFleet.bodies(listOf(
            "settings${S}calc${S}cloud_calc_jev${S}limit" to 9,
            "settings${S}calc${S}cloud_calc_jev${S}model_route" to "x",
            "settings${S}nav${S}cloud_nav_cockpit${S}mode" to "drive",
            "mail${S}accounts${S}yo${S}name" to "not settings"), server)
        assertEquals(setOf("calc", "nav"), b.keys)
        val jev = b.getValue("calc").getJSONObject("stores").getJSONObject("cloud_calc_jev")
        assertEquals("l", jev.getJSONObject(FleetConfig.TYPES).getString("limit"))
        assertEquals("x", jev.getString("model_route"))
    }

    @Test fun `a value the manifest classes secret is masked, whatever its key is called`() {
        assertTrue(AccountFleet.isSecret(m, "settings${S}calc${S}cloud_calc_jev_secret${S}openrouter_token"))
        assertTrue("a narrowed key", AccountFleet.isSecret(m, "settings${S}aa_cloud-superapp${S}wireguard_prefs${S}if_privkey"))
        assertFalse(AccountFleet.isSecret(m, "settings${S}aa_cloud-superapp${S}wireguard_prefs${S}tunnel_name"))
        assertTrue("an app this build does not know fails closed", AccountFleet.isSecret(m, "settings${S}future-app${S}x${S}y"))
    }

    @Test fun `every fleet app owns its settings, and an id the cockpit also has is one app`() {
        val cockpit = listOf(AccountDrift.App("mail", "Mail", listOf("mail")), AccountDrift.App("about", "About", listOf("about")))
        val apps = AccountFleet.driftApps(cockpit, m, emptyMap())
        assertEquals(1, apps.count { it.id == "mail" })
        assertEquals(listOf("mail", "settings${S}mail"), apps.first { it.id == "mail" }.sections)
        assertEquals(m.apps.size + 1, apps.size)   // 30 fleet apps + about
        assertEquals("mail", AccountDrift.ownerOf("settings${S}mail${S}text_tools${S}provider", apps))
        assertEquals("mail", AccountDrift.ownerOf("mail${S}accounts${S}yo", apps))
        assertEquals("calc", AccountDrift.ownerOf("settings${S}calc${S}cloud_clock${S}data", apps))
        assertEquals("", AccountDrift.ownerOf("settings${S}calculator${S}x", apps))
    }

    @Test fun `the new-phone plan installs what is missing, applies what is there, skips what landed, resumes`() {
        val declared = JSONObject().put("settings", JSONObject()
            .put("calc", JSONObject().put("_schema", 1).put("cloud_calc_jev", JSONObject().put("model_route", "a")))
            .put("nav", JSONObject().put("_schema", 1))
            .put("aa_cloud-superapp", JSONObject().put("_schema", 1).put("launcher_theme_prefs", JSONObject().put("theme", "cloud"))))
        val installed = setOf("com.diegonmarcos.superapp", "com.diegonmarcos.cloudnav")
        val journal = HashMap<String, String>()
        fun plan() = AccountFleet.plan(m, declared, emptyMap(), { it in installed }, { journal[it] }).associateBy { it.id }
        val p = plan()
        assertEquals(AccountFleet.INSTALL_APPLY, p.getValue("calc").action)
        assertEquals(AccountFleet.APPLY, p.getValue("nav").action)
        assertEquals(AccountFleet.APPLY, p.getValue("aa_cloud-superapp").action)
        assertEquals(AccountFleet.NOTHING, p.getValue("mail").action)
        assertEquals("the SuperApp is planned first", "aa_cloud-superapp", m.apps.keys.first())
        // The superapp applied: a re-run skips it while the declared copy is unchanged …
        journal["aa_cloud-superapp"] = p.getValue("aa_cloud-superapp").sha + "|✓ aa_cloud-superapp: 1 written"
        assertEquals(AccountFleet.DONE, plan().getValue("aa_cloud-superapp").action)
        // … and applies again once it changes; a failed step is retried.
        declared.getJSONObject("settings").getJSONObject("aa_cloud-superapp").getJSONObject("launcher_theme_prefs").put("theme", "black")
        assertEquals(AccountFleet.APPLY, plan().getValue("aa_cloud-superapp").action)
        journal["nav"] = p.getValue("nav").sha + "|✗ nav: unreachable"
        assertEquals(AccountFleet.APPLY, plan().getValue("nav").action)
        assertEquals(JSONArray::class, AccountFleet.planJson(plan().values.toList())::class)
    }
}
