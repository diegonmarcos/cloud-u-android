package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** #802 I2 the settings catalogue, parsed from the SHIPPED build.json, refuses what it must. */
class BrowserSettingsCatalogueTest {

    private val cat = BrowserConfig.parse(
        JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString()
    ).settings

    private fun refused(key: String, raw: String): String =
        try { cat.parseValue(key, raw); fail("$key=$raw was accepted"); "" }
        catch (e: IllegalArgumentException) { e.message.orEmpty() }

    @Test
    fun `typed defaults come from the declaration`() {
        assertEquals(100, cat["text_zoom"]!!.default)
        assertEquals(true, cat["javascript"]!!.default)
        assertEquals(false, cat["desktop_mode"]!!.default)
        // values_from: the engine list and its default are the app's, declared once.
        assertEquals(listOf("qwant", "duckduckgo", "google"), cat["search_engine_id"]!!.values)
        assertEquals("duckduckgo", cat["search_engine_id"]!!.default)
    }

    @Test
    fun `values are parsed to their type`() {
        assertEquals(130, cat.parseValue("text_zoom", "130"))
        assertEquals(true, cat.parseValue("desktop_mode", "on"))
        assertEquals(false, cat.parseValue("javascript", "false"))
        assertEquals("google", cat.parseValue("search_engine_id", "google"))
    }

    @Test
    fun `out of range, wrong type, unknown enum and unknown key are refused in words`() {
        assertTrue(refused("text_zoom", "900").contains("range"))
        assertTrue(refused("text_zoom", "49").contains("range"))
        assertTrue(refused("text_zoom", "big").contains("not an int"))
        assertTrue(refused("desktop_mode", "maybe").contains("not a bool"))
        assertTrue(refused("search_engine_id", "bing").contains("not one of"))
        assertTrue(refused("no_such_setting", "1").contains("not a setting"))
    }

    @Test
    fun `snapshot carries every key and its type, the FleetConfig shape`() {
        val snap = cat.snapshot { s -> if (s.key == "text_zoom") 130 else null }
        assertEquals(130, snap.getInt("text_zoom"))
        assertEquals(true, snap.getBoolean("javascript"))   // unset → declared default
        val types = snap.getJSONObject("_types")
        cat.settings.forEach { assertTrue("${it.key} has a _types entry", types.has(it.key)) }
        assertEquals("int", types.getString("text_zoom"))
    }
}
