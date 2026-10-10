package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Fixtures
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #913b the agent registry: ten agents in two sides (Buy-Side: Real Estate, Things, Services; Sell-Side), each a
 * definition the same engine runs; the draft-only guard no definition can get round; the house agent's rename.
 */
class AgentCatalogTest {
    private val a = Fixtures.cfg.agents!!

    private fun agentsAfter(edit: (JSONObject) -> Unit): String {
        val j = Fixtures.searchJson()
        edit(j.getJSONObject("agents"))
        return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    private fun agentJson(o: JSONObject, id: String): JSONObject {
        val arr = o.getJSONArray("agents")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("id") == id }
    }

    // ── the registry ─────────────────────────────────────────────────────────────────────────────

    @Test fun allTenAgentsArePresentWithTheirSideAndCategory() {
        val want = listOf(
            Triple("RS_House-Purchase", "buy", "real_estate"), Triple("RS_House-Rental", "buy", "real_estate"),
            Triple("RS_Commercial-Stores", "buy", "real_estate"), Triple("RS_Commercial-Buildings", "buy", "real_estate"),
            Triple("Things_Housing", "buy", "things"), Triple("Things_Electronics", "buy", "things"),
            Triple("Services_Medical", "buy", "services"), Triple("Services_General", "buy", "services"),
            Triple("Real-Estate", "sell", ""), Triple("Job-Placement", "sell", ""),
        )
        assertEquals(want, a.agents.map { Triple(it.label, it.side, it.category) })
        assertEquals(
            listOf("rs_house_purchase", "rs_house_rental", "rs_commercial_stores", "rs_commercial_buildings", "things_housing",
                "things_electronics", "services_medical", "services_general", "sell_real_estate", "job_placement"),
            a.agents.map { it.id },
        )
    }

    @Test fun idsAreUniqueIncludingTheEarlierOnes() {
        val ids = a.agents.map { it.id } + a.agents.flatMap { it.legacyIds }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(a.agents.size, a.agents.map { it.label }.toSet().size)
        assertTrue(agentsAfter { o -> agentJson(o, "rs_house_purchase").put("id", "rs_house_rental") }.contains("duplicate agent ids"))
        // An earlier id may not be reused as an id either: an old run would belong to two agents.
        assertTrue(agentsAfter { o -> agentJson(o, "rs_house_purchase").put("id", "house_search") }.contains("duplicate agent ids"))
    }

    @Test fun theAgentsPageIsGroupedBuySideThenSellSide() {
        val g = a.groups()
        assertEquals(
            listOf("Buy-Side › Real Estate", "Buy-Side › Things", "Buy-Side › Services", "Sell-Side"),
            g.map { it.side.label + (it.category?.let { c -> " › " + c.label } ?: "") },
        )
        assertEquals(listOf("buy/real_estate", "buy/things", "buy/services", "sell/"), g.map { it.key })
        assertEquals(listOf(4, 2, 2, 2), g.map { it.agents.size })
        assertEquals(listOf("RS_House-Purchase", "RS_House-Rental", "RS_Commercial-Stores", "RS_Commercial-Buildings"), g[0].agents.map { it.label })
        assertEquals(listOf("Real-Estate", "Job-Placement"), g[3].agents.map { it.label })
        // Every agent is under exactly one header.
        assertEquals(a.agents.map { it.id }.sorted(), g.flatMap { it.agents }.map { it.id }.sorted())
    }

    @Test fun anEmptyGroupIsNotShown() {
        val only = a.copy(agents = a.agents.filter { it.side == "sell" })
        assertEquals(listOf("sell/"), only.groups().map { it.key })
        assertTrue(a.copy(sides = emptyList()).groups().isEmpty())
    }

    @Test fun everyAgentIsACompleteDefinition() {
        for (x in a.agents) {
            assertTrue(x.id, x.goal.isNotBlank() && x.blurb.isNotBlank() && x.group.isNotBlank())
            assertTrue(x.id, x.filters.isNotEmpty() && x.filters.all { a.filter(it) != null })
            assertEquals(x.id, AgentsConfig.OUTPUTS, x.outputs)
            assertTrue(x.id, x.sources.any { it.mailFrom.isNotBlank() })
            assertTrue(x.id, x.sources.all { Fixtures.cfg.sources[it.search]?.kind == SearchConfig.KIND_LINK })
            assertTrue(x.id, a.template(x.template)?.agent == x.id || x.id == "rs_house_rental")
            assertTrue(x.id, x.reportKind.endsWith("_digest"))
        }
        assertEquals(listOf("location", "radius_km", "price_min", "price_max", "size", "dates", "keywords"), a.filters.map { it.id })
        assertEquals(listOf("radius_km", "price_min", "price_max"), a.filters.filter { it.number }.map { it.id })
        // Each agent's template is its own; the house agent kept the one it had.
        assertEquals("rs_house_rental", a.template("wg_gesucht_room")!!.agent)
    }

