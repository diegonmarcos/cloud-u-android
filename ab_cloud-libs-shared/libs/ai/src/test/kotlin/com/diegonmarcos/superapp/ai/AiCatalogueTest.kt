package com.diegonmarcos.superapp.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shared catalogues (assets/ai/ai-catalogue.json): the Model Catalogue's subpages and the Task Catalogue, over the real assets. */
class AiCatalogueTest {
    private val dir = File("src/main/assets/ai")
    private val shared = AiDesign.parseTasks(File(dir, "ai-tasks.json").readText())
    private val cat = AiCatalogue.parse(File(dir, "ai-catalogue.json").readText(), shared)

    @Test fun theModelCatalogueHasTheFiveSubpagesInOrder() {
        assertEquals(listOf("frontier", "core", "specialist", "medium", "small"), cat.tabs.map { it.id })
        assertEquals(listOf("Frontier", "Core", "Specialist", "Medium", "Small"), cat.tabs.map { it.label })
    }

    @Test fun frontierNamesTheMajorCompaniesAndSpecialistTheDomains() {
        val labels = cat.frontier.map { it.label }
        for (c in listOf("Anthropic", "OpenAI", "Google", "xAI", "Meta", "Mistral", "DeepSeek", "Qwen")) assertTrue(c, c in labels)
        val domains = cat.specialist.map { it.id }
        for (d in listOf("medical", "legal", "finance", "science", "code")) assertTrue(d, d in domains)
        for (g in cat.frontier + cat.specialist) {
            assertTrue(g.id, g.models.isNotEmpty())
            assertTrue(g.id, g.models.all { '/' in it.id })
        }
        val ids = (cat.frontier + cat.specialist).flatMap { it.models.map { m -> m.id } }
        assertTrue("a model is listed once per page", cat.frontier.flatMap { it.models.map { m -> m.id } }.let { it.size == it.toSet().size })
        assertTrue(ids.isNotEmpty())
    }

    @Test fun mediumAndSmallAreCloudToolsChatModelsOfThatTier() {
        val medium = cat.tier("medium")
        val small = cat.tier("small")
        assertTrue(medium.isNotEmpty() && small.isNotEmpty())
        assertTrue(medium.any { it.name == "phi-4" && it.id == "microsoft/phi-4" && !it.local })
        assertTrue("a row with no OpenRouter id is phone-only", small.any { it.name == "smollm2-360m-instruct" && it.local })
        assertTrue(medium.none { it in small })
    }

    @Test fun everyTaskItemResolvesToAnAgentAndModelAndIdsAreUnique() {
        val items = cat.groups.flatMap { g -> g.categories.flatMap { it.items } }
        for (i in items) {
            val t = i.task
            assertNotNull("task ${i.id} has no mapping", t)
            assertTrue(i.id, t!!.agent.isNotBlank() && '/' in t.model)
            assertEquals(i.label, t.label)
        }
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertEquals("the selectable tasks are the shared mapping plus the Big LLM items", cat.tasks.map { it.id }.toSet().size, cat.tasks.size)
        assertTrue(shared.all { s -> cat.tasks.any { it.id == s.id } })
    }

    @Test fun theTaskCatalogueHasTheTwoGroupsAndTheListedCategories() {
        assertEquals(listOf("Tasks - Big LLMs", "Tasks - Medium & Small LLMs"), cat.groups.map { it.label })
        val big = cat.groups[0].categories
        assertEquals(listOf("Code", "Search (AI search engines)", "Conversation", "Data Triage"), big.map { it.label })
        assertEquals(listOf("Architecture", "Agentic coding"), big[0].items.map { it.label })
        assertEquals(listOf("DuckDuckGo AI", "Google AI search", "Qwant", "Perplexity", "Brave"), big[1].items.map { it.label })
        assertEquals(4, big[2].items.size)
        assertEquals(listOf("Files organisation", "Mail triage", "Tasks triage"), big[3].items.map { it.label })
        val small = cat.groups[1].categories
        assertEquals(listOf("Conversation", "Data Triage", "Math"), small.map { it.label })
        assertEquals(listOf("translate", "rewrite", "grammar", "summarize", "keywords", "explain", "qa", "reply"), small[0].items.map { it.id })
        assertEquals(listOf("extract", "classify"), small[1].items.map { it.id })
        assertEquals(listOf("math", "sentiment"), small[2].items.map { it.id })
    }

    @Test fun pickingACatalogueTaskGreysAndSetsAgentAndModel() {
        val chat = AiChatState(object : AiHost {
            override var model = "free/model"
            override var web = false
            override fun newSession(model: String, now: Long) = AiSession("s", "", model, now, emptyList())
            override fun models() = emptyList<AiModel>()
            override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink) = AiOutcome(session, null, emptyList())
        })
        chat.tasks = cat.tasks
        chat.chooseTask("code_architecture")
        assertTrue(chat.locked)
        assertEquals("anthropic/claude-opus-5.5", chat.model)
        chat.chooseTask("translate")
        assertEquals(shared.first { it.id == "translate" }.model, chat.model)
        chat.chooseTask("")
        assertFalse(chat.locked)
        assertEquals("free/model", chat.model)
    }

    @Test fun theDesignNoLongerCarriesTheOldPickerTabs() {
        assertFalse(JSONObject(File(dir, "ai-design.json").readText()).has("model_tabs"))
    }

    @Test fun sizeColumnsAreComputedFromDeclaredParamsOnly() {
        val m = cat.tier("small").first { it.name == "smollm2-360m-instruct" }
        val z = cat.sizes(m)!!
        assertEquals(0.36 * 8.5 / 8, z.diskQ8, 1e-9)
        assertEquals(z.diskQ8 + 0.5, z.ramQ8, 1e-9)
        assertTrue(z.ramQ8 > z.ramQ6 && z.ramQ6 > z.ramQ4)
        assertTrue(z.text(cat.sizeLabels).startsWith("Params 0.36B · Q8 disk 0.38 GB · RAM Q8 / Q6 / Q4 0.88 / "))
        assertEquals(null, cat.sizes(AiCatalogueModel("x/y", "no params")))
        for (t in listOf("small", "medium")) assertTrue(t, cat.tier(t).all { cat.sizes(it) != null })
        assertNotNull(cat.sizes(cat.specialist.flatMap { it.models }.first { it.params.isNotEmpty() }))
    }
}
