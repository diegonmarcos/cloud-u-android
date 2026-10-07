package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Golden behaviour of every pure rule a mode applies. */
class LogicTest {
    private val k = { label: String, insert: String -> Declarations.Key(label, insert) }

    @Test fun `keypad clears, deletes, types and leaves = to the screen`() {
        assertEquals("", Logic.press("12+3", k("AC", "AC")))
        assertEquals("12+", Logic.press("12+3", k("DEL", "DEL")))
        assertEquals("", Logic.press("", k("DEL", "DEL")))
        assertEquals("12+3", Logic.press("12+3", k("=", "=")))
        assertEquals("sqrt(", Logic.press("", k("√", "sqrt(")))
        assertEquals("5 xor ", Logic.press("5", k("XOR", " xor ")))
    }

    @Test fun `autocomplete replaces only the word being typed`() {
        assertEquals("sq", Logic.lastWord("2 + sq"))
        assertEquals("", Logic.lastWord("2 + 3"))
        assertEquals("log10", Logic.lastWord("log10"))
        assertEquals("2 + sqrt", Logic.complete("2 + sq", "sqrt"))
    }

    @Test fun `forms substitute every field, trimmed`() {
        assertEquals("(80) - (80) * (25)%", Logic.fill("({price}) - ({price}) * ({pct})%", mapOf("price" to " 80 ", "pct" to "25")))
        assertEquals(listOf("price", "price", "pct"), Logic.placeholders("({price}) - ({price}) * ({pct})%"))
        assertEquals("days(\"\", \"x\")", Logic.fill("days(\"{a}\", \"{b}\")", mapOf("b" to "x")))
    }

    @Test fun `every declared form fills completely from its defaults`() {
        val modes = Declarations.parseModes(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("modes").toString())
        modes.flatMap { it.forms }.forEach { f ->
            val values = f.fields.associate { it.id to it.default }
            f.outputs.forEach { o ->
                val e = Logic.fill(o.expr, values)
                assertFalse("${f.id}/${o.label}: $e", e.contains('{') || e.contains('}'))
            }
        }
    }

    @Test fun `the converter asks libqalculate for its own to-conversion`() {
        assertEquals("(1) m to ft", Logic.convert(" 1 ", "m", "ft"))
        assertEquals("(100) EUR to USD", Logic.convert("100", "EUR", "USD"))
    }

    @Test fun `options merge overrides over the mode's own`() {
        val o = JSONObject(Logic.options("""{"approx":2,"angle":2}""", mapOf("angle" to 1, "out_base" to 16)))
        assertEquals(2, o.getInt("approx")); assertEquals(1, o.getInt("angle")); assertEquals(16, o.getInt("out_base"))
        assertEquals(16, Logic.optionValue(o.toString(), "out_base", 10))
        assertEquals(10, Logic.optionValue("{}", "out_base", 10))
    }

    @Test fun `engine answers parse, and a not-ready engine reads as its sentence`() {
        val ok = Logic.result("""{"ok":true,"result":"4","messages":[{"type":"warning","text":"w"}]}""")
        assertTrue(ok.ok); assertEquals("4", ok.text); assertEquals(listOf("w"), ok.messages)
        val down = Logic.result("""{"ok":false,"error":"com.diegonmarcos.cloudlib.calc is not installed"}""")
        assertFalse(down.ok); assertTrue(down.error.contains("not installed"))
        assertFalse(Logic.result("garbage").ok)
        assertEquals(emptyList<Logic.Item>(), Logic.items("""{"error":"x"}"""))
        assertEquals("sqrt", Logic.items("""[{"name":"sqrt","title":"Square Root","kind":"function","category":"c"}]""").single().name)
    }

    @Test fun `plot gaps stay gaps`() {
        val p = Logic.plot("""{"ok":true,"x":[-1,0,1],"y":[1,null,1]}""")
        assertEquals(listOf(-1.0, 0.0, 1.0), p.xs)
        assertNull(p.ys[1])
        assertEquals(1.0, p.ys[2]!!, 0.0)
    }

    @Test fun `history is newest first, keeps every press, capped, and round-trips with its time`() {
        val a = Logic.Entry("standard", "2+2", "4", ts = 1_700_000_000_000L)
        val b = Logic.Entry("standard", "3*3", "9")
        var h = Logic.remember(emptyList(), a, 3)
        h = Logic.remember(h, a, 3)
        assertEquals(listOf(a, a), h)
        h = Logic.remember(h, b, 3)
        h = Logic.remember(h, Logic.Entry("cas", "x", "x"), 3)
        assertEquals(3, h.size)
        assertEquals(b, h[1])
        assertFalse(Logic.encode(listOf(b)).contains("ts"))
        assertEquals(1_700_000_000_000L, Logic.decode(Logic.encode(listOf(a))).single().ts)
        assertEquals(h, Logic.decode(Logic.encode(h)))
        // #770 a follow-up question rides with its entry; an entry without one stores no key.
        val asked = listOf(Logic.Entry("standard", "6*7", "42", """{"question":"Plausible?"}"""), a)
        assertEquals(asked, Logic.decode(Logic.encode(asked)))
        assertFalse(Logic.encode(listOf(a)).contains("decision"))
        assertEquals("", Logic.decode("""[{"mode":"m","expr":"e","result":"r"}]""").single().decision)
        assertEquals(emptyList<Logic.Entry>(), Logic.decode("not json"))
    }
}