    @Test fun undeclaredSidesCategoriesAndFiltersAreRefused() {
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").put("side", "middle") }.contains("side middle"))
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").put("category", "ghost") }.contains("category ghost"))
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").put("category", "things") }.contains("not of its own side sell"))
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").getJSONArray("filters").put("mood") }.contains("filter mood"))
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").put("goal", "A job {{salary}}") }.contains("goal uses {{salary}}"))
        assertTrue(agentsAfter { o -> o.getJSONArray("categories").getJSONObject(0).put("side", "nowhere") }.contains("side nowhere"))
        assertTrue(agentsAfter { o -> o.getJSONArray("filters").put(o.getJSONArray("filters").getJSONObject(0)) }.contains("duplicate filter ids"))
        assertTrue(agentsAfter { o -> o.getJSONArray("sides").put(o.getJSONArray("sides").getJSONObject(0)) }.contains("duplicate side ids"))
        assertTrue(agentsAfter { o -> o.getJSONArray("categories").put(o.getJSONArray("categories").getJSONObject(0)) }.contains("duplicate category ids"))
    }

    @Test fun theVerticalsLeadToDeclaredAgentsAndTheAgentsToDeclaredSearches() {
        val c = Fixtures.cfg
        assertEquals(listOf("rs_house_rental", "rs_house_purchase"), c.vertical("house")!!.agents)
        assertEquals(listOf("things_housing", "things_electronics"), c.vertical("things")!!.agents)
        assertEquals(listOf("rs_commercial_stores", "rs_commercial_buildings", "sell_real_estate"), c.vertical("commercial")!!.agents)
        for (v in c.verticals) for (id in v.agents) assertTrue(id, c.agents!!.agents.any { it.id == id })
        fun after(edit: (JSONObject) -> Unit): String { val j = Fixtures.searchJson(); edit(j); return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: "" }
        assertTrue(after { it.getJSONArray("verticals").getJSONObject(0).put("agents", JSONArray().put("ghost")) }.contains("links agent ghost"))
        assertTrue(after { j -> agentJson(j.getJSONObject("agents"), "job_placement").getJSONArray("sources").getJSONObject(0).put("search", "ba-jobsuche") }.contains("not a declared link source"))
    }

    @Test fun theCatalogueIsBakedApartAndPutBack() {
        val bj = Fixtures.buildJson
        // build.json::search carries the engines and defaults, build.json::agents the definitions and templates.
        val bare = bj.getJSONObject("search").getJSONObject("agents")
        assertFalse(bare.has("agents")); assertFalse(bare.has("templates"))
        assertEquals(10, bj.getJSONObject("agents").getJSONArray("agents").length())
        // Without the catalogue (Cloud Browser bakes search alone) the declaration parses, with no agent and no dangling link.
        val alone = SearchConfig.parse(JSONObject(bj.getJSONObject("search").toString()))
        assertTrue(alone.agents!!.agents.isEmpty()); assertTrue(alone.agents!!.templates.isEmpty())
        assertEquals(listOf("rs_house_rental", "rs_house_purchase"), alone.vertical("house")!!.agents)
        // Put back, it is the full set; the input is not changed; no catalogue or no agents block changes nothing.
        val merged = SearchConfig.withAgents(bj.getJSONObject("search"), bj.getJSONObject("agents"))
        assertEquals(10, SearchConfig.parse(merged).agents!!.agents.size)
        assertFalse(bj.getJSONObject("search").getJSONObject("agents").has("agents"))
        // The catalogue is copied, not shared: editing the merged declaration leaves build.json's as it was.
        merged.getJSONObject("agents").getJSONArray("agents").getJSONObject(0).put("mode", "x")
        assertEquals("draft_only", bj.getJSONObject("agents").getJSONArray("agents").getJSONObject(0).getString("mode"))
        assertFalse(SearchConfig.withAgents(bj.getJSONObject("search"), null).getJSONObject("agents").has("agents"))
        val noBlock = JSONObject(bj.getJSONObject("search").toString()); noBlock.remove("agents")
        assertFalse(SearchConfig.withAgents(noBlock, bj.getJSONObject("agents")).has("agents"))
        // Each baked string stays well inside a Java string constant (65535 bytes), as base64.
        for (part in listOf(bj.getJSONObject("search"), bj.getJSONObject("agents"))) {
            val b64 = java.util.Base64.getEncoder().encodeToString(part.toString().toByteArray(Charsets.UTF_8))
            assertTrue("a baked block is ${b64.length} bytes", b64.length < 60_000)
        }
    }

    // ── draft only ───────────────────────────────────────────────────────────────────────────────

