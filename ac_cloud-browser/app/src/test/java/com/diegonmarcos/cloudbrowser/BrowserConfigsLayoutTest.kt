package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSettingsLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #886 parts 1, 2, 5 and 9 as DECLARED: Configs by topic, the translate engine setting, Tabs in the island, the strip on by default. */
class BrowserConfigsLayoutTest {

    private val root = JSONObject(File("../build.json").readText())
    private val cfg = BrowserConfig.parse(root.getJSONObject("ui").getJSONObject("browser").toString())

    @Test
    fun `every setting sits in a declared topic and every topic holds something`() {
        val ids = cfg.settingsSections.map { it.first }
        assertEquals(listOf("search", "privacy", "tabs", "translate", "appearance", "data", "advanced"), ids)
        val used = cfg.settings.settings.map { it.section }.toSet()
        assertTrue("undeclared topics: ${used - ids.toSet()}", ids.containsAll(used))
        // advanced holds the add-ons switch; data holds the download folder and the offline limits: none is empty
        for (id in ids) assertTrue("topic $id is empty", cfg.settings.settings.any { it.section == id } || cfg.menu.items.any { it.settingsSection == id })
    }

    @Test
    fun `the page draws topics in the declared order under their titles`() {
        val order = BrowserSettingsLayout.order(cfg.settings.settings, cfg.settingsSections, emptySet())
        assertEquals(listOf("Search", "Privacy & security", "Tabs & groups", "Translate & summary", "Appearance", "Data & storage", "Advanced"), order.map { it.second })
        // an undeclared section is still drawn, after the declared ones, rather than lost
        val extra = BrowserSettingsLayout.order(cfg.settings.settings + cfg.settings.settings.first().copy(key = "x", section = "mystery"), cfg.settingsSections, emptySet())
        assertEquals("Mystery", extra.last().second)
    }

    @Test
    fun `the translate engine is a setting - on-device ML or OpenRouter - and defaults to on-device`() {
        val s = cfg.settings["translate_engine"]!!
        assertEquals("enum", s.type)
        assertEquals(listOf("on_device", "openrouter"), s.values)
        assertEquals("on_device", s.default)
        assertEquals("translate", s.section)
        assertEquals("OpenRouter (LLM)", s.valueLabels["openrouter"])
        assertEquals("device", cfg.settings["translate_target"]!!.default)
    }

    @Test
    fun `Tabs is a destination of the island and a section that opens the tab switcher`() {
        val ui = root.getJSONObject("ui")
        val nav = (0 until ui.getJSONArray("bottom_nav").length()).map { ui.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("favourites", "tabs", "browser", "search", "configs"), nav)
        val secs = ui.getJSONArray("sections")
        val ids = (0 until secs.length()).map { secs.getJSONObject(it).getString("id") }
        assertTrue(ids.containsAll(nav))
        assertTrue(nav.size <= 5)
    }

    @Test
    fun `the tab strip is on by default and the suggestions feed is declared per engine`() {
        assertEquals(true, cfg.settings["tab_strip"]!!.default)
        assertEquals(true, cfg.settings["search_suggestions"]!!.default)
        assertEquals("duckduckgo", cfg.defaultEngineId)
        assertEquals("https://duckduckgo.com/ac/?q={q}&type=list", cfg.engine("duckduckgo").suggest)
        assertFalse(cfg.engine("qwant").suggest != null)
    }

    @Test
    fun `translate and summarise by topics are in the page menu, translate as a toggle`() {
        val t = cfg.menu.item("translate")!!
        assertEquals("toggle", t.kind); assertEquals("translated", t.checked)
        assertEquals("Summarise page by topics", cfg.menu.item("summarize_topics")!!.label)
        val ids = cfg.menu.items.filter { it.section == "page" }.map { it.id }
        assertEquals("topics sits right after translate", ids.indexOf("translate") + 1, ids.indexOf("summarize_topics"))
    }
}
