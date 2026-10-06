package com.diegonmarcos.superapp.decisions.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * What /api/decisions shows: NAMES AND ✓/✗ ONLY. The fleet uploads diagnostics, so the debug routes say
 * whether things work (token present, breaker, each use's state) and never what was asked, answered,
 * spent or whose it was.
 */
object DebugView {
    const val YES = "✓"
    const val NO = "✗"
    private fun mark(b: Boolean) = if (b) YES else NO

    fun status(s: JSONObject): JSONObject {
        val uses = JSONObject()
        val u = s.optJSONObject("uses") ?: JSONObject()
        for (name in u.keys()) {
            val o = u.getJSONObject(name)
            uses.put(name, mark(o.optBoolean("enabled") && o.optBoolean("allowed_for_app")))
        }
        return JSONObject()
            .put("token", mark(s.optBoolean("token")))
            .put("online", mark(s.optBoolean("online")))
            .put("metered", mark(s.optBoolean("metered")))
            .put("battery_saver", mark(s.optBoolean("battery_saver")))
            .put("breaker_open", mark(s.optBoolean("breaker_open")))
            .put("uses", uses)
            .put("rejected", s.optJSONArray("rejected") ?: JSONArray())
    }

    fun journal(lines: List<String>): JSONObject = JSONObject().put("lines", JSONArray(lines))

    fun probe(reply: JSONObject): JSONObject {
        val ok = reply.optBoolean("ok")
        return JSONObject().put("probe", mark(ok)).also { if (!ok) it.put("reason", reply.optString("reason")) }
    }
}