    @Test fun noAgentDefinitionCanEnableAutoSubmit() {
        val keys = listOf("auto_submit", "auto_send", "auto_apply", "auto_buy", "autosubmit", "autoSubmit", "submit", "send", "apply", "buy", "post",
            "checkout", "order", "book", "pay", "contact", "reply", "submit_url", "send_to", "Auto_Submit")
        for (x in a.agents) for (k in keys) {
            val why = agentsAfter { o -> agentJson(o, x.id).put(k, true) }
            assertTrue("${x.id} with $k: $why", why.contains("only drafts"))
            val inSource = agentsAfter { o -> agentJson(o, x.id).getJSONArray("sources").getJSONObject(0).put(k, true) }
            assertTrue("${x.id} source with $k: $inSource", inSource.contains("only drafts"))
        }
    }

    @Test fun aKeyNoDefinitionHasIsRefused() {
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").put("one_click", true) }.contains("which no agent definition has"))
        assertTrue(agentsAfter { o -> agentJson(o, "job_placement").getJSONArray("sources").getJSONObject(0).put("form", "x") }.contains("which no agent definition has"))
        assertEquals("", agentsAfter { o -> agentJson(o, "job_placement").put("_doc", "a note") })
    }

    @Test fun everyAgentIsDraftOnlyAndProducesOnlyResultsAndDrafts() {
        for (x in a.agents) {
            assertTrue(agentsAfter { o -> agentJson(o, x.id).put("mode", "auto_submit") }.contains("only drafts"))
            assertTrue(agentsAfter { o -> agentJson(o, x.id).remove("mode") }.contains("only drafts"))
            assertTrue(agentsAfter { o -> agentJson(o, x.id).getJSONArray("outputs").put("submissions") }.contains("output submissions"))
        }
        val raw = Fixtures.searchJson().getJSONObject("agents").getJSONArray("agents")
        for (i in 0 until raw.length()) assertEquals("draft_only", raw.getJSONObject(i).getString("mode"))
    }

    @Test fun anAgentHasNoFieldThatCouldAct() {
        val fields = (AgentsConfig.Agent::class.java.declaredFields + AgentsConfig.Source::class.java.declaredFields).map { it.name.lowercase() }
        val acting = Regex("submit|send|apply|buy|post|auto|checkout|order|book|pay")
        assertTrue(fields.toString(), fields.none { acting.containsMatchIn(it) })
        // The engine's doors stay read-only: nothing on them sends.
        val doors = (MailSource::class.java.methods + PageSource::class.java.methods + ReportSink::class.java.methods).map { it.name }.toSet()
        assertEquals(setOf("messages", "body", "text", "publish"), doors - Any::class.java.methods.map { it.name }.toSet())
    }

    // ── the house agent's rename ─────────────────────────────────────────────────────────────────

    @Test fun theHouseAgentIsNowRsHouseRentalAndKeepsItsDefinition() {
        val r = a.agent("rs_house_rental")!!
        assertEquals(r, a.agent("house_search"))
        assertEquals(listOf("house_search"), r.legacyIds)
        assertEquals("House search", r.group)
        assertEquals("house_search_digest", r.reportKind)
        assertEquals("wg_gesucht_room", r.template)
        val wg = r.sources.first()
        assertEquals("wg-gesucht.de", wg.mailFrom); assertEquals("", wg.mailSubject)
        assertTrue(r.owns("house_search")); assertTrue(r.owns("rs_house_rental")); assertFalse(r.owns("rs_house_purchase"))
        assertNull(a.agent("ghost"))
    }

    @Test fun theSavedConfigOfTheHouseAgentMovesToItsNewId() {
        val before = mapOf<String, Any?>(
            "agents_seen_house_search" to """["111","222"]""",
            "agents_template_wg_gesucht_room" to "Mein eigener Text {{name}}",
            "agents_profile_name" to "Ada", "agents_model" to "m/x", "agents_budget_run" to 0.1f,
            "agents_filter_house_search_location" to "Köln",
            "agents_source_house_search_wg-gesucht" to false,
            "agents_seen_rs_house_purchase" to """["9"]""",
        )
        val after = a.migratePrefs(before)
        assertEquals("""["111","222"]""", after["agents_seen_rs_house_rental"])
        assertEquals("Köln", after["agents_filter_rs_house_rental_location"])
        assertEquals(false, after["agents_source_rs_house_rental_wg-gesucht"])
        assertFalse(after.keys.any { it.contains("house_search") })
        // The template (keyed by template id), the profile, the model, the budget and the other agents are untouched.
        for (k in listOf("agents_template_wg_gesucht_room", "agents_profile_name", "agents_model", "agents_budget_run", "agents_seen_rs_house_purchase"))
            assertEquals(k, before[k], after[k])
        assertEquals(before.size, after.size)
        // Applying it again changes nothing.
        assertEquals(after, a.migratePrefs(after))
    }

