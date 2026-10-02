package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parser the phone runs, over THIS repository's build.json::ui (the test runs from app/), and
 * the per-mode declaration rules every renderer relies on.
 */
class DeclarationsTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")
    private val tabs = Declarations.parseTabs(ui.getJSONArray("tabs").toString())
    private val modes = Declarations.parseModes(ui.getJSONArray("modes").toString())

    @Test fun `the declaration parses and matches what gradle baked`() {
        assertEquals(tabs, Declarations.tabs)
        assertEquals(modes, Declarations.modes)
        assertTrue(tabs.any { it.id == Declarations.defaultTab })
    }

    @Test fun `every tab has a mode and every mode has a tab`() {
        tabs.forEach { t -> assertTrue("tab ${t.id} has no mode", modes.any { it.tab == t.id }) }
        modes.forEach { m -> assertTrue("mode ${m.id} names tab ${m.tab}", tabs.any { it.id == m.tab }) }
        assertEquals(modes.size, modes.map { it.id }.toSet().size)
    }

    @Test fun `each kind carries what its renderer reads`() {
        modes.forEach { m ->
            when (m.kind) {
                "expression" -> assertTrue("${m.id}: no keys", m.keys.isNotEmpty() && m.keys.all { r -> r.all { it.label.isNotBlank() } })
                "catalog" -> assertTrue("${m.id}: no catalog", m.catalog.isNotEmpty())
                "converter" -> assertTrue("${m.id}: no categories", m.categories.isNotEmpty() && (m.defaults["category"] ?: m.categories.first()) in m.categories)
                "form" -> assertTrue("${m.id}: no forms", m.forms.isNotEmpty())
                "plot" -> assertTrue("${m.id}: bad range", m.plot != null && m.plot!!.xmax > m.plot!!.xmin && m.plot!!.steps > 0)
                "meter" -> m.meter!!.let { assertTrue("${m.id}: fft ${it.fftSize}", it.fftSize > 0 && (it.fftSize and (it.fftSize - 1)) == 0) }
                "history" -> assertTrue(m.historyMax > 0)
            }
        }
    }

    @Test fun `every expression keypad can evaluate and clear`() {
        modes.filter { it.kind == "expression" }.forEach { m ->
            val actions = m.keys.flatten().mapNotNull { it.action }.toSet()
            assertTrue("${m.id} has no = key", Declarations.Action.EVALUATE in actions)
            assertTrue("${m.id} has no AC key", Declarations.Action.CLEAR in actions)
        }
    }

    @Test fun `every form expression names only its own fields`() {
        modes.flatMap { it.forms }.forEach { f ->
            val ids = f.fields.map { it.id }.toSet()
            f.outputs.forEach { o ->
                val named = Logic.placeholders(o.expr)
                assertFalse("${f.id}/${o.label} names nothing", named.isEmpty())
                assertTrue("${f.id}/${o.label} names ${named - ids}", ids.containsAll(named))
            }
        }
    }

    @Test fun `choice rows set a real engine option`() {
        val keys = setOf("in_base", "out_base", "angle", "approx", "precision")
        modes.flatMap { it.angleChoices + it.baseChoices + it.showBases }.forEach { assertTrue(it.key, it.key in keys) }
    }

    @Test fun `a choice row starts on the option its mode really sets`() {
        modes.forEach { m ->
            (m.angleChoices + m.baseChoices).groupBy { it.key }.forEach { (key, row) ->
                val v = Logic.optionValue(m.options, key, Int.MIN_VALUE)
                assertTrue("${m.id}: options set $key=$v, which no chip shows", row.any { it.value == v })
            }
        }
    }
}
