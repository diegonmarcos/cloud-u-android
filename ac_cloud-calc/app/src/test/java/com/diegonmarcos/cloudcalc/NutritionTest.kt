package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.ui.ovenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Convert ▸ Nutrition – Food over THIS repository's nutrition.json (USDA FoodData Central figures, NIST unit definitions). */
class NutritionTest {
    private val d = Nutrition.bundled
    private fun food(id: String) = d.food(id)!!
    private fun conv(id: String, x: String, from: String, to: String) =
        Nutrition.show(Nutrition.convert(d, food(id), BigDecimal(x), d.quantityUnit(from)!!, d.quantityUnit(to)!!))

    @Test fun `the table is cited, complete and sane`() {
        assertTrue("USDA is cited", d.sources["usda"]!!.contains("FoodData Central"))
        assertTrue(d.foods.size >= 20)
        assertEquals("ids are unique", d.foods.size, d.foods.map { it.id }.toSet().size)
        d.foods.forEach { f ->
            assertTrue("${f.id}: a cup weighs something", f.cupG.signum() > 0)
            assertTrue("${f.id}: FDC id", f.fdcId > 0 && f.usda.isNotBlank())
            listOf(f.kcal, f.protein, f.fat, f.carbs, f.fiber).forEach { assertTrue("${f.id}: per 100 g is not negative", it.signum() >= 0) }
            // Macronutrients of 100 g cannot exceed 100 g.
            assertTrue("${f.id}: macros per 100 g", f.protein + f.fat + f.carbs <= BigDecimal("100.5"))
            // Density = cup_g / cup volume: every food lies between air-light and mercury-heavy; water is about 1 g/ml.
            val density = f.cupG.divide(d.cupMl, java.math.MathContext.DECIMAL64).toDouble()
            assertTrue("${f.id}: density $density", density in 0.2..2.0)
        }
        assertEquals(1.0, food("water").cupG.divide(d.cupMl, java.math.MathContext.DECIMAL64).toDouble(), 0.01)
        assertEquals(12, d.oven.size)
    }

    @Test fun `volumes follow the US customary definitions exactly`() {
        fun ml(n: String) = d.volume.first { it.name == n }.perBase
        assertEquals(BigDecimal("236.5882365"), ml("cup"))
        assertEquals(0, ml("cup").compareTo(ml("tbsp").multiply(BigDecimal(16))))
        assertEquals(0, ml("cup").compareTo(ml("tsp").multiply(BigDecimal(48))))
        assertEquals(0, ml("cup").compareTo(ml("fl oz").multiply(BigDecimal(8))))
    }

    @Test fun `cups, tablespoons and grams of a food go through its density`() {
        assertEquals("200", conv("sugar", "1", "cup", "g"))
        assertEquals("0.5", conv("sugar", "100", "g", "cup"))
        assertEquals("12.5", conv("sugar", "1", "tbsp", "g"))
        assertEquals("4.167", conv("sugar", "1", "tsp", "g"))
        assertEquals("125", conv("flour_ap", "1", "cup", "g"))
        assertEquals("4.409", conv("flour_ap", "1", "cup", "oz"))
        assertEquals("7.063", conv("honey", "1", "tsp", "g"))
        assertEquals("237", conv("water", "1", "cup", "g"))
        assertEquals("16", conv("flour_ap", "1", "cup", "tbsp"))
        assertEquals("1", conv("butter", "227", "g", "cup"))
    }

    @Test fun `a same-dimension conversion does not need the food, and a round trip returns`() {
        assertEquals("3.527", conv("sugar", "100", "g", "oz"))
        assertEquals("453.6", conv("sugar", "1", "lb", "g"))
        assertEquals("1000", conv("sugar", "1", "l", "ml"))
        val x = Nutrition.convert(d, food("rice_white"), BigDecimal("2.5"), d.quantityUnit("cup")!!, d.quantityUnit("lb")!!)
        val back = Nutrition.convert(d, food("rice_white"), x, d.quantityUnit("lb")!!, d.quantityUnit("cup")!!)
        assertEquals("2.5", Nutrition.show(back))
    }

