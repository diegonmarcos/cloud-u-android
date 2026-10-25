package com.diegonmarcos.cloudcalc.measure

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * The sensor catalog (build.json::sensors): every measurement the Measure > Sensors page can show, as DATA. A sensor names
 * where its values come from ([Decl.source], and for a hardware sensor its Android type), its unit and the units it converts
 * to, how its values read ([Decl.display], [Decl.axes]) and the derived quantities worked out of them ([Derived], by the named
 * [Formulas]). The app enumerates SensorManager at run time and shows each declared sensor the phone has, live; one it does
 * not have is listed as absent, never with a made-up value. No Android here, so this parse, the conversions and the formulas
 * run on the JVM and PIT mutates them.
 */
object Sensors {
    /** A bucket of a derived number: the first band whose [max] is at least the value names it. */
    data class Band(val max: Double, val label: String)

    /**
     * A quantity worked out of a sensor's values by [fn]. [inputs] picks values by index (empty = all), then the first value of
     * each sensor in [needs] is appended: a derived quantity whose needed sensor the phone lacks is not shown at all. [band]
     * names the band table that turns the number into words.
     */
    data class Derived(
        val id: String,
        val label: String,
        val fn: String,
        val unit: String,
        val decimals: Int,
        val inputs: List<Int>,
        val needs: List<String>,
        val args: Map<String, Double>,
        val band: String,
    )

    data class Decl(
        val id: String,
        val label: String,
        val group: String,
        val source: String,
        val androidType: Int,
        val unit: String,
        val display: String,
        val axes: List<String>,
        val axisUnits: List<String>,
        val axisDecimals: List<Int>,
        val decimals: Int,
        val permission: String,
        val rawScale: Double,
        val units: List<String>,
        val derived: List<Derived>,
        val doc: String,
    ) {
        /** What the platform reported, scaled into the declared unit (a battery temperature arrives in tenths of a degree). */
        fun scaled(raw: DoubleArray): DoubleArray = DoubleArray(raw.size) { raw[it] * rawScale }
        fun unitOfAxis(i: Int): String = axisUnits.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: unit
        fun decimalsOfAxis(i: Int): Int = axisDecimals.getOrNull(i) ?: decimals
    }

    /** The result of a [Derived]: the number and, when it has a band table, the words for it. */
    data class Value(val number: Double, val words: String?)

    /** to = from * factor + offset. */
    data class Conversion(val from: String, val to: String, val factor: Double, val offset: Double)

    data class Catalog(val sensors: List<Decl>, val conversions: List<Conversion>, val bands: Map<String, List<Band>>) {
        fun sensor(id: String): Decl? = sensors.firstOrNull { it.id == id }
        fun inGroup(group: String): List<Decl> = sensors.filter { it.group == group }
        fun groups(): List<String> = sensors.map { it.group }.distinct()
    }

    val SOURCES = setOf("sensor", "gnss", "battery_temperature", "battery_voltage", "wifi", "cell", "nfc")
    val DISPLAYS = setOf("scalar", "vector", "rotation", "event", "trigger", "state", "gnss", "presence")
    val PERMISSIONS = setOf("", "ACTIVITY_RECOGNITION", "BODY_SENSORS", "ACCESS_FINE_LOCATION")

