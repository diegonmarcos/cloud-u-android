package com.diegonmarcos.cloudcalc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.json.JSONObject

/**
 * Convert ▸ Nutrition – Food: food quantities by density, calories and kJ, macros per portion and oven
 * temperatures, in BigDecimal. Every number is read from nutrition.json (generated from USDA FoodData Central
 * by data/usda-extract.py, cited in its `sources`); nothing is typed in here. Pure, so the JVM suite holds each rule.
 */
object Nutrition {
    /** A mass ([perBase] = grams), volume (millilitres) or energy (joules) unit. */
    data class Qty(val name: String, val label: String, val perBase: BigDecimal)
    data class Food(
        val id: String, val name: String, val aliases: List<String>, val fdcId: Int, val usda: String, val portion: String,
        val cupG: BigDecimal, val kcal: BigDecimal, val protein: BigDecimal, val fat: BigDecimal, val carbs: BigDecimal, val fiber: BigDecimal,
    )
    data class Oven(val gas: String, val c: BigDecimal, val f: BigDecimal, val desc: String)
    data class Data(val mass: List<Qty>, val volume: List<Qty>, val energy: List<Qty>, val oven: List<Oven>, val foods: List<Food>, val sources: Map<String, String>) {
        fun food(id: String): Food? = foods.firstOrNull { it.id == id }
        val cupMl: BigDecimal get() = volume.first { it.name == "cup" }.perBase
        /** Mass then volume, the order of the pickers. */
        val quantityUnits: List<Qty> get() = mass + volume
        fun quantityUnit(name: String): Qty? = quantityUnits.firstOrNull { it.name == name }
    }

    /** What a macros line shows for one portion; every amount in grams except [kcal]/[kj]. */
    data class Macros(val grams: BigDecimal, val kcal: BigDecimal, val kj: BigDecimal, val protein: BigDecimal, val fat: BigDecimal, val carbs: BigDecimal, val fiber: BigDecimal)

    private val MC = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)
    private val NINE = BigDecimal(9)
    private val FIVE = BigDecimal(5)
    private val THIRTY_TWO = BigDecimal(32)
    private val KCAL_J = BigDecimal("4184")

    /** The bundled table (classpath resource nutrition.json, in the APK and on the JVM test classpath). */
    val bundled: Data by lazy {
        val s = Nutrition::class.java.classLoader!!.getResourceAsStream("nutrition.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        parse(s)
    }

    fun parse(json: String): Data {
        val o = JSONObject(json)
        fun units(key: String, field: String) = o.getJSONArray(key).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Qty(it.getString("name"), it.getString("label"), BigDecimal(it.getString(field))) }
        }
        val src = o.getJSONObject("sources").let { s -> s.keys().asSequence().associateWith { s.getJSONObject(it).getString("name") } }
        val oven = o.getJSONArray("oven").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Oven(it.getString("gas"), BigDecimal(it.getString("c")), BigDecimal(it.getString("f")), it.getString("desc")) }
        }
        val foods = o.getJSONArray("foods").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
                Food(
                    f.getString("id"), f.getString("name"), f.getJSONArray("aliases").let { al -> (0 until al.length()).map { al.getString(it) } },
                    f.getInt("fdc_id"), f.getString("usda"), f.getString("portion"), BigDecimal(f.getString("cup_g")),
                    BigDecimal(f.getString("kcal")), BigDecimal(f.getString("protein")), BigDecimal(f.getString("fat")),
                    BigDecimal(f.getString("carbs")), BigDecimal(f.getString("fiber")),
                )
            }
        }
        return Data(units("mass", "g"), units("volume", "ml"), units("energy", "j"), oven, foods, src)
    }

    /** Grams of [x] [unit] of [food]: a mass is itself, a volume weighs its density (cup_g per cup) times the volume. */
    fun grams(d: Data, food: Food, x: BigDecimal, unit: Qty): BigDecimal =
        if (d.mass.any { it.name == unit.name }) x.multiply(unit.perBase)
        else DataUnits.divide(x.multiply(unit.perBase).multiply(food.cupG), d.cupMl)

    /** [x] of [from] expressed in [to] for [food]: a mass↔volume conversion goes through the food's density. */
    fun convert(d: Data, food: Food, x: BigDecimal, from: Qty, to: Qty): BigDecimal {
        val g = grams(d, food, x, from)
        return if (d.mass.any { it.name == to.name }) DataUnits.divide(g, to.perBase)
        else DataUnits.divide(DataUnits.divide(g.multiply(d.cupMl), food.cupG), to.perBase)
    }

    /** Macros of a portion of [x] [unit] of [food]. */
    fun macros(d: Data, food: Food, x: BigDecimal, unit: Qty): Macros {
        val g = grams(d, food, x, unit)
        fun per(v: BigDecimal) = g.multiply(v).divide(HUNDRED, MC)
        val kcal = per(food.kcal)
        return Macros(g, kcal, kcal.multiply(KCAL_J).divide(BigDecimal(1000), MC), per(food.protein), per(food.fat), per(food.carbs), per(food.fiber))
    }

    /** [x] of energy unit [from] in [to] (kcal = 4184 J exactly). */
    fun energy(x: BigDecimal, from: Qty, to: Qty): BigDecimal = DataUnits.divide(x.multiply(from.perBase), to.perBase)

    fun cToF(c: BigDecimal): BigDecimal = c.multiply(NINE).divide(FIVE, MC).add(THIRTY_TWO)
    fun fToC(f: BigDecimal): BigDecimal = f.subtract(THIRTY_TWO).multiply(FIVE).divide(NINE, MC)

    /** A gas mark as typed: "1/4", "¼", "1/2", "½", "4"; the table row, or null. */
    fun gasRow(d: Data, text: String): Oven? {
        val t = text.trim().replace("¼", "1/4").replace("½", "1/2").replace(" ", "")
        return d.oven.firstOrNull { it.gas == t }
    }

    /** The table row nearest [c] degrees Celsius; a tie goes to the cooler mark. */
    fun nearestGas(d: Data, c: BigDecimal): Oven = d.oven.minWithOrNull(compareBy<Oven>({ (it.c - c).abs() }, { it.c }))!!

    /** Display: 4 significant digits, no trailing zeros, no exponent (1 cup flour = 4.409 oz). */
    fun show(x: BigDecimal): String {
        if (x.signum() == 0) return "0"
        val r = x.round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros()
        return r.toPlainString()
    }

    /** Display for macros and energy: one decimal, no trailing zeros. */
    fun show1(x: BigDecimal): String = x.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
}
