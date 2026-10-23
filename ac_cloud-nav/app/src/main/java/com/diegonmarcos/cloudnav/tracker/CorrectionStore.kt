package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The owner's corrections (confirm, edit place / mode, delete, split, add visit), kept in the app's own preferences. */
class CorrectionStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tracker_corrections", Context.MODE_PRIVATE)

    fun all(): List<Correction> = runCatching { decode(sp.getString("list", "[]") ?: "[]") }.getOrDefault(emptyList())

    fun add(c: Correction) { sp.edit().putString("list", encode(all() + c).toString()).apply() }

    /** Named places the owner confirmed or typed: later stays near them take the name. */
    fun memory(): List<Remembered> = memoryOf(all())

    companion object {
        fun memoryOf(cs: List<Correction>): List<Remembered> =
            cs.mapNotNull { c -> c.place?.takeIf { it.located && !it.name.isNullOrBlank() && (c.type == Correction.Type.CONFIRM || c.type == Correction.Type.EDIT_PLACE || c.type == Correction.Type.ADD_VISIT) } }
                .map { Remembered(it) }

        fun encode(cs: List<Correction>): JSONArray {
            val arr = JSONArray()
            for (c in cs) arr.put(JSONObject().put("t", c.type.name).put("s", c.startMs).put("e", c.endMs).put("at", c.atMs)
                .put("sk", c.stayKind.name).apply {
                    if (c.mode != null) put("m", c.mode.name)
                    c.place?.let { p -> put("p", DayJson.place(p)) }
                })
            return arr
        }

        fun decode(s: String): List<Correction> {
            val arr = JSONArray(s)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val p = o.optJSONObject("p")?.let { j ->
                    fun str(k: String) = j.optString(k).takeIf { it.isNotBlank() }
                    Place(if (j.has("lat")) j.getDouble("lat") else null, if (j.has("lon")) j.getDouble("lon") else null,
                        str("name"), str("street"), str("postcode"), str("neighborhood"), str("city"), str("state"), str("country"))
                }
                Correction(Correction.Type.valueOf(o.getString("t")), o.getLong("s"), o.getLong("e"),
                    o.optString("m").takeIf { it.isNotBlank() }?.let { Mode.parse(it) }, p, o.optLong("at"),
                    runCatching { StayKind.valueOf(o.optString("sk", "IDLE")) }.getOrDefault(StayKind.IDLE))
            }
        }
    }
}