    /** Parse and validate; a catalog that names an unknown formula, band, unit or source never reaches the screen. */
    fun parse(json: String): Catalog {
        val o = JSONObject(json)
        val conversions = o.getJSONArray("conversions").objects().map {
            Conversion(it.getString("from"), it.getString("to"), it.getDouble("factor"), it.optDouble("offset", 0.0))
        }
        val bands = o.getJSONObject("bands").let { b ->
            b.keys().asSequence().associateWith { k ->
                b.getJSONArray(k).objects().map { Band(if (it.isNull("max")) Double.MAX_VALUE else it.getDouble("max"), it.getString("label")) }
            }
        }
        val sensors = o.getJSONArray("sensors").objects().map { s ->
            Decl(
                id = s.getString("id"), label = s.getString("label"), group = s.getString("group"),
                source = s.getString("source"), androidType = s.optInt("android_type", 0), unit = s.optString("unit"),
                display = s.getString("display"), axes = s.optJSONArray("axes").strings(), axisUnits = s.optJSONArray("axis_units").strings(),
                axisDecimals = (s.optJSONArray("axis_decimals") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getInt(it) } },
                decimals = s.optInt("decimals", 2), permission = s.optString("permission"), rawScale = s.optDouble("raw_scale", 1.0),
                units = s.optJSONArray("units").strings(),
                derived = (s.optJSONArray("derived") ?: JSONArray()).objects().map { d ->
                    Derived(
                        d.getString("id"), d.getString("label"), d.getString("fn"), d.optString("unit"), d.optInt("decimals", 2),
                        (d.optJSONArray("inputs") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getInt(it) } },
                        d.optJSONArray("needs").strings(),
                        (d.optJSONObject("args") ?: JSONObject()).let { a -> a.keys().asSequence().associateWith { k -> a.getDouble(k) } },
                        d.optString("band"),
                    )
                },
                doc = s.optString("_doc"),
            )
        }
        val catalog = Catalog(sensors, conversions, bands)
        val errors = validate(catalog)
        require(errors.isEmpty()) { "build.json::sensors is invalid: " + errors.joinToString("; ") }
        return catalog
    }

    fun validate(c: Catalog): List<String> {
        val out = mutableListOf<String>()
        val ids = c.sensors.map { it.id }
        ids.filter { id -> ids.count { it == id } > 1 }.toSet().forEach { out += "duplicate sensor id $it" }
        c.bands.forEach { (name, list) ->
            if (list.isEmpty()) out += "band $name is empty"
            if (list.zipWithNext().any { (a, b) -> a.max >= b.max }) out += "band $name is not ascending"
        }
        c.sensors.forEach { s ->
            if (s.group.isBlank()) out += "${s.id} has no group"
            if (s.source !in SOURCES) out += "${s.id} source ${s.source} is unknown"
            if (s.source == "sensor" && s.androidType <= 0) out += "${s.id} is a hardware sensor without an android_type"
            if (s.display !in DISPLAYS) out += "${s.id} display ${s.display} is unknown"
            if (s.permission !in PERMISSIONS) out += "${s.id} permission ${s.permission} is unknown"
            if ((s.display == "vector" || s.display == "rotation" || s.display == "gnss") && s.axes.isEmpty()) out += "${s.id} shows axes but declares none"
            if (s.axisUnits.isNotEmpty() && s.axisUnits.size != s.axes.size) out += "${s.id} axis_units and axes differ in length"
            if (s.axisDecimals.isNotEmpty() && s.axisDecimals.size != s.axes.size) out += "${s.id} axis_decimals and axes differ in length"
            if (s.rawScale == 0.0) out += "${s.id} raw_scale is zero"
            s.units.forEach { u -> if (Units.convert(c.conversions, 1.0, s.unit, u) == null) out += "${s.id} cannot convert ${s.unit} to $u" }
            s.derived.forEach { d ->
                if (d.fn !in Formulas.names) out += "${s.id}.${d.id} uses unknown formula ${d.fn}"
                if (d.band.isNotEmpty() && d.band !in c.bands) out += "${s.id}.${d.id} uses unknown band ${d.band}"
                d.needs.forEach { n -> if (n !in ids) out += "${s.id}.${d.id} needs unknown sensor $n" }
            }
        }
        return out
    }

    /** [v] with [decimals] places, locale-independent. */
    fun format(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${max(decimals, 0)}f", v)

    /** The derived value of [d] for [own] values; [lookup] gives the latest values of a needed sensor (null = the phone lacks it or it has not reported). */
    fun derive(c: Catalog, d: Derived, own: DoubleArray, lookup: (String) -> DoubleArray?): Value? {
        val picked = if (d.inputs.isEmpty()) own.toList() else d.inputs.map { own.getOrNull(it) ?: return null }
        val extra = d.needs.map { lookup(it)?.firstOrNull() ?: return null }
        val n = Formulas.apply(d.fn, (picked + extra).toDoubleArray(), d.args) ?: return null
        return Value(n, d.band.takeIf { it.isNotEmpty() }?.let { Bands.label(c.bands[it].orEmpty(), n) })
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
