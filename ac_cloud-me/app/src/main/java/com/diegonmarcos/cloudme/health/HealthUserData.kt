package com.diegonmarcos.cloudme.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user types or ticks in Health, as one value. It is stored as ONE JSON string in the
 * SharedPreferences file [HealthUserStore.PREFS] (declared in fleet-config.json as a migrating `config` store, so
 * the new-phone migration carries it). Health Connect readings are never copied in here: trackers read them live.
 */
data class Measurement(val t: Long, val massKg: Double?, val heightCm: Double?, val bfPct: Double?)
data class LabResult(val marker: String, val value: Double, val t: Long)
data class WorkoutEntry(val date: String, val day: String, val exercise: String, val done: Boolean, val loadKg: Double?)

data class HealthUserData(
    val measurements: List<Measurement> = emptyList(),
    val labs: List<LabResult> = emptyList(),
    /** "yyyy-MM-dd:<event id>" for every plan event ticked off. */
    val done: Set<String> = emptySet(),
    /** Shopping list ids ticked as bought this week: "<week key>:<item id or extra text>". */
    val shopChecked: Set<String> = emptySet(),
    val shopExtra: List<String> = emptyList(),
    val workout: List<WorkoutEntry> = emptyList(),
    val activity: String? = null,
    val sleepTargetH: Double? = null,
    val dossierNotes: String = "",
    val dossierSections: Set<String>? = null,
) {
    fun latestMeasure(): Measurement? = measurements.maxByOrNull { it.t }

    fun withMeasurement(m: Measurement) = copy(measurements = (measurements + m).sortedBy { it.t })
    fun withLab(l: LabResult) = copy(labs = (labs + l).sortedBy { it.t })
    fun labsFor(marker: String) = labs.filter { it.marker == marker }.sortedBy { it.t }
    fun toggled(set: Set<String>, key: String) = if (key in set) set - key else set + key
    fun withWorkout(e: WorkoutEntry) =
        copy(workout = workout.filterNot { it.date == e.date && it.day == e.day && it.exercise == e.exercise } + e)

    fun toJson(): String = JSONObject().apply {
        put("measurements", JSONArray().apply {
            measurements.forEach { put(JSONObject().put("t", it.t).putOpt("mass", it.massKg).putOpt("height", it.heightCm).putOpt("bf", it.bfPct)) }
        })
        put("labs", JSONArray().apply { labs.forEach { put(JSONObject().put("marker", it.marker).put("value", it.value).put("t", it.t)) } })
        put("done", JSONArray(done.toList()))
        put("shop_checked", JSONArray(shopChecked.toList()))
        put("shop_extra", JSONArray(shopExtra))
        put("workout", JSONArray().apply {
            workout.forEach { put(JSONObject().put("date", it.date).put("day", it.day).put("exercise", it.exercise).put("done", it.done).putOpt("load", it.loadKg)) }
        })
        putOpt("activity", activity)
        putOpt("sleep_target_h", sleepTargetH)
        put("dossier_notes", dossierNotes)
        if (dossierSections != null) put("dossier_sections", JSONArray(dossierSections.toList()))
    }.toString()

    companion object {
        private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
        private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

        /** Blank or malformed text is an empty record, never a crash. */
        fun fromJson(text: String?): HealthUserData {
            if (text.isNullOrBlank()) return HealthUserData()
            return runCatching {
                val o = JSONObject(text)
                HealthUserData(
                    measurements = o.optJSONArray("measurements").objs().map { Measurement(it.getLong("t"), it.dbl("mass"), it.dbl("height"), it.dbl("bf")) },
                    labs = o.optJSONArray("labs").objs().map { LabResult(it.getString("marker"), it.getDouble("value"), it.getLong("t")) },
                    done = o.optJSONArray("done").strs().toSet(),
                    shopChecked = o.optJSONArray("shop_checked").strs().toSet(),
                    shopExtra = o.optJSONArray("shop_extra").strs(),
                    workout = o.optJSONArray("workout").objs().map { WorkoutEntry(it.getString("date"), it.getString("day"), it.getString("exercise"), it.optBoolean("done"), it.dbl("load")) },
                    activity = o.optString("activity").ifBlank { null },
                    sleepTargetH = o.dbl("sleep_target_h"),
                    dossierNotes = o.optString("dossier_notes"),
                    dossierSections = o.optJSONArray("dossier_sections")?.strs()?.toSet(),
                )
            }.getOrDefault(HealthUserData())
        }
    }
}

/** The one place the user data touches disk. */
object HealthUserStore {
    /** Declared in ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json (config, migrates). */
    const val PREFS = "health_user"
    private const val KEY = "data"

    fun load(ctx: Context): HealthUserData = HealthUserData.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, d: HealthUserData) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, d.toJson()).apply()
    }
}
