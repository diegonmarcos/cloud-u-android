package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.CloudConfig
import com.diegonmarcos.cloudsearch.core.Fixtures
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsConfigTest {
    private fun agentsAfter(edit: (JSONObject) -> Unit): String {
        val j = Fixtures.searchJson()
        edit(j.getJSONObject("agents"))
        return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    @Test fun theShippedDeclarationParses() {
        val a = Fixtures.cfg.agents!!
        assertTrue(a.problems().isEmpty())
        assertEquals(listOf("house_search"), a.agents.map { it.id })
        assertEquals(listOf("wg-gesucht.de"), a.agents[0].links.hosts)
        assertEquals("wg_gesucht_room", a.agents[0].template)
        assertEquals(listOf("name", "about", "move_in", "phone"), a.profileFields.map { it.id })
        assertTrue(a.defaults.budgetRunUsd <= a.defaults.budgetDayUsd)
        assertTrue(a.templates[0].body.contains("{{listing_title}}"))
    }

    @Test fun everyTemplateVariableIsSupplied() {
        val a = Fixtures.cfg.agents!!
        for (t in a.templates) for (v in Template.variables(t.body)) assertTrue(v, v in a.profileFields.map { it.id } + a.builtinVars)
    }

    @Test fun anAgentThatIsNotDraftOnlyIsRefused() {
        assertTrue(agentsAfter { it.getJSONArray("agents").getJSONObject(0).put("mode", "auto_send") }.contains("only drafts"))
        assertTrue(agentsAfter { it.getJSONArray("agents").getJSONObject(0).remove("mode") }.contains("only drafts"))
    }

    @Test fun danglingReferencesAreRefused() {
        assertTrue(agentsAfter { it.getJSONArray("agents").getJSONObject(0).put("template", "ghost") }.contains("template ghost"))
        assertTrue(agentsAfter { it.getJSONArray("templates").getJSONObject(0).put("agent", "ghost") }.contains("agent ghost"))
        assertTrue(agentsAfter { it.getJSONArray("templates").getJSONObject(0).put("body", "Hi {{nope}}") }.contains("{{nope}}"))
        assertTrue(agentsAfter { it.getJSONArray("agents").put(it.getJSONArray("agents").getJSONObject(0)) }.contains("duplicate agent ids"))
        assertTrue(agentsAfter { it.getJSONArray("templates").put(it.getJSONArray("templates").getJSONObject(0)) }.contains("duplicate template ids"))
        assertTrue(agentsAfter { it.getJSONArray("profile_fields").put(it.getJSONArray("profile_fields").getJSONObject(0)) }.contains("duplicate profile field ids"))
    }

    @Test fun linkRulesMustBeUsable() {
        assertTrue(agentsAfter { it.getJSONArray("agents").getJSONObject(0).getJSONObject("links").put("hosts", org.json.JSONArray()) }.contains("allows no link host"))
        assertTrue(agentsAfter { it.getJSONArray("agents").getJSONObject(0).getJSONObject("links").put("id_regex", "\\d+\\.html$") }.contains("capture group"))
    }

    @Test fun budgetsAndDefaultsMustBeSane() {
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("budget_run_usd", 2.0) }.contains("per-run cap is above"))
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("budget_day_usd", -1.0).put("budget_run_usd", -1.0) }.contains("negative"))
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("max_listings", 0) }.contains("out of range"))
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("page_chars", 100) }.contains("out of range"))
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("unknown_price_per_token", 0) }.contains("unknown_price_per_token"))
        assertTrue(agentsAfter { it.getJSONObject("defaults").put("model", "") }.contains("model is empty"))
    }

    @Test fun theEnginesMustAgreeOnThePermission() {
        assertTrue(agentsAfter { it.getJSONObject("engines").getJSONObject("browser").put("permission", "other") }.contains("different permissions"))
        assertTrue(agentsAfter { it.getJSONObject("engines").getJSONObject("mail").put("max_limit", 0) }.contains("max_limit"))
        assertTrue(agentsAfter { it.getJSONObject("engines").getJSONObject("mail").put("body_columns", org.json.JSONArray()) }.contains("columns"))
    }

    @Test fun anAppWithoutAgentsStillParses() {
        val j = Fixtures.searchJson(); j.remove("agents"); j.remove("cloud")
        val c = SearchConfig.parse(j)
        assertEquals(null, c.agents); assertEquals(null, c.cloud)
    }

    @Test fun theCloudSection() {
        val c = Fixtures.cfg.cloud!!
        val apps = listOf(CloudConfig.FleetApp("mail", "cloud-mail", "p.mail"), CloudConfig.FleetApp("calc", "cloud-calc", "p.calc"), CloudConfig.FleetApp("code", "cloud-code", "p.code"))
        assertEquals(listOf("cloud-calc", "cloud-code", "cloud-mail"), c.apps(apps, "").map { it.label })
        assertEquals(listOf("cloud-mail"), c.apps(apps, " MAIL ").map { it.label })
        assertEquals(listOf("calc"), c.apps(apps, "calc").map { it.id })
        assertTrue(c.apps(apps, "zzz").isEmpty())
        assertEquals("https://github.com/search?q=fun%20main%20user%3Adiegonmarcos&type=code", c.codeUrl("fun main"))
        assertEquals(null, c.codeUrl("   "))
        assertEquals(1_000_000_000L - 90 * 86_400_000L, c.messagesSince(1_000_000_000L))
        assertEquals(apps, CloudConfig.fleetApps("""[{"id":"mail","label":"cloud-mail","package":"p.mail"},{"id":"calc","label":"cloud-calc","package":"p.calc"},{"id":"code","label":"cloud-code","package":"p.code"}]"""))
        assertTrue(CloudConfig.fleetApps("nope").isEmpty())
        assertNotNull(c.codeOpenFleet)
    }

    @Test fun theCloudDeclarationIsValidated() {
        fun after(edit: (JSONObject) -> Unit): String { val j = Fixtures.searchJson(); edit(j.getJSONObject("cloud")); return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: "" }
        assertTrue(after { it.getJSONObject("messages").put("limit", 0) }.contains("limit"))
        assertTrue(after { it.getJSONObject("messages").put("limit", 201) }.contains("limit"))
        assertTrue(after { it.getJSONObject("messages").put("lookback_days", 0) }.contains("lookback_days"))
        assertTrue(after { it.getJSONObject("code").put("search_url", "http://x/{q}") }.contains("https"))
        assertTrue(after { it.getJSONObject("code").put("search_url", "https://x/") }.contains("{q}"))
    }
}
