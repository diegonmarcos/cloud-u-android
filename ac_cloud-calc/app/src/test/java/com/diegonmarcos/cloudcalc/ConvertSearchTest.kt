package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Convert search over THIS repository's declared converters, units, aliases and foods. */
class ConvertSearchTest {
    private val modes = Declarations.parseModes(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("modes").toString())
    private val index = ConvertSearch.index(modes, Nutrition.bundled)
    private fun hits(q: String, limit: Int = 8) = ConvertSearch.search(index, q, limit).map { it.entry }
    private fun top(q: String) = hits(q).first()

    @Test fun `Units is split into one converter per quantity type, each declared in data`() {
        val units = modes.filter { it.id.startsWith("unit_") }
        assertTrue("no broad Units mode is left", modes.none { it.id == "units" })
        assertEquals("one category each", units.size, units.flatMap { it.categories }.toSet().size)
        units.forEach { assertEquals("${it.id}: a single category", 1, it.categories.size) }
        listOf("Length", "Area", "Volume", "Mass", "Temperature", "Speed", "Pressure", "Energy", "Power", "Time", "Information", "Angle",
            "Frequency", "Force", "Density", "Fuel Economy", "Acceleration").forEach { c ->
            assertTrue("$c has its own mode", units.any { it.categories == listOf(c) })
        }
        units.forEach { assertTrue("${it.id}: on the Convert page", it.tab == "convert" && it.kind == "converter") }
    }

    @Test fun `a category, a label and an alias find their converter`() {
        assertEquals("unit_length", top("length").modeId)
        assertEquals("unit_temperature", top("temperature").modeId)
        assertEquals("unit_fuel", top("mpg").modeId)
        assertEquals("unit_fuel", top("miles per gallon").modeId)
        assertEquals("unit_pressure", top("psi").modeId)
        assertEquals("unit_speed", top("km/h").modeId)
        assertEquals("unit_data", top("gigabyte").modeId)
        assertEquals("currency", top("currency").modeId)
    }

    @Test fun `an alias opens its engine unit`() {
        assertEquals("km", top("kilometre").unit)
        assertEquals("unit_length", top("kilometer").modeId)
        assertEquals("kg", top("kilogram").unit)
        assertEquals("kW", top("kilowatt").unit)
    }

    @Test fun `case, accents and partial words do not matter`() {
        assertEquals("unit_temperature", top("FAHRENHEIT").modeId)
        assertEquals("unit_temperature", top("  fahren").modeId)
        assertEquals(top("celsius"), top("CELSIUS"))
        assertEquals("unit_pressure", top("Pascál").modeId)
    }

    @Test fun `the app's own tables are searched by unit name and label`() {
        val e = top("mbit")
        assertEquals("network", e.modeId)
        assertTrue(e.unit.startsWith("Mbit"))
        assertEquals("Data rate", hits("Mbps").first { it.unit == "Mbit/s" }.category)
        assertEquals("Data size", top("gibibyte").category)
        assertEquals("network", top("transfer time").modeId)
    }

    @Test fun `foods, chapters and cooking terms find Nutrition`() {
        val flour = top("flour")
        assertEquals("nutrition", flour.modeId)
        assertEquals("Food quantity", flour.category)
        assertTrue(flour.unit.startsWith("flour"))
        assertEquals("nutrition", top("icing sugar").modeId)
        assertEquals("sugar_powdered", top("icing sugar").unit)
        val kcal = top("kcal")
        assertEquals(Triple("nutrition", "Energy", "kcal"), Triple(kcal.modeId, kcal.category, kcal.unit))
        assertEquals("Oven", top("gas mark").category)
        assertEquals("Oven", top("oven").category)
        assertEquals("Macros", top("protein").category)
        assertEquals("Food quantity", top("cups to grams").category)
        assertEquals("Nutrition – Food", hits("nutrition").first { it.title == "Nutrition – Food" }.title)
    }

    @Test fun `exact beats prefix beats substring, and nothing matches nonsense`() {
        val hs = ConvertSearch.search(index, "foot")
        assertTrue(hs.first().score >= hs.last().score)
        assertEquals(100, ConvertSearch.search(index, "length").first().score)
        assertTrue(hits("zzzzqqq").isEmpty())
        assertTrue(hits("   ").isEmpty())
        assertTrue(ConvertSearch.search(index, "a", limit = 3).size <= 3)
    }

    @Test fun `every converter and every declared search term is reachable`() {
        modes.filter { it.kind in ConvertSearch.KINDS }.forEach { m ->
            assertTrue("${m.id}: by label", hits(m.label, 100).any { it.modeId == m.id })
            m.categories.forEach { c -> assertTrue("${m.id}: category $c", hits(c, 100).any { it.modeId == m.id && it.category == c }) }
            m.search.forEach { s -> assertTrue("${m.id}: term $s", hits(s.substringBefore('|'), 100).any { it.modeId == m.id }) }
        }
    }
}
