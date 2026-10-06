package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject

/**
 * Answers by sha256(use + redacted state + questions), kept for the use's ttl_s: a background use asks
 * once and never again while the answer stands, and a user-facing screen redrawn ten times asks once.
 * Bounded to [max] entries (the soonest to expire go first) and persisted, so the same state after an
 * engine restart is still not re-asked. Keys are hashes and values are answers: no state is stored.
 */
class AnswerCache(private val store: KvStore?, private val max: Int, private val clock: () -> Long) {

    private val entries = LinkedHashMap<String, Pair<Long, String>>()

    init {
        val saved = store?.read()?.optJSONObject("entries")
        if (saved != null) {
            for (k in saved.keys()) {
                val e = saved.optJSONObject(k) ?: continue
                val exp = e.optLong("exp", 0)
                val v = e.optString("results")
                if (exp > clock() && v.isNotEmpty()) entries[k] = exp to v
            }
        }
    }

    @Synchronized
    fun get(key: String): JSONObject? {
        val e = entries[key] ?: return null
        if (e.first <= clock()) {
            entries.remove(key)
            return null
        }
        return JSONObject(e.second)
    }

    @Synchronized
    fun put(key: String, results: JSONObject, ttlS: Long) {
        val now = clock()
        entries.entries.removeAll { it.value.first <= now }
        entries.remove(key)
        entries[key] = (now + ttlS * 1000) to results.toString()
        while (entries.size > max) {
            val oldest = entries.entries.minByOrNull { it.value.first } ?: break
            entries.remove(oldest.key)
        }
        persist()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun persist() {
        val doc = JSONObject()
        for ((k, v) in entries) doc.put(k, JSONObject().put("exp", v.first).put("results", v.second))
        store?.write(JSONObject().put("entries", doc))
    }
}