    @Test fun listingsSeenUnderBothIdsAreMerged() {
        val after = a.migratePrefs(mapOf("agents_seen_house_search" to """["1","2"]""", "agents_seen_rs_house_rental" to """["2","3"]"""))
        assertEquals(setOf("1", "2", "3"), JSONArray(after["agents_seen_rs_house_rental"] as String).let { j -> (0 until j.length()).map { j.getString(it) }.toSet() })
        assertEquals(1, after.size)
        // A filter set under the new id wins over the old one.
        val f = a.migratePrefs(mapOf("agents_filter_house_search_location" to "Bonn", "agents_filter_rs_house_rental_location" to "Köln"))
        assertEquals(mapOf("agents_filter_rs_house_rental_location" to "Köln"), f)
        // Unreadable seen lists keep the newer one rather than losing it.
        assertEquals("[\"5\"]", a.migratePrefs(mapOf("agents_seen_house_search" to "nope", "agents_seen_rs_house_rental" to "[\"5\"]"))["agents_seen_rs_house_rental"])
    }

    @Test fun runsAndReportsSavedUnderTheOldIdStillBelongToTheAgent() {
        val old = RunRecord("r1", "house_search", 1, 2, RunRecord.OK, "s", 1, 1, 1, 1, 0, 0.0, emptyList())
        val other = RunRecord("r2", "rs_house_purchase", 3, 4, RunRecord.OK, "s", 1, 1, 1, 1, 0, 0.0, emptyList())
        val runs = RunRecord.listFromJson(JSONArray(listOf(old, other).map { it.toJson() }).toString())
        val rental = a.agent("rs_house_rental")!!
        assertEquals(listOf("r1"), runs.filter { rental.owns(it.agentId) }.map { it.id })
        assertEquals("RS_House-Rental", a.agent(runs[0].agentId)!!.label)
        val book = ReportBook(listOf(Report("p1", "house_search", "r1", "house_search_digest", "House search digest", 5, "s", listOf(ReportItem("1", "t", "u", "b", ReportItem.DRAFT)))))
        val restored = ReportBook.fromJson(book.toJson().toString())
        assertEquals(listOf("p1"), restored.newestFirst().filter { rental.owns(it.agentId) }.map { it.id })
        assertEquals("House search", a.agent(restored.newestFirst().single().agentId)!!.group)
    }

    // ── the plan: goal and site searches from the filters ────────────────────────────────────────

    @Test fun theGoalIsFilledFromTheFilters() {
        val r = a.agent("rs_house_rental")!!
        val bare = Plan.goal(r, emptyMap())
        assertTrue(bare, bare.startsWith("Rent a flat or room in your city (within 10 km)"))
        assertFalse(bare.contains("{{"))
        val set = Plan.goal(r, mapOf("location" to "Köln", "price_max" to "900", "radius_km" to " ", "keywords" to "Balkon"))
        assertTrue(set, set.contains("in Köln (within 10 km)") && set.contains("to 900 EUR") && set.endsWith("Keywords: Balkon."))
    }

    @Test fun eachSourceOpensItsSiteSearchWithTheFilters() {
        val links = Fixtures.cfg.sources
        val r = a.agent("rs_house_rental")!!
        val wg = r.sources.first { it.id == "wg-gesucht" }
        assertEquals("https://duckduckgo.com/?q=site%3Awg-gesucht.de+Balkon+K%C3%B6ln", Plan.searchUrl(wg, links, mapOf("keywords" to "Balkon", "location" to "Köln"), r.query, "Berlin"))
        assertEquals("https://duckduckgo.com/?q=site%3Awg-gesucht.de+wohnung%20mieten+Berlin", Plan.searchUrl(wg, links, mapOf("keywords" to "  "), r.query, "Berlin"))
        val ba = a.agent("job_placement")!!.sources.first { it.id == "arbeitsagentur" }
        assertEquals("https://www.arbeitsagentur.de/jobsuche/suche?was=Koch&wo=Bonn", Plan.searchUrl(ba, links, mapOf("keywords" to "Koch", "location" to "Bonn"), "", "Berlin"))
        assertNull(Plan.searchUrl(wg.copy(search = "ba-jobsuche"), links, emptyMap(), "", "Berlin"))
        assertNull(Plan.searchUrl(wg.copy(search = "ghost"), links, emptyMap(), "", "Berlin"))
        val off = links + ("wg-gesucht" to links.getValue("wg-gesucht").copy(enabled = false))
        assertNull(Plan.searchUrl(wg, off, emptyMap(), "", "Berlin"))
        assertEquals("x", Plan.query(mapOf("keywords" to ""), "x"))
        assertEquals("y", Plan.query(mapOf("keywords" to " y "), "x"))
    }
}