    @Test fun `macros per portion scale the per 100 g table`() {
        val g = d.quantityUnit("g")!!
        val m = Nutrition.macros(d, food("flour_ap"), BigDecimal(100), g)
        assertEquals(listOf("364", "1523", "10.3", "1", "76.3", "2.7"), listOf(m.kcal, m.kj, m.protein, m.fat, m.carbs, m.fiber).map { Nutrition.show1(it) })
        val cup = Nutrition.macros(d, food("flour_ap"), BigDecimal.ONE, d.quantityUnit("cup")!!)
        assertEquals("125", Nutrition.show(cup.grams))
        assertEquals("455", Nutrition.show1(cup.kcal))
        assertEquals("1903.7", Nutrition.show1(cup.kj))
        assertEquals("12.9", Nutrition.show1(cup.protein))
        val twice = Nutrition.macros(d, food("flour_ap"), BigDecimal(200), g)
        assertEquals(0, twice.kcal.compareTo(m.kcal.multiply(BigDecimal(2))))
    }

    @Test fun `calories and kilojoules use the thermochemical calorie`() {
        fun e(x: String, a: String, b: String) = Nutrition.show(Nutrition.energy(BigDecimal(x), d.energy.first { it.name == a }, d.energy.first { it.name == b }))
        assertEquals("418.4", e("100", "kcal", "kJ"))
        assertEquals("239", e("1000", "kJ", "kcal"))
        assertEquals("1", e("4.184", "kJ", "kcal"))
        assertEquals("1000", e("1", "kcal", "cal"))
        assertEquals("4184", e("1", "kcal", "J"))
    }

    @Test fun `oven temperatures convert between Celsius, Fahrenheit and the gas mark table`() {
        assertEquals("356", Nutrition.show(Nutrition.cToF(BigDecimal(180))))
        assertEquals("180", Nutrition.show(Nutrition.fToC(BigDecimal(356))))
        assertEquals("4", Nutrition.nearestGas(d, BigDecimal(180)).gas)
        assertEquals("4", Nutrition.nearestGas(d, Nutrition.fToC(BigDecimal(350))).gas)
        assertEquals("a tie goes to the cooler mark", "1", Nutrition.nearestGas(d, BigDecimal(145)).gas)
        assertEquals("1/4", Nutrition.nearestGas(d, BigDecimal(50)).gas)
        assertEquals("10", Nutrition.nearestGas(d, BigDecimal(300)).gas)
        assertEquals("120", Nutrition.gasRow(d, "½")!!.c.toPlainString())
        assertEquals("225", Nutrition.gasRow(d, "1/4")!!.f.toPlainString())
        assertEquals(null, Nutrition.gasRow(d, "11"))

        val c = ovenResult(d, "180", "°C")!!
        assertEquals("356 °F", c.text)
        assertTrue(c.messages.single().startsWith("Nearest gas mark 4 "))
        assertEquals("176.7 °C", ovenResult(d, "350", "°F")!!.text)
        val g = ovenResult(d, "6", "gas mark")!!
        assertEquals("200 °C", g.text)
        assertEquals("400 °F", g.messages.first())
        assertEquals("120 °C", ovenResult(d, "1/2", "gas mark")!!.text)
        assertFalse(ovenResult(d, "abc", "°C")!!.ok)
        assertFalse(ovenResult(d, "12", "gas mark")!!.ok)
        assertEquals(null, ovenResult(d, " ", "°C"))
    }

    @Test fun `every food and chapter is declared by the mode`() {
        val m = Declarations.parseModes(org.json.JSONObject(java.io.File("../build.json").readText()).getJSONObject("ui").getJSONArray("modes").toString())
            .first { it.kind == "nutrition" }
        assertEquals("Nutrition – Food", m.label)
        assertEquals(listOf("Food quantity", "Macros", "Energy", "Oven"), m.categories)
        assertNotNull(d.food(m.defaults["food"]!!))
        assertNotNull(d.quantityUnit(m.defaults["from"]!!)); assertNotNull(d.quantityUnit(m.defaults["to"]!!))
    }
}
